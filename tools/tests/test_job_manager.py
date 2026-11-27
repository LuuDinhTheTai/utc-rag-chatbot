"""Tests for JobManager: claiming, heartbeat, lease fencing, retry policy, reclaim, error sanitization."""

import random
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, Mock

import pytest

from src.database import Database
from src.exceptions import (
    ClaimTokenMismatchError,
    DatabaseError,
    JobStateError,
    LeaseExpiredError,
)
from src.job_manager import JobManager, RetryPolicy, sanitize_error_message


def setup_mock_transaction(mock_db, mock_cursor):
    mock_conn = MagicMock()
    mock_db.transaction.return_value.__enter__ = MagicMock(return_value=mock_conn)
    mock_db.transaction.return_value.__exit__ = MagicMock(return_value=False)
    mock_conn.cursor.return_value.__enter__ = MagicMock(return_value=mock_cursor)
    mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)
    return mock_conn


@pytest.fixture
def mock_db():
    return Mock(spec=Database)


@pytest.fixture
def job_manager(mock_db):
    return JobManager(
        mock_db,
        lease_duration_seconds=600,
        retry_policy=RetryPolicy(max_attempts=3, base_delay_seconds=900, max_delay_seconds=21600, jitter_ratio=0.0),
    )


def test_retry_policy_exponential_backoff_cap_and_jitter():
    policy = RetryPolicy(max_attempts=4, base_delay_seconds=600, max_delay_seconds=1800, jitter_ratio=0.0)
    assert policy.should_retry(1) is True
    assert policy.should_retry(3) is True
    assert policy.should_retry(4) is False
    assert policy.should_retry(1, retryable=False) is False
    assert policy.delay_seconds(1) == 600
    assert policy.delay_seconds(2) == 1200
    assert policy.delay_seconds(3) == 1800  # capped
    assert policy.delay_seconds(4) == 1800  # capped

    jittered = RetryPolicy(max_attempts=3, base_delay_seconds=1000, max_delay_seconds=10000, jitter_ratio=0.2)
    d = jittered.delay_seconds(1, rng=random.Random(42))
    assert 800 <= d <= 1200


def test_sanitize_error_message_redacts_secrets_and_truncates():
    msg = "Failed connecting to https://alice:supersecret@db.internal/app token=abc123 Bearer xyz.999"
    cleaned = sanitize_error_message(msg)
    assert "supersecret" not in cleaned
    assert "abc123" not in cleaned
    assert "xyz.999" not in cleaned
    assert "***" in cleaned

    long_msg = "a" * 3000
    truncated = sanitize_error_message(long_msg, limit=200)
    assert len(truncated) <= 200
    assert truncated.endswith("[truncated]")


def test_claim_job_success(job_manager, mock_db):
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    token = uuid.uuid4()
    mock_cursor.fetchone.return_value = {
        "id": 123,
        "source_id": 456,
        "status": "RUNNING",
        "claim_token": token,
        "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10),
        "attempt_no": 1,
    }
    job = job_manager.claim_job()
    assert job["id"] == 123
    assert job["claim_token"] == token


def test_claim_job_none_available(job_manager, mock_db):
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    mock_cursor.fetchone.return_value = None
    assert job_manager.claim_job() is None


def test_heartbeat_success_and_failures(job_manager, mock_db):
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    token = uuid.uuid4()
    future = datetime.now(timezone.utc) + timedelta(minutes=10)
    mock_cursor.fetchone.return_value = {"lease_until": future}
    assert job_manager.heartbeat(1, token) == future

    # Token mismatch
    mock_cursor.fetchone.side_effect = [
        None,
        {"status": "RUNNING", "claim_token": uuid.uuid4(), "lease_until": future, "db_now": datetime.now(timezone.utc)},
    ]
    with pytest.raises(ClaimTokenMismatchError):
        job_manager.heartbeat(1, token)

    # Lease expired
    now = datetime.now(timezone.utc)
    mock_cursor.fetchone.side_effect = [
        None,
        {"status": "RUNNING", "claim_token": token, "lease_until": now - timedelta(seconds=5), "db_now": now},
    ]
    with pytest.raises(LeaseExpiredError):
        job_manager.heartbeat(1, token)


def test_complete_job_checks_lease_and_sets_completed_at(job_manager, mock_db):
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    token = uuid.uuid4()
    now = datetime.now(timezone.utc)
    mock_cursor.fetchone.return_value = {"id": 10, "completed_at": now}

    job_manager.complete_job(10, token)
    sql = mock_cursor.execute.call_args[0][0]
    assert "completed_at = clock_timestamp()" in sql
    assert "lease_until > clock_timestamp()" in sql

    # Expired lease -> LeaseExpiredError (not silent completion!)
    mock_cursor.fetchone.side_effect = [
        None,
        {"status": "RUNNING", "claim_token": token, "lease_until": now - timedelta(seconds=1), "db_now": now},
    ]
    with pytest.raises(LeaseExpiredError):
        job_manager.complete_job(10, token)

    # Token mismatch -> ClaimTokenMismatchError
    mock_cursor.fetchone.side_effect = [
        None,
        {"status": "RUNNING", "claim_token": uuid.uuid4(), "lease_until": now + timedelta(minutes=5), "db_now": now},
    ]
    with pytest.raises(ClaimTokenMismatchError):
        job_manager.complete_job(10, token)


def test_fail_job_retry_permanent_and_lease_expired(job_manager, mock_db):
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    token = uuid.uuid4()
    now = datetime.now(timezone.utc)
    future = now + timedelta(minutes=5)

    # Attempt 1 -> retry (PENDING) and stores error_message
    mock_cursor.fetchone.side_effect = [
        {"id": 5, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now},
        {"run_after": future},
    ]
    outcome = job_manager.fail_job(5, token, "Timeout connecting to site")
    assert outcome == "PENDING"
    update_sql = mock_cursor.execute.call_args_list[1][0][0]
    assert "error_message = %(msg)s" in update_sql
    assert "FOR UPDATE" in mock_cursor.execute.call_args_list[0][0][0]

    # Attempt 3 (max_attempts=3) -> FAILED with completed_at and error_message
    mock_cursor.execute.reset_mock()
    mock_cursor.fetchone.side_effect = [
        {"id": 5, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 3, "db_now": now},
        {"id": 5},
    ]
    outcome = job_manager.fail_job(5, token, "Still failing")
    assert outcome == "FAILED"
    update_sql = mock_cursor.execute.call_args_list[1][0][0]
    assert "status = 'FAILED'" in update_sql
    assert "completed_at = clock_timestamp()" in update_sql
    assert "error_message = %(msg)s" in update_sql

    # Non-retryable on attempt 1 -> immediate FAILED
    mock_cursor.fetchone.side_effect = [
        {"id": 5, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now},
        {"id": 5},
    ]
    assert job_manager.fail_job(5, token, "Source not found", retryable=False) == "FAILED"

    # Lease expired -> raises LeaseExpiredError, refuses to update
    mock_cursor.fetchone.side_effect = [
        {"id": 5, "status": "RUNNING", "claim_token": token, "lease_until": now - timedelta(seconds=1), "attempt_no": 1, "db_now": now},
    ]
    with pytest.raises(LeaseExpiredError):
        job_manager.fail_job(5, token, "Late failure")


def test_reclaim_expired_jobs_respects_max_attempts(job_manager, mock_db):
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    now = datetime.now(timezone.utc)
    mock_cursor.fetchall.return_value = [
        {"id": 100, "attempt_no": 1, "lease_until": now - timedelta(minutes=2)},
        {"id": 101, "attempt_no": 3, "lease_until": now - timedelta(minutes=2)},
    ]
    mock_cursor.rowcount = 1

    count = job_manager.reclaim_expired_jobs()
    assert count == 2
    # Job 100 -> PENDING, Job 101 (attempt 3 >= max 3) -> FAILED
    sqls = [c[0][0] for c in mock_cursor.execute.call_args_list]
    assert "FOR UPDATE SKIP LOCKED" in sqls[0]
    assert "status = 'PENDING'" in sqls[1]
    assert "status = 'FAILED'" in sqls[2]


def test_verify_ownership_success_and_errors(job_manager, mock_db):
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    token = uuid.uuid4()
    now = datetime.now(timezone.utc)

    mock_cursor.fetchone.return_value = {"?column?": 1}
    job_manager.verify_ownership(123, token)

    mock_cursor.fetchone.side_effect = [
        None,
        {"status": "COMPLETED", "claim_token": token, "lease_until": None, "db_now": now},
    ]
    with pytest.raises(JobStateError):
        job_manager.verify_ownership(123, token)

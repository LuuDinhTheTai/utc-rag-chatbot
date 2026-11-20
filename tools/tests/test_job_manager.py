"""Tests for job manager."""

import pytest
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import Mock, MagicMock

from src.job_manager import JobManager
from src.database import Database
from src.exceptions import (
    ClaimTokenMismatchError,
    LeaseExpiredError,
    DatabaseError,
)


def setup_mock_transaction(mock_db, mock_cursor):
    """Helper to setup transaction and cursor context managers."""
    mock_conn = MagicMock()
    mock_db.transaction.return_value.__enter__ = MagicMock(return_value=mock_conn)
    mock_db.transaction.return_value.__exit__ = MagicMock(return_value=False)
    mock_conn.cursor.return_value.__enter__ = MagicMock(return_value=mock_cursor)
    mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)
    return mock_conn


@pytest.fixture
def mock_db():
    """Create mock database."""
    db = Mock(spec=Database)
    return db


@pytest.fixture
def job_manager(mock_db):
    """Create job manager with mock database."""
    return JobManager(mock_db, lease_duration_minutes=10)


def test_init(mock_db):
    """Test job manager initialization."""
    manager = JobManager(mock_db, lease_duration_minutes=15)
    assert manager.db == mock_db
    assert manager.lease_duration_minutes == 15
    assert manager.current_job_id is None
    assert manager.current_claim_token is None


def test_claim_job_success(job_manager, mock_db):
    """Test successful job claiming."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)

    claim_token = uuid.uuid4()
    lease_until = datetime.now(timezone.utc) + timedelta(minutes=10)

    mock_cursor.fetchone.return_value = {
        "id": 123,
        "source_id": 456,
        "status": "RUNNING",
        "claim_token": claim_token,
        "lease_until": lease_until,
        "attempt_no": 1,
    }

    result = job_manager.claim_job()

    assert result is not None
    assert result["id"] == 123
    assert job_manager.current_job_id == 123
    assert job_manager.current_claim_token == claim_token


def test_claim_job_none_available(job_manager, mock_db):
    """Test claiming when no jobs available."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    mock_cursor.fetchone.return_value = None

    result = job_manager.claim_job()

    assert result is None
    assert job_manager.current_job_id is None


def test_claim_job_database_error(job_manager, mock_db):
    """Test claim_job with database error."""
    mock_db.transaction.side_effect = Exception("Connection lost")

    with pytest.raises(DatabaseError, match="Job claiming failed"):
        job_manager.claim_job()


def test_heartbeat_success(job_manager, mock_db):
    """Test successful heartbeat."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)

    claim_token = uuid.uuid4()
    lease_until = datetime.now(timezone.utc) + timedelta(minutes=10)

    mock_cursor.fetchone.return_value = {"id": 123, "lease_until": lease_until}

    job_manager.heartbeat(123, claim_token)  # Should not raise


def test_heartbeat_token_mismatch(job_manager, mock_db):
    """Test heartbeat with token mismatch."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)

    claim_token = uuid.uuid4()
    different_token = uuid.uuid4()

    mock_cursor.fetchone.side_effect = [
        None,  # UPDATE failed
        {
            "status": "RUNNING",
            "claim_token": different_token,
            "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10),
        },
    ]

    with pytest.raises(ClaimTokenMismatchError):
        job_manager.heartbeat(123, claim_token)


def test_complete_job_success(job_manager, mock_db):
    """Test successful job completion."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)

    claim_token = uuid.uuid4()
    job_manager.current_job_id = 123
    job_manager.current_claim_token = claim_token

    mock_cursor.fetchone.return_value = {"id": 123}

    job_manager.complete_job(123, claim_token)

    assert job_manager.current_job_id is None


def test_fail_job_with_retry(job_manager, mock_db):
    """Test fail_job that schedules retry."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)

    claim_token = uuid.uuid4()

    mock_cursor.fetchone.side_effect = [
        {"attempt_no": 2},  # Still under max_attempts
        {"id": 123},  # UPDATE successful
    ]

    job_manager.fail_job(123, claim_token, "Network timeout", max_attempts=5)


def test_reclaim_expired_jobs_success(job_manager, mock_db):
    """Test reclaiming expired jobs."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)

    mock_cursor.fetchall.return_value = [
        {"id": 100, "source_id": 1},
        {"id": 101, "source_id": 2},
    ]

    count = job_manager.reclaim_expired_jobs()

    assert count == 2


def test_verify_ownership_success(job_manager, mock_db):
    """Test successful ownership verification."""
    claim_token = uuid.uuid4()
    db_now = datetime.now(timezone.utc)
    lease_until = db_now + timedelta(minutes=10)

    mock_db.execute_one.return_value = {
        "status": "RUNNING",
        "claim_token": claim_token,
        "lease_until": lease_until,
        "db_now": db_now,
    }

    job_manager.verify_ownership(123, claim_token)  # Should not raise


def test_verify_ownership_token_mismatch(job_manager, mock_db):
    """Test verify_ownership with token mismatch."""
    claim_token = uuid.uuid4()
    different_token = uuid.uuid4()

    mock_db.execute_one.return_value = {
        "status": "RUNNING",
        "claim_token": different_token,
        "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10),
    }

    with pytest.raises(ClaimTokenMismatchError):
        job_manager.verify_ownership(123, claim_token)

"""Regression tests for HIGH severity findings remediation (HIGH-001, HIGH-002, HIGH-003)."""

import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, Mock

import pytest

from src.database import Database
from src.embedder import OllamaEmbedder
from src.exceptions import ClaimTokenMismatchError, LeaseExpiredError
from src.job_manager import JobManager
from src.revision_processor import RevisionProcessor


def _setup_tx(mock_database, mock_cursor):
    mock_conn = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
    mock_conn.cursor.return_value.__exit__.return_value = False
    mock_database.transaction.return_value.__enter__.return_value = mock_conn
    mock_database.transaction.return_value.__exit__.return_value = False
    return mock_conn


@pytest.fixture
def mock_database():
    db = Mock(spec=Database)
    db.transaction = MagicMock()
    return db


@pytest.fixture
def mock_embedder():
    return Mock(spec=OllamaEmbedder)


class TestHIGH001_LeaseExpirationTiming:
    """Lease expiration checks use PostgreSQL clock_timestamp()."""

    def test_verify_ownership_uses_database_clock(self, mock_database):
        mock_cursor = MagicMock()
        _setup_tx(mock_database, mock_cursor)
        job_manager = JobManager(database=mock_database)
        token = uuid.uuid4()
        now = datetime.now(timezone.utc)

        mock_cursor.fetchone.side_effect = [
            None,
            {
                "claim_token": token,
                "status": "RUNNING",
                "lease_until": now - timedelta(minutes=5),
                "db_now": now,
            },
        ]

        with pytest.raises(LeaseExpiredError) as exc_info:
            job_manager.verify_ownership(123, token)

        assert "lease expired" in str(exc_info.value).lower()
        assert "database time" in str(exc_info.value).lower()
        assert "clock_timestamp()" in mock_cursor.execute.call_args_list[0][0][0].lower()

    def test_verify_ownership_passes_when_lease_valid(self, mock_database):
        mock_cursor = MagicMock()
        _setup_tx(mock_database, mock_cursor)
        job_manager = JobManager(database=mock_database)
        mock_cursor.fetchone.return_value = {"?column?": 1}

        job_manager.verify_ownership(123, uuid.uuid4())
        assert "clock_timestamp()" in mock_cursor.execute.call_args_list[0][0][0].lower()


class TestHIGH002_HeartbeatLeaseCheck:
    """Heartbeat cannot extend an expired lease."""

    def test_heartbeat_fails_on_expired_lease(self, mock_database):
        mock_cursor = MagicMock()
        _setup_tx(mock_database, mock_cursor)
        job_manager = JobManager(database=mock_database)
        token = uuid.uuid4()
        now = datetime.now(timezone.utc)

        mock_cursor.fetchone.side_effect = [
            None,
            {
                "claim_token": token,
                "status": "RUNNING",
                "lease_until": now - timedelta(minutes=1),
                "db_now": now,
            },
        ]

        with pytest.raises(LeaseExpiredError):
            job_manager.heartbeat(123, token)

    def test_heartbeat_update_includes_lease_check(self, mock_database):
        mock_cursor = MagicMock()
        _setup_tx(mock_database, mock_cursor)
        job_manager = JobManager(database=mock_database)
        mock_cursor.fetchone.return_value = {
            "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10),
        }

        job_manager.heartbeat(123, uuid.uuid4())
        query = mock_cursor.execute.call_args_list[0][0][0]
        assert "lease_until > clock_timestamp()" in query.lower()


class TestHIGH003_SafeCleanup:
    """Cleanup verifies token ownership and status in a transaction before deleting."""

    def test_cleanup_skips_when_token_mismatch(self, mock_database, mock_embedder):
        mock_cursor = MagicMock()
        _setup_tx(mock_database, mock_cursor)
        processor = RevisionProcessor(
            database=mock_database,
            embedder=mock_embedder,
            job_manager=JobManager(database=mock_database),
            embedding_model="test-model",
        )
        mock_cursor.fetchone.return_value = {
            "id": 456,
            "indexing_claim_token": uuid.uuid4(),
            "status": "INDEXING",
        }

        assert processor._cleanup_failed_revision(456, uuid.uuid4()) is False
        assert mock_cursor.execute.call_count == 1
        assert "FOR UPDATE" in mock_cursor.execute.call_args_list[0][0][0]

    def test_cleanup_skips_when_revision_not_indexing(self, mock_database, mock_embedder):
        mock_cursor = MagicMock()
        _setup_tx(mock_database, mock_cursor)
        processor = RevisionProcessor(
            database=mock_database,
            embedder=mock_embedder,
            job_manager=JobManager(database=mock_database),
            embedding_model="test-model",
        )
        token = uuid.uuid4()
        mock_cursor.fetchone.return_value = {
            "id": 456,
            "indexing_claim_token": token,
            "status": "PENDING",
        }

        assert processor._cleanup_failed_revision(456, token) is False
        assert mock_cursor.execute.call_count == 1

    def test_cleanup_proceeds_when_ownership_valid(self, mock_database, mock_embedder):
        mock_cursor = MagicMock()
        _setup_tx(mock_database, mock_cursor)
        processor = RevisionProcessor(
            database=mock_database,
            embedder=mock_embedder,
            job_manager=JobManager(database=mock_database),
            embedding_model="test-model",
        )
        token = uuid.uuid4()
        mock_cursor.fetchone.return_value = {
            "id": 456,
            "indexing_claim_token": token,
            "status": "INDEXING",
        }

        assert processor._cleanup_failed_revision(456, token) is True
        assert mock_cursor.execute.call_count == 3
        queries = [c[0][0] for c in mock_cursor.execute.call_args_list]
        assert any("DELETE FROM document_chunks" in q for q in queries)
        assert any("DELETE FROM document_revisions" in q for q in queries)

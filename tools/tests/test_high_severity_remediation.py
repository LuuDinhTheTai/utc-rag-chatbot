"""Regression tests for HIGH severity findings remediation.

Tests for HIGH-001, HIGH-002, HIGH-003 fixes.
"""

import pytest
import uuid
import time
from datetime import datetime, timezone, timedelta
from unittest.mock import Mock, MagicMock, patch

from src.database import Database
from src.job_manager import JobManager
from src.revision_processor import RevisionProcessor
from src.embedder import OllamaEmbedder
from src.exceptions import (
    LeaseExpiredError,
    ClaimTokenMismatchError,
    DatabaseError,
)


class TestHIGH001_LeaseExpirationTiming:
    """Test HIGH-001 fix: Lease expiration check uses database clock_timestamp()."""

    def test_verify_ownership_uses_database_clock(self, mock_database):
        """Verify that lease expiration uses database clock, not Python clock."""
        job_manager = JobManager(database=mock_database)

        # Mock database to return lease_until in past according to DB clock
        mock_result = {
            "id": 123,
            "claim_token": uuid.uuid4(),
            "status": "RUNNING",
            "lease_until": datetime.now(timezone.utc) - timedelta(minutes=5),  # 5 minutes ago
            "db_now": datetime.now(timezone.utc),  # Current DB time
        }

        mock_database.execute_one.return_value = mock_result

        # Should raise LeaseExpiredError based on database comparison
        with pytest.raises(LeaseExpiredError) as exc_info:
            job_manager.verify_ownership(123, mock_result["claim_token"])

        assert "lease expired" in str(exc_info.value).lower()
        assert "database time" in str(exc_info.value).lower()

    def test_verify_ownership_passes_when_lease_valid(self, mock_database):
        """Verify that valid lease passes verification."""
        job_manager = JobManager(database=mock_database)

        token = uuid.uuid4()
        mock_result = {
            "id": 123,
            "claim_token": token,
            "status": "RUNNING",
            "lease_until": datetime.now(timezone.utc) + timedelta(minutes=5),  # 5 minutes future
            "db_now": datetime.now(timezone.utc),
        }

        mock_database.execute_one.return_value = mock_result

        # Should not raise
        job_manager.verify_ownership(123, token)

    def test_verify_ownership_query_includes_clock_timestamp(self, mock_database):
        """Verify that query fetches clock_timestamp()."""
        job_manager = JobManager(database=mock_database)

        token = uuid.uuid4()
        mock_database.execute_one.return_value = {
            "claim_token": token,
            "status": "RUNNING",
            "lease_until": datetime.now(timezone.utc) + timedelta(minutes=5),
            "db_now": datetime.now(timezone.utc),
        }

        job_manager.verify_ownership(123, token)

        # Check that query includes clock_timestamp()
        call_args = mock_database.execute_one.call_args
        query = call_args[0][0]
        assert "clock_timestamp()" in query.lower()


class TestHIGH002_HeartbeatLeaseCheck:
    """Test HIGH-002 fix: Heartbeat cannot extend expired lease."""

    def test_heartbeat_fails_on_expired_lease(self, mock_database):
        """Heartbeat with expired lease should fail immediately."""
        job_manager = JobManager(database=mock_database)

        # Mock transaction and cursor
        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.__enter__.return_value = mock_conn
        mock_conn.__exit__.return_value = False
        mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
        mock_conn.cursor.return_value.__exit__.return_value = False

        mock_database.transaction.return_value.__enter__.return_value = mock_conn
        mock_database.transaction.return_value.__exit__.return_value = False

        token = uuid.uuid4()

        # First query (UPDATE) returns None because lease expired
        # Second query (SELECT) shows why
        mock_cursor.fetchone.side_effect = [
            None,  # UPDATE returned no rows
            {
                "claim_token": token,
                "status": "RUNNING",
                "lease_until": datetime.now(timezone.utc) - timedelta(minutes=1),
                "db_now": datetime.now(timezone.utc),
            },
        ]

        with pytest.raises(LeaseExpiredError):
            job_manager.heartbeat(123, token)

    def test_heartbeat_update_includes_lease_check(self, mock_database):
        """Verify UPDATE query checks lease_until > clock_timestamp()."""
        job_manager = JobManager(database=mock_database)

        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.__enter__.return_value = mock_conn
        mock_conn.__exit__.return_value = False
        mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
        mock_conn.cursor.return_value.__exit__.return_value = False

        mock_database.transaction.return_value.__enter__.return_value = mock_conn
        mock_database.transaction.return_value.__exit__.return_value = False

        token = uuid.uuid4()
        mock_cursor.fetchone.return_value = {
            "id": 123,
            "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10),
        }

        job_manager.heartbeat(123, token)

        # Check UPDATE query
        call_args = mock_cursor.execute.call_args_list[0]
        query = call_args[0][0]
        assert "lease_until > clock_timestamp()" in query.lower()

    def test_heartbeat_succeeds_when_lease_valid(self, mock_database):
        """Heartbeat should succeed when lease is still valid."""
        job_manager = JobManager(database=mock_database)

        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.__enter__.return_value = mock_conn
        mock_conn.__exit__.return_value = False
        mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
        mock_conn.cursor.return_value.__exit__.return_value = False

        mock_database.transaction.return_value.__enter__.return_value = mock_conn
        mock_database.transaction.return_value.__exit__.return_value = False

        token = uuid.uuid4()
        mock_cursor.fetchone.return_value = {
            "id": 123,
            "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10),
        }

        # Should not raise
        job_manager.heartbeat(123, token)


class TestHIGH003_SafeCleanup:
    """Test HIGH-003 fix: Cleanup verifies token ownership before deletion."""

    def test_cleanup_verifies_ownership_before_delete(self, mock_database, mock_embedder):
        """Cleanup should verify token ownership in same transaction."""
        job_manager = JobManager(database=mock_database)
        processor = RevisionProcessor(
            database=mock_database,
            embedder=mock_embedder,
            job_manager=job_manager,
            embedding_model="test-model",
        )

        # Mock transaction
        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.__enter__.return_value = mock_conn
        mock_conn.__exit__.return_value = False
        mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
        mock_conn.cursor.return_value.__exit__.return_value = False

        mock_database.transaction.return_value.__enter__.return_value = mock_conn
        mock_database.transaction.return_value.__exit__.return_value = False

        token = uuid.uuid4()
        
        # Mock ownership verification returns valid ownership
        mock_cursor.fetchone.return_value = {
            "id": 456,
            "indexing_claim_token": token,
            "status": "INDEXING",
        }

        processor._cleanup_failed_revision(456, token)

        # Verify SELECT ... FOR UPDATE was called first
        first_query = mock_cursor.execute.call_args_list[0][0][0]
        assert "FOR UPDATE" in first_query
        assert "indexing_claim_token" in first_query

    def test_cleanup_skips_when_token_mismatch(self, mock_database, mock_embedder):
        """Cleanup should skip deletion if token doesn't match (revision reclaimed)."""
        job_manager = JobManager(database=mock_database)
        processor = RevisionProcessor(
            database=mock_database,
            embedder=mock_embedder,
            job_manager=job_manager,
            embedding_model="test-model",
        )

        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.__enter__.return_value = mock_conn
        mock_conn.__exit__.return_value = False
        mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
        mock_cursor.execute.return_value = None
        mock_cursor.fetchone.return_value = {
            "id": 456,
            "indexing_claim_token": uuid.uuid4(),  # Different token!
            "status": "INDEXING",
        }
        mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
        mock_conn.cursor.return_value.__exit__.return_value = False

        mock_database.transaction.return_value.__enter__.return_value = mock_conn
        mock_database.transaction.return_value.__exit__.return_value = False

        our_token = uuid.uuid4()
        
        # Should not raise, but should skip deletion
        processor._cleanup_failed_revision(456, our_token)

        # Verify DELETE was NOT called (only 1 query: the SELECT FOR UPDATE)
        assert mock_cursor.execute.call_count == 1
        first_query = mock_cursor.execute.call_args_list[0][0][0]
        assert "DELETE" not in first_query

    def test_cleanup_skips_when_revision_not_indexing(self, mock_database, mock_embedder):
        """Cleanup should skip if revision is no longer INDEXING."""
        job_manager = JobManager(database=mock_database)
        processor = RevisionProcessor(
            database=mock_database,
            embedder=mock_embedder,
            job_manager=job_manager,
            embedding_model="test-model",
        )

        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.__enter__.return_value = mock_conn
        mock_conn.__exit__.return_value = False
        mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
        mock_conn.cursor.return_value.__exit__.return_value = False

        mock_database.transaction.return_value.__enter__.return_value = mock_conn
        mock_database.transaction.return_value.__exit__.return_value = False

        token = uuid.uuid4()
        mock_cursor.fetchone.return_value = {
            "id": 456,
            "indexing_claim_token": token,
            "status": "PENDING",  # No longer INDEXING!
        }

        processor._cleanup_failed_revision(456, token)

        # Should skip deletion
        assert mock_cursor.execute.call_count == 1

    def test_cleanup_proceeds_when_ownership_valid(self, mock_database, mock_embedder):
        """Cleanup should proceed when we still own the revision."""
        job_manager = JobManager(database=mock_database)
        processor = RevisionProcessor(
            database=mock_database,
            embedder=mock_embedder,
            job_manager=job_manager,
            embedding_model="test-model",
        )

        mock_conn = MagicMock()
        mock_cursor = MagicMock()
        mock_conn.__enter__.return_value = mock_conn
        mock_conn.__exit__.return_value = False
        mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
        mock_conn.cursor.return_value.__exit__.return_value = False

        mock_database.transaction.return_value.__enter__.return_value = mock_conn
        mock_database.transaction.return_value.__exit__.return_value = False

        token = uuid.uuid4()
        mock_cursor.fetchone.return_value = {
            "id": 456,
            "indexing_claim_token": token,
            "status": "INDEXING",
        }

        processor._cleanup_failed_revision(456, token)

        # Should have 3 queries: SELECT, DELETE chunks, DELETE revision
        assert mock_cursor.execute.call_count == 3

        # Verify DELETE queries were called
        queries = [call[0][0] for call in mock_cursor.execute.call_args_list]
        assert any("DELETE FROM document_chunks" in q for q in queries)
        assert any("DELETE FROM document_revisions" in q for q in queries)


@pytest.fixture
def mock_database():
    """Mock database for unit testing."""
    db = Mock(spec=Database)
    db.transaction = MagicMock()
    return db


@pytest.fixture
def mock_embedder():
    """Mock embedder for testing."""
    embedder = Mock(spec=OllamaEmbedder)
    return embedder


if __name__ == "__main__":
    pytest.main([__file__, "-v"])

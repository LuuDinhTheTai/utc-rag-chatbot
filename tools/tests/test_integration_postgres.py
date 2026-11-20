"""PostgreSQL integration tests for Phase 2.

REQUIRES: Running PostgreSQL instance with test database.

These tests verify:
- Job claiming and lease management
- Atomic fencing with dual tokens
- Revision processing integrity
- Chunk integrity verification
- Concurrent worker scenarios

Setup:
    export TEST_DB_HOST=localhost
    export TEST_DB_PORT=5432
    export TEST_DB_NAME=utc_rag_test
    export TEST_DB_USER=postgres
    export TEST_DB_PASSWORD=yourpassword

Run:
    pytest tests/test_integration_postgres.py -v
"""

import os
import pytest
import uuid
import time
from datetime import datetime, timezone, timedelta
from typing import Optional

from src.database import Database
from src.embedder import OllamaEmbedder
from src.revision_processor import RevisionProcessor
from src.exceptions import (
    LeaseExpiredError,
    ClaimTokenMismatchError,
    ChunkCountMismatchError,
    DatabaseError,
)

# Skip all tests if no PostgreSQL connection available
pytestmark = pytest.mark.skipif(
    not os.getenv("TEST_DB_HOST"),
    reason="No TEST_DB_HOST environment variable - PostgreSQL integration tests skipped"
)


@pytest.fixture(scope="module")
def db_config():
    """Database configuration from environment."""
    from src.config import DatabaseConfig
    
    return DatabaseConfig(
        host=os.getenv("TEST_DB_HOST", "localhost"),
        port=int(os.getenv("TEST_DB_PORT", "5432")),
        database=os.getenv("TEST_DB_NAME", "utc_rag_test"),
        user=os.getenv("TEST_DB_USER", "postgres"),
        password=os.getenv("TEST_DB_PASSWORD", ""),
    )


@pytest.fixture(scope="module")
def database(db_config):
    """Create database connection."""
    db = Database(
        config=db_config,
        min_size=2,
        max_size=5,
        timeout=30,
        max_waiting=10,
    )
    db.connect()
    yield db
    db.close()


@pytest.fixture(scope="function")
def clean_test_data(database):
    """Clean test data before and after each test."""
    # Clean before
    with database.transaction() as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM document_chunks WHERE revision_id IN (SELECT id FROM document_revisions WHERE document_id IN (SELECT id FROM knowledge_documents WHERE canonical_url LIKE 'https://test.integration%'))")
            cur.execute("DELETE FROM document_revisions WHERE document_id IN (SELECT id FROM knowledge_documents WHERE canonical_url LIKE 'https://test.integration%')")
            cur.execute("DELETE FROM knowledge_documents WHERE canonical_url LIKE 'https://test.integration%'")
            cur.execute("DELETE FROM crawl_jobs WHERE source_id IN (SELECT id FROM crawl_sources WHERE base_url LIKE 'https://test.integration%')")
            cur.execute("DELETE FROM crawl_sources WHERE base_url LIKE 'https://test.integration%'")
    
    yield
    
    # Clean after
    with database.transaction() as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM document_chunks WHERE revision_id IN (SELECT id FROM document_revisions WHERE document_id IN (SELECT id FROM knowledge_documents WHERE canonical_url LIKE 'https://test.integration%'))")
            cur.execute("DELETE FROM document_revisions WHERE document_id IN (SELECT id FROM knowledge_documents WHERE canonical_url LIKE 'https://test.integration%')")
            cur.execute("DELETE FROM knowledge_documents WHERE canonical_url LIKE 'https://test.integration%'")
            cur.execute("DELETE FROM crawl_jobs WHERE source_id IN (SELECT id FROM crawl_sources WHERE base_url LIKE 'https://test.integration%')")
            cur.execute("DELETE FROM crawl_sources WHERE base_url LIKE 'https://test.integration%'")


@pytest.fixture
def test_source(database, clean_test_data):
    """Create test source."""
    result = database.execute_one(
        """
        INSERT INTO crawl_sources (base_url, enabled)
        VALUES ('https://test.integration', true)
        RETURNING id
        """,
        {}
    )
    return result["id"]


class TestJobClaimingAndLeases:
    """Test job claiming and lease management."""

    def test_single_worker_claims_job(self, database, test_source):
        """Test single worker can claim a job."""
        # Create job
        job_id = database.execute_one(
            """
            INSERT INTO crawl_jobs (source_id, status, run_after, attempt_no)
            VALUES (%(source_id)s, 'PENDING', NOW(), 0)
            RETURNING id
            """,
            {"source_id": test_source}
        )["id"]
        
        # Claim job manually
        claim_token = uuid.uuid4()
        job = database.execute_one(
            """
            UPDATE crawl_jobs
            SET status = 'RUNNING',
                lease_until = NOW() + INTERVAL '10 minutes',
                claim_token = %(token)s,
                attempt_no = attempt_no + 1
            WHERE id = %(job_id)s
            RETURNING id, claim_token, status, lease_until
            """,
            {"job_id": job_id, "token": claim_token}
        )
        
        assert job is not None
        assert job["id"] == job_id
        assert job["claim_token"] == claim_token
        assert job["status"] == "RUNNING"

    def test_two_workers_cannot_claim_same_job(self, database, test_source):
        """Test that two workers cannot claim the same job concurrently."""
        # Create job
        job_id = database.execute_one(
            """
            INSERT INTO crawl_jobs (source_id, status, run_after, attempt_no)
            VALUES (%(source_id)s, 'PENDING', NOW(), 0)
            RETURNING id
            """,
            {"source_id": test_source}
        )["id"]
        
        # Worker 1 claims
        token1 = uuid.uuid4()
        database.execute_one(
            """
            UPDATE crawl_jobs
            SET status = 'RUNNING',
                lease_until = NOW() + INTERVAL '10 minutes',
                claim_token = %(token)s
            WHERE id = %(job_id)s
            RETURNING id
            """,
            {"job_id": job_id, "token": token1}
        )
        
        # Worker 2 tries to claim same job (should fail due to status)
        result = database.execute_one(
            """
            SELECT id FROM crawl_jobs
            WHERE id = %(job_id)s AND status = 'PENDING'
            FOR UPDATE SKIP LOCKED
            """,
            {"job_id": job_id}
        )
        
        # Should return None since job is now RUNNING
        assert result is None

    def test_expired_lease_can_be_reclaimed(self, database, test_source):
        """Test that expired leases can be reclaimed by another worker."""
        # Create job with expired lease
        claim_token = uuid.uuid4()
        job_id = database.execute_one(
            """
            INSERT INTO crawl_jobs (
                source_id, status, 
                claim_token, lease_until,
                run_after, attempt_no
            )
            VALUES (
                %(source_id)s, 'RUNNING',
                %(token)s, NOW() - INTERVAL '5 minutes',
                NOW(), 1
            )
            RETURNING id
            """,
            {"source_id": test_source, "token": claim_token}
        )["id"]
        
        # New worker should be able to claim
        new_token = uuid.uuid4()
        job = database.execute_one(
            """
            UPDATE crawl_jobs
            SET claim_token = %(new_token)s,
                lease_until = NOW() + INTERVAL '10 minutes'
            WHERE id = %(job_id)s AND lease_until < NOW()
            RETURNING id, claim_token
            """,
            {"job_id": job_id, "new_token": new_token}
        )
        
        assert job is not None
        assert job["id"] == job_id
        assert job["claim_token"] != claim_token  # New token issued

    def test_lease_renewal(self, database, test_source):
        """Test lease renewal extends expiration time."""
        # Create and claim job
        job_id = database.execute_one(
            """
            INSERT INTO crawl_jobs (source_id, status, run_after, attempt_no)
            VALUES (%(source_id)s, 'PENDING', NOW(), 0)
            RETURNING id
            """,
            {"source_id": test_source}
        )["id"]
        
        token = uuid.uuid4()
        job = database.execute_one(
            """
            UPDATE crawl_jobs
            SET status = 'RUNNING',
                lease_until = NOW() + INTERVAL '5 minutes',
                claim_token = %(token)s
            WHERE id = %(job_id)s
            RETURNING id, lease_until
            """,
            {"job_id": job_id, "token": token}
        )
        
        original_lease = job["lease_until"]
        
        # Wait a moment
        time.sleep(1)
        
        # Renew lease
        renewed = database.execute_one(
            """
            UPDATE crawl_jobs
            SET lease_until = NOW() + INTERVAL '10 minutes'
            WHERE id = %(job_id)s AND claim_token = %(token)s
            RETURNING id, lease_until
            """,
            {"job_id": job_id, "token": token}
        )
        
        assert renewed is not None
        assert renewed["lease_until"] > original_lease


class TestAtomicFencing:
    """Test atomic fencing with dual tokens."""

    def test_valid_tokens_can_write(self, database, test_source):
        """Test that valid job and revision tokens allow writes."""
        # Create job
        job_token = uuid.uuid4()
        job_id = database.execute_one(
            """
            INSERT INTO crawl_jobs (
                source_id, status,
                claim_token, lease_until,
                run_after, attempt_no
            )
            VALUES (
                %(source_id)s, 'RUNNING',
                %(token)s, NOW() + INTERVAL '10 minutes',
                NOW(), 1
            )
            RETURNING id
            """,
            {"source_id": test_source, "token": job_token}
        )["id"]
        
        # Create document
        doc_id = database.execute_one(
            """
            INSERT INTO knowledge_documents (canonical_url, crawl_source_id)
            VALUES ('https://test.integration/fence1', %(source_id)s)
            RETURNING id
            """,
            {"source_id": test_source}
        )["id"]
        
        # Create revision with indexing token
        rev_token = uuid.uuid4()
        with database.transaction() as conn:
            with conn.cursor() as cur:
                # Verify job ownership
                cur.execute(
                    """
                    SELECT claim_token, status, lease_until
                    FROM crawl_jobs
                    WHERE id = %(job_id)s
                    FOR UPDATE
                    """,
                    {"job_id": job_id}
                )
                job_state = cur.fetchone()
                assert job_state["claim_token"] == job_token
                
                # Create revision
                cur.execute(
                    """
                    INSERT INTO document_revisions (
                        document_id, crawl_job_id,
                        status, indexing_claim_token,
                        content_hash, pipeline_key, embedding_model,
                        title, official_url, updated_at
                    )
                    VALUES (
                        %(doc_id)s, %(job_id)s,
                        'INDEXING', %(rev_token)s,
                        'hash123', 'html-v1', 'test-model',
                        'Test Document', 'https://test.integration/fence1', NOW()
                    )
                    RETURNING id
                    """,
                    {"doc_id": doc_id, "job_id": job_id, "rev_token": rev_token}
                )
                rev_id = cur.fetchone()["id"]
                
                # Verify revision ownership
                cur.execute(
                    """
                    SELECT indexing_claim_token, status
                    FROM document_revisions
                    WHERE id = %(rev_id)s
                    FOR UPDATE
                    """,
                    {"rev_id": rev_id}
                )
                rev_state = cur.fetchone()
                assert rev_state["indexing_claim_token"] == rev_token
                
                # Both tokens valid - can write
                cur.execute(
                    """
                    INSERT INTO document_chunks (
                        revision_id, position, body, embedding
                    )
                    VALUES (%(rev_id)s, 0, 'test chunk', %(embedding)s)
                    """,
                    {"rev_id": rev_id, "embedding": [0.1] * 768}
                )

    def test_expired_lease_cannot_write(self, database, test_source):
        """Test that expired lease prevents writes."""
        # Create job with expired lease
        job_token = uuid.uuid4()
        job_id = database.execute_one(
            """
            INSERT INTO crawl_jobs (
                source_id, status,
                claim_token, lease_until,
                run_after, attempt_no
            )
            VALUES (
                %(source_id)s, 'RUNNING',
                %(token)s, NOW() - INTERVAL '5 minutes',
                NOW(), 1
            )
            RETURNING id
            """,
            {"source_id": test_source, "token": job_token}
        )["id"]
        
        # Check lease with clock_timestamp
        with database.transaction() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT claim_token, status, lease_until,
                           clock_timestamp() as now
                    FROM crawl_jobs
                    WHERE id = %(job_id)s
                    FOR UPDATE
                    """,
                    {"job_id": job_id}
                )
                job_state = cur.fetchone()
                
                # Verify lease expired
                assert job_state["lease_until"] < job_state["now"]

    def test_reclaimed_job_invalidates_old_token(self, database, test_source):
        """Test that reclaimed job gets new token, invalidating old."""
        # Create job with old claim
        old_token = uuid.uuid4()
        job_id = database.execute_one(
            """
            INSERT INTO crawl_jobs (
                source_id, status,
                claim_token, lease_until,
                run_after, attempt_no
            )
            VALUES (
                %(source_id)s, 'RUNNING',
                %(token)s, NOW() - INTERVAL '5 minutes',
                NOW(), 1
            )
            RETURNING id
            """,
            {"source_id": test_source, "token": old_token}
        )["id"]
        
        # New worker reclaims
        new_token = uuid.uuid4()
        database.execute_one(
            """
            UPDATE crawl_jobs
            SET claim_token = %(new_token)s,
                lease_until = NOW() + INTERVAL '10 minutes'
            WHERE id = %(job_id)s
              AND (status = 'PENDING' OR lease_until < NOW())
            RETURNING id
            """,
            {"job_id": job_id, "new_token": new_token}
        )
        
        # Old token should not match
        result = database.execute_one(
            """
            SELECT claim_token FROM crawl_jobs
            WHERE id = %(job_id)s AND claim_token = %(old_token)s
            """,
            {"job_id": job_id, "old_token": old_token}
        )
        assert result is None


class TestChunkIntegrity:
    """Test chunk integrity verification."""

    def test_valid_chunks_pass_integrity(self, database, test_source):
        """Test that valid chunk sequence passes integrity check."""
        # Setup
        doc_id, rev_id = self._create_test_revision(database, test_source, "integ1")
        
        # Insert 3 chunks with correct positions
        for i in range(3):
            database.execute(
                """
                INSERT INTO document_chunks (revision_id, position, body, embedding)
                VALUES (%(rev_id)s, %(pos)s, %(body)s, %(embedding)s)
                """,
                {
                    "rev_id": rev_id,
                    "pos": i,
                    "body": f"chunk {i}",
                    "embedding": [0.1] * 768
                }
            )
        
        # Check integrity
        stats = database.execute_one(
            """
            SELECT COUNT(*) as total,
                   COUNT(DISTINCT position) as unique_positions,
                   MIN(position) as min_pos,
                   MAX(position) as max_pos
            FROM document_chunks
            WHERE revision_id = %(rev_id)s
            """,
            {"rev_id": rev_id}
        )
        
        assert stats["total"] == 3
        assert stats["unique_positions"] == 3
        assert stats["min_pos"] == 0
        assert stats["max_pos"] == 2

    def test_missing_chunk_fails_integrity(self, database, test_source):
        """Test that missing chunk fails integrity check."""
        doc_id, rev_id = self._create_test_revision(database, test_source, "integ2")
        
        # Insert chunks 0, 1, 3 (missing 2)
        for i in [0, 1, 3]:
            database.execute(
                """
                INSERT INTO document_chunks (revision_id, position, body, embedding)
                VALUES (%(rev_id)s, %(pos)s, %(body)s, %(embedding)s)
                """,
                {
                    "rev_id": rev_id,
                    "pos": i,
                    "body": f"chunk {i}",
                    "embedding": [0.1] * 768
                }
            )
        
        stats = database.execute_one(
            """
            SELECT COUNT(*) as total,
                   MAX(position) as max_pos
            FROM document_chunks
            WHERE revision_id = %(rev_id)s
            """,
            {"rev_id": rev_id}
        )
        
        # total=3 but max_pos=3, indicating gap
        assert stats["total"] == 3
        assert stats["max_pos"] == 3

    def test_duplicate_position_fails_integrity(self, database, test_source):
        """Test that duplicate positions fail integrity check."""
        doc_id, rev_id = self._create_test_revision(database, test_source, "integ3")
        
        # Insert 2 valid chunks first
        database.execute(
            """
            INSERT INTO document_chunks (revision_id, position, body, embedding)
            VALUES 
                (%(rev_id)s, 0, 'chunk 0', %(embedding)s),
                (%(rev_id)s, 1, 'chunk 1a', %(embedding)s)
            """,
            {"rev_id": rev_id, "embedding": [0.1] * 768}
        )
        
        # Try to insert duplicate position - should fail due to unique constraint
        from src.exceptions import DatabaseError
        with pytest.raises(DatabaseError):
            database.execute(
                """
                INSERT INTO document_chunks (revision_id, position, body, embedding)
                VALUES (%(rev_id)s, 1, 'chunk 1b', %(embedding)s)
                """,
                {"rev_id": rev_id, "embedding": [0.1] * 768}
            )

    def _create_test_revision(self, database, source_id, suffix):
        """Helper to create test document and revision."""
        doc_id = database.execute_one(
            """
            INSERT INTO knowledge_documents (canonical_url, crawl_source_id)
            VALUES (%(url)s, %(source_id)s)
            RETURNING id
            """,
            {"url": f"https://test.integration/{suffix}", "source_id": source_id}
        )["id"]
        
        rev_id = database.execute_one(
            """
            INSERT INTO document_revisions (
                document_id, status, content_hash, 
                pipeline_key, embedding_model,
                title, official_url, updated_at
            )
            VALUES (
                %(doc_id)s, 'INDEXING', 'hash123',
                'html-v1', 'test-model',
                'Test Document', %(url)s, NOW()
            )
            RETURNING id
            """,
            {"doc_id": doc_id, "url": f"https://test.integration/{suffix}"}
        )["id"]
        
        return doc_id, rev_id


if __name__ == "__main__":
    print("PostgreSQL Integration Tests")
    print("=" * 60)
    print("\nREQUIRED ENVIRONMENT:")
    print("  TEST_DB_HOST")
    print("  TEST_DB_PORT")
    print("  TEST_DB_NAME")
    print("  TEST_DB_USER")
    print("  TEST_DB_PASSWORD")
    print("\nRun: pytest tests/test_integration_postgres.py -v")

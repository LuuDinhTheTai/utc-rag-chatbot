"""End-to-end integration tests against real PostgreSQL (as utc_worker) and Ollama.

Skipped automatically unless ``AUDIT_TEST_DB_HOST`` is set so normal offline unit
test runs remain fast and self-contained.
"""

import os
import uuid
from unittest.mock import MagicMock

import psycopg
import pytest

from src.chunker import Chunker
from src.config import CrawlerConfig, DatabaseConfig, OllamaConfig
from src.crawler import CrawlEngine
from src.database import Database
from src.embedder import OllamaEmbedder
from src.exceptions import (
    ClaimTokenMismatchError,
    DatabaseError,
    LeaseExpiredError,
)
from src.job_context import JobContext
from src.job_manager import JobManager, RetryPolicy
from src.revision_processor import RevisionProcessor
from src.scheduler import CrawlScheduler
from src.source_registry import SourceRegistry

pytestmark = pytest.mark.skipif(
    not os.getenv("AUDIT_TEST_DB_HOST"),
    reason="Set AUDIT_TEST_DB_HOST to run live PostgreSQL + Ollama integration tests",
)


@pytest.fixture(scope="module")
def worker_db():
    cfg = DatabaseConfig(
        host=os.environ["AUDIT_TEST_DB_HOST"],
        port=int(os.environ.get("AUDIT_TEST_DB_PORT", "5432")),
        database=os.environ["AUDIT_TEST_DB_NAME"],
        user=os.environ.get("AUDIT_TEST_DB_USER", "utc_worker"),
        password=os.environ["AUDIT_TEST_DB_PASSWORD"],
    )
    db = Database(cfg, min_size=1, max_size=4)
    db.connect()
    yield db
    db.close()


@pytest.fixture(scope="module")
def ollama_embedder():
    cfg = OllamaConfig(
        base_url=os.environ.get("AUDIT_OLLAMA_URL", "http://ai:11434"),
        slm_model=os.environ.get("SLM_MODEL", "qwen3:0.6b"),
        embedding_model=os.environ.get("EMBEDDING_MODEL", "embeddinggemma:300m-qat-q4_0"),
        embedding_dimension=768,
    )
    emb = OllamaEmbedder(cfg)
    yield emb
    emb.close()


def test_least_privilege_enforced_for_utc_worker(worker_db):
    """utc_worker must be blocked from DELETE on crawl_jobs, UPDATE/DELETE on knowledge_documents, and SELECT on users."""
    with pytest.raises(DatabaseError):
        worker_db.execute("SELECT * FROM users LIMIT 1")
    with pytest.raises(DatabaseError):
        worker_db.execute("DELETE FROM crawl_jobs WHERE id = -1")
    with pytest.raises(DatabaseError):
        worker_db.execute("DELETE FROM knowledge_documents WHERE id = -1")


def test_full_scheduler_worker_crawler_revision_ollama_flow(worker_db, ollama_embedder):
    """Scheduler -> PostgreSQL (utc_worker) -> JobManager -> CrawlEngine -> RevisionProcessor -> Ollama -> DB."""
    assert ollama_embedder.health_check() is True

    # 1. Scheduler sees enabled source (seeded by test runner) and creates exactly 1 job
    scheduler = CrawlScheduler(worker_db, default_interval_hours=6, register_signals=False)
    assert scheduler._verify_schema_ready() is True
    created1 = scheduler.schedule_once()
    assert created1 >= 1

    # Second pass immediately must NOT create a duplicate job (active PENDING exists)
    created2 = scheduler.schedule_once()
    assert created2 == 0

    # 2. Worker claims job, heartbeats, and verifies ownership
    jm = JobManager(
        worker_db,
        lease_duration_seconds=600,
        retry_policy=RetryPolicy(max_attempts=3, base_delay_seconds=60, max_delay_seconds=600, jitter_ratio=0.0),
    )
    job = jm.claim_job()
    assert job is not None
    job_id = job["id"]
    token = job["claim_token"]

    jm.heartbeat(job_id, token)
    jm.verify_ownership(job_id, token)

    # Stale worker with wrong token is rejected on heartbeat, complete, and fail
    wrong_token = uuid.uuid4()
    with pytest.raises(ClaimTokenMismatchError):
        jm.heartbeat(job_id, wrong_token)
    with pytest.raises(ClaimTokenMismatchError):
        jm.complete_job(job_id, wrong_token)
    with pytest.raises(ClaimTokenMismatchError):
        jm.fail_job(job_id, wrong_token, "stale")

    # 3. CrawlEngine + RevisionProcessor + real Ollama embedding + real DB persistence
    registry = SourceRegistry(worker_db)
    source = registry.get_source_by_id(job["source_id"])
    assert source is not None

    rp = RevisionProcessor(
        database=worker_db,
        embedder=ollama_embedder,
        job_manager=jm,
        embedding_model=ollama_embedder.config.embedding_model,
        chunker=Chunker(min_chunk_size=20, target_chunk_size=200, max_chunk_size=400, overlap_size=20),
    )

    sample_html = (
        "<html><head><title><span>Tuyển sinh</span> UTC 2026</title></head>"
        "<body><h1>Thông tin tuyển sinh</h1>"
        "<p>Trường Đại học Giao thông Vận tải thông báo chỉ tiêu tuyển sinh đại học chính quy năm 2026.</p>"
        "</body></html>"
    ).encode("utf-8")

    mock_http = MagicMock()
    mock_http.fetch.return_value = (sample_html, "text/html", None)

    crawler_cfg = CrawlerConfig(default_rate_limit_seconds=0.0, respect_robots_txt=False, max_discovery_depth=0)
    engine = CrawlEngine(
        config=crawler_cfg,
        database=worker_db,
        job_manager=jm,
        revision_processor=rp,
        http_client=mock_http,
    )
    engine.security_validator = MagicMock()

    ctx = JobContext(job_id=job_id, claim_token=token)
    report = engine.crawl_source(source, job_id, token, job_context=ctx)
    assert report.status == "completed"
    assert report.success_count == 1
    assert report.new_count == 1

    # Verify DB state: revision is PENDING and chunks have 768-dim embeddings
    rev_id = report.results[0].revision_id
    rev_row = worker_db.execute_one(
        "SELECT status, expected_chunk_count, indexing_claim_token FROM document_revisions WHERE id = %(id)s",
        {"id": rev_id},
    )
    assert rev_row["status"] == "PENDING"
    assert rev_row["indexing_claim_token"] is None

    chunk_row = worker_db.execute_one(
        "SELECT COUNT(*) AS cnt, MIN(vector_dims(embedding)) AS dims FROM document_chunks WHERE revision_id = %(id)s",
        {"id": rev_id},
    )
    assert chunk_row["cnt"] == rev_row["expected_chunk_count"]
    assert chunk_row["dims"] == 768

    # Verify pgvector adapter decodes embedding on SELECT as a 768-float sequence (not raw str)
    vec_row = worker_db.execute_one(
        "SELECT embedding FROM document_chunks WHERE revision_id = %(id)s ORDER BY position LIMIT 1",
        {"id": rev_id},
    )
    assert not isinstance(vec_row["embedding"], str)
    assert len(vec_row["embedding"]) == 768

    # Re-processing identical content must reuse existing PENDING revision (unchanged)
    report2 = engine.crawl_source(source, job_id, token, job_context=ctx)
    assert report2.unchanged_count == 1

    # 4. Complete job -> sets completed_at -> scheduler skips because interval hasn't elapsed
    jm.complete_job(job_id, token)
    done_row = worker_db.execute_one(
        "SELECT status, completed_at, claim_token, lease_until FROM crawl_jobs WHERE id = %(id)s",
        {"id": job_id},
    )
    assert done_row["status"] == "COMPLETED"
    assert done_row["completed_at"] is not None
    assert done_row["claim_token"] is None
    assert done_row["lease_until"] is None

    # Scheduler must NOT schedule immediately after completion
    assert scheduler.schedule_once() == 0


def test_expired_lease_blocks_complete_and_reclaim_works(worker_db):
    """Expired lease cannot complete; reclaim_expired_jobs resets job and clears token."""
    jm = JobManager(
        worker_db,
        lease_duration_seconds=600,
        retry_policy=RetryPolicy(max_attempts=3, base_delay_seconds=0, max_delay_seconds=60, jitter_ratio=0.0),
    )

    # Create a job manually via scheduler helper
    src = worker_db.execute_one("SELECT id, base_url FROM crawl_sources WHERE enabled = true LIMIT 1")
    sched = CrawlScheduler(worker_db, register_signals=False)
    jid = sched._create_job(src)
    assert jid is not None

    job = jm.claim_job()
    assert job["id"] == jid
    tok = job["claim_token"]

    # Force lease expiry in DB
    worker_db.execute(
        "UPDATE crawl_jobs SET lease_until = clock_timestamp() - interval '10 seconds' WHERE id = %(id)s",
        {"id": jid},
    )

    with pytest.raises(LeaseExpiredError):
        jm.complete_job(jid, tok)

    reclaimed = jm.reclaim_expired_jobs()
    assert reclaimed >= 1

    row = worker_db.execute_one(
        "SELECT status, claim_token, error_message FROM crawl_jobs WHERE id = %(id)s",
        {"id": jid},
    )
    assert row["status"] == "PENDING"
    assert row["claim_token"] is None
    assert "Lease expired" in (row["error_message"] or "")


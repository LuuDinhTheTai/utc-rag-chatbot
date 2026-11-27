"""Tests for RevisionProcessor: ownership fencing, status filtering, conflict recovery, empty content, cleanup."""

import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, Mock

import pytest

from src.chunker import Chunker
from src.database import Database
from src.embedder import OllamaEmbedder
from src.exceptions import (
    ChunkCountMismatchError,
    ClaimTokenMismatchError,
    EmptyContentError,
    LeaseExpiredError,
    UnsupportedContentTypeError,
)
from src.job_manager import JobManager
from src.revision_processor import (
    PIPELINE_DOCX,
    PIPELINE_HTML,
    PIPELINE_PDF,
    RevisionProcessor,
)


def _setup_tx(mock_db, mock_cursor):
    mock_conn = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
    mock_conn.cursor.return_value.__exit__.return_value = False
    mock_db.transaction = MagicMock()
    mock_db.transaction.return_value.__enter__.return_value = mock_conn
    mock_db.transaction.return_value.__exit__.return_value = False
    return mock_conn


@pytest.fixture
def mock_db():
    return Mock(spec=Database)


@pytest.fixture
def mock_embedder():
    emb = Mock(spec=OllamaEmbedder)
    emb.embed_batch.side_effect = lambda texts, job_context=None: [[0.1] * 768 for _ in texts]
    return emb


@pytest.fixture
def job_manager(mock_db):
    return JobManager(mock_db, lease_duration_seconds=600)


@pytest.fixture
def processor(mock_db, mock_embedder, job_manager):
    return RevisionProcessor(
        database=mock_db,
        embedder=mock_embedder,
        job_manager=job_manager,
        embedding_model="embeddinggemma:300m-qat-q4_0",
        chunker=Chunker(min_chunk_size=10, target_chunk_size=100, max_chunk_size=200, overlap_size=0),
    )


def test_pipeline_key_mapping_and_doc_rejection(processor):
    assert processor._get_pipeline_key("text/html") == PIPELINE_HTML
    assert processor._get_pipeline_key("application/xhtml+xml") == PIPELINE_HTML
    assert processor._get_pipeline_key("application/pdf") == PIPELINE_PDF
    assert processor._get_pipeline_key(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ) == PIPELINE_DOCX

    with pytest.raises(UnsupportedContentTypeError, match="Legacy .doc"):
        processor._get_pipeline_key("application/msword")
    with pytest.raises(UnsupportedContentTypeError):
        processor._get_pipeline_key("application/octet-stream")


def test_empty_text_rejected_before_db_writes(processor, mock_db):
    with pytest.raises(EmptyContentError):
        processor.process_document(
            job_id=1,
            job_claim_token=uuid.uuid4(),
            canonical_url="https://utc.edu.vn/empty",
            title="Empty",
            extracted_text="   ",
            content_type="text/html",
            source_id=1,
        )
    mock_db.transaction.assert_not_called()


def test_new_document_full_lifecycle(processor, mock_db, mock_embedder):
    mock_cursor = MagicMock()
    _setup_tx(mock_db, mock_cursor)

    token = uuid.uuid4()
    now = datetime.now(timezone.utc)
    future = now + timedelta(minutes=10)
    captured_rev_token = {}

    def fake_execute(sql, params=None):
        if "INSERT INTO document_revisions" in sql and params and "token" in params:
            captured_rev_token["tok"] = params["token"]

    mock_cursor.execute.side_effect = fake_execute

    # Fetchone sequence across the 2 transactions:
    # Tx 1 (_acquire_revision):
    #   1. lock_owned_job -> valid job
    #   2. INSERT knowledge_documents RETURNING id -> doc 10
    #   3. INSERT document_revisions RETURNING id -> rev 50
    # Tx 2 (_persist_chunks_and_complete):
    #   4. lock_owned_job -> valid job
    #   5. SELECT document_revisions FOR UPDATE -> rev 50 with matching token
    #   6. integrity SELECT -> 1 valid chunk
    #   7. UPDATE document_revisions SET status='PENDING' RETURNING id -> rev 50
    def fetchone_gen():
        yield {"id": 1, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now}
        yield {"id": 10}
        yield {"id": 50}
        yield {"id": 1, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now}
        yield {"crawl_job_id": 1, "indexing_claim_token": captured_rev_token["tok"], "expected_chunk_count": 1, "status": "INDEXING"}
        yield {"total": 1, "unique_positions": 1, "min_pos": 0, "max_pos": 0, "invalid_rows": 0}
        yield {"id": 50, "document_id": 10}

    gen = fetchone_gen()
    mock_cursor.fetchone.side_effect = lambda: next(gen)

    res = processor.process_document(
        job_id=1,
        job_claim_token=token,
        canonical_url="https://utc.edu.vn/page",
        title="UTC Page",
        extracted_text="This is valid admissions content for UTC.",
        content_type="text/html",
        source_id=1,
    )

    assert res["document_id"] == 10
    assert res["revision_id"] == 50
    assert res["chunks_created"] == 1
    assert res["content_changed"] is True
    assert res["document_created"] is True
    mock_embedder.embed_batch.assert_called_once()


@pytest.mark.parametrize("existing_status", ["PENDING", "PUBLISHED", "ARCHIVED", "REVOKED"])
def test_existing_revision_statuses_skip_embedding(processor, mock_db, mock_embedder, existing_status):
    mock_cursor = MagicMock()
    _setup_tx(mock_db, mock_cursor)
    token = uuid.uuid4()
    now = datetime.now(timezone.utc)
    future = now + timedelta(minutes=10)

    mock_cursor.fetchone.side_effect = [
        {"id": 1, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now},
        None,        # INSERT doc ON CONFLICT DO NOTHING -> already exists
        {"id": 10},  # SELECT doc id
        None,        # INSERT rev ON CONFLICT DO NOTHING -> conflict
        {
            "id": 99,
            "status": existing_status,
            "crawl_job_id": 1,
            "indexing_claim_token": None,
            "owner_job_status": "COMPLETED",
            "owner_lease_until": None,
            "db_now": now,
        },
    ]

    res = processor.process_document(
        job_id=1,
        job_claim_token=token,
        canonical_url="https://utc.edu.vn/page",
        title="UTC Page",
        extracted_text="Same content as before.",
        content_type="text/html",
        source_id=1,
    )

    assert res["content_changed"] is False
    assert res["revision_id"] == 99
    assert res["skipped_reason"] == existing_status.lower()
    mock_embedder.embed_batch.assert_not_called()


def test_abandoned_indexing_revision_is_reclaimed(processor, mock_db, mock_embedder):
    mock_cursor = MagicMock()
    _setup_tx(mock_db, mock_cursor)
    token = uuid.uuid4()
    now = datetime.now(timezone.utc)
    future = now + timedelta(minutes=10)
    captured = {}

    def fake_execute(sql, params=None):
        if "UPDATE document_revisions" in sql and "SET crawl_job_id" in sql:
            captured["tok"] = params["token"]

    mock_cursor.execute.side_effect = fake_execute

    def gen():
        yield {"id": 2, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now}
        yield None
        yield {"id": 10}
        yield None  # conflict on INSERT
        yield {
            "id": 77,
            "status": "INDEXING",
            "crawl_job_id": 1,
            "indexing_claim_token": uuid.uuid4(),
            "owner_job_status": "FAILED",  # previous owner died!
            "owner_lease_until": now - timedelta(minutes=30),
            "db_now": now,
        }
        yield {"id": 77}  # UPDATE reclaim RETURNING id
        # Phase 2: persist & complete
        yield {"id": 2, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now}
        yield {"crawl_job_id": 2, "indexing_claim_token": captured["tok"], "expected_chunk_count": 1, "status": "INDEXING"}
        yield {"total": 1, "unique_positions": 1, "min_pos": 0, "max_pos": 0, "invalid_rows": 0}
        yield {"id": 77, "document_id": 10}

    it = gen()
    mock_cursor.fetchone.side_effect = lambda: next(it)

    res = processor.process_document(
        job_id=2,
        job_claim_token=token,
        canonical_url="https://utc.edu.vn/stuck",
        title="Recovered",
        extracted_text="Recovered text after previous worker crashed.",
        content_type="text/html",
        source_id=1,
    )
    assert res["revision_id"] == 77
    assert res["content_changed"] is True


def test_lease_expired_during_completion_fails_and_cleans_up(processor, mock_db):
    mock_cursor = MagicMock()
    _setup_tx(mock_db, mock_cursor)
    token = uuid.uuid4()
    now = datetime.now(timezone.utc)
    future = now + timedelta(minutes=10)
    captured = {}

    def fake_execute(sql, params=None):
        if "INSERT INTO document_revisions" in sql and params and "token" in params:
            captured["tok"] = params["token"]

    mock_cursor.execute.side_effect = fake_execute

    def gen():
        # Acquire succeeds
        yield {"id": 1, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now}
        yield {"id": 10}
        yield {"id": 50}
        # Persist sees expired lease!
        yield {"id": 1, "status": "RUNNING", "claim_token": token, "lease_until": now - timedelta(seconds=5), "attempt_no": 1, "db_now": now}
        # Cleanup checks revision ownership
        yield {"id": 50, "indexing_claim_token": captured["tok"], "status": "INDEXING"}

    it = gen()
    mock_cursor.fetchone.side_effect = lambda: next(it)

    with pytest.raises(LeaseExpiredError):
        processor.process_document(
            job_id=1,
            job_claim_token=token,
            canonical_url="https://utc.edu.vn/slow",
            title="Slow",
            extracted_text="Some text whose embedding outlived the lease.",
            content_type="text/html",
            source_id=1,
        )


def test_chunk_integrity_failure_raises_chunk_count_mismatch(processor, mock_db):
    mock_cursor = MagicMock()
    _setup_tx(mock_db, mock_cursor)
    token = uuid.uuid4()
    rev_token = uuid.uuid4()
    now = datetime.now(timezone.utc)
    future = now + timedelta(minutes=10)

    mock_cursor.fetchone.side_effect = [
        {"id": 1, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now},
        {"crawl_job_id": 1, "indexing_claim_token": rev_token, "expected_chunk_count": 2, "status": "INDEXING"},
        {"total": 1, "unique_positions": 1, "min_pos": 0, "max_pos": 0, "invalid_rows": 0},  # mismatch!
    ]

    with pytest.raises(ChunkCountMismatchError):
        processor._persist_chunks_and_complete(
            job_id=1,
            job_claim_token=token,
            revision_id=50,
            indexing_claim_token=rev_token,
            embedded_chunks=[{"revision_id": 50, "position": 0, "body": "a", "embedding": [0.1] * 768}],
            expected_count=2,
        )


def test_archive_document_revisions_and_missing_source_documents(processor, mock_db):
    mock_cursor = MagicMock()
    _setup_tx(mock_db, mock_cursor)
    token = uuid.uuid4()
    now = datetime.now(timezone.utc)
    future = now + timedelta(minutes=10)

    # 1. archive_document_revisions archives PENDING/PUBLISHED revisions
    mock_cursor.fetchone.side_effect = [
        {"id": 1, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now},
        {"id": 10},
    ]
    mock_cursor.fetchall.return_value = [{"id": 50}, {"id": 51}]
    archived = processor.archive_document_revisions(1, token, "https://utc.edu.vn/removed", reason="HTTP 404")
    assert archived == 2

    # 2. archive_missing_source_documents archives disappeared URLs
    mock_cursor.fetchone.side_effect = [
        {"id": 1, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now},
    ]
    mock_cursor.fetchall.return_value = [{"id": 60}]
    archived_missing = processor.archive_missing_source_documents(1, token, source_id=5, active_canonical_urls=["https://utc.edu.vn/keep"])
    assert archived_missing == 1


def test_auto_publish_archives_old_published_before_publishing_new(mock_db, mock_embedder, job_manager):
    proc = RevisionProcessor(
        database=mock_db,
        embedder=mock_embedder,
        job_manager=job_manager,
        embedding_model="embeddinggemma:300m-qat-q4_0",
        auto_publish=True,
    )
    mock_cursor = MagicMock()
    _setup_tx(mock_db, mock_cursor)
    token = uuid.uuid4()
    rev_token = uuid.uuid4()
    now = datetime.now(timezone.utc)
    future = now + timedelta(minutes=10)

    mock_cursor.fetchone.side_effect = [
        {"id": 1, "status": "RUNNING", "claim_token": token, "lease_until": future, "attempt_no": 1, "db_now": now},
        {"document_id": 10, "crawl_job_id": 1, "indexing_claim_token": rev_token, "expected_chunk_count": 1, "status": "INDEXING"},
        {"total": 1, "unique_positions": 1, "min_pos": 0, "max_pos": 0, "invalid_rows": 0},
        {"id": 50, "document_id": 10},
    ]

    proc._persist_chunks_and_complete(
        job_id=1,
        job_claim_token=token,
        revision_id=50,
        indexing_claim_token=rev_token,
        embedded_chunks=[{"revision_id": 50, "position": 0, "body": "text", "embedding": [0.1] * 768}],
        expected_count=1,
    )

    sqls = [c[0][0] for c in mock_cursor.execute.call_args_list]
    archive_idx = next(i for i, s in enumerate(sqls) if "SET status = 'ARCHIVED'" in s)
    publish_idx = next(i for i, s in enumerate(sqls) if "SET status = 'PUBLISHED'" in s)
    assert archive_idx < publish_idx
    assert "source_verified = true" in sqls[publish_idx]
    assert "published_at = clock_timestamp()" in sqls[publish_idx]

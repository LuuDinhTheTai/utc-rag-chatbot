"""Unit tests for revision processor."""

import pytest
import uuid
from unittest.mock import Mock, MagicMock, patch, call
from datetime import datetime, timezone, timedelta

from src.revision_processor import RevisionProcessor, PIPELINE_HTML, PIPELINE_PDF, PIPELINE_DOCX
from src.exceptions import (
    DatabaseError,
    LeaseExpiredError,
    ClaimTokenMismatchError,
    ChunkCountMismatchError,
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
    db = Mock()
    db.transaction = MagicMock()
    return db


@pytest.fixture
def mock_embedder():
    """Create mock embedder."""
    embedder = Mock()
    embedder.embed.return_value = [0.1] * 768  # Valid 768-dim embedding
    return embedder


@pytest.fixture
def mock_job_manager():
    """Create mock job manager."""
    return Mock()


@pytest.fixture
def revision_processor(mock_db, mock_embedder, mock_job_manager):
    """Create revision processor with mocks."""
    return RevisionProcessor(
        database=mock_db,
        embedder=mock_embedder,
        job_manager=mock_job_manager,
        embedding_model="embeddinggemma:300m-qat-q4_0",
    )


def test_get_pipeline_key_html(revision_processor):
    """Test pipeline key detection for HTML."""
    assert revision_processor._get_pipeline_key("text/html") == PIPELINE_HTML
    assert revision_processor._get_pipeline_key("text/html; charset=utf-8") == PIPELINE_HTML


def test_get_pipeline_key_pdf(revision_processor):
    """Test pipeline key detection for PDF."""
    assert revision_processor._get_pipeline_key("application/pdf") == PIPELINE_PDF


def test_get_pipeline_key_docx(revision_processor):
    """Test pipeline key detection for DOCX."""
    assert revision_processor._get_pipeline_key("application/vnd.openxmlformats-officedocument.wordprocessingml.document") == PIPELINE_DOCX


def test_get_or_create_document_existing(revision_processor, mock_db):
    """Test finding existing document."""
    mock_db.execute_one.return_value = {"id": 123}
    
    doc_id = revision_processor._get_or_create_document("https://example.com", 1)
    
    assert doc_id == 123
    mock_db.execute_one.assert_called_once()


def test_get_or_create_document_new(revision_processor, mock_db):
    """Test creating new document."""
    mock_db.execute_one.side_effect = [
        None,  # Document not found
        {"id": 456},  # Document created
    ]
    
    doc_id = revision_processor._get_or_create_document("https://example.com", 1)
    
    assert doc_id == 456
    assert mock_db.execute_one.call_count == 2


def test_find_existing_revision_found(revision_processor, mock_db):
    """Test finding existing revision with same content."""
    mock_db.execute_one.return_value = {"id": 789, "status": "PUBLISHED"}
    
    result = revision_processor._find_existing_revision(
        document_id=123,
        content_hash="abc123",
        pipeline_key="html-v1",
        embedding_model="test-model",
    )
    
    assert result is not None
    assert result["id"] == 789


def test_find_existing_revision_not_found(revision_processor, mock_db):
    """Test when no existing revision found."""
    mock_db.execute_one.return_value = None
    
    result = revision_processor._find_existing_revision(
        document_id=123,
        content_hash="abc123",
        pipeline_key="html-v1",
        embedding_model="test-model",
    )
    
    assert result is None


@patch("src.revision_processor.compute_content_hash")
def test_process_document_unchanged_content(
    mock_hash, revision_processor, mock_db, mock_embedder
):
    """Test skipping embedding for unchanged content."""
    mock_hash.return_value = "hash123"
    mock_db.execute_one.side_effect = [
        {"id": 10},  # get_or_create_document
        {"id": 20, "status": "PUBLISHED"},  # find_existing_revision
    ]
    
    result = revision_processor.process_document(
        job_id=1,
        job_claim_token=uuid.uuid4(),
        canonical_url="https://example.com",
        title="Test",
        extracted_text="content",
        content_type="text/html",
        source_id=5,
    )
    
    assert result["content_changed"] is False
    assert result["chunks_created"] == 0
    assert result["revision_id"] == 20
    mock_embedder.embed.assert_not_called()


@patch("src.revision_processor.compute_content_hash")
def test_process_document_new_content(
    mock_hash, revision_processor, mock_db, mock_embedder, mock_job_manager
):
    """Test processing new content creates revision and chunks."""
    mock_hash.return_value = "newhash"
    
    # Mock chunker
    mock_chunker = Mock()
    mock_chunker.chunk_text.return_value = [
        {"text": "chunk1", "position": 0, "body": "chunk1", "char_count": 6},
        {"text": "chunk2", "position": 1, "body": "chunk2", "char_count": 6},
    ]
    revision_processor.chunker = mock_chunker
    
    # Setup mocks for document creation
    mock_db.execute_one.side_effect = [
        {"id": 10},  # get_or_create_document
        None,  # find_existing_revision (not found)
        {"id": 30},  # set_expected_chunk_count RETURNING id
    ]
    
    # Mock the internal methods to avoid complex transaction mocking
    job_token = uuid.uuid4()
    rev_token = uuid.uuid4()
    
    with patch.object(revision_processor, '_create_revision', return_value=(30, rev_token)), \
         patch.object(revision_processor, '_embed_and_insert_chunks'), \
         patch.object(revision_processor, '_complete_indexing'):
        
        result = revision_processor.process_document(
            job_id=1,
            job_claim_token=job_token,
            canonical_url="https://example.com",
            title="Test",
            extracted_text="new content",
            content_type="text/html",
            source_id=5,
        )
        
        assert result["content_changed"] is True
        assert result["chunks_created"] == 2
        
        # Verify the methods were called correctly
        revision_processor._create_revision.assert_called_once()
        revision_processor._embed_and_insert_chunks.assert_called_once()
        revision_processor._complete_indexing.assert_called_once()


def test_create_revision_job_verification(revision_processor, mock_db, mock_job_manager):
    """Test revision creation verifies job ownership."""
    job_token = uuid.uuid4()
    mock_job_manager.verify_ownership.side_effect = ClaimTokenMismatchError("Invalid token")
    
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    with pytest.raises(ClaimTokenMismatchError):
        revision_processor._create_revision(
            job_id=1,
            job_claim_token=job_token,
            document_id=10,
            canonical_url="https://example.com",
            title="Test",
            content_hash="hash",
            pipeline_key="html-v1",
        )


def test_set_expected_chunk_count_success(revision_processor, mock_db):
    """Test setting expected chunk count."""
    token = uuid.uuid4()
    mock_db.execute_one.return_value = {"id": 30}
    
    revision_processor._set_expected_chunk_count(
        revision_id=30,
        indexing_claim_token=token,
        expected_count=5,
    )
    
    mock_db.execute_one.assert_called_once()


def test_set_expected_chunk_count_token_mismatch(revision_processor, mock_db):
    """Test setting chunk count with invalid token."""
    token = uuid.uuid4()
    mock_db.execute_one.return_value = None  # UPDATE failed
    
    with pytest.raises(ClaimTokenMismatchError):
        revision_processor._set_expected_chunk_count(
            revision_id=30,
            indexing_claim_token=token,
            expected_count=5,
        )


def test_embed_and_insert_chunks_job_token_mismatch(
    revision_processor, mock_db, mock_embedder
):
    """Test chunk insertion rejects invalid job token."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    job_token = uuid.uuid4()
    different_token = uuid.uuid4()
    
    mock_cursor.fetchone.return_value = {
        "claim_token": different_token,
        "status": "RUNNING",
        "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10),
    }
    
    chunks = [{"body": "test", "position": 0}]
    
    with pytest.raises(ClaimTokenMismatchError, match="job reclaimed"):
        revision_processor._embed_and_insert_chunks(
            job_id=1,
            job_claim_token=job_token,
            revision_id=30,
            indexing_claim_token=uuid.uuid4(),
            chunks=chunks,
        )


def test_embed_and_insert_chunks_lease_expired(
    revision_processor, mock_db, mock_embedder
):
    """Test chunk insertion rejects expired lease."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    job_token = uuid.uuid4()
    expired_time = datetime.now(timezone.utc) - timedelta(minutes=5)
    
    mock_cursor.fetchone.side_effect = [
        {"claim_token": job_token, "status": "RUNNING", "lease_until": expired_time},
        {"now": datetime.now(timezone.utc)},
    ]
    
    chunks = [{"body": "test", "position": 0}]
    
    with pytest.raises(LeaseExpiredError):
        revision_processor._embed_and_insert_chunks(
            job_id=1,
            job_claim_token=job_token,
            revision_id=30,
            indexing_claim_token=uuid.uuid4(),
            chunks=chunks,
        )


def test_embed_and_insert_chunks_revision_token_mismatch(
    revision_processor, mock_db, mock_embedder
):
    """Test chunk insertion rejects invalid revision token."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    job_token = uuid.uuid4()
    rev_token = uuid.uuid4()
    different_rev_token = uuid.uuid4()
    
    mock_cursor.fetchone.side_effect = [
        {"claim_token": job_token, "status": "RUNNING", "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10)},
        {"now": datetime.now(timezone.utc)},
        {"crawl_job_id": 1, "indexing_claim_token": different_rev_token, "status": "INDEXING"},
    ]
    
    chunks = [{"body": "test", "position": 0}]
    
    with pytest.raises(ClaimTokenMismatchError, match="revision reclaimed"):
        revision_processor._embed_and_insert_chunks(
            job_id=1,
            job_claim_token=job_token,
            revision_id=30,
            indexing_claim_token=rev_token,
            chunks=chunks,
        )


def test_complete_indexing_count_mismatch(revision_processor, mock_db):
    """Test integrity verification fails with wrong count."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    job_token = uuid.uuid4()
    rev_token = uuid.uuid4()
    
    mock_cursor.fetchone.side_effect = [
        {"claim_token": job_token, "status": "RUNNING", "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10)},
        {"indexing_claim_token": rev_token, "expected_chunk_count": 5, "status": "INDEXING"},
        {"total": 3, "unique_positions": 3, "min_pos": 0, "max_pos": 2},  # Wrong count
    ]
    
    with pytest.raises(ChunkCountMismatchError, match="expected 5 chunks, got 3"):
        revision_processor._complete_indexing(
            job_id=1,
            job_claim_token=job_token,
            revision_id=30,
            indexing_claim_token=rev_token,
            expected_count=5,
        )


def test_complete_indexing_duplicate_positions(revision_processor, mock_db):
    """Test integrity verification fails with duplicate positions."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    job_token = uuid.uuid4()
    rev_token = uuid.uuid4()
    
    mock_cursor.fetchone.side_effect = [
        {"claim_token": job_token, "status": "RUNNING", "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10)},
        {"indexing_claim_token": rev_token, "expected_chunk_count": 5, "status": "INDEXING"},
        {"total": 5, "unique_positions": 4, "min_pos": 0, "max_pos": 4},  # Duplicate
    ]
    
    with pytest.raises(ChunkCountMismatchError, match="duplicate positions"):
        revision_processor._complete_indexing(
            job_id=1,
            job_claim_token=job_token,
            revision_id=30,
            indexing_claim_token=rev_token,
            expected_count=5,
        )


def test_complete_indexing_wrong_min_position(revision_processor, mock_db):
    """Test integrity verification fails with wrong min position."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    job_token = uuid.uuid4()
    rev_token = uuid.uuid4()
    
    mock_cursor.fetchone.side_effect = [
        {"claim_token": job_token, "status": "RUNNING", "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10)},
        {"indexing_claim_token": rev_token, "expected_chunk_count": 5, "status": "INDEXING"},
        {"total": 5, "unique_positions": 5, "min_pos": 1, "max_pos": 5},  # Wrong min
    ]
    
    with pytest.raises(ChunkCountMismatchError, match="min position is 1, expected 0"):
        revision_processor._complete_indexing(
            job_id=1,
            job_claim_token=job_token,
            revision_id=30,
            indexing_claim_token=rev_token,
            expected_count=5,
        )


def test_complete_indexing_wrong_max_position(revision_processor, mock_db):
    """Test integrity verification fails with wrong max position."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    job_token = uuid.uuid4()
    rev_token = uuid.uuid4()
    
    mock_cursor.fetchone.side_effect = [
        {"claim_token": job_token, "status": "RUNNING", "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10)},
        {"indexing_claim_token": rev_token, "expected_chunk_count": 5, "status": "INDEXING"},
        {"total": 5, "unique_positions": 5, "min_pos": 0, "max_pos": 5},  # Wrong max
    ]
    
    with pytest.raises(ChunkCountMismatchError, match="max position is 5, expected 4"):
        revision_processor._complete_indexing(
            job_id=1,
            job_claim_token=job_token,
            revision_id=30,
            indexing_claim_token=rev_token,
            expected_count=5,
        )


def test_complete_indexing_gaps_in_positions(revision_processor, mock_db):
    """Test integrity verification fails with gaps."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    job_token = uuid.uuid4()
    rev_token = uuid.uuid4()
    
    mock_cursor.fetchone.side_effect = [
        {"claim_token": job_token, "status": "RUNNING", "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10)},
        {"indexing_claim_token": rev_token, "expected_chunk_count": 5, "status": "INDEXING"},
        {"total": 4, "unique_positions": 4, "min_pos": 0, "max_pos": 4},  # Gap (missing position 2)
    ]
    
    with pytest.raises(ChunkCountMismatchError, match="expected 5 chunks, got 4"):
        revision_processor._complete_indexing(
            job_id=1,
            job_claim_token=job_token,
            revision_id=30,
            indexing_claim_token=rev_token,
            expected_count=5,
        )


def test_complete_indexing_success(revision_processor, mock_db):
    """Test successful integrity verification and transition to PENDING."""
    mock_cursor = MagicMock()
    setup_mock_transaction(mock_db, mock_cursor)
    
    job_token = uuid.uuid4()
    rev_token = uuid.uuid4()
    
    mock_cursor.fetchone.side_effect = [
        {"claim_token": job_token, "status": "RUNNING", "lease_until": datetime.now(timezone.utc) + timedelta(minutes=10)},
        {"indexing_claim_token": rev_token, "expected_chunk_count": 3, "status": "INDEXING"},
        {"total": 3, "unique_positions": 3, "min_pos": 0, "max_pos": 2},  # Valid
        {"id": 30},  # UPDATE successful
    ]
    
    # Should not raise
    revision_processor._complete_indexing(
        job_id=1,
        job_claim_token=job_token,
        revision_id=30,
        indexing_claim_token=rev_token,
        expected_count=3,
    )


def test_cleanup_failed_revision(revision_processor, mock_db):
    """Test cleanup deletes chunks and revision when ownership valid."""
    # Setup mock transaction with cursor
    mock_conn = MagicMock()
    mock_cursor = MagicMock()
    mock_conn.__enter__.return_value = mock_conn
    mock_conn.__exit__.return_value = False
    mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
    mock_conn.cursor.return_value.__exit__.return_value = False
    mock_db.transaction.return_value.__enter__.return_value = mock_conn
    mock_db.transaction.return_value.__exit__.return_value = False
    
    token = uuid.uuid4()
    mock_cursor.fetchone.return_value = {
        "id": 30,
        "indexing_claim_token": token,
        "status": "INDEXING",
    }
    
    revision_processor._cleanup_failed_revision(30, token)
    
    # Should have 3 queries: SELECT FOR UPDATE, DELETE chunks, DELETE revision
    assert mock_cursor.execute.call_count == 3


def test_embedding_failure_triggers_cleanup(
    revision_processor, mock_db, mock_embedder, mock_job_manager
):
    """Test that embedding failure triggers cleanup."""
    mock_embedder.embed.side_effect = Exception("Ollama unavailable")
    
    # Setup mocks
    mock_db.execute_one.side_effect = [
        {"id": 10},  # get_or_create_document
        None,  # find_existing_revision
        {"id": 30},  # set_expected_chunk_count RETURNING id
    ]
    
    # Setup transaction mock for cleanup
    mock_conn = MagicMock()
    mock_cursor = MagicMock()
    mock_conn.__enter__.return_value = mock_conn
    mock_conn.__exit__.return_value = False
    mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
    mock_conn.cursor.return_value.__exit__.return_value = False
    mock_db.transaction.return_value.__enter__.return_value = mock_conn
    mock_db.transaction.return_value.__exit__.return_value = False
    
    job_token = uuid.uuid4()
    rev_token = uuid.uuid4()
    
    # Mock cleanup will find ownership valid
    mock_cursor.fetchone.return_value = {
        "id": 30,
        "indexing_claim_token": rev_token,
        "status": "INDEXING",
    }
    
    # Mock chunker
    mock_chunker = Mock()
    mock_chunker.chunk_text.return_value = [{"text": "chunk1", "position": 0, "body": "chunk1", "char_count": 6}]
    revision_processor.chunker = mock_chunker
    
    with pytest.raises(DatabaseError, match="Failed to generate embedding"):
        revision_processor.process_document(
            job_id=1,
            job_claim_token=job_token,
            canonical_url="https://example.com",
            title="Test",
            extracted_text="content",
            content_type="text/html",
            source_id=5,
        )
    
    # Cleanup should have been attempted (3 execute calls: SELECT FOR UPDATE, DELETE chunks, DELETE revision)
    assert mock_cursor.execute.call_count >= 1

"""Document revision processing with atomic fencing and integrity verification.

Implements AD-003, AD-007, AD-010, AD-011, AD-012, AD-013.
"""

import logging
import uuid
from typing import Dict, List, Any, Optional, Tuple
from datetime import datetime

from .database import Database
from .hash_utils import compute_content_hash
from .chunker import Chunker
from .embedder import OllamaEmbedder
from .job_manager import JobManager
from .exceptions import (
    DatabaseError,
    LeaseExpiredError,
    ClaimTokenMismatchError,
    ChunkCountMismatchError,
)

logger = logging.getLogger(__name__)


# Pipeline keys per AD-008
PIPELINE_HTML = "html-v1"
PIPELINE_PDF = "pdf-v2"
PIPELINE_DOCX = "docx-v1"


class RevisionProcessor:
    """Processes document revisions with atomic fencing and integrity verification."""

    def __init__(
        self,
        database: Database,
        embedder: OllamaEmbedder,
        job_manager: JobManager,
        embedding_model: str,
        chunker: Optional[Chunker] = None,
    ):
        """Initialize revision processor.

        Args:
            database: Database connection manager
            embedder: Ollama embedding client
            job_manager: Job manager for ownership verification
            embedding_model: Embedding model name (e.g., 'embeddinggemma:300m-qat-q4_0')
            chunker: Text chunker (creates default if None)
        """
        self.db = database
        self.embedder = embedder
        self.job_manager = job_manager
        self.embedding_model = embedding_model
        self.chunker = chunker or Chunker()

    def process_document(
        self,
        job_id: int,
        job_claim_token: uuid.UUID,
        canonical_url: str,
        title: str,
        extracted_text: str,
        content_type: str,
        source_id: int,
    ) -> Dict[str, Any]:
        """Process document: detect changes, create revision, generate embeddings, persist chunks.

        Implements AD-003 (revision lifecycle), AD-007 (content hashing),
        AD-010 (revision ownership), AD-011 (atomic fencing), AD-012 (integrity verification).

        Args:
            job_id: Current job ID
            job_claim_token: Job claim token for verification
            canonical_url: Document canonical URL
            title: Document title
            extracted_text: Extracted text content
            content_type: MIME type (text/html, application/pdf, etc.)
            source_id: Crawl source ID

        Returns:
            Dict with keys: document_id, revision_id, chunks_created, content_changed

        Raises:
            LeaseExpiredError: If job lease expired
            ClaimTokenMismatchError: If tokens don't match
            ChunkCountMismatchError: If chunk integrity verification fails
            DatabaseError: If database operation fails
        """
        # Step 1: Compute content hash per AD-007
        content_hash = compute_content_hash(extracted_text)
        pipeline_key = self._get_pipeline_key(content_type)

        logger.info(
            f"Processing document: url={canonical_url}, hash={content_hash[:12]}..., pipeline={pipeline_key}"
        )

        # Step 2: Find or create knowledge_document
        document_id = self._get_or_create_document(canonical_url, source_id)

        # Step 3: Check for unchanged content (skip embedding if duplicate)
        existing_revision = self._find_existing_revision(
            document_id, content_hash, pipeline_key, self.embedding_model
        )

        if existing_revision:
            logger.info(
                f"Content unchanged for document {document_id}, "
                f"reusing revision {existing_revision['id']}"
            )
            return {
                "document_id": document_id,
                "revision_id": existing_revision["id"],
                "chunks_created": 0,
                "content_changed": False,
            }

        # Step 4: Create new revision with status=INDEXING
        revision_id, indexing_claim_token = self._create_revision(
            job_id=job_id,
            job_claim_token=job_claim_token,
            document_id=document_id,
            canonical_url=canonical_url,
            title=title,
            content_hash=content_hash,
            pipeline_key=pipeline_key,
        )

        try:
            # Step 5: Chunk text
            chunks = self.chunker.chunk_text(extracted_text)
            logger.info(
                f"Chunked document into {len(chunks)} chunks for revision {revision_id}"
            )

            # Step 6: Set expected_chunk_count for integrity verification (AD-012)
            self._set_expected_chunk_count(
                revision_id=revision_id,
                indexing_claim_token=indexing_claim_token,
                expected_count=len(chunks),
            )

            # Step 7: Generate embeddings and insert chunks with atomic fencing (AD-011)
            self._embed_and_insert_chunks(
                job_id=job_id,
                job_claim_token=job_claim_token,
                revision_id=revision_id,
                indexing_claim_token=indexing_claim_token,
                chunks=chunks,
            )

            # Step 8: Verify chunk integrity and transition to PENDING (AD-012)
            self._complete_indexing(
                job_id=job_id,
                job_claim_token=job_claim_token,
                revision_id=revision_id,
                indexing_claim_token=indexing_claim_token,
                expected_count=len(chunks),
            )

            logger.info(
                f"Successfully processed revision {revision_id} with {len(chunks)} chunks"
            )

            return {
                "document_id": document_id,
                "revision_id": revision_id,
                "chunks_created": len(chunks),
                "content_changed": True,
            }

        except Exception as e:
            logger.error(
                f"Failed to process revision {revision_id}: {e}, rolling back"
            )
            # Attempt cleanup (best effort, may fail if token invalid)
            try:
                self._cleanup_failed_revision(revision_id, indexing_claim_token)
            except Exception as cleanup_error:
                logger.warning(f"Cleanup failed: {cleanup_error}")
            raise

    def _get_pipeline_key(self, content_type: str) -> str:
        """Determine pipeline key based on content type (AD-008)."""
        if "html" in content_type.lower():
            return PIPELINE_HTML
        elif "pdf" in content_type.lower():
            return PIPELINE_PDF
        elif "docx" in content_type.lower() or "wordprocessingml" in content_type.lower():
            return PIPELINE_DOCX
        else:
            # Default to HTML pipeline for unknown types
            logger.warning(f"Unknown content type {content_type}, using {PIPELINE_HTML}")
            return PIPELINE_HTML

    def _get_or_create_document(
        self, canonical_url: str, source_id: int
    ) -> int:
        """Find or create knowledge_document by canonical URL."""
        try:
            # Try to find existing document
            result = self.db.execute_one(
                """
                SELECT id FROM knowledge_documents
                WHERE canonical_url = %(url)s
                """,
                {"url": canonical_url},
            )

            if result:
                return result["id"]

            # Create new document
            result = self.db.execute_one(
                """
                INSERT INTO knowledge_documents (canonical_url, crawl_source_id)
                VALUES (%(url)s, %(source_id)s)
                ON CONFLICT (canonical_url) DO UPDATE
                SET crawl_source_id = EXCLUDED.crawl_source_id
                RETURNING id
                """,
                {"url": canonical_url, "source_id": source_id},
            )

            logger.info(f"Created knowledge_document {result['id']} for {canonical_url}")
            return result["id"]

        except Exception as e:
            raise DatabaseError(f"Failed to get/create document: {e}") from e

    def _find_existing_revision(
        self,
        document_id: int,
        content_hash: str,
        pipeline_key: str,
        embedding_model: str,
    ) -> Optional[Dict[str, Any]]:
        """Check if revision with same content already exists."""
        try:
            return self.db.execute_one(
                """
                SELECT id, status
                FROM document_revisions
                WHERE document_id = %(doc_id)s
                  AND content_hash = %(hash)s
                  AND pipeline_key = %(pipeline)s
                  AND embedding_model = %(model)s
                LIMIT 1
                """,
                {
                    "doc_id": document_id,
                    "hash": content_hash,
                    "pipeline": pipeline_key,
                    "model": embedding_model,
                },
            )
        except Exception as e:
            raise DatabaseError(f"Failed to check existing revision: {e}") from e

    def _create_revision(
        self,
        job_id: int,
        job_claim_token: uuid.UUID,
        document_id: int,
        canonical_url: str,
        title: str,
        content_hash: str,
        pipeline_key: str,
    ) -> Tuple[int, uuid.UUID]:
        """Create new revision with status=INDEXING and indexing_claim_token.

        Returns:
            Tuple of (revision_id, indexing_claim_token)
        """
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
                    # Verify job ownership first (AD-011)
                    self.job_manager.verify_ownership(job_id, job_claim_token)

                    # Generate revision claim token (AD-010)
                    indexing_claim_token = uuid.uuid4()

                    # Create revision
                    cur.execute(
                        """
                        INSERT INTO document_revisions (
                            document_id,
                            crawl_job_id,
                            title,
                            official_url,
                            content_hash,
                            pipeline_key,
                            embedding_model,
                            status,
                            indexing_claim_token,
                            updated_at
                        ) VALUES (
                            %(doc_id)s,
                            %(job_id)s,
                            %(title)s,
                            %(url)s,
                            %(hash)s,
                            %(pipeline)s,
                            %(model)s,
                            'INDEXING',
                            %(token)s,
                            clock_timestamp()
                        )
                        RETURNING id
                        """,
                        {
                            "doc_id": document_id,
                            "job_id": job_id,
                            "title": title,
                            "url": canonical_url,
                            "hash": content_hash,
                            "pipeline": pipeline_key,
                            "model": self.embedding_model,
                            "token": indexing_claim_token,
                        },
                    )

                    result = cur.fetchone()
                    revision_id = result["id"]

                    logger.info(
                        f"Created revision {revision_id} with indexing_claim_token"
                    )

                    return revision_id, indexing_claim_token

        except (LeaseExpiredError, ClaimTokenMismatchError):
            raise
        except Exception as e:
            raise DatabaseError(f"Failed to create revision: {e}") from e

    def _set_expected_chunk_count(
        self,
        revision_id: int,
        indexing_claim_token: uuid.UUID,
        expected_count: int,
    ) -> None:
        """Set expected_chunk_count for integrity verification (AD-012)."""
        try:
            result = self.db.execute_one(
                """
                UPDATE document_revisions
                SET expected_chunk_count = %(count)s
                WHERE id = %(rev_id)s
                  AND indexing_claim_token = %(token)s
                  AND status = 'INDEXING'
                RETURNING id
                """,
                {
                    "rev_id": revision_id,
                    "count": expected_count,
                    "token": indexing_claim_token,
                },
            )

            if not result:
                raise ClaimTokenMismatchError(
                    f"Cannot set expected_chunk_count for revision {revision_id}: token mismatch or wrong status"
                )

            logger.debug(
                f"Set expected_chunk_count={expected_count} for revision {revision_id}"
            )

        except ClaimTokenMismatchError:
            raise
        except Exception as e:
            raise DatabaseError(f"Failed to set expected_chunk_count: {e}") from e

    def _embed_and_insert_chunks(
        self,
        job_id: int,
        job_claim_token: uuid.UUID,
        revision_id: int,
        indexing_claim_token: uuid.UUID,
        chunks: List[Dict[str, Any]],
    ) -> None:
        """Generate embeddings and insert chunks with atomic fencing (AD-011).

        Phase 1: Compute embeddings OUTSIDE transaction (no locks held)
        Phase 2: SHORT transaction with dual-token verification + INSERT
        """
        # Phase 1: Compute embeddings (5-10s, NO LOCKS)
        logger.info(f"Generating embeddings for {len(chunks)} chunks (Phase 1)...")
        embedded_chunks = []

        for chunk in chunks:
            try:
                embedding = self.embedder.embed(chunk["body"])
                embedded_chunks.append({
                    "revision_id": revision_id,
                    "position": chunk["position"],
                    "body": chunk["body"],
                    "embedding": embedding,
                })
            except Exception as e:
                raise DatabaseError(
                    f"Failed to generate embedding for chunk {chunk['position']}: {e}"
                ) from e

        logger.info(f"Embeddings generated, inserting chunks (Phase 2)...")

        # Phase 2: SHORT transaction with dual verification and INSERT (AD-011)
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
                    # Lock and verify job ownership (AD-013: jobs first)
                    cur.execute(
                        """
                        SELECT claim_token, status, lease_until
                        FROM crawl_jobs
                        WHERE id = %(job_id)s
                        FOR UPDATE
                        """,
                        {"job_id": job_id},
                    )

                    job_state = cur.fetchone()

                    if not job_state:
                        raise DatabaseError(f"Job {job_id} not found")

                    if job_state["claim_token"] != job_claim_token:
                        raise ClaimTokenMismatchError(
                            f"Job {job_id} claim token mismatch (job reclaimed)"
                        )

                    if job_state["status"] != "RUNNING":
                        raise DatabaseError(
                            f"Job {job_id} status is {job_state['status']}, expected RUNNING"
                        )

                    # Check lease validity with clock_timestamp() per AD-011
                    cur.execute("SELECT clock_timestamp() as now")
                    now = cur.fetchone()["now"]

                    if job_state["lease_until"] and job_state["lease_until"] < now:
                        raise LeaseExpiredError(
                            f"Job {job_id} lease expired at {job_state['lease_until']}"
                        )

                    # Lock and verify revision ownership (AD-013: then revisions)
                    cur.execute(
                        """
                        SELECT crawl_job_id, indexing_claim_token, status
                        FROM document_revisions
                        WHERE id = %(rev_id)s
                        FOR UPDATE
                        """,
                        {"rev_id": revision_id},
                    )

                    rev_state = cur.fetchone()

                    if not rev_state:
                        raise DatabaseError(f"Revision {revision_id} not found")

                    if rev_state["indexing_claim_token"] != indexing_claim_token:
                        raise ClaimTokenMismatchError(
                            f"Revision {revision_id} indexing token mismatch (revision reclaimed)"
                        )

                    if rev_state["crawl_job_id"] != job_id:
                        raise DatabaseError(
                            f"Revision {revision_id} belongs to job {rev_state['crawl_job_id']}, not {job_id}"
                        )

                    if rev_state["status"] != "INDEXING":
                        raise DatabaseError(
                            f"Revision {revision_id} status is {rev_state['status']}, expected INDEXING"
                        )

                    # All verifications passed, insert chunks
                    # Use ON CONFLICT to handle any duplicate position attempts
                    for chunk in embedded_chunks:
                        cur.execute(
                            """
                            INSERT INTO document_chunks (revision_id, position, body, embedding)
                            VALUES (%(revision_id)s, %(position)s, %(body)s, %(embedding)s)
                            ON CONFLICT (revision_id, position) DO NOTHING
                            """,
                            chunk,
                        )

                    logger.info(
                        f"Inserted {len(embedded_chunks)} chunks for revision {revision_id}"
                    )

        except (LeaseExpiredError, ClaimTokenMismatchError):
            raise
        except Exception as e:
            raise DatabaseError(f"Failed to insert chunks: {e}") from e

    def _complete_indexing(
        self,
        job_id: int,
        job_claim_token: uuid.UUID,
        revision_id: int,
        indexing_claim_token: uuid.UUID,
        expected_count: int,
    ) -> None:
        """Verify chunk integrity and transition INDEXING → PENDING (AD-012).

        Implements all integrity checks from AD-012:
        - expected_chunk_count is set
        - COUNT(*) = expected_chunk_count
        - COUNT(DISTINCT position) = COUNT(*) (no duplicates)
        - MIN(position) = 0
        - MAX(position) = expected_count - 1
        - MAX - MIN + 1 = COUNT (no gaps)
        """
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
                    # Verify job ownership (AD-013: jobs first)
                    cur.execute(
                        """
                        SELECT claim_token, status, lease_until
                        FROM crawl_jobs
                        WHERE id = %(job_id)s
                        FOR UPDATE
                        """,
                        {"job_id": job_id},
                    )

                    job_state = cur.fetchone()

                    if not job_state or job_state["claim_token"] != job_claim_token:
                        raise ClaimTokenMismatchError(
                            f"Job {job_id} claim token mismatch during completion"
                        )

                    if job_state["status"] != "RUNNING":
                        raise DatabaseError(f"Job {job_id} not in RUNNING state")

                    # Lock revision
                    cur.execute(
                        """
                        SELECT indexing_claim_token, expected_chunk_count, status
                        FROM document_revisions
                        WHERE id = %(rev_id)s
                        FOR UPDATE
                        """,
                        {"rev_id": revision_id},
                    )

                    rev_state = cur.fetchone()

                    if not rev_state:
                        raise DatabaseError(f"Revision {revision_id} not found")

                    if rev_state["indexing_claim_token"] != indexing_claim_token:
                        raise ClaimTokenMismatchError(
                            f"Revision {revision_id} token mismatch during completion"
                        )

                    if rev_state["status"] != "INDEXING":
                        raise DatabaseError(
                            f"Revision {revision_id} not in INDEXING state"
                        )

                    if rev_state["expected_chunk_count"] is None:
                        raise ChunkCountMismatchError(
                            f"Revision {revision_id} expected_chunk_count not set"
                        )

                    # Perform integrity verification (AD-012)
                    cur.execute(
                        """
                        SELECT
                            COUNT(*) as total,
                            COUNT(DISTINCT position) as unique_positions,
                            MIN(position) as min_pos,
                            MAX(position) as max_pos
                        FROM document_chunks
                        WHERE revision_id = %(rev_id)s
                        """,
                        {"rev_id": revision_id},
                    )

                    integrity = cur.fetchone()

                    total = integrity["total"]
                    unique_positions = integrity["unique_positions"]
                    min_pos = integrity["min_pos"]
                    max_pos = integrity["max_pos"]

                    # All integrity checks from AD-012
                    if total != expected_count:
                        raise ChunkCountMismatchError(
                            f"Revision {revision_id}: expected {expected_count} chunks, got {total}"
                        )

                    if unique_positions != total:
                        raise ChunkCountMismatchError(
                            f"Revision {revision_id}: duplicate positions detected ({unique_positions} unique, {total} total)"
                        )

                    if min_pos != 0:
                        raise ChunkCountMismatchError(
                            f"Revision {revision_id}: min position is {min_pos}, expected 0"
                        )

                    if max_pos != expected_count - 1:
                        raise ChunkCountMismatchError(
                            f"Revision {revision_id}: max position is {max_pos}, expected {expected_count - 1}"
                        )

                    if max_pos - min_pos + 1 != total:
                        raise ChunkCountMismatchError(
                            f"Revision {revision_id}: gaps detected in positions"
                        )

                    # All checks passed, transition to PENDING (AD-003)
                    cur.execute(
                        """
                        UPDATE document_revisions
                        SET status = 'PENDING',
                            indexing_claim_token = NULL,
                            updated_at = clock_timestamp()
                        WHERE id = %(rev_id)s
                          AND indexing_claim_token = %(token)s
                        RETURNING id
                        """,
                        {"rev_id": revision_id, "token": indexing_claim_token},
                    )

                    result = cur.fetchone()

                    if not result:
                        raise ClaimTokenMismatchError(
                            f"Failed to transition revision {revision_id} to PENDING: token mismatch"
                        )

                    logger.info(
                        f"Revision {revision_id} completed indexing: {total} chunks verified, transitioned to PENDING"
                    )

        except (LeaseExpiredError, ClaimTokenMismatchError, ChunkCountMismatchError):
            raise
        except Exception as e:
            raise DatabaseError(f"Failed to complete indexing: {e}") from e

    def _cleanup_failed_revision(
        self, revision_id: int, indexing_claim_token: uuid.UUID
    ) -> None:
        """Clean up failed revision with token verification (HIGH-003 fix).
        
        Only deletes chunks/revision if we still own it. If revision was reclaimed
        by another worker, cleanup is skipped to prevent data loss.
        """
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
                    # Verify we still own the revision before cleanup (HIGH-003 fix)
                    cur.execute(
                        """
                        SELECT id, indexing_claim_token, status
                        FROM document_revisions
                        WHERE id = %(rev_id)s
                        FOR UPDATE
                        """,
                        {"rev_id": revision_id},
                    )

                    rev_state = cur.fetchone()

                    if not rev_state:
                        logger.warning(f"Revision {revision_id} not found during cleanup, skipping")
                        return

                    # Only proceed if we still own the revision
                    if rev_state["indexing_claim_token"] != indexing_claim_token:
                        logger.warning(
                            f"Revision {revision_id} token changed during cleanup "
                            f"(reclaimed by another worker), skipping to prevent data loss"
                        )
                        return

                    if rev_state["status"] != "INDEXING":
                        logger.warning(
                            f"Revision {revision_id} status is {rev_state['status']} during cleanup, skipping"
                        )
                        return

                    # Safe to delete - we still own the revision
                    cur.execute(
                        """
                        DELETE FROM document_chunks
                        WHERE revision_id = %(rev_id)s
                        """,
                        {"rev_id": revision_id},
                    )

                    # Delete revision
                    cur.execute(
                        """
                        DELETE FROM document_revisions
                        WHERE id = %(rev_id)s
                          AND indexing_claim_token = %(token)s
                          AND status = 'INDEXING'
                        """,
                        {"rev_id": revision_id, "token": indexing_claim_token},
                    )

                    logger.info(f"Cleaned up failed revision {revision_id}")

        except Exception as e:
            logger.warning(f"Cleanup of revision {revision_id} failed: {e}")

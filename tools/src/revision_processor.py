"""Document revision processing with atomic ownership fencing and integrity verification.

Invariants:
1. Every database write checks job ownership (``status = 'RUNNING'``, matching
   ``claim_token``, and ``lease_until > clock_timestamp()``) in the **same
   transaction** via ``JobManager.lock_owned_job``.
2. Pipeline keys reflect the actual extractor version (``html-v2``, ``pdf-v3``,
   ``docx-v2``). Unsupported types (including legacy ``.doc``) are rejected.
3. Empty extracted text or zero chunks are rejected with ``EmptyContentError``
   before creating a revision (never leaves an empty PENDING/INDEXING revision).
4. Existing revisions with the same ``(document_id, content_hash, pipeline_key,
   embedding_model)`` are handled by status:
   - ``PENDING`` / ``PUBLISHED``: reuse (unchanged).
   - ``REVOKED`` / ``ARCHIVED``: respect editorial state; do not resurrect.
   - ``INDEXING``: if owned by a live job with unexpired lease, skip; otherwise
     safely reclaim by rotating ``indexing_claim_token`` and re-indexing.
5. Chunk insertion replaces any prior partial chunks for our own ``INDEXING``
   revision inside the fenced transaction, and completion verifies count,
   contiguous 0..N-1 positions, and non-empty body/embedding before transitioning
   ``INDEXING -> PENDING``.
"""

import logging
import uuid
from typing import Any, Dict, List, Optional, Tuple

from .chunker import Chunker
from .database import Database
from .embedder import OllamaEmbedder
from .exceptions import (
    ChunkCountMismatchError,
    ClaimTokenMismatchError,
    DatabaseError,
    EmptyContentError,
    OwnershipLostError,
    ShutdownRequested,
    UnsupportedContentTypeError,
)
from .hash_utils import compute_content_hash
from .job_context import JobContext
from .job_manager import JobManager

logger = logging.getLogger(__name__)

# Pipeline keys — bump version whenever extraction/chunking semantics change
PIPELINE_HTML = "html-v2"
PIPELINE_PDF = "pdf-v3"
PIPELINE_DOCX = "docx-v2"

_CONTENT_TYPE_TO_PIPELINE = {
    "text/html": PIPELINE_HTML,
    "application/xhtml+xml": PIPELINE_HTML,
    "application/pdf": PIPELINE_PDF,
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": PIPELINE_DOCX,
}


class RevisionProcessor:
    """Processes document revisions with atomic fencing and integrity verification."""

    def __init__(
        self,
        database: Database,
        embedder: OllamaEmbedder,
        job_manager: JobManager,
        embedding_model: str,
        chunker: Optional[Chunker] = None,
        auto_publish: bool = False,
    ):
        self.db = database
        self.embedder = embedder
        self.job_manager = job_manager
        self.embedding_model = embedding_model
        self.chunker = chunker or Chunker()
        self.auto_publish = auto_publish

    def _get_pipeline_key(self, content_type: str) -> str:
        """Map MIME content type to pipeline key; reject legacy .doc and unknown types."""
        mime = (content_type or "").split(";", 1)[0].strip().lower()
        if mime == "application/msword":
            raise UnsupportedContentTypeError(
                "Legacy .doc (application/msword) is not supported; only .docx is supported"
            )
        key = _CONTENT_TYPE_TO_PIPELINE.get(mime)
        if not key:
            raise UnsupportedContentTypeError(f"Unsupported content type for pipeline: {content_type!r}")
        return key

    def process_document(
        self,
        job_id: int,
        job_claim_token: uuid.UUID,
        canonical_url: str,
        title: str,
        extracted_text: str,
        content_type: str,
        source_id: int,
        *,
        campus_id: Optional[int] = None,
        admission_year: Optional[int] = None,
        job_context: Optional[JobContext] = None,
    ) -> Dict[str, Any]:
        """Process one extracted document end-to-end.

        Returns:
            Dict with keys ``document_id``, ``revision_id``, ``chunks_created``,
            ``content_changed``, ``document_created``, ``skipped_reason``.
        """
        if job_context:
            job_context.checkpoint()

        if not extracted_text or not extracted_text.strip():
            raise EmptyContentError(f"Empty extracted text for {canonical_url}")

        pipeline_key = self._get_pipeline_key(content_type)
        chunks = self.chunker.chunk_text(extracted_text)
        if not chunks:
            raise EmptyContentError(f"Chunker produced 0 chunks for {canonical_url}")

        content_hash = compute_content_hash(extracted_text)
        clean_title = (title or "Untitled").strip()[:500] or "Untitled"

        logger.info(
            "Processing document: url=%s, hash=%s..., pipeline=%s, chunks=%d",
            canonical_url, content_hash[:12], pipeline_key, len(chunks),
        )

        # Atomic Step 1: verify job ownership, ensure document exists, inspect or acquire revision
        doc_id, doc_created, existing_status, revision_id, indexing_token = (
            self._acquire_revision(
                job_id=job_id,
                job_claim_token=job_claim_token,
                canonical_url=canonical_url,
                source_id=source_id,
                title=clean_title,
                content_hash=content_hash,
                pipeline_key=pipeline_key,
                expected_count=len(chunks),
                campus_id=campus_id,
                admission_year=admission_year,
            )
        )

        if existing_status is not None:
            logger.info(
                "Skipping re-indexing for document %s (revision %s, status=%s)",
                doc_id, revision_id, existing_status,
            )
            return {
                "document_id": doc_id,
                "revision_id": revision_id,
                "chunks_created": 0,
                "content_changed": False,
                "document_created": doc_created,
                "skipped_reason": existing_status.lower(),
            }

        assert revision_id is not None and indexing_token is not None

        try:
            # Step 2: Generate embeddings OUTSIDE any DB transaction (no locks held)
            chunk_bodies = [c["body"] for c in chunks]
            embeddings = self.embedder.embed_batch(chunk_bodies, job_context=job_context)

            if len(embeddings) != len(chunks):
                raise ChunkCountMismatchError(
                    f"Embedder returned {len(embeddings)} vectors for {len(chunks)} chunks"
                )

            if job_context:
                job_context.checkpoint()

            embedded_chunks = [
                {
                    "revision_id": revision_id,
                    "position": chunk["position"],
                    "body": chunk["body"],
                    "embedding": embedding,
                }
                for chunk, embedding in zip(chunks, embeddings)
            ]

            # Step 3: Insert chunks + verify integrity + transition INDEXING -> PENDING
            # in a single short fenced transaction.
            self._persist_chunks_and_complete(
                job_id=job_id,
                job_claim_token=job_claim_token,
                revision_id=revision_id,
                indexing_claim_token=indexing_token,
                embedded_chunks=embedded_chunks,
                expected_count=len(chunks),
            )

            logger.info(
                "Successfully processed revision %s (doc=%s) with %d chunk(s)",
                revision_id, doc_id, len(chunks),
            )

            return {
                "document_id": doc_id,
                "revision_id": revision_id,
                "chunks_created": len(chunks),
                "content_changed": True,
                "document_created": doc_created,
                "skipped_reason": None,
            }

        except (OwnershipLostError, ShutdownRequested):
            # Best-effort cleanup only if we still own the indexing_claim_token
            self._cleanup_failed_revision(revision_id, indexing_token)
            raise
        except Exception as e:
            logger.error("Failed to process revision %s: %s; cleaning up", revision_id, e)
            self._cleanup_failed_revision(revision_id, indexing_token)
            raise

    def _acquire_revision(
        self,
        *,
        job_id: int,
        job_claim_token: uuid.UUID,
        canonical_url: str,
        source_id: int,
        title: str,
        content_hash: str,
        pipeline_key: str,
        expected_count: int,
        campus_id: Optional[int],
        admission_year: Optional[int],
    ) -> Tuple[int, bool, Optional[str], Optional[int], Optional[uuid.UUID]]:
        """Atomically verify ownership, get-or-create document, and inspect/create/reclaim revision.

        Returns:
            ``(document_id, document_created, skipped_status, revision_id, indexing_token)``
            When ``skipped_status`` is not None, caller should NOT index (``indexing_token`` is None).
        """
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                # 1. Lock and verify job ownership first (consistent lock order: jobs -> docs -> revisions)
                self.job_manager.lock_owned_job(cur, job_id, job_claim_token)

                # 2. Get or create knowledge_document (uses INSERT ... ON CONFLICT DO NOTHING so
                #    utc_worker does NOT need UPDATE privilege on knowledge_documents).
                cur.execute(
                    """
                    INSERT INTO knowledge_documents (canonical_url, crawl_source_id)
                    VALUES (%(url)s, %(source_id)s)
                    ON CONFLICT (canonical_url) DO NOTHING
                    RETURNING id
                    """,
                    {"url": canonical_url, "source_id": source_id},
                )
                inserted_doc = cur.fetchone()
                if inserted_doc:
                    document_id = inserted_doc["id"]
                    document_created = True
                else:
                    cur.execute(
                        "SELECT id FROM knowledge_documents WHERE canonical_url = %(url)s",
                        {"url": canonical_url},
                    )
                    existing_doc = cur.fetchone()
                    if not existing_doc:
                        raise DatabaseError(f"Failed to locate knowledge_document for {canonical_url}")
                    document_id = existing_doc["id"]
                    document_created = False

                # 3. Try to insert a brand-new INDEXING revision; handle unique conflicts cleanly.
                new_token = uuid.uuid4()
                cur.execute(
                    """
                    INSERT INTO document_revisions (
                        document_id,
                        crawl_job_id,
                        campus_id,
                        admission_year,
                        title,
                        official_url,
                        content_hash,
                        pipeline_key,
                        embedding_model,
                        status,
                        expected_chunk_count,
                        indexing_claim_token,
                        updated_at
                    ) VALUES (
                        %(doc_id)s,
                        %(job_id)s,
                        %(campus_id)s,
                        %(admission_year)s,
                        %(title)s,
                        %(url)s,
                        %(hash)s,
                        %(pipeline)s,
                        %(model)s,
                        'INDEXING',
                        %(expected_count)s,
                        %(token)s,
                        clock_timestamp()
                    )
                    ON CONFLICT (document_id, content_hash, pipeline_key, embedding_model)
                    DO NOTHING
                    RETURNING id
                    """,
                    {
                        "doc_id": document_id,
                        "job_id": job_id,
                        "campus_id": campus_id,
                        "admission_year": admission_year,
                        "title": title,
                        "url": canonical_url,
                        "hash": content_hash,
                        "pipeline": pipeline_key,
                        "model": self.embedding_model,
                        "expected_count": expected_count,
                        "token": new_token,
                    },
                )
                created_rev = cur.fetchone()
                if created_rev:
                    return document_id, document_created, None, created_rev["id"], new_token

                # 4. Conflict: reload the existing revision FOR UPDATE and inspect its real status.
                cur.execute(
                    """
                    SELECT
                        r.id,
                        r.status,
                        r.crawl_job_id,
                        r.indexing_claim_token,
                        j.status AS owner_job_status,
                        j.lease_until AS owner_lease_until,
                        clock_timestamp() AS db_now
                    FROM document_revisions r
                    LEFT JOIN crawl_jobs j ON j.id = r.crawl_job_id
                    WHERE r.document_id = %(doc_id)s
                      AND r.content_hash = %(hash)s
                      AND r.pipeline_key = %(pipeline)s
                      AND r.embedding_model = %(model)s
                    FOR UPDATE OF r
                    """,
                    {
                        "doc_id": document_id,
                        "hash": content_hash,
                        "pipeline": pipeline_key,
                        "model": self.embedding_model,
                    },
                )
                existing_rev = cur.fetchone()
                if not existing_rev:
                    # Extremely rare: concurrent worker deleted its failed INDEXING revision between
                    # ON CONFLICT and SELECT. Retry insert once.
                    cur.execute(
                        """
                        INSERT INTO document_revisions (
                            document_id, crawl_job_id, campus_id, admission_year,
                            title, official_url, content_hash, pipeline_key,
                            embedding_model, status, expected_chunk_count,
                            indexing_claim_token, updated_at
                        ) VALUES (
                            %(doc_id)s, %(job_id)s, %(campus_id)s, %(admission_year)s,
                            %(title)s, %(url)s, %(hash)s, %(pipeline)s,
                            %(model)s, 'INDEXING', %(expected_count)s,
                            %(token)s, clock_timestamp()
                        )
                        RETURNING id
                        """,
                        {
                            "doc_id": document_id,
                            "job_id": job_id,
                            "campus_id": campus_id,
                            "admission_year": admission_year,
                            "title": title,
                            "url": canonical_url,
                            "hash": content_hash,
                            "pipeline": pipeline_key,
                            "model": self.embedding_model,
                            "expected_count": expected_count,
                            "token": new_token,
                        },
                    )
                    retry_rev = cur.fetchone()
                    return document_id, document_created, None, retry_rev["id"], new_token

                rev_id = existing_rev["id"]
                status = existing_rev["status"]

                if status in ("PENDING", "PUBLISHED", "ARCHIVED", "REVOKED"):
                    return document_id, document_created, status, rev_id, None

                if status == "INDEXING":
                    owner_live = (
                        existing_rev["crawl_job_id"] is not None
                        and existing_rev["crawl_job_id"] != job_id
                        and existing_rev["owner_job_status"] == "RUNNING"
                        and existing_rev["owner_lease_until"] is not None
                        and existing_rev["owner_lease_until"] > existing_rev["db_now"]
                    )
                    if owner_live:
                        logger.info(
                            "Revision %s is actively being indexed by job %s; skipping",
                            rev_id, existing_rev["crawl_job_id"],
                        )
                        return document_id, document_created, "INDEXING_BY_OTHER", rev_id, None

                    # Abandoned / stuck INDEXING revision: take over safely with a fresh token
                    logger.warning(
                        "Reclaiming abandoned INDEXING revision %s (previous job=%s)",
                        rev_id, existing_rev["crawl_job_id"],
                    )
                    cur.execute(
                        "DELETE FROM document_chunks WHERE revision_id = %(rev_id)s",
                        {"rev_id": rev_id},
                    )
                    cur.execute(
                        """
                        UPDATE document_revisions
                        SET crawl_job_id = %(job_id)s,
                            title = %(title)s,
                            expected_chunk_count = %(expected_count)s,
                            indexing_claim_token = %(token)s,
                            updated_at = clock_timestamp()
                        WHERE id = %(rev_id)s
                          AND status = 'INDEXING'
                        RETURNING id
                        """,
                        {
                            "rev_id": rev_id,
                            "job_id": job_id,
                            "title": title,
                            "expected_count": expected_count,
                            "token": new_token,
                        },
                    )
                    if not cur.fetchone():
                        raise DatabaseError(f"Failed to reclaim INDEXING revision {rev_id}")
                    return document_id, document_created, None, rev_id, new_token

                raise DatabaseError(f"Unexpected revision status {status!r} for revision {rev_id}")

    def _persist_chunks_and_complete(
        self,
        *,
        job_id: int,
        job_claim_token: uuid.UUID,
        revision_id: int,
        indexing_claim_token: uuid.UUID,
        embedded_chunks: List[Dict[str, Any]],
        expected_count: int,
    ) -> None:
        """Atomically verify job + revision ownership, replace chunks, verify integrity,
        and transition ``INDEXING -> PENDING``.
        """
        if expected_count <= 0 or not embedded_chunks:
            raise EmptyContentError(f"Cannot complete revision {revision_id} with 0 chunks")

        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                # 1. Lock and verify job ownership (checks token, RUNNING, AND lease_until > clock_timestamp())
                self.job_manager.lock_owned_job(cur, job_id, job_claim_token)

                # 2. Lock and verify revision ownership
                cur.execute(
                    """
                    SELECT document_id, crawl_job_id, indexing_claim_token, expected_chunk_count, status
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
                        f"Revision {revision_id} indexing token mismatch (reclaimed)"
                    )
                if rev_state["crawl_job_id"] != job_id:
                    raise ClaimTokenMismatchError(
                        f"Revision {revision_id} belongs to job {rev_state['crawl_job_id']}, not {job_id}"
                    )
                if rev_state["status"] != "INDEXING":
                    raise DatabaseError(
                        f"Revision {revision_id} status is {rev_state['status']}, expected INDEXING"
                    )
                if rev_state["expected_chunk_count"] != expected_count:
                    raise ChunkCountMismatchError(
                        f"Revision {revision_id} expected_chunk_count={rev_state['expected_chunk_count']} "
                        f"!= {expected_count}"
                    )

                # 3. Replace any partial chunks cleanly (never blindly ignore conflicts)
                cur.execute(
                    "DELETE FROM document_chunks WHERE revision_id = %(rev_id)s",
                    {"rev_id": revision_id},
                )

                cur.executemany(
                    """
                    INSERT INTO document_chunks (revision_id, position, body, embedding)
                    VALUES (%(revision_id)s, %(position)s, %(body)s, %(embedding)s)
                    """,
                    embedded_chunks,
                )

                # 4. Integrity verification in database
                cur.execute(
                    """
                    SELECT
                        COUNT(*) AS total,
                        COUNT(DISTINCT position) AS unique_positions,
                        MIN(position) AS min_pos,
                        MAX(position) AS max_pos,
                        COUNT(*) FILTER (WHERE length(trim(body)) = 0 OR embedding IS NULL) AS invalid_rows
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
                invalid_rows = integrity["invalid_rows"]

                if invalid_rows > 0:
                    raise ChunkCountMismatchError(
                        f"Revision {revision_id}: {invalid_rows} chunk(s) have empty body or null embedding"
                    )
                if total != expected_count:
                    raise ChunkCountMismatchError(
                        f"Revision {revision_id}: expected {expected_count} chunks, got {total}"
                    )
                if unique_positions != total:
                    raise ChunkCountMismatchError(
                        f"Revision {revision_id}: duplicate positions ({unique_positions} unique vs {total})"
                    )
                if min_pos != 0 or max_pos != expected_count - 1 or (max_pos - min_pos + 1) != total:
                    raise ChunkCountMismatchError(
                        f"Revision {revision_id}: non-contiguous positions (min={min_pos}, max={max_pos}, total={total})"
                    )

                # 5. Transition INDEXING -> PUBLISHED (when auto_publish) or PENDING
                if self.auto_publish:
                    # Archive previous PUBLISHED/PENDING revisions FIRST so the partial unique
                    # index `document_revisions (document_id) WHERE status = 'PUBLISHED'` is preserved.
                    cur.execute(
                        """
                        UPDATE document_revisions
                        SET status = 'ARCHIVED',
                            updated_at = clock_timestamp()
                        WHERE document_id = %(doc_id)s
                          AND id <> %(rev_id)s
                          AND status IN ('PENDING', 'PUBLISHED')
                        """,
                        {"doc_id": rev_state["document_id"], "rev_id": revision_id},
                    )
                    cur.execute(
                        """
                        UPDATE document_revisions
                        SET status = 'PUBLISHED',
                            source_verified = true,
                            published_at = clock_timestamp(),
                            indexing_claim_token = NULL,
                            updated_at = clock_timestamp()
                        WHERE id = %(rev_id)s
                          AND indexing_claim_token = %(token)s
                          AND status = 'INDEXING'
                        RETURNING id, document_id
                        """,
                        {"rev_id": revision_id, "token": indexing_claim_token},
                    )
                    if not cur.fetchone():
                        raise ClaimTokenMismatchError(
                            f"Failed to transition revision {revision_id} to PUBLISHED: token mismatch"
                        )
                else:
                    cur.execute(
                        """
                        UPDATE document_revisions
                        SET status = 'PENDING',
                            indexing_claim_token = NULL,
                            updated_at = clock_timestamp()
                        WHERE id = %(rev_id)s
                          AND indexing_claim_token = %(token)s
                          AND status = 'INDEXING'
                        RETURNING id, document_id
                        """,
                        {"rev_id": revision_id, "token": indexing_claim_token},
                    )
                    transitioned = cur.fetchone()
                    if not transitioned:
                        raise ClaimTokenMismatchError(
                            f"Failed to transition revision {revision_id} to PENDING: token mismatch"
                        )

                    # Mark older unreviewed PENDING revisions of the same document as ARCHIVED
                    # (keeps any live PUBLISHED revision intact until admin publishes this new one).
                    cur.execute(
                        """
                        UPDATE document_revisions
                        SET status = 'ARCHIVED',
                            updated_at = clock_timestamp()
                        WHERE document_id = %(doc_id)s
                          AND id <> %(rev_id)s
                          AND status = 'PENDING'
                        """,
                        {"doc_id": transitioned["document_id"], "rev_id": revision_id},
                    )

    def archive_document_revisions(
        self,
        job_id: int,
        job_claim_token: uuid.UUID,
        canonical_url: str,
        reason: str = "Source removed",
    ) -> int:
        """Archive active (PENDING/PUBLISHED) revisions for a deleted URL (e.g. HTTP 404/410).

        Fenced by ``lock_owned_job`` so a stale worker cannot archive revisions.
        """
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                self.job_manager.lock_owned_job(cur, job_id, job_claim_token)
                cur.execute(
                    "SELECT id FROM knowledge_documents WHERE canonical_url = %(url)s",
                    {"url": canonical_url},
                )
                doc = cur.fetchone()
                if not doc:
                    return 0

                cur.execute(
                    """
                    UPDATE document_revisions
                    SET status = 'ARCHIVED',
                        updated_at = clock_timestamp()
                    WHERE document_id = %(doc_id)s
                      AND status IN ('PENDING', 'PUBLISHED')
                    RETURNING id
                    """,
                    {"doc_id": doc["id"]},
                )
                archived = cur.fetchall()
                if archived:
                    logger.info(
                        "Archived %d revision(s) for removed URL %s (%s)",
                        len(archived), canonical_url, reason,
                    )
                return len(archived)

    def archive_missing_source_documents(
        self,
        job_id: int,
        job_claim_token: uuid.UUID,
        source_id: int,
        active_canonical_urls: List[str],
    ) -> int:
        """Archive active revisions for documents of ``source_id`` no longer present
        in a complete sitemap discovery pass.
        """
        if not active_canonical_urls:
            return 0
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                self.job_manager.lock_owned_job(cur, job_id, job_claim_token)
                cur.execute(
                    """
                    UPDATE document_revisions r
                    SET status = 'ARCHIVED',
                        updated_at = clock_timestamp()
                    FROM knowledge_documents d
                    WHERE r.document_id = d.id
                      AND d.crawl_source_id = %(source_id)s
                      AND NOT (d.canonical_url = ANY(%(active_urls)s))
                      AND r.status IN ('PENDING', 'PUBLISHED')
                    RETURNING r.id
                    """,
                    {"source_id": source_id, "active_urls": list(active_canonical_urls)},
                )
                archived = cur.fetchall()
                if archived:
                    logger.info(
                        "Archived %d revision(s) for URLs removed from source %s sitemap",
                        len(archived), source_id,
                    )
                return len(archived)

    def _cleanup_failed_revision(
        self, revision_id: int, indexing_claim_token: uuid.UUID
    ) -> bool:
        """Delete chunks and revision ONLY if we still own ``indexing_claim_token`` and status is INDEXING.

        Returns:
            True if cleaned up, False if skipped or cleanup failed.
        """
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
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
                        return False
                    if rev_state["indexing_claim_token"] != indexing_claim_token:
                        logger.warning(
                            "Skipping cleanup of revision %s: token changed (reclaimed by another worker)",
                            revision_id,
                        )
                        return False
                    if rev_state["status"] != "INDEXING":
                        logger.warning(
                            "Skipping cleanup of revision %s: status is %s (not INDEXING)",
                            revision_id, rev_state["status"],
                        )
                        return False

                    cur.execute(
                        "DELETE FROM document_chunks WHERE revision_id = %(rev_id)s",
                        {"rev_id": revision_id},
                    )
                    cur.execute(
                        """
                        DELETE FROM document_revisions
                        WHERE id = %(rev_id)s
                          AND indexing_claim_token = %(token)s
                          AND status = 'INDEXING'
                        """,
                        {"rev_id": revision_id, "token": indexing_claim_token},
                    )
                    logger.info("Cleaned up failed INDEXING revision %s", revision_id)
                    return True
        except Exception as e:
            logger.error(
                "Cleanup failed for INDEXING revision %s (may need later reclaim): %s",
                revision_id, e,
            )
            return False

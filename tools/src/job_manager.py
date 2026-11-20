"""Job management with lease-based claiming.

Implements AD-002: Lease-based job claiming with token fencing.
"""

import logging
import uuid
from typing import Optional, Dict, Any
from datetime import datetime, timedelta

from .database import Database
from .exceptions import LeaseExpiredError, ClaimTokenMismatchError, DatabaseError

logger = logging.getLogger(__name__)


class JobManager:
    """Manages crawler job lifecycle with lease-based ownership."""

    def __init__(self, database: Database, lease_duration_minutes: int = 10):
        """Initialize job manager.

        Args:
            database: Database connection manager
            lease_duration_minutes: Lease duration in minutes (default 10)
        """
        self.db = database
        self.lease_duration_minutes = lease_duration_minutes
        self.current_job_id: Optional[int] = None
        self.current_claim_token: Optional[uuid.UUID] = None

    def claim_job(self) -> Optional[Dict[str, Any]]:
        """Atomically claim a pending job using SELECT FOR UPDATE SKIP LOCKED.

        Implements AD-002 lease-based claiming with token generation.

        Returns:
            Job record dict with id, source_id, claim_token, etc., or None if no jobs available

        Raises:
            DatabaseError: If claiming fails
        """
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
                    # Atomically claim next pending job
                    # Uses FOR UPDATE SKIP LOCKED per AD-002
                    cur.execute(
                        """
                        UPDATE crawl_jobs
                        SET status = 'RUNNING',
                            lease_until = clock_timestamp() + %(lease_interval)s::interval,
                            claim_token = gen_random_uuid(),
                            attempt_no = attempt_no + 1
                        WHERE id = (
                            SELECT id FROM crawl_jobs
                            WHERE status = 'PENDING' 
                              AND run_after <= clock_timestamp()
                            ORDER BY run_after, id
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING id, source_id, status, claim_token, lease_until, attempt_no
                        """,
                        {"lease_interval": f"{self.lease_duration_minutes} minutes"},
                    )

                    result = cur.fetchone()

                    if result:
                        self.current_job_id = result["id"]
                        self.current_claim_token = result["claim_token"]

                        logger.info(
                            f"Claimed job {result['id']} for source {result['source_id']} "
                            f"(attempt {result['attempt_no']}, lease until {result['lease_until']})"
                        )

                        return result

                    logger.debug("No pending jobs available to claim")
                    return None

        except Exception as e:
            logger.error(f"Failed to claim job: {e}")
            raise DatabaseError(f"Job claiming failed: {e}") from e

    def heartbeat(self, job_id: int, claim_token: uuid.UUID) -> None:
        """Extend job lease to keep ownership alive.

        Implements AD-002 heartbeat mechanism with token verification.

        Args:
            job_id: Job ID
            claim_token: Current claim token

        Raises:
            ClaimTokenMismatchError: If token doesn't match (job reclaimed)
            LeaseExpiredError: If lease already expired
            DatabaseError: If update fails
        """
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
                    # Extend lease only if token matches, still RUNNING, and lease not expired (HIGH-002 fix)
                    cur.execute(
                        """
                        UPDATE crawl_jobs
                        SET lease_until = clock_timestamp() + %(lease_interval)s::interval
                        WHERE id = %(job_id)s
                          AND claim_token = %(claim_token)s
                          AND status = 'RUNNING'
                          AND lease_until > clock_timestamp()
                        RETURNING id, lease_until
                        """,
                        {
                            "job_id": job_id,
                            "claim_token": claim_token,
                            "lease_interval": f"{self.lease_duration_minutes} minutes",
                        },
                    )

                    result = cur.fetchone()

                    if not result:
                        # Check why update failed
                        cur.execute(
                            """
                            SELECT status, claim_token, lease_until, clock_timestamp() as db_now
                            FROM crawl_jobs WHERE id = %(job_id)s
                            """,
                            {"job_id": job_id},
                        )
                        job_state = cur.fetchone()

                        if not job_state:
                            raise DatabaseError(f"Job {job_id} not found")

                        if job_state["claim_token"] != claim_token:
                            raise ClaimTokenMismatchError(
                                f"Job {job_id} claim token changed (job reclaimed by another worker)"
                            )

                        # Use database clock for lease check (consistent with HIGH-001 fix)
                        if job_state["lease_until"] and job_state["lease_until"] < job_state["db_now"]:
                            raise LeaseExpiredError(
                                f"Job {job_id} lease expired at {job_state['lease_until']} (database time: {job_state['db_now']})"
                            )

                        raise DatabaseError(
                            f"Job {job_id} heartbeat failed: status={job_state['status']}"
                        )

                    logger.debug(
                        f"Heartbeat successful for job {job_id}, lease extended to {result['lease_until']}"
                    )

        except (ClaimTokenMismatchError, LeaseExpiredError):
            raise
        except Exception as e:
            logger.error(f"Heartbeat failed for job {job_id}: {e}")
            raise DatabaseError(f"Heartbeat failed: {e}") from e

    def complete_job(
        self, job_id: int, claim_token: uuid.UUID, completed_at: Optional[datetime] = None
    ) -> None:
        """Mark job as successfully completed.

        Args:
            job_id: Job ID
            claim_token: Current claim token
            completed_at: Completion timestamp (defaults to now)

        Raises:
            ClaimTokenMismatchError: If token doesn't match
            DatabaseError: If update fails
        """
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
                    # Transition to COMPLETED only if we still own it
                    # Note: completed_at column added in migration 005
                    cur.execute(
                        """
                        UPDATE crawl_jobs
                        SET status = 'COMPLETED',
                            lease_until = NULL,
                            claim_token = NULL
                        WHERE id = %(job_id)s
                          AND claim_token = %(claim_token)s
                          AND status = 'RUNNING'
                        RETURNING id
                        """,
                        {
                            "job_id": job_id,
                            "claim_token": claim_token,
                        },
                    )

                    result = cur.fetchone()

                    if not result:
                        # Check if token mismatch
                        cur.execute(
                            "SELECT claim_token FROM crawl_jobs WHERE id = %(job_id)s",
                            {"job_id": job_id},
                        )
                        job_state = cur.fetchone()

                        if job_state and job_state["claim_token"] != claim_token:
                            raise ClaimTokenMismatchError(
                                f"Cannot complete job {job_id}: claim token mismatch"
                            )

                        raise DatabaseError(
                            f"Failed to complete job {job_id} (may have been reclaimed)"
                        )

                    logger.info(f"Job {job_id} completed successfully")

                    # Clear current job tracking
                    if self.current_job_id == job_id:
                        self.current_job_id = None
                        self.current_claim_token = None

        except ClaimTokenMismatchError:
            raise
        except Exception as e:
            logger.error(f"Failed to complete job {job_id}: {e}")
            raise DatabaseError(f"Job completion failed: {e}") from e

    def fail_job(
        self,
        job_id: int,
        claim_token: uuid.UUID,
        error_message: str,
        max_attempts: int = 5,
        retry_delay_minutes: int = 60,
    ) -> None:
        """Mark job as failed and schedule retry or mark permanently failed.

        Args:
            job_id: Job ID
            claim_token: Current claim token
            error_message: Failure reason
            max_attempts: Maximum retry attempts (default 5)
            retry_delay_minutes: Delay before retry (default 60)

        Raises:
            ClaimTokenMismatchError: If token doesn't match
            DatabaseError: If update fails
        """
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
                    # Get current attempt number
                    cur.execute(
                        "SELECT attempt_no FROM crawl_jobs WHERE id = %(job_id)s",
                        {"job_id": job_id},
                    )
                    job_state = cur.fetchone()

                    if not job_state:
                        raise DatabaseError(f"Job {job_id} not found")

                    attempt_no = job_state["attempt_no"]

                    if attempt_no >= max_attempts:
                        # Permanently failed
                        # Note: error_message column added in migration 005
                        cur.execute(
                            """
                            UPDATE crawl_jobs
                            SET status = 'FAILED',
                                lease_until = NULL,
                                claim_token = NULL
                            WHERE id = %(job_id)s
                              AND claim_token = %(claim_token)s
                            RETURNING id
                            """,
                            {
                                "job_id": job_id,
                                "claim_token": claim_token,
                            },
                        )

                        result = cur.fetchone()

                        if not result:
                            raise ClaimTokenMismatchError(
                                f"Cannot fail job {job_id}: claim token mismatch"
                            )

                        logger.error(
                            f"Job {job_id} permanently FAILED after {attempt_no} attempts: {error_message}"
                        )

                    else:
                        # Schedule retry
                        cur.execute(
                            """
                            UPDATE crawl_jobs
                            SET status = 'PENDING',
                                run_after = clock_timestamp() + %(retry_interval)s::interval,
                                lease_until = NULL,
                                claim_token = NULL
                            WHERE id = %(job_id)s
                              AND claim_token = %(claim_token)s
                            RETURNING id
                            """,
                            {
                                "job_id": job_id,
                                "claim_token": claim_token,
                                "retry_interval": f"{retry_delay_minutes} minutes",
                            },
                        )

                        result = cur.fetchone()

                        if not result:
                            raise ClaimTokenMismatchError(
                                f"Cannot schedule retry for job {job_id}: claim token mismatch"
                            )

                        logger.warning(
                            f"Job {job_id} failed (attempt {attempt_no}/{max_attempts}), "
                            f"scheduled retry in {retry_delay_minutes}m: {error_message}"
                        )

                    # Clear current job tracking
                    if self.current_job_id == job_id:
                        self.current_job_id = None
                        self.current_claim_token = None

        except ClaimTokenMismatchError:
            raise
        except Exception as e:
            logger.error(f"Failed to handle job failure for {job_id}: {e}")
            raise DatabaseError(f"Job failure handling failed: {e}") from e

    def reclaim_expired_jobs(self) -> int:
        """Reclaim jobs with expired leases.

        Resets jobs back to PENDING when lease expires and worker disappeared.
        Implements AD-002 recovery mechanism.

        Returns:
            Number of jobs reclaimed

        Raises:
            DatabaseError: If reclaim operation fails
        """
        try:
            with self.db.transaction() as conn:
                with conn.cursor() as cur:
                    # Find and reset expired jobs
                    cur.execute(
                        """
                        UPDATE crawl_jobs
                        SET status = 'PENDING',
                            run_after = clock_timestamp(),
                            lease_until = NULL,
                            claim_token = NULL
                        WHERE status = 'RUNNING'
                          AND lease_until < clock_timestamp()
                        RETURNING id, source_id
                        """
                    )

                    reclaimed = cur.fetchall()

                    if reclaimed:
                        job_ids = [job["id"] for job in reclaimed]
                        logger.warning(
                            f"Reclaimed {len(reclaimed)} expired jobs: {job_ids}"
                        )

                    return len(reclaimed)

        except Exception as e:
            logger.error(f"Failed to reclaim expired jobs: {e}")
            raise DatabaseError(f"Job reclaim failed: {e}") from e

    def verify_ownership(self, job_id: int, claim_token: uuid.UUID) -> None:
        """Verify current ownership of job (for atomic fencing per AD-011).

        Uses PostgreSQL clock_timestamp() for lease validation to avoid clock skew issues.

        Args:
            job_id: Job ID
            claim_token: Expected claim token

        Raises:
            ClaimTokenMismatchError: If token doesn't match
            LeaseExpiredError: If lease expired
            DatabaseError: If verification fails
        """
        try:
            result = self.db.execute_one(
                """
                SELECT status, claim_token, lease_until, clock_timestamp() as db_now
                FROM crawl_jobs
                WHERE id = %(job_id)s
                FOR UPDATE
                """,
                {"job_id": job_id},
            )

            if not result:
                raise DatabaseError(f"Job {job_id} not found")

            if result["claim_token"] != claim_token:
                raise ClaimTokenMismatchError(
                    f"Job {job_id} claim token mismatch (job reclaimed)"
                )

            if result["status"] != "RUNNING":
                raise DatabaseError(
                    f"Job {job_id} status is {result['status']}, expected RUNNING"
                )

            # Use database clock for lease expiration check (HIGH-001 fix)
            if result["lease_until"] and result["lease_until"] < result["db_now"]:
                raise LeaseExpiredError(
                    f"Job {job_id} lease expired at {result['lease_until']} (database time: {result['db_now']})"
                )

            logger.debug(f"Job {job_id} ownership verified")

        except (ClaimTokenMismatchError, LeaseExpiredError):
            raise
        except Exception as e:
            logger.error(f"Failed to verify job ownership: {e}")
            raise DatabaseError(f"Ownership verification failed: {e}") from e

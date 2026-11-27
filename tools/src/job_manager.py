"""Job management with lease-based claiming and token fencing.

Ownership model
---------------
A worker owns a job iff, at the moment a statement executes inside PostgreSQL:

    status = 'RUNNING' AND claim_token = <token> AND lease_until > clock_timestamp()

Every state transition (heartbeat, complete, fail, release) re-checks that
predicate *inside the same UPDATE / transaction*, using the database clock, so a
stale worker (lease expired, job reclaimed, token rotated) can never overwrite
the job state. Failure diagnostics distinguish token mismatch, lease expiry and
wrong status.

``attempt_no`` counts how many times the job has been claimed (execution
attempts). It is incremented by ``claim_job`` and decremented only by
``release_job`` (graceful shutdown, the attempt is not counted).
"""

import logging
import random
import re
import uuid
from dataclasses import dataclass
from typing import Any, Dict, Optional

from .database import Database
from .exceptions import (
    ClaimTokenMismatchError,
    DatabaseError,
    JobStateError,
    LeaseExpiredError,
)

logger = logging.getLogger(__name__)

MAX_ERROR_MESSAGE_LENGTH = 2000

_SECRET_PATTERNS = [
    # key=value / key: value secrets
    (re.compile(r"(?i)\b(password|passwd|pwd|secret|token|api[_-]?key|authorization)\b\s*[=:]\s*\S+"),
     r"\1=***"),
    # credentials in URLs: scheme://user:pass@host
    (re.compile(r"(?i)\b([a-z][a-z0-9+.-]*://)[^\s/@:]+:[^\s/@]+@"), r"\1***:***@"),
    # Bearer tokens
    (re.compile(r"(?i)\bbearer\s+[a-z0-9._\-]+"), "Bearer ***"),
]


def sanitize_error_message(message: Optional[str], limit: int = MAX_ERROR_MESSAGE_LENGTH) -> str:
    """Remove obvious secrets and bound the length of an error message."""
    text = (message or "").strip() or "Unknown error"
    for pattern, replacement in _SECRET_PATTERNS:
        text = pattern.sub(replacement, text)
    text = text.replace("\x00", "")
    if len(text) > limit:
        text = text[: limit - 15] + "... [truncated]"
    return text


@dataclass(frozen=True)
class RetryPolicy:
    """Exponential backoff with cap and jitter.

    delay(attempt) = min(base * 2^(attempt-1), max) * (1 ± jitter)

    With the defaults (max_attempts=3, base=15 min, max=6 h, jitter 20%):
    attempt 1 fails -> retry after ~15 min, attempt 2 fails -> ~30 min,
    attempt 3 fails -> FAILED (no more retries).
    """

    max_attempts: int = 3
    base_delay_seconds: int = 900
    max_delay_seconds: int = 21600
    jitter_ratio: float = 0.2

    def __post_init__(self):
        if self.max_attempts < 1:
            raise ValueError("max_attempts must be >= 1")
        if self.base_delay_seconds < 0 or self.max_delay_seconds < self.base_delay_seconds:
            raise ValueError("invalid retry delays")
        if not 0 <= self.jitter_ratio <= 1:
            raise ValueError("jitter_ratio must be within [0, 1]")

    def should_retry(self, attempt_no: int, retryable: bool = True) -> bool:
        return retryable and attempt_no < self.max_attempts

    def delay_seconds(self, attempt_no: int, rng: Optional[random.Random] = None) -> float:
        attempt = max(1, attempt_no)
        raw = min(self.base_delay_seconds * (2 ** (attempt - 1)), self.max_delay_seconds)
        if self.jitter_ratio and raw > 0:
            r = (rng or random).uniform(-self.jitter_ratio, self.jitter_ratio)
            raw = raw * (1 + r)
        return max(0.0, min(raw, self.max_delay_seconds * (1 + self.jitter_ratio)))


class JobManager:
    """Manages crawler job lifecycle with lease-based ownership."""

    def __init__(
        self,
        database: Database,
        lease_duration_seconds: int = 600,
        retry_policy: Optional[RetryPolicy] = None,
    ):
        if lease_duration_seconds <= 0:
            raise ValueError("lease_duration_seconds must be > 0")
        self.db = database
        self.lease_duration_seconds = int(lease_duration_seconds)
        self.retry_policy = retry_policy or RetryPolicy()

    # ------------------------------------------------------------------ helpers
    @property
    def _lease_interval(self) -> str:
        return f"{self.lease_duration_seconds} seconds"

    @staticmethod
    def _diagnose(cur, job_id: int, claim_token: uuid.UUID, action: str) -> None:
        """Raise the precise ownership error for a job (called after a fenced UPDATE matched 0 rows)."""
        cur.execute(
            """
            SELECT status, claim_token, lease_until, clock_timestamp() AS db_now
            FROM crawl_jobs WHERE id = %(job_id)s
            """,
            {"job_id": job_id},
        )
        state = cur.fetchone()
        if not state:
            raise DatabaseError(f"Cannot {action} job {job_id}: job not found")
        if state["claim_token"] != claim_token:
            raise ClaimTokenMismatchError(
                f"Cannot {action} job {job_id}: claim token mismatch (job reclaimed or finished)"
            )
        if state["status"] != "RUNNING":
            raise JobStateError(f"Cannot {action} job {job_id}: status is {state['status']}")
        if state["lease_until"] is None or state["lease_until"] <= state["db_now"]:
            raise LeaseExpiredError(
                f"Cannot {action} job {job_id}: lease expired at {state['lease_until']} "
                f"(database time {state['db_now']})"
            )
        raise DatabaseError(f"Cannot {action} job {job_id}: unexpected state {dict(state)}")

    def lock_owned_job(self, cur, job_id: int, claim_token: uuid.UUID) -> Dict[str, Any]:
        """Lock the job row FOR UPDATE inside the caller's transaction and verify ownership.

        The lock is held until the caller's transaction ends, so writes performed in
        the same transaction are fenced: reclaim/complete by others must wait, and
        will observe our committed state.
        """
        cur.execute(
            """
            SELECT id, status, claim_token, lease_until, attempt_no,
                   clock_timestamp() AS db_now
            FROM crawl_jobs
            WHERE id = %(job_id)s
            FOR UPDATE
            """,
            {"job_id": job_id},
        )
        state = cur.fetchone()
        if not state:
            raise DatabaseError(f"Job {job_id} not found")
        if state["claim_token"] != claim_token:
            raise ClaimTokenMismatchError(f"Job {job_id} claim token mismatch (job reclaimed)")
        if state["status"] != "RUNNING":
            raise JobStateError(f"Job {job_id} status is {state['status']}, expected RUNNING")
        if state["lease_until"] is None or state["lease_until"] <= state["db_now"]:
            raise LeaseExpiredError(
                f"Job {job_id} lease expired at {state['lease_until']} (database time {state['db_now']})"
            )
        return state

    # --------------------------------------------------------------- lifecycle
    def claim_job(self) -> Optional[Dict[str, Any]]:
        """Atomically claim the next runnable PENDING job (FOR UPDATE SKIP LOCKED)."""
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE crawl_jobs
                    SET status = 'RUNNING',
                        lease_until = clock_timestamp() + %(lease)s::interval,
                        claim_token = gen_random_uuid(),
                        attempt_no = attempt_no + 1,
                        completed_at = NULL
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
                    {"lease": self._lease_interval},
                )
                job = cur.fetchone()

        if job:
            logger.info(
                "Claimed job %s for source %s (attempt %s/%s, lease until %s)",
                job["id"], job["source_id"], job["attempt_no"],
                self.retry_policy.max_attempts, job["lease_until"],
            )
        return job

    def heartbeat(self, job_id: int, claim_token: uuid.UUID) -> Any:
        """Extend the lease. Never extends a lease that already expired or was lost.

        Returns:
            New lease_until.
        Raises:
            ClaimTokenMismatchError / LeaseExpiredError / JobStateError on lost ownership.
        """
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE crawl_jobs
                    SET lease_until = clock_timestamp() + %(lease)s::interval
                    WHERE id = %(job_id)s
                      AND claim_token = %(token)s
                      AND status = 'RUNNING'
                      AND lease_until > clock_timestamp()
                    RETURNING lease_until
                    """,
                    {"job_id": job_id, "token": claim_token, "lease": self._lease_interval},
                )
                row = cur.fetchone()
                if not row:
                    self._diagnose(cur, job_id, claim_token, "heartbeat")
        logger.debug("Heartbeat ok for job %s, lease until %s", job_id, row["lease_until"])
        return row["lease_until"]

    def complete_job(
        self, job_id: int, claim_token: uuid.UUID, summary: Optional[str] = None
    ) -> None:
        """Mark job COMPLETED (only with valid token AND unexpired lease).

        Args:
            summary: Optional note stored in error_message (e.g. per-URL failures of a
                partially successful crawl). NULL for a clean run.
        """
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE crawl_jobs
                    SET status = 'COMPLETED',
                        completed_at = clock_timestamp(),
                        error_message = %(summary)s,
                        lease_until = NULL,
                        claim_token = NULL
                    WHERE id = %(job_id)s
                      AND claim_token = %(token)s
                      AND status = 'RUNNING'
                      AND lease_until > clock_timestamp()
                    RETURNING id, completed_at
                    """,
                    {
                        "job_id": job_id,
                        "token": claim_token,
                        "summary": sanitize_error_message(summary) if summary else None,
                    },
                )
                row = cur.fetchone()
                if not row:
                    self._diagnose(cur, job_id, claim_token, "complete")
        logger.info("Job %s COMPLETED at %s", job_id, row["completed_at"])

    def fail_job(
        self,
        job_id: int,
        claim_token: uuid.UUID,
        error_message: str,
        retryable: bool = True,
    ) -> str:
        """Record a failure: schedule a retry (PENDING) or mark FAILED.

        The job row is locked and ownership (token, RUNNING, lease) is verified in
        the same transaction as the update, so attempt_no and the new state are
        consistent and a stale worker cannot reset a job it lost.

        Returns:
            'PENDING' if a retry was scheduled, 'FAILED' otherwise.
        """
        message = sanitize_error_message(error_message)
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                state = self.lock_owned_job(cur, job_id, claim_token)
                attempt_no = state["attempt_no"]

                if self.retry_policy.should_retry(attempt_no, retryable):
                    delay = self.retry_policy.delay_seconds(attempt_no)
                    cur.execute(
                        """
                        UPDATE crawl_jobs
                        SET status = 'PENDING',
                            run_after = clock_timestamp() + make_interval(secs => %(delay)s),
                            error_message = %(msg)s,
                            lease_until = NULL,
                            claim_token = NULL
                        WHERE id = %(job_id)s AND claim_token = %(token)s
                        RETURNING run_after
                        """,
                        {"job_id": job_id, "token": claim_token, "delay": delay, "msg": message},
                    )
                    row = cur.fetchone()
                    logger.warning(
                        "Job %s failed (attempt %s/%s), retry at %s: %s",
                        job_id, attempt_no, self.retry_policy.max_attempts, row["run_after"], message,
                    )
                    return "PENDING"

                cur.execute(
                    """
                    UPDATE crawl_jobs
                    SET status = 'FAILED',
                        completed_at = clock_timestamp(),
                        error_message = %(msg)s,
                        lease_until = NULL,
                        claim_token = NULL
                    WHERE id = %(job_id)s AND claim_token = %(token)s
                    RETURNING id
                    """,
                    {"job_id": job_id, "token": claim_token, "msg": message},
                )
                cur.fetchone()
                logger.error(
                    "Job %s permanently FAILED after %s attempt(s)%s: %s",
                    job_id, attempt_no, "" if retryable else " (non-retryable)", message,
                )
                return "FAILED"

    def release_job(self, job_id: int, claim_token: uuid.UUID, reason: str = "worker shutdown") -> None:
        """Give an owned job back to the queue without counting the attempt."""
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                self.lock_owned_job(cur, job_id, claim_token)
                cur.execute(
                    """
                    UPDATE crawl_jobs
                    SET status = 'PENDING',
                        run_after = clock_timestamp(),
                        attempt_no = GREATEST(attempt_no - 1, 0),
                        error_message = %(msg)s,
                        lease_until = NULL,
                        claim_token = NULL
                    WHERE id = %(job_id)s AND claim_token = %(token)s
                    """,
                    {"job_id": job_id, "token": claim_token, "msg": sanitize_error_message(f"Released: {reason}")},
                )
        logger.info("Job %s released back to queue (%s)", job_id, reason)

    def reclaim_expired_jobs(self) -> int:
        """Reset RUNNING jobs whose lease expired.

        The old claim token is cleared, so the previous owner is fenced out. A job
        that already used all attempts becomes FAILED (crash loops cannot retry
        forever); otherwise it is rescheduled with the retry backoff.
        Rows locked by a live transaction (heartbeat/complete in progress) are
        skipped and re-evaluated on the next pass.
        """
        reclaimed = 0
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, attempt_no, lease_until
                    FROM crawl_jobs
                    WHERE status = 'RUNNING'
                      AND lease_until <= clock_timestamp()
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    """
                )
                rows = cur.fetchall()
                for row in rows:
                    message = sanitize_error_message(
                        f"Lease expired at {row['lease_until']} (worker lost or stalled) "
                        f"on attempt {row['attempt_no']}"
                    )
                    if self.retry_policy.should_retry(row["attempt_no"]):
                        cur.execute(
                            """
                            UPDATE crawl_jobs
                            SET status = 'PENDING',
                                run_after = clock_timestamp() + make_interval(secs => %(delay)s),
                                error_message = %(msg)s,
                                lease_until = NULL,
                                claim_token = NULL
                            WHERE id = %(id)s AND status = 'RUNNING' AND lease_until <= clock_timestamp()
                            """,
                            {"id": row["id"], "msg": message,
                             "delay": self.retry_policy.delay_seconds(row["attempt_no"])},
                        )
                    else:
                        cur.execute(
                            """
                            UPDATE crawl_jobs
                            SET status = 'FAILED',
                                completed_at = clock_timestamp(),
                                error_message = %(msg)s,
                                lease_until = NULL,
                                claim_token = NULL
                            WHERE id = %(id)s AND status = 'RUNNING' AND lease_until <= clock_timestamp()
                            """,
                            {"id": row["id"], "msg": message},
                        )
                    reclaimed += cur.rowcount
        if reclaimed:
            logger.warning("Reclaimed %s expired job(s): %s", reclaimed, [r["id"] for r in rows])
        return reclaimed

    def verify_ownership(self, job_id: int, claim_token: uuid.UUID) -> None:
        """Point-in-time ownership check (no lock is held afterwards).

        Use it only to stop early; it does NOT protect later writes. Writes must
        re-check ownership in their own transaction (see ``lock_owned_job``).
        """
        with self.db.transaction() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT 1 FROM crawl_jobs
                    WHERE id = %(job_id)s AND claim_token = %(token)s
                      AND status = 'RUNNING' AND lease_until > clock_timestamp()
                    """,
                    {"job_id": job_id, "token": claim_token},
                )
                if not cur.fetchone():
                    self._diagnose(cur, job_id, claim_token, "verify")

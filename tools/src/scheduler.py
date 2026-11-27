"""Automatic crawl job scheduler.

Creates periodic crawl jobs for enabled sources without manual intervention.

Scheduling rules
----------------
A new PENDING job is created for an enabled source iff:
1. No PENDING or RUNNING job currently exists for that source (enforced both in
   the query and by the partial unique index ``crawl_jobs (source_id) WHERE
   status IN ('PENDING', 'RUNNING')`` so multiple scheduler instances are safe).
2. Either no previous COMPLETED/FAILED job exists, OR the elapsed time since the
   most recent finished job's ``completed_at`` (falling back to ``created_at``
   for legacy rows) is >= ``crawl_interval_hours`` (or ``default_interval_hours``).
"""

import logging
import signal
import sys
import threading
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

import psycopg

from .config import SchedulerConfig, load_config, touch_liveness
from .database import Database
from .exceptions import DatabaseError

logger = logging.getLogger(__name__)


class CrawlScheduler:
    """Automatic scheduler that creates crawl jobs for enabled sources."""

    def __init__(
        self,
        database: Database,
        default_interval_hours: int = 6,
        check_interval_seconds: int = 300,
        liveness_file: Optional[str] = None,
        register_signals: bool = True,
    ):
        if default_interval_hours < 1:
            raise ValueError("default_interval_hours must be >= 1")
        if check_interval_seconds < 1:
            raise ValueError("check_interval_seconds must be >= 1")

        self.database = database
        self.default_interval_hours = default_interval_hours
        self.check_interval_seconds = check_interval_seconds
        self.liveness_file = liveness_file
        self._stop_event = threading.Event()

        if register_signals:
            try:
                signal.signal(signal.SIGTERM, self._handle_shutdown)
                signal.signal(signal.SIGINT, self._handle_shutdown)
            except ValueError:
                # Signal registration only works in the main thread (e.g. unit tests)
                pass

    @property
    def stopped(self) -> bool:
        return self._stop_event.is_set()

    @stopped.setter
    def stopped(self, value: bool) -> None:
        if value:
            self._stop_event.set()
        else:
            self._stop_event.clear()

    def _handle_shutdown(self, signum, frame) -> None:
        logger.info("Scheduler received signal %s, shutting down gracefully...", signum)
        self._stop_event.set()

    def _verify_schema_ready(self) -> bool:
        """Confirm required scheduler columns exist before entering the main loop."""
        try:
            row = self.database.execute_one(
                """
                SELECT
                    EXISTS (
                        SELECT 1 FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'crawl_sources'
                          AND column_name = 'crawl_interval_hours'
                    ) AS has_interval,
                    EXISTS (
                        SELECT 1 FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'crawl_jobs'
                          AND column_name = 'created_at'
                    ) AS has_created_at,
                    EXISTS (
                        SELECT 1 FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'crawl_jobs'
                          AND column_name = 'completed_at'
                    ) AS has_completed_at
                """
            )
            if not row:
                return False
            missing = [
                col
                for col, ok in (
                    ("crawl_sources.crawl_interval_hours", row["has_interval"]),
                    ("crawl_jobs.created_at", row["has_created_at"]),
                    ("crawl_jobs.completed_at", row["has_completed_at"]),
                )
                if not ok
            ]
            if missing:
                logger.error(
                    "Database schema is not ready for scheduler; missing columns: %s. "
                    "Apply migrations 005..009 before starting.",
                    ", ".join(missing),
                )
                return False
            return True
        except Exception as e:
            logger.error("Failed to verify database schema: %s", e)
            return False

    def run(self) -> None:
        """Main scheduler loop. Always closes the database pool on exit."""
        logger.info(
            "Scheduler starting: checking every %ss, default interval %sh",
            self.check_interval_seconds,
            self.default_interval_hours,
        )

        try:
            self.database.connect()

            if not self.database.health_check():
                logger.error("Database health check failed")
                sys.exit(1)

            if not self._verify_schema_ready():
                sys.exit(1)

            logger.info("Scheduler ready")
            touch_liveness(self.liveness_file)

            while not self._stop_event.is_set():
                try:
                    self.schedule_once()
                    touch_liveness(self.liveness_file)
                except KeyboardInterrupt:
                    logger.info("Keyboard interrupt received")
                    self._stop_event.set()
                    break
                except Exception as e:
                    logger.error("Scheduler pass failed: %s", e, exc_info=True)
                    if self._stop_event.wait(min(60, self.check_interval_seconds)):
                        break
                    continue

                if self._stop_event.wait(self.check_interval_seconds):
                    break
        finally:
            logger.info("Scheduler shutting down...")
            self.database.close()
            logger.info("Scheduler stopped")

    def schedule_once(self) -> int:
        """Run one scheduling pass across all enabled sources.

        Returns:
            Number of new jobs created.
        """
        sources = self._get_enabled_sources()
        if not sources:
            logger.debug("No enabled sources found")
            return 0

        logger.debug("Checking %d enabled sources for scheduling", len(sources))
        scheduled_count = 0

        for source in sources:
            if self._stop_event.is_set():
                break
            try:
                if self._should_schedule_job(source):
                    if self._create_job(source) is not None:
                        scheduled_count += 1
            except Exception as e:
                logger.error(
                    "Failed to schedule job for source %s (%s): %s",
                    source["id"],
                    source["base_url"],
                    e,
                )

        if scheduled_count > 0:
            logger.info("Scheduled %d new crawl job(s)", scheduled_count)

        return scheduled_count

    # Backward-compatible alias
    _schedule_jobs = schedule_once

    def _get_enabled_sources(self) -> List[Dict[str, Any]]:
        """Get all enabled crawl sources (uses actual schema columns; no name column)."""
        try:
            return self.database.execute(
                """
                SELECT id, base_url, crawl_interval_hours
                FROM crawl_sources
                WHERE enabled = true
                ORDER BY id
                """
            )
        except Exception as e:
            raise DatabaseError(f"Failed to fetch enabled sources: {e}") from e

    def _should_schedule_job(self, source: Dict[str, Any]) -> bool:
        """Check whether a new job should be scheduled for ``source``."""
        source_id = source["id"]
        label = source["base_url"]

        active_job = self.database.execute_one(
            """
            SELECT id, status
            FROM crawl_jobs
            WHERE source_id = %(source_id)s
              AND status IN ('PENDING', 'RUNNING')
            LIMIT 1
            """,
            {"source_id": source_id},
        )
        if active_job:
            logger.debug(
                "Source %s (%s) has active %s job %s, skipping",
                source_id, label, active_job["status"], active_job["id"],
            )
            return False

        # Compare using the database clock so clock skew between containers cannot
        # trigger premature or delayed crawls.
        last_job = self.database.execute_one(
            """
            SELECT
                id,
                status,
                completed_at,
                created_at,
                clock_timestamp() AS db_now
            FROM crawl_jobs
            WHERE source_id = %(source_id)s
              AND status IN ('COMPLETED', 'FAILED')
            ORDER BY COALESCE(completed_at, created_at) DESC, id DESC
            LIMIT 1
            """,
            {"source_id": source_id},
        )

        if not last_job:
            logger.info("Source %s (%s) has no previous finished jobs, scheduling", source_id, label)
            return True

        interval_hours = source.get("crawl_interval_hours") or self.default_interval_hours
        last_time = last_job.get("completed_at") or last_job.get("created_at")

        if not last_time:
            logger.warning("Source %s (%s) last job %s has no timestamp, scheduling", source_id, label, last_job["id"])
            return True

        now = last_job.get("db_now") or datetime.now(timezone.utc)
        if last_time.tzinfo is None:
            last_time = last_time.replace(tzinfo=timezone.utc)
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)

        elapsed_hours = (now - last_time).total_seconds() / 3600.0

        if elapsed_hours >= interval_hours:
            logger.info(
                "Source %s (%s): %.2fh since last %s job %s (interval: %sh), scheduling",
                source_id, label, elapsed_hours, last_job["status"], last_job["id"], interval_hours,
            )
            return True

        remaining_hours = interval_hours - elapsed_hours
        logger.debug("Source %s (%s): %.2fh until next crawl", source_id, label, remaining_hours)
        return False

    def _create_job(self, source: Dict[str, Any]) -> Optional[int]:
        """Insert a new PENDING job. Safe under concurrent schedulers.

        Uses ``ON CONFLICT DO NOTHING`` against the partial unique index
        ``(source_id) WHERE status IN ('PENDING', 'RUNNING')`` so two schedulers
        racing for the same source produce at most one job without throwing an error.

        Returns:
            Created job ID, or None if another scheduler/API created an active job first.
        """
        source_id = source["id"]
        label = source["base_url"]

        try:
            with self.database.transaction() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        INSERT INTO crawl_jobs (
                            source_id,
                            status,
                            run_after,
                            attempt_no,
                            created_at
                        )
                        VALUES (
                            %(source_id)s,
                            'PENDING',
                            clock_timestamp(),
                            0,
                            clock_timestamp()
                        )
                        ON CONFLICT (source_id) WHERE status IN ('PENDING', 'RUNNING')
                        DO NOTHING
                        RETURNING id, created_at
                        """,
                        {"source_id": source_id},
                    )
                    row = cur.fetchone()
        except DatabaseError as e:
            cause = e.__cause__
            if isinstance(cause, psycopg.errors.UniqueViolation):
                logger.info("Active job already exists for source %s (%s), skipping", source_id, label)
                return None
            raise DatabaseError(f"Failed to create job for source {source_id}: {e}") from e

        if not row:
            logger.info("Active job already exists for source %s (%s), skipping", source_id, label)
            return None

        logger.info("Created job %s for source %s (%s) at %s", row["id"], source_id, label, row["created_at"])
        return row["id"]


def main() -> None:
    """Scheduler entry point."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    )
    logger.info("UTC Crawler Scheduler starting...")

    try:
        config = load_config()
        sched_cfg: SchedulerConfig = config.scheduler
        scheduler = CrawlScheduler(
            database=Database(config.database),
            default_interval_hours=sched_cfg.default_interval_hours,
            check_interval_seconds=sched_cfg.check_interval_seconds,
            liveness_file=sched_cfg.liveness_file,
        )
        scheduler.run()
    except SystemExit:
        raise
    except Exception as e:
        logger.error("Scheduler failed to start: %s", e, exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    main()

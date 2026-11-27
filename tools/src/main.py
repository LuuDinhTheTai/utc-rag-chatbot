"""Worker entry point — claims crawl jobs, maintains time-based leases, and runs ingestion."""

import logging
import signal
import sys
import threading
import time
from typing import Optional

from .chunker import Chunker
from .config import Config, load_config, touch_liveness
from .crawler import CrawlEngine
from .database import Database
from .embedder import OllamaEmbedder
from .exceptions import (
    DiscoveryError,
    OwnershipLostError,
    PermanentJobError,
    ShutdownRequested,
    ValidationError,
)
from .job_context import JobContext, LeaseKeeper
from .job_manager import JobManager, RetryPolicy
from .revision_processor import RevisionProcessor
from .source_registry import SourceRegistry

logger = logging.getLogger(__name__)


class Worker:
    """Crawler worker that claims and processes jobs from PostgreSQL."""

    def __init__(self, config: Config, register_signals: bool = True):
        self.config = config
        self._stop_event = threading.Event()

        self.database = Database(config.database)
        retry_policy = RetryPolicy(
            max_attempts=config.worker.max_attempts,
            base_delay_seconds=config.worker.retry_base_delay_seconds,
            max_delay_seconds=config.worker.retry_max_delay_seconds,
            jitter_ratio=config.worker.retry_jitter_ratio,
        )
        self.job_manager = JobManager(
            self.database,
            lease_duration_seconds=config.worker.lease_duration_seconds,
            retry_policy=retry_policy,
        )
        self.source_registry = SourceRegistry(
            self.database,
            default_rate_limit_seconds=config.crawler.default_rate_limit_seconds,
        )
        self.embedder = OllamaEmbedder(config=config.ollama)
        self.chunker = Chunker()
        self.revision_processor = RevisionProcessor(
            database=self.database,
            embedder=self.embedder,
            job_manager=self.job_manager,
            embedding_model=config.ollama.embedding_model,
            chunker=self.chunker,
            auto_publish=config.crawler.auto_publish,
        )
        self.crawler = CrawlEngine(
            config=config.crawler,
            database=self.database,
            job_manager=self.job_manager,
            revision_processor=self.revision_processor,
        )

        if register_signals:
            try:
                signal.signal(signal.SIGTERM, self._handle_shutdown)
                signal.signal(signal.SIGINT, self._handle_shutdown)
            except ValueError:
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
        logger.info("Received signal %s, shutting down gracefully...", signum)
        self._stop_event.set()

    def run(self) -> None:
        """Main worker loop. Always cleans up resources even if startup checks fail."""
        logger.info(
            "Worker starting (lease=%ss, heartbeat=%ss, max_attempts=%d)...",
            self.config.worker.lease_duration_seconds,
            self.config.worker.heartbeat_interval_seconds,
            self.config.worker.max_attempts,
        )

        try:
            self.database.connect()

            if not self.database.health_check():
                logger.error("Database health check failed")
                sys.exit(1)

            if not self.embedder.health_check():
                logger.error("Embedder health check failed")
                sys.exit(1)

            logger.info("Health checks passed")
            touch_liveness(self.config.worker.liveness_file)

            idle_count = 0
            last_reclaim = 0.0

            while not self._stop_event.is_set():
                try:
                    now = time.monotonic()
                    if now - last_reclaim >= self.config.worker.reclaim_interval_seconds:
                        try:
                            self.job_manager.reclaim_expired_jobs()
                        except Exception as e:
                            logger.warning("Reclaim pass failed: %s", e)
                        last_reclaim = now

                    job = self.job_manager.claim_job()
                    touch_liveness(self.config.worker.liveness_file)

                    if job is None:
                        idle_count += 1
                        if idle_count == 1:
                            logger.info("No jobs available, waiting...")
                        wait_time = min(
                            2 ** (idle_count - 1),
                            self.config.worker.idle_poll_max_seconds,
                        )
                        self._stop_event.wait(wait_time)
                        continue

                    idle_count = 0
                    self._process_job(job)

                except KeyboardInterrupt:
                    logger.info("Keyboard interrupt received")
                    self._stop_event.set()
                    break
                except Exception as e:
                    logger.error("Unexpected error in worker loop: %s", e, exc_info=True)
                    if self._stop_event.wait(5):
                        break
        finally:
            logger.info("Worker shutting down...")
            self._cleanup()
            logger.info("Worker stopped")

    def _process_job(self, job: dict) -> None:
        """Process a single claimed job with a time-based LeaseKeeper."""
        job_id = job["id"]
        source_id = job["source_id"]
        claim_token = job["claim_token"]

        logger.info("Processing job %s for source %s (attempt %s)", job_id, source_id, job.get("attempt_no"))

        ctx = JobContext(job_id=job_id, claim_token=claim_token, stop_event=self._stop_event)

        def on_beat() -> None:
            touch_liveness(self.config.worker.liveness_file)

        with LeaseKeeper(
            heartbeat=self.job_manager.heartbeat,
            context=ctx,
            interval_seconds=self.config.worker.heartbeat_interval_seconds,
            lease_duration_seconds=self.config.worker.lease_duration_seconds,
            on_beat=on_beat,
        ):
            try:
                source = self.source_registry.get_source_by_id(source_id)
                if not source:
                    raise PermanentJobError(f"Source {source_id} not found")

                if not source.enabled:
                    logger.warning("Source %s is disabled; completing job %s without crawling", source_id, job_id)
                    self.job_manager.complete_job(job_id, claim_token, summary="Source disabled; skipped")
                    return

                issues = self.source_registry.validate_source_config(source)
                if issues:
                    logger.warning("Source %s validation issues: %s", source_id, issues)

                report = self.crawler.crawl_source(source, job_id, claim_token, job_context=ctx)

                # Final checkpoint before completing
                ctx.checkpoint()

                if report.status == "failed":
                    # Every discovered URL failed -> do NOT mark job COMPLETED
                    self.job_manager.fail_job(
                        job_id,
                        claim_token,
                        error_message=report.error_summary or "All discovered URLs failed",
                        retryable=True,
                    )
                    return

                self.job_manager.complete_job(
                    job_id,
                    claim_token,
                    summary=report.error_summary,
                )

            except OwnershipLostError as e:
                logger.error("Lost ownership of job %s: %s (not updating job row)", job_id, e)

            except ShutdownRequested:
                logger.info("Shutdown requested during job %s; releasing job back to queue", job_id)
                try:
                    self.job_manager.release_job(job_id, claim_token, reason="worker shutdown")
                except OwnershipLostError:
                    logger.warning("Could not release job %s: ownership already lost", job_id)
                except Exception as release_err:
                    logger.error("Failed to release job %s: %s", job_id, release_err)

            except (PermanentJobError, ValidationError) as e:
                logger.error("Job %s failed permanently: %s", job_id, e)
                self._safe_fail(job_id, claim_token, str(e), retryable=False)

            except DiscoveryError as e:
                logger.error("Job %s discovery failed: %s", job_id, e)
                self._safe_fail(job_id, claim_token, f"Discovery failed: {e}", retryable=True)

            except Exception as e:
                logger.error("Job %s failed: %s", job_id, e, exc_info=True)
                self._safe_fail(job_id, claim_token, str(e), retryable=True)

    def _safe_fail(self, job_id: int, claim_token, message: str, *, retryable: bool) -> None:
        try:
            self.job_manager.fail_job(job_id, claim_token, error_message=message, retryable=retryable)
        except OwnershipLostError as e:
            logger.warning("Skipping fail_job for %s: ownership lost (%s)", job_id, e)
        except Exception as e:
            logger.error("Failed to mark job %s as failed: %s", job_id, e)

    def _cleanup(self) -> None:
        for name, obj in (("crawler", self.crawler), ("embedder", self.embedder), ("database", self.database)):
            try:
                obj.close()
            except Exception as e:
                logger.error("Error closing %s: %s", name, e)


def main() -> None:
    """Main worker entry point."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    )
    logger.info("UTC Crawler Worker starting...")

    try:
        config = load_config()
        worker = Worker(config)
        worker.run()
    except SystemExit:
        raise
    except Exception as e:
        logger.error("Worker failed to start: %s", e, exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    main()

"""
Automatic crawl job scheduler.

Creates periodic crawl jobs for enabled sources without manual intervention.
Implements requirement: "crawler tự lập lịch; không cần chạy lệnh ingest thủ công"
"""

import logging
import signal
import time
import sys
from datetime import datetime, timezone
from typing import List, Dict, Any

from .database import Database
from .config import Config
from .source_registry import SourceRegistry
from .exceptions import DatabaseError

logger = logging.getLogger(__name__)


class CrawlScheduler:
    """
    Automatic scheduler that creates crawl jobs for enabled sources.
    
    - Runs periodically to check if sources need crawling
    - Creates jobs for sources without active/pending jobs
    - Respects source-specific crawl intervals
    """
    
    def __init__(
        self,
        database: Database,
        default_interval_hours: int = 6,
        check_interval_seconds: int = 300,  # Check every 5 minutes
    ):
        """
        Initialize scheduler.
        
        Args:
            database: Database connection manager
            default_interval_hours: Default crawl interval (6 hours)
            check_interval_seconds: How often to check for scheduling (5 minutes)
        """
        self.database = database
        self.default_interval_hours = default_interval_hours
        self.check_interval_seconds = check_interval_seconds
        self.stopped = False
        
        # Register signal handlers
        signal.signal(signal.SIGTERM, self._handle_shutdown)
        signal.signal(signal.SIGINT, self._handle_shutdown)
    
    def _handle_shutdown(self, signum, frame):
        """Handle shutdown signals."""
        logger.info(f"Scheduler received signal {signum}, shutting down gracefully...")
        self.stopped = True
    
    def run(self):
        """
        Main scheduler loop.
        
        Continuously checks for sources needing crawl jobs and creates them.
        """
        logger.info(
            f"Scheduler starting: checking every {self.check_interval_seconds}s, "
            f"default interval {self.default_interval_hours}h"
        )
        
        # Initialize database
        self.database.connect()
        
        if not self.database.health_check():
            logger.error("Database health check failed")
            sys.exit(1)
        
        logger.info("Scheduler ready")
        
        # Initial scheduling pass
        try:
            self._schedule_jobs()
        except Exception as e:
            logger.error(f"Initial scheduling failed: {e}", exc_info=True)
        
        # Main loop
        while not self.stopped:
            try:
                time.sleep(self.check_interval_seconds)
                
                if self.stopped:
                    break
                
                self._schedule_jobs()
                
            except KeyboardInterrupt:
                logger.info("Keyboard interrupt received")
                self.stopped = True
                break
            
            except Exception as e:
                logger.error(f"Scheduler error: {e}", exc_info=True)
                time.sleep(60)  # Wait 1 minute before retrying on error
        
        # Cleanup
        logger.info("Scheduler shutting down...")
        self.database.close()
        logger.info("Scheduler stopped")
    
    def _schedule_jobs(self):
        """
        Check all enabled sources and create jobs if needed.
        
        A source needs a job if:
        - It's enabled
        - No PENDING or RUNNING job exists for it
        - Last completed job is older than crawl interval (or no completed job exists)
        """
        try:
            # Get all enabled sources
            source_registry = SourceRegistry(self.database)
            sources = self._get_enabled_sources()
            
            if not sources:
                logger.debug("No enabled sources found")
                return
            
            logger.debug(f"Checking {len(sources)} enabled sources for scheduling")
            
            scheduled_count = 0
            
            for source in sources:
                try:
                    if self._should_schedule_job(source):
                        self._create_job(source)
                        scheduled_count += 1
                except Exception as e:
                    logger.error(
                        f"Failed to schedule job for source {source['id']} ({source['name']}): {e}"
                    )
            
            if scheduled_count > 0:
                logger.info(f"Scheduled {scheduled_count} new crawl jobs")
            
        except Exception as e:
            logger.error(f"Failed to schedule jobs: {e}", exc_info=True)
            raise
    
    def _get_enabled_sources(self) -> List[Dict[str, Any]]:
        """Get all enabled crawl sources."""
        try:
            results = self.database.execute_many(
                """
                SELECT id, name, base_url, crawl_interval_hours
                FROM crawl_sources
                WHERE enabled = true
                ORDER BY id
                """
            )
            return results
        except Exception as e:
            raise DatabaseError(f"Failed to fetch enabled sources: {e}") from e
    
    def _should_schedule_job(self, source: Dict[str, Any]) -> bool:
        """
        Check if a job should be scheduled for this source.
        
        Args:
            source: Source dictionary with id, name, crawl_interval_hours
            
        Returns:
            True if a job should be created
        """
        source_id = source['id']
        source_name = source['name']
        
        # Check for active/pending jobs
        active_job = self.database.execute_one(
            """
            SELECT id, status
            FROM crawl_jobs
            WHERE source_id = %(source_id)s
              AND status IN ('PENDING', 'RUNNING')
            LIMIT 1
            """,
            {'source_id': source_id}
        )
        
        if active_job:
            logger.debug(
                f"Source {source_id} ({source_name}) has active job {active_job['id']}, skipping"
            )
            return False
        
        # Get last completed job
        last_job = self.database.execute_one(
            """
            SELECT id, status, completed_at, created_at
            FROM crawl_jobs
            WHERE source_id = %(source_id)s
              AND status IN ('COMPLETED', 'FAILED')
            ORDER BY completed_at DESC NULLS LAST, created_at DESC
            LIMIT 1
            """,
            {'source_id': source_id}
        )
        
        # If no previous job, schedule immediately
        if not last_job:
            logger.info(
                f"Source {source_id} ({source_name}) has no previous jobs, scheduling"
            )
            return True
        
        # Check if enough time has passed since last job
        interval_hours = source.get('crawl_interval_hours') or self.default_interval_hours
        
        # Use completed_at if available, otherwise use created_at
        last_time = last_job.get('completed_at') or last_job['created_at']
        
        if not last_time:
            logger.warning(
                f"Source {source_id} ({source_name}) has job without timestamp, scheduling"
            )
            return True
        
        # Calculate time since last job
        now = datetime.now(timezone.utc)
        
        # Ensure last_time is timezone-aware
        if last_time.tzinfo is None:
            last_time = last_time.replace(tzinfo=timezone.utc)
        
        elapsed_hours = (now - last_time).total_seconds() / 3600
        
        if elapsed_hours >= interval_hours:
            logger.info(
                f"Source {source_id} ({source_name}): {elapsed_hours:.1f}h since last crawl "
                f"(interval: {interval_hours}h), scheduling"
            )
            return True
        else:
            remaining_hours = interval_hours - elapsed_hours
            logger.debug(
                f"Source {source_id} ({source_name}): {remaining_hours:.1f}h until next crawl"
            )
            return False
    
    def _create_job(self, source: Dict[str, Any]):
        """
        Create a new crawl job for the source.
        
        Args:
            source: Source dictionary with id and name
        """
        source_id = source['id']
        source_name = source['name']
        
        try:
            result = self.database.execute_one(
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
                    NOW(),
                    0,
                    NOW()
                )
                RETURNING id, created_at
                """,
                {'source_id': source_id}
            )
            
            job_id = result['id']
            created_at = result['created_at']
            
            logger.info(
                f"Created job {job_id} for source {source_id} ({source_name}) at {created_at}"
            )
            
        except Exception as e:
            # Check if it's a unique constraint violation (another process created the job)
            if 'unique' in str(e).lower() or 'duplicate' in str(e).lower():
                logger.info(
                    f"Job already exists for source {source_id} ({source_name}), skipping"
                )
            else:
                raise DatabaseError(
                    f"Failed to create job for source {source_id}: {e}"
                ) from e


def main():
    """Scheduler entry point."""
    # Configure logging
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    )
    
    logger.info("UTC Crawler Scheduler starting...")
    
    try:
        # Load configuration
        from .config import load_config
        config = load_config()
        
        # Create scheduler with default 6-hour interval
        scheduler = CrawlScheduler(
            database=Database(config.database),
            default_interval_hours=6,
            check_interval_seconds=300,  # Check every 5 minutes
        )
        
        # Run scheduler
        scheduler.run()
        
    except Exception as e:
        logger.error(f"Scheduler failed to start: {e}", exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    main()

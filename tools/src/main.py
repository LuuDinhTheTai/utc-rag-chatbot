"""
Worker entry point - Phase 3 crawler worker.

Implements main worker loop:
- Claim jobs from queue
- Process each job (crawl source)
- Send heartbeats
- Handle graceful shutdown
"""

import logging
import signal
import time
import sys
from typing import Optional

from .config import CrawlerConfig
from .database import Database
from .job_manager import JobManager
from .revision_processor import RevisionProcessor
from .crawler import CrawlEngine
from .embedder import OllamaEmbedder
from .chunker import Chunker
from .source_registry import SourceRegistry
from .exceptions import LeaseExpiredError, ClaimTokenMismatchError


logger = logging.getLogger(__name__)


class Worker:
    """Crawler worker that processes jobs from queue."""
    
    def __init__(self, config: CrawlerConfig):
        """
        Initialize worker.
        
        Args:
            config: Crawler configuration
        """
        self.config = config
        self.stopped = False
        
        # Initialize components
        self.database = Database(config)
        self.job_manager = JobManager(self.database, config)
        
        # Initialize embedder and chunker
        self.embedder = OllamaEmbedder(
            base_url=config.ollama_base_url,
            model=config.embedding_model,
            expected_dimension=config.embedding_dimension,
        )
        self.chunker = Chunker(
            max_chunk_size=config.chunk_max_size,
            overlap_size=config.chunk_overlap_size,
        )
        
        # Initialize revision processor
        self.revision_processor = RevisionProcessor(
            database=self.database,
            embedder=self.embedder,
            chunker=self.chunker,
            config=config,
        )
        
        # Initialize crawler
        self.crawler = CrawlEngine(
            config=config,
            database=self.database,
            job_manager=self.job_manager,
            revision_processor=self.revision_processor,
        )
        
        # Register signal handlers
        signal.signal(signal.SIGTERM, self._handle_shutdown)
        signal.signal(signal.SIGINT, self._handle_shutdown)
    
    def _handle_shutdown(self, signum, frame):
        """Handle shutdown signals."""
        logger.info(f"Received signal {signum}, shutting down gracefully...")
        self.stopped = True
    
    def run(self):
        """
        Main worker loop.
        
        Continuously claims and processes jobs until stopped.
        """
        logger.info("Worker starting...")
        
        # Health check
        if not self.database.health_check():
            logger.error("Database health check failed")
            sys.exit(1)
        
        if not self.embedder.health_check():
            logger.error("Embedder health check failed")
            sys.exit(1)
        
        logger.info("Health checks passed")
        
        # Main loop
        idle_count = 0
        
        while not self.stopped:
            try:
                # Attempt to claim a job
                job = self.job_manager.claim_job()
                
                if job is None:
                    # No jobs available
                    idle_count += 1
                    
                    if idle_count == 1:
                        logger.info("No jobs available, waiting...")
                    
                    # Exponential backoff (1s, 2s, 4s, ..., max 30s)
                    wait_time = min(2 ** (idle_count - 1), 30)
                    time.sleep(wait_time)
                    continue
                
                # Reset idle count when job claimed
                idle_count = 0
                
                # Process job
                self._process_job(job)
                
            except KeyboardInterrupt:
                logger.info("Keyboard interrupt received")
                self.stopped = True
                break
            
            except Exception as e:
                logger.error(f"Unexpected error in main loop: {e}", exc_info=True)
                time.sleep(5)  # Brief pause before retrying
        
        # Cleanup
        logger.info("Worker shutting down...")
        self._cleanup()
        logger.info("Worker stopped")
    
    def _process_job(self, job: dict):
        """
        Process a single crawl job.
        
        Args:
            job: Job dictionary with id, source_id, claim_token
        """
        job_id = job['id']
        source_id = job['source_id']
        claim_token = job['claim_token']
        
        logger.info(f"Processing job {job_id} for source {source_id}")
        
        try:
            # Get source configuration
            source_registry = SourceRegistry(self.database)
            source = source_registry.get_source_by_id(source_id)
            
            if not source:
                logger.error(f"Source {source_id} not found")
                self.job_manager.fail_job(
                    job_id,
                    claim_token,
                    error_message=f"Source {source_id} not found",
                )
                return
            
            if not source.enabled:
                logger.warning(f"Source {source_id} is disabled")
                self.job_manager.complete_job(job_id, claim_token)
                return
            
            # Validate source configuration
            issues = source_registry.validate_source_config(source)
            if issues:
                logger.warning(f"Source {source_id} validation issues: {issues}")
            
            # Crawl source
            results = self.crawler.crawl_source(source, job_id, claim_token)
            
            # Log results
            success_count = sum(1 for r in results if r.success)
            new_count = sum(1 for r in results if r.status == 'new')
            changed_count = sum(1 for r in results if r.status == 'changed')
            unchanged_count = sum(1 for r in results if r.status == 'unchanged')
            error_count = sum(1 for r in results if not r.success)
            
            logger.info(
                f"Job {job_id} completed: "
                f"{success_count}/{len(results)} successful, "
                f"{new_count} new, {changed_count} changed, "
                f"{unchanged_count} unchanged, {error_count} errors"
            )
            
            # Update source last_crawled timestamp
            source_registry.update_last_crawled(source_id)
            
            # Mark job as complete
            self.job_manager.complete_job(job_id, claim_token)
            
        except (LeaseExpiredError, ClaimTokenMismatchError) as e:
            logger.error(f"Lost job ownership for job {job_id}: {e}")
            # Don't try to update job status - we no longer own it
        
        except Exception as e:
            logger.error(f"Job {job_id} failed: {e}", exc_info=True)
            
            try:
                self.job_manager.fail_job(job_id, claim_token, error_message=str(e))
            except Exception as fail_error:
                logger.error(f"Failed to mark job as failed: {fail_error}")
    
    def _cleanup(self):
        """Cleanup resources."""
        try:
            self.crawler.close()
        except Exception as e:
            logger.error(f"Error closing crawler: {e}")
        
        try:
            self.embedder.close()
        except Exception as e:
            logger.error(f"Error closing embedder: {e}")
        
        try:
            self.database.close()
        except Exception as e:
            logger.error(f"Error closing database: {e}")


def main() -> None:
    """Main worker entry point."""
    # Configure logging
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    )
    
    logger.info("UTC Crawler Worker starting...")
    
    try:
        # Load configuration
        config = CrawlerConfig()
        
        # Create and run worker
        worker = Worker(config)
        worker.run()
        
    except Exception as e:
        logger.error(f"Worker failed to start: {e}", exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    main()

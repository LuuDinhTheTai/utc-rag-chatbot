-- Migration 009: Indexes supporting the scheduler and stuck-revision recovery
-- Idempotent: safe to run multiple times.

BEGIN;

-- Scheduler: latest finished job per source
CREATE INDEX IF NOT EXISTS idx_crawl_jobs_source_finished
    ON crawl_jobs (source_id, completed_at DESC)
    WHERE status IN ('COMPLETED', 'FAILED');

-- Worker: reclaim of expired leases
CREATE INDEX IF NOT EXISTS idx_crawl_jobs_running_lease
    ON crawl_jobs (lease_until)
    WHERE status = 'RUNNING';

-- RevisionProcessor: lookup of INDEXING revisions owned by a job
CREATE INDEX IF NOT EXISTS idx_document_revisions_crawl_job
    ON document_revisions (crawl_job_id)
    WHERE status = 'INDEXING';

DO $$
BEGIN
    IF (SELECT count(*) FROM pg_indexes
        WHERE schemaname = current_schema()
          AND indexname IN ('idx_crawl_jobs_source_finished',
                            'idx_crawl_jobs_running_lease',
                            'idx_document_revisions_crawl_job')) <> 3 THEN
        RAISE EXCEPTION 'Migration 009 incomplete';
    END IF;
    RAISE NOTICE 'Migration 009 completed successfully';
END $$;

COMMIT;


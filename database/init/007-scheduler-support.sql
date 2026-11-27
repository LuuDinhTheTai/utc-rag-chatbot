-- Migration 007: Add scheduler support
-- Adds crawl_sources.crawl_interval_hours (per-source schedule) and
-- crawl_jobs.created_at (job creation time, fallback for interval calculation).
--
-- NOTE (audit 2026-11-27): the original version of this file backfilled from
-- crawl_jobs.updated_at, a column that has never existed, so the migration
-- aborted on every database and could never have been applied. Fixing it in
-- place is therefore safe for every existing deployment. The backfill is also
-- corrected: the column is added WITHOUT a default first (otherwise PostgreSQL
-- fills all existing rows with the migration time and the backfill is a no-op).
-- Idempotent: safe to run multiple times.

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'crawl_sources' AND column_name = 'crawl_interval_hours'
    ) THEN
        ALTER TABLE crawl_sources
            ADD COLUMN crawl_interval_hours integer DEFAULT 6;
        RAISE NOTICE 'Added crawl_sources.crawl_interval_hours (default: 6 hours)';
    ELSE
        RAISE NOTICE 'crawl_sources.crawl_interval_hours already exists';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'crawl_sources'::regclass
          AND conname = 'crawl_sources_crawl_interval_hours_check'
    ) THEN
        ALTER TABLE crawl_sources
            ADD CONSTRAINT crawl_sources_crawl_interval_hours_check
            CHECK (crawl_interval_hours IS NULL OR crawl_interval_hours > 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'crawl_jobs' AND column_name = 'created_at'
    ) THEN
        -- 1. add nullable, no default (existing rows stay NULL)
        ALTER TABLE crawl_jobs ADD COLUMN created_at timestamptz;

        -- 2. backfill from the best existing timestamp
        UPDATE crawl_jobs
        SET created_at = COALESCE(run_after, completed_at, now())
        WHERE created_at IS NULL;

        -- 3. default for new rows (backend inserts do not provide it) + NOT NULL
        ALTER TABLE crawl_jobs ALTER COLUMN created_at SET DEFAULT now();
        ALTER TABLE crawl_jobs ALTER COLUMN created_at SET NOT NULL;

        RAISE NOTICE 'Added crawl_jobs.created_at (backfilled from run_after/completed_at)';
    ELSE
        -- Repair partially-configured columns from manual attempts
        UPDATE crawl_jobs SET created_at = COALESCE(run_after, completed_at, now())
        WHERE created_at IS NULL;
        ALTER TABLE crawl_jobs ALTER COLUMN created_at SET DEFAULT now();
        ALTER TABLE crawl_jobs ALTER COLUMN created_at SET NOT NULL;
        RAISE NOTICE 'crawl_jobs.created_at already exists (default/NOT NULL ensured)';
    END IF;
END $$;

-- Verification
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'crawl_sources' AND column_name = 'crawl_interval_hours'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'crawl_jobs' AND column_name = 'created_at'
          AND is_nullable = 'NO' AND column_default IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Migration 007 incomplete';
    END IF;
    RAISE NOTICE 'Migration 007 completed successfully';
END $$;

COMMIT;

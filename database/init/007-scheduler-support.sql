-- Migration 007: Add scheduler support
-- Adds crawl_interval_hours column for per-source scheduling configuration

BEGIN;

-- Add crawl_interval_hours to crawl_sources
-- Default to 6 hours if not specified
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_sources' AND column_name = 'crawl_interval_hours'
    ) THEN
        ALTER TABLE crawl_sources 
        ADD COLUMN crawl_interval_hours integer DEFAULT 6 CHECK (crawl_interval_hours > 0);
        
        RAISE NOTICE 'Added crawl_interval_hours column to crawl_sources (default: 6 hours)';
    ELSE
        RAISE NOTICE 'crawl_interval_hours column already exists';
    END IF;
END $$;

-- Add created_at to crawl_jobs if not exists (for tracking when job was created)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_jobs' AND column_name = 'created_at'
    ) THEN
        ALTER TABLE crawl_jobs ADD COLUMN created_at timestamptz DEFAULT NOW();
        
        -- Backfill created_at for existing jobs using updated_at or run_after
        UPDATE crawl_jobs 
        SET created_at = COALESCE(updated_at, run_after, NOW())
        WHERE created_at IS NULL;
        
        -- Make it NOT NULL after backfill
        ALTER TABLE crawl_jobs ALTER COLUMN created_at SET NOT NULL;
        
        RAISE NOTICE 'Added created_at column to crawl_jobs';
    ELSE
        RAISE NOTICE 'created_at column already exists';
    END IF;
END $$;

-- Verification
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_sources' AND column_name = 'crawl_interval_hours'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_jobs' AND column_name = 'created_at'
    ) THEN
        RAISE NOTICE 'Migration 007 completed successfully';
    ELSE
        RAISE EXCEPTION 'Migration 007 incomplete';
    END IF;
END $$;

COMMIT;

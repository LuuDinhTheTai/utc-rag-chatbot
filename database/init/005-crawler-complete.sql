-- Migration 005: Crawler completion tracking and constraints
-- Adds columns and constraints needed for crawler worker operations
-- Safe for existing data: uses sensible defaults and validates before constraining

BEGIN;

-- ============================================================================
-- CRAWL_JOBS: Add completion tracking
-- ============================================================================

-- Add completed_at timestamp for tracking job completion time
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_jobs' AND column_name = 'completed_at'
    ) THEN
        ALTER TABLE crawl_jobs ADD COLUMN completed_at timestamptz;
    END IF;
END $$;

-- Add error_message for failure tracking
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_jobs' AND column_name = 'error_message'
    ) THEN
        ALTER TABLE crawl_jobs ADD COLUMN error_message text;
    END IF;
END $$;

-- Add CHECK constraint for valid status values
-- First validate existing data
DO $$
DECLARE
    invalid_count integer;
BEGIN
    SELECT COUNT(*) INTO invalid_count
    FROM crawl_jobs
    WHERE status NOT IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED');
    
    IF invalid_count > 0 THEN
        RAISE EXCEPTION 'Cannot add status constraint: % jobs have invalid status values', invalid_count;
    END IF;
    
    -- Add constraint if it doesn't exist
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'crawl_jobs' 
        AND constraint_name = 'crawl_jobs_status_check'
    ) THEN
        ALTER TABLE crawl_jobs ADD CONSTRAINT crawl_jobs_status_check 
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'));
    END IF;
END $$;

-- ============================================================================
-- CRAWL_SOURCES: Add crawler configuration
-- ============================================================================

-- Add sitemap_url for automated discovery
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_sources' AND column_name = 'sitemap_url'
    ) THEN
        ALTER TABLE crawl_sources ADD COLUMN sitemap_url text;
    END IF;
END $$;

-- Add rate_limit_seconds (technical default, stakeholder policy pending)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_sources' AND column_name = 'rate_limit_seconds'
    ) THEN
        ALTER TABLE crawl_sources ADD COLUMN rate_limit_seconds numeric DEFAULT 2.0;
    END IF;
END $$;

-- Add allowed_path_patterns for URL filtering
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_sources' AND column_name = 'allowed_path_patterns'
    ) THEN
        ALTER TABLE crawl_sources ADD COLUMN allowed_path_patterns text[] DEFAULT '{}';
    END IF;
END $$;

-- Add excluded_path_patterns for URL filtering
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'crawl_sources' AND column_name = 'excluded_path_patterns'
    ) THEN
        ALTER TABLE crawl_sources ADD COLUMN excluded_path_patterns text[] DEFAULT '{}';
    END IF;
END $$;

-- ============================================================================
-- DOCUMENT_REVISIONS: Add indexing tracking and constraints
-- ============================================================================

-- Add expected_chunk_count for integrity verification (AD-012)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'document_revisions' AND column_name = 'expected_chunk_count'
    ) THEN
        ALTER TABLE document_revisions ADD COLUMN expected_chunk_count integer;
    END IF;
END $$;

-- Add indexing_claim_token for revision-level ownership (AD-010)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'document_revisions' AND column_name = 'indexing_claim_token'
    ) THEN
        ALTER TABLE document_revisions ADD COLUMN indexing_claim_token uuid;
    END IF;
END $$;

-- Add CHECK constraint for valid revision status values
-- First validate existing data
DO $$
DECLARE
    invalid_count integer;
BEGIN
    SELECT COUNT(*) INTO invalid_count
    FROM document_revisions
    WHERE status NOT IN ('INDEXING', 'PENDING', 'PUBLISHED', 'ARCHIVED', 'REVOKED');
    
    IF invalid_count > 0 THEN
        RAISE WARNING 'Found % document_revisions with invalid status values', invalid_count;
        -- Show sample of invalid statuses for debugging
        RAISE WARNING 'Sample invalid statuses: %', (
            SELECT array_agg(DISTINCT status)
            FROM (
                SELECT status FROM document_revisions 
                WHERE status NOT IN ('INDEXING', 'PENDING', 'PUBLISHED', 'ARCHIVED', 'REVOKED')
                LIMIT 10
            ) AS sample
        );
        RAISE EXCEPTION 'Cannot add status constraint: fix invalid status values first';
    END IF;
    
    -- Add constraint if it doesn't exist
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'document_revisions' 
        AND constraint_name = 'document_revisions_status_check'
    ) THEN
        ALTER TABLE document_revisions ADD CONSTRAINT document_revisions_status_check 
        CHECK (status IN ('INDEXING', 'PENDING', 'PUBLISHED', 'ARCHIVED', 'REVOKED'));
    END IF;
END $$;

-- ============================================================================
-- INDEXES: Performance and stuck revision detection
-- ============================================================================

-- Index for finding stuck INDEXING revisions (AD-010)
-- Allows monitoring query: WHERE status='INDEXING' AND updated_at < NOW() - INTERVAL '1 hour'
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'idx_document_revisions_indexing_stuck'
    ) THEN
        CREATE INDEX idx_document_revisions_indexing_stuck 
        ON document_revisions (updated_at) 
        WHERE status = 'INDEXING';
    END IF;
END $$;

-- Index for efficient job claiming (AD-002)
-- Optimizes: WHERE status='PENDING' AND run_after <= NOW() ORDER BY run_after, id
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE indexname = 'idx_crawl_jobs_pending_claim'
    ) THEN
        CREATE INDEX idx_crawl_jobs_pending_claim 
        ON crawl_jobs (run_after, id) 
        WHERE status = 'PENDING';
    END IF;
END $$;

-- ============================================================================
-- VERIFICATION: Confirm all changes applied
-- ============================================================================

DO $$
DECLARE
    missing_columns text[];
    missing_constraints text[];
    missing_indexes text[];
BEGIN
    -- Check columns
    SELECT array_agg(col) INTO missing_columns FROM (
        SELECT unnest(ARRAY[
            'crawl_jobs.completed_at',
            'crawl_jobs.error_message',
            'crawl_sources.sitemap_url',
            'crawl_sources.rate_limit_seconds',
            'crawl_sources.allowed_path_patterns',
            'crawl_sources.excluded_path_patterns',
            'document_revisions.expected_chunk_count',
            'document_revisions.indexing_claim_token'
        ]) AS col
        EXCEPT
        SELECT table_name || '.' || column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
        AND table_name IN ('crawl_jobs', 'crawl_sources', 'document_revisions')
    ) AS missing;
    
    -- Check constraints
    SELECT array_agg(const) INTO missing_constraints FROM (
        SELECT unnest(ARRAY[
            'crawl_jobs_status_check',
            'document_revisions_status_check'
        ]) AS const
        EXCEPT
        SELECT constraint_name
        FROM information_schema.table_constraints
        WHERE table_schema = 'public'
        AND constraint_type = 'CHECK'
    ) AS missing;
    
    -- Check indexes
    SELECT array_agg(idx) INTO missing_indexes FROM (
        SELECT unnest(ARRAY[
            'idx_document_revisions_indexing_stuck',
            'idx_crawl_jobs_pending_claim'
        ]) AS idx
        EXCEPT
        SELECT indexname
        FROM pg_indexes
        WHERE schemaname = 'public'
    ) AS missing;
    
    -- Report results
    IF missing_columns IS NOT NULL THEN
        RAISE WARNING 'Missing columns: %', missing_columns;
    END IF;
    
    IF missing_constraints IS NOT NULL THEN
        RAISE WARNING 'Missing constraints: %', missing_constraints;
    END IF;
    
    IF missing_indexes IS NOT NULL THEN
        RAISE WARNING 'Missing indexes: %', missing_indexes;
    END IF;
    
    IF missing_columns IS NULL AND missing_constraints IS NULL AND missing_indexes IS NULL THEN
        RAISE NOTICE 'Migration 005 completed successfully: all columns, constraints and indexes verified';
    ELSE
        RAISE EXCEPTION 'Migration 005 incomplete: see warnings above';
    END IF;
END $$;

COMMIT;

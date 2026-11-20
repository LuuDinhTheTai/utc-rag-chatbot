-- Migration 006: Worker service account with minimal privileges
-- Creates utc_worker role following AD-005 principle of least privilege
-- Password must be set separately after role creation

BEGIN;

-- ============================================================================
-- CREATE ROLE: utc_worker with LOGIN, no elevated privileges
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'utc_worker') THEN
        CREATE ROLE utc_worker WITH LOGIN 
            NOCREATEROLE NOCREATEDB NOREPLICATION NOSUPERUSER;
        RAISE NOTICE 'Created role utc_worker (password must be set separately)';
    ELSE
        RAISE NOTICE 'Role utc_worker already exists';
    END IF;
END $$;

-- Set connection limit
ALTER ROLE utc_worker CONNECTION LIMIT 10;

-- ============================================================================
-- GRANT: Read-only access to reference data
-- ============================================================================

-- Can read campus reference data
GRANT SELECT ON campuses TO utc_worker;

-- Can read crawl source configuration
GRANT SELECT ON crawl_sources TO utc_worker;

-- ============================================================================
-- GRANT: Read-write access to job tables
-- ============================================================================

-- Can claim, update, and query jobs (but not delete)
GRANT SELECT, UPDATE ON crawl_jobs TO utc_worker;

-- Can read and create documents
GRANT SELECT, INSERT, UPDATE ON knowledge_documents TO utc_worker;

-- Can read, create, and update document revisions
GRANT SELECT, INSERT, UPDATE ON document_revisions TO utc_worker;

-- Can read, create, and delete chunks (delete needed for recovery cleanup)
GRANT SELECT, INSERT, DELETE ON document_chunks TO utc_worker;

-- Can create audit events for worker actions
GRANT INSERT ON audit_events TO utc_worker;

-- ============================================================================
-- GRANT: Sequence access for identity columns
-- ============================================================================

-- Allow utc_worker to use sequences for INSERT operations
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO utc_worker;

-- ============================================================================
-- REVOKE: Explicitly deny dangerous operations
-- ============================================================================

-- Cannot delete documents or jobs (must be done via Backend)
REVOKE DELETE ON knowledge_documents FROM utc_worker;
REVOKE DELETE ON crawl_jobs FROM utc_worker;
REVOKE DELETE ON document_revisions FROM utc_worker;

-- Cannot truncate any tables
REVOKE TRUNCATE ON ALL TABLES IN SCHEMA public FROM utc_worker;

-- ============================================================================
-- VERIFICATION: Confirm grants and denials
-- ============================================================================

DO $$
DECLARE
    v_has_select_campuses boolean;
    v_has_select_sources boolean;
    v_has_update_jobs boolean;
    v_has_insert_documents boolean;
    v_has_delete_chunks boolean;
    v_has_insert_audit boolean;
    v_has_delete_documents boolean;
    v_has_delete_jobs boolean;
    v_has_superuser boolean;
    v_has_createrole boolean;
    v_has_createdb boolean;
    v_conn_limit integer;
BEGIN
    -- Check required grants
    SELECT has_table_privilege('utc_worker', 'campuses', 'SELECT') INTO v_has_select_campuses;
    SELECT has_table_privilege('utc_worker', 'crawl_sources', 'SELECT') INTO v_has_select_sources;
    SELECT has_table_privilege('utc_worker', 'crawl_jobs', 'UPDATE') INTO v_has_update_jobs;
    SELECT has_table_privilege('utc_worker', 'knowledge_documents', 'INSERT') INTO v_has_insert_documents;
    SELECT has_table_privilege('utc_worker', 'document_chunks', 'DELETE') INTO v_has_delete_chunks;
    SELECT has_table_privilege('utc_worker', 'audit_events', 'INSERT') INTO v_has_insert_audit;
    
    -- Check denied operations
    SELECT has_table_privilege('utc_worker', 'knowledge_documents', 'DELETE') INTO v_has_delete_documents;
    SELECT has_table_privilege('utc_worker', 'crawl_jobs', 'DELETE') INTO v_has_delete_jobs;
    
    -- Check role attributes
    SELECT rolsuper, rolcreaterole, rolcreatedb, rolconnlimit
    INTO v_has_superuser, v_has_createrole, v_has_createdb, v_conn_limit
    FROM pg_roles WHERE rolname = 'utc_worker';
    
    -- Report verification
    IF NOT v_has_select_campuses THEN
        RAISE WARNING 'Missing SELECT on campuses';
    END IF;
    
    IF NOT v_has_select_sources THEN
        RAISE WARNING 'Missing SELECT on crawl_sources';
    END IF;
    
    IF NOT v_has_update_jobs THEN
        RAISE WARNING 'Missing UPDATE on crawl_jobs';
    END IF;
    
    IF NOT v_has_insert_documents THEN
        RAISE WARNING 'Missing INSERT on knowledge_documents';
    END IF;
    
    IF NOT v_has_delete_chunks THEN
        RAISE WARNING 'Missing DELETE on document_chunks';
    END IF;
    
    IF NOT v_has_insert_audit THEN
        RAISE WARNING 'Missing INSERT on audit_events';
    END IF;
    
    IF v_has_delete_documents THEN
        RAISE WARNING 'Should NOT have DELETE on knowledge_documents';
    END IF;
    
    IF v_has_delete_jobs THEN
        RAISE WARNING 'Should NOT have DELETE on crawl_jobs';
    END IF;
    
    IF v_has_superuser THEN
        RAISE WARNING 'Should NOT have SUPERUSER privilege';
    END IF;
    
    IF v_has_createrole THEN
        RAISE WARNING 'Should NOT have CREATEROLE privilege';
    END IF;
    
    IF v_has_createdb THEN
        RAISE WARNING 'Should NOT have CREATEDB privilege';
    END IF;
    
    IF v_conn_limit != 10 THEN
        RAISE WARNING 'Connection limit should be 10, got %', v_conn_limit;
    END IF;
    
    -- Success message
    IF v_has_select_campuses AND v_has_select_sources AND v_has_update_jobs 
        AND v_has_insert_documents AND v_has_delete_chunks AND v_has_insert_audit
        AND NOT v_has_delete_documents AND NOT v_has_delete_jobs
        AND NOT v_has_superuser AND NOT v_has_createrole AND NOT v_has_createdb
        AND v_conn_limit = 10 THEN
        RAISE NOTICE 'Migration 006 completed successfully: utc_worker role configured with minimal privileges';
        RAISE NOTICE 'IMPORTANT: Set password with: ALTER ROLE utc_worker WITH PASSWORD ''<strong-random-password>'';';
    ELSE
        RAISE EXCEPTION 'Migration 006 incomplete: see warnings above';
    END IF;
END $$;

COMMIT;

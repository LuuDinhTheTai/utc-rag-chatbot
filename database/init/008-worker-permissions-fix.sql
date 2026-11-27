-- Migration 008: Least-privilege permissions for utc_worker (worker + scheduler)
--
-- Derived from the SQL actually executed by tools/src (audit 2026-11-27):
--   crawl_sources       SELECT                      (SourceRegistry, scheduler)
--   crawl_jobs          SELECT, INSERT, UPDATE      (scheduler creates jobs; worker claims,
--                                                    heartbeats, completes, fails, reclaims)
--   knowledge_documents SELECT, INSERT              (INSERT ... ON CONFLICT DO NOTHING)
--   document_revisions  SELECT, INSERT, UPDATE, DELETE (DELETE = cleanup of own INDEXING revision)
--   document_chunks     SELECT, INSERT, DELETE      (replace chunks of own INDEXING revision)
-- Everything else is revoked (including grants made by 006 that the code never uses:
-- campuses, audit_events, UPDATE on knowledge_documents, USAGE on ALL sequences).
-- Identity columns do not need sequence privileges for INSERT.
--
-- The password is NOT set here (SQL init scripts cannot read secrets). It is set by
-- 010-worker-password.sh on new volumes, or manually on existing volumes:
--   ALTER ROLE utc_worker WITH PASSWORD '<POSTGRES_WORKER_PASSWORD>';
-- Idempotent: safe to run multiple times.

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'utc_worker') THEN
        CREATE ROLE utc_worker WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
        RAISE NOTICE 'Created role utc_worker';
    END IF;
END $$;

ALTER ROLE utc_worker WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE utc_worker CONNECTION LIMIT 20;

-- Start from a clean slate on every table/sequence in the schema
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM utc_worker;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM utc_worker;
GRANT USAGE ON SCHEMA public TO utc_worker;

GRANT SELECT                         ON crawl_sources       TO utc_worker;
GRANT SELECT, INSERT, UPDATE         ON crawl_jobs          TO utc_worker;
GRANT SELECT, INSERT                 ON knowledge_documents TO utc_worker;
GRANT SELECT, INSERT, UPDATE, DELETE ON document_revisions  TO utc_worker;
GRANT SELECT, INSERT, DELETE         ON document_chunks     TO utc_worker;

-- ============================================================================
-- VERIFICATION: exact privilege matrix
-- ============================================================================
DO $$
DECLARE
    r record;
    v_errors text[] := ARRAY[]::text[];
    v_role record;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('crawl_sources',       'SELECT', true),
            ('crawl_sources',       'INSERT', false),
            ('crawl_sources',       'UPDATE', false),
            ('crawl_sources',       'DELETE', false),
            ('crawl_jobs',          'SELECT', true),
            ('crawl_jobs',          'INSERT', true),
            ('crawl_jobs',          'UPDATE', true),
            ('crawl_jobs',          'DELETE', false),
            ('knowledge_documents', 'SELECT', true),
            ('knowledge_documents', 'INSERT', true),
            ('knowledge_documents', 'UPDATE', false),
            ('knowledge_documents', 'DELETE', false),
            ('document_revisions',  'SELECT', true),
            ('document_revisions',  'INSERT', true),
            ('document_revisions',  'UPDATE', true),
            ('document_revisions',  'DELETE', true),
            ('document_chunks',     'SELECT', true),
            ('document_chunks',     'INSERT', true),
            ('document_chunks',     'UPDATE', false),
            ('document_chunks',     'DELETE', true),
            ('users',               'SELECT', false),
            ('audit_events',        'INSERT', false),
            ('campuses',            'SELECT', false)
        ) AS t(tbl, priv, expected)
    LOOP
        IF has_table_privilege('utc_worker', r.tbl, r.priv) IS DISTINCT FROM r.expected THEN
            v_errors := v_errors || format('%s on %s should be %s', r.priv, r.tbl,
                                           CASE WHEN r.expected THEN 'granted' ELSE 'denied' END);
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1 FROM information_schema.table_privileges
        WHERE grantee = 'utc_worker' AND privilege_type IN ('TRUNCATE', 'REFERENCES', 'TRIGGER')
    ) THEN
        v_errors := v_errors || 'utc_worker must not have TRUNCATE/REFERENCES/TRIGGER';
    END IF;

    SELECT rolcanlogin, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
    INTO v_role FROM pg_roles WHERE rolname = 'utc_worker';
    IF NOT v_role.rolcanlogin THEN v_errors := v_errors || 'utc_worker must have LOGIN'; END IF;
    IF v_role.rolsuper OR v_role.rolcreaterole OR v_role.rolcreatedb
       OR v_role.rolreplication OR v_role.rolbypassrls THEN
        v_errors := v_errors || 'utc_worker must not have elevated role attributes';
    END IF;

    IF array_length(v_errors, 1) IS NOT NULL THEN
        RAISE EXCEPTION 'Migration 008 verification failed: %', array_to_string(v_errors, '; ');
    END IF;
    RAISE NOTICE 'Migration 008 completed: utc_worker privileges verified (least privilege)';
END $$;

COMMIT;

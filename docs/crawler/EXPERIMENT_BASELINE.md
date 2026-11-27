# EXPERIMENT BASELINE — Phase 3 Crawler

**Date:** 2026-11-20  
**Type:** Controlled Experiment Baseline  
**Status:** Development Frozen, Ready for Experiment

---

## 1. Executive Summary

Phase 3 development is frozen for controlled experiment execution. This baseline documents the exact state of code, infrastructure, and configuration before experiment begins.

**Decision:** Stakeholder accepts risk and proceeds with controlled experiment on current environment without separate staging validation.

**Critical Finding:** Migration 005-crawler-complete.sql is **PARTIALLY APPLIED**. Some columns exist (lease_until, claim_token) but others are missing (sitemap_url, rate_limit_seconds, etc.). This creates **schema mismatch** risk.

---

## 2. Git Status

### 2.1. Branch Information

**Current Branch:** `quyen`  
**Base Commit:** `55fb409` (refactor(backend): organize layers and separate business from HTTP handling)  
**Branch Status:** `quyen` is at same commit as `main` and `origin/main`

### 2.2. Working Tree Status

**Modified Files (3):**
1. `compose.yaml` — Added worker service configuration
2. `tools/requirements.txt` — Added Phase 1/2/3 dependencies
3. `tools/src/main.py` — Replaced placeholder with Phase 3 worker

**Untracked Files (Critical):**

**Database Migrations:**
- `database/init/005-crawler-complete.sql` (235 lines) — **NOT FULLY APPLIED**
- `database/init/006-worker-role.sql` (185 lines) — **STATUS UNKNOWN**

**Phase 1/2/3 Implementation:**
- `tools/src/config.py`
- `tools/src/database.py`
- `tools/src/embedder.py`
- `tools/src/job_manager.py`
- `tools/src/revision_processor.py`
- `tools/src/crawler.py`
- `tools/src/source_registry.py`
- `tools/src/sitemap_parser.py`
- `tools/src/url_discovery.py`
- `tools/src/security_validator.py`
- `tools/src/extractors/` (html.py, pdf.py, docx.py)
- `tools/src/url_utils.py`
- `tools/src/hash_utils.py`
- `tools/src/chunker.py`
- `tools/src/robots.py`
- `tools/src/http_client.py`
- `tools/src/exceptions.py`

**Tests:**
- `tools/tests/` (12 test files, 176 tests)

**Documentation:**
- `docs/crawler/` (entire documentation tree)
- `docs/ADR-001-Crawler-Architecture.md`

**Test Utilities:**
- `database/scripts/test_migrations.ps1`
- `tools/run_integration_tests.sh`
- `tools/test_db_conn.py`
- `tools/test_ollama_direct.py`
- `tools/verify_ollama.py`
- `verify_ollama_simple.py`

**Total Untracked:** ~35 files (implementation + tests + docs)

### 2.3. Commit Status

**No Commit Created Yet** — All Phase 3 work is in working tree, not committed to Git history.

**Risk:** If working tree is lost, all Phase 3 work is lost.

**Mitigation:** This baseline serves as documentation checkpoint.

---

## 3. Code Version

### 3.1. Implementation Status

**Phase 1 (Foundation):** ✅ COMPLETE
- 16 files created
- 79 tests passing
- Dependencies: 9 packages added

**Phase 2 (Database Integration):** ✅ COMPLETE
- 9 files created
- 33 tests passing (23 revision_processor + 10 HIGH remediation)
- Migrations designed but not fully applied

**Phase 3 (Crawler Engine):** ✅ COMPLETE
- 6 core modules created
- 64 tests passing
- Docker Compose updated

**Overall Test Results:** 176/176 tests PASSING (100%)

### 3.2. Source Code Files

**Core Modules (17 files):**
1. config.py (134 lines)
2. database.py (242 lines)
3. embedder.py (147 lines)
4. job_manager.py (383 lines)
5. revision_processor.py (665 lines)
6. crawler.py (497 lines)
7. main.py (246 lines)
8. source_registry.py (169 lines)
9. sitemap_parser.py (358 lines)
10. url_discovery.py (179 lines)
11. security_validator.py (208 lines)
12. extractors/html.py (131 lines)
13. extractors/pdf.py (119 lines)
14. extractors/docx.py (115 lines)
15. url_utils.py (123 lines)
16. hash_utils.py (57 lines)
17. chunker.py (162 lines)

**Supporting Modules (4 files):**
18. http_client.py (194 lines)
19. robots.py (112 lines)
20. exceptions.py (67 lines)
21. extractors/__init__.py (9 lines)

**Total Lines of Code:** ~4,717 lines (implementation only, excludes tests)

### 3.3. Test Files

**Test Suite (12 files):**
1. test_database.py (12 tests)
2. test_embedder.py (16 tests)
3. test_extractors.py (12 tests)
4. test_hash_utils.py (8 tests)
5. test_high_severity_remediation.py (10 tests)
6. test_job_manager.py (11 tests)
7. test_revision_processor.py (23 tests)
8. test_security_validator.py (18 tests)
9. test_sitemap_parser.py (20 tests)
10. test_source_registry.py (18 tests)
11. test_url_discovery.py (11 tests)
12. test_url_utils.py (21 tests)

**Integration Tests (NOT RUN):**
- test_integration_postgres.py (456 lines, requires PostgreSQL)
- test_ollama_integration.py (planned, not created)

**Total Tests:** 176 unit tests + 2 integration test files

---

## 4. Migration Status

### 4.1. Applied Migrations (Verified in Database)

**Base Schema:**
- ✅ `001-init.sql` — Initial schema (assumed applied)
- ✅ `002-schema.sql` — Core schema (48 tables, verified present)
- ✅ `003-api-state.sql` — API state columns (verified present)
- ✅ `004-public-views.sql` — Public views (assumed applied)

### 4.2. Crawler Migrations (PARTIALLY APPLIED)

**Migration 005: Crawler Complete Schema**

**File:** `database/init/005-crawler-complete.sql` (235 lines, UNTRACKED)

**Applied Columns (Verified Present):**
- ✅ `crawl_jobs.lease_until` — timestamp with time zone
- ✅ `crawl_jobs.claim_token` — uuid

**Missing Columns (Verified Absent):**
- ❌ `crawl_jobs.completed_at` — Job completion timestamp
- ❌ `crawl_jobs.error_message` — Failure reason
- ❌ `crawl_sources.sitemap_url` — Sitemap location
- ❌ `crawl_sources.rate_limit_seconds` — Per-source rate limit
- ❌ `crawl_sources.allowed_path_patterns` — Allowlist regex
- ❌ `crawl_sources.excluded_path_patterns` — Blocklist regex
- ❌ `document_revisions.expected_chunk_count` — Integrity verification
- ❌ `document_revisions.indexing_claim_token` — Revision ownership

**Status Constraints (Unknown):**
- ❓ CHECK constraints for crawl_jobs.status
- ❓ CHECK constraints for document_revisions.status

**Indexes (Unknown):**
- ❓ `idx_document_revisions_indexing_stuck`
- ❓ `idx_crawl_jobs_pending_claim`

**CRITICAL RISK:** Code expects columns that don't exist. Worker will fail on first execution.

**Migration 006: Worker Role**

**File:** `database/init/006-worker-role.sql` (185 lines, UNTRACKED)

**Status:** ❓ UNKNOWN

**Expected Content:**
- CREATE ROLE utc_worker WITH LOGIN
- GRANT permissions for worker operations

**Verification Needed:** Check if role exists, check current worker connection

### 4.3. Schema Mismatch Risk

**Code Dependencies on Missing Columns:**

1. **source_registry.py** expects:
   - `sitemap_url`, `rate_limit_seconds`, `allowed_path_patterns`, `excluded_path_patterns`
   - **Impact:** Will fail to load sources

2. **revision_processor.py** expects:
   - `expected_chunk_count`, `indexing_claim_token`
   - **Impact:** Will fail on chunk integrity verification

3. **job_manager.py** expects:
   - `completed_at`, `error_message`
   - **Impact:** May fail on job completion

**Severity:** HIGH — Worker cannot function without these columns

**Mitigation Options:**
1. Apply full migration 005 before experiment
2. Modify code to handle missing columns gracefully (NOT RECOMMENDED)
3. Use test fixtures instead of real database (LIMITED)

**Decision Required:** Cannot proceed with experiment until schema mismatch resolved.

---

## 5. Docker Compose Configuration

### 5.1. Services Running

**Verified Running Services (5):**
1. **frontend** — Up 20 hours (healthy) — Port 127.0.0.1:5173
2. **backend** — Up 20 hours (healthy) — Port 127.0.0.1:8080
3. **database** — Up 20 hours (healthy) — pgvector/pgvector:0.8.7-pg17-trixie
4. **ai** — Up 20 hours (healthy) — ollama/ollama:latest
5. **tools** — Up 20 hours — utc-rag-chatbot-tools (PLACEHOLDER VERSION)

### 5.2. Worker Service Status

**Current Worker Behavior:**
```python
# tools/src/main.py (OLD VERSION RUNNING IN CONTAINER)
def main() -> None:
    logging.basicConfig(level=logging.INFO)
    stopped = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopped.set())
    logging.info("Tools scaffold started; waiting for shutdown.")
    stopped.wait()
```

**Status:** Running PLACEHOLDER version (17 lines)
- Does NOT claim jobs
- Does NOT process crawls
- Only logs "Tools scaffold started; waiting for shutdown."
- Waits indefinitely

**New Worker Code:** EXISTS in working tree (`tools/src/main.py` modified, 246 lines)
- Will NOT be active until container rebuilt

**Action Required:** Rebuild tools container to activate Phase 3 worker

### 5.3. Docker Compose Configuration

**Modified:** `compose.yaml` (untracked changes)

**Worker Service Configuration (New):**
```yaml
tools:
  build: ./tools
  environment:
    # Database (7 vars)
    DATABASE_HOST: database
    DATABASE_PORT: "5432"
    DATABASE_NAME: ${POSTGRES_DB:-utc_rag}
    DATABASE_USER: ${POSTGRES_WORKER_USER:-utc}
    DATABASE_PASSWORD: ${POSTGRES_PASSWORD}
    DATABASE_POOL_SIZE: "5"
    DATABASE_MAX_OVERFLOW: "10"
    DATABASE_TIMEOUT_SECONDS: "30"
    DATABASE_MAX_WAITING: "10"
    
    # Ollama (4 vars)
    OLLAMA_BASE_URL: http://ai:11434
    EMBEDDING_MODEL: embeddinggemma:300m-qat-q4_0
    EMBEDDING_DIMENSION: "768"
    OLLAMA_TIMEOUT_SECONDS: "120"
    
    # Crawler (6 vars)
    USER_AGENT: "UTC-RAG-Crawler/1.0"
    MAX_RETRIES: "3"
    REQUEST_TIMEOUT_SECONDS: "30"
    DEFAULT_RATE_LIMIT_SECONDS: "2.0"
    MAX_CONTENT_SIZE_MB: "50"
    RESPECT_ROBOTS_TXT: "true"
    
    # Worker (4 vars)
    JOB_CLAIM_TIMEOUT_SECONDS: "10"
    HEARTBEAT_INTERVAL_SECONDS: "180"
    LEASE_DURATION_SECONDS: "600"
    MAX_CLAIM_WAIT_SECONDS: "30"
    
  depends_on:
    database: {condition: service_healthy}
    ai: {condition: service_healthy}
  restart: unless-stopped
  healthcheck:
    test: ["CMD-SHELL", "python -c 'import psycopg; ...'"]
    interval: 30s
  networks: [utc]
```

**Restart Policy:** `unless-stopped` ✅
**Health Check:** Database connectivity ✅
**Dependencies:** database + ai (both healthy) ✅

---

## 6. PostgreSQL Status

### 6.1. Connection Information

**Host:** database (Docker internal network)  
**Port:** 5432  
**Database:** utc_rag  
**User:** utc (assumed, from base setup)  
**Worker User:** ${POSTGRES_WORKER_USER:-utc} (fallback to utc)

### 6.2. Schema Status

**Core Tables (Verified Present):**
- ✅ `crawl_sources` — Base columns only (id, base_url, enabled)
- ✅ `crawl_jobs` — Partial migration (has lease_until, claim_token)
- ✅ `knowledge_documents` — Base schema
- ✅ `document_revisions` — Base schema (missing Phase 2 columns)
- ✅ `document_chunks` — Base schema

**Table Counts:**
```sql
-- Need to check actual data
SELECT COUNT(*) FROM crawl_sources;
SELECT COUNT(*) FROM crawl_jobs;
SELECT COUNT(*) FROM knowledge_documents;
```

### 6.3. Worker Role Status

**Check Required:**
```sql
SELECT rolname FROM pg_roles WHERE rolname = 'utc_worker';
```

**If Not Exists:** Migration 006 not applied, worker using default user

### 6.4. Data Safety

**Existing Data:** Unknown (need to check if production data exists)

**Risk Level:** 
- HIGH if production data exists
- LOW if test/development data only

**Protection:**
- No `docker compose down -v` command will be executed
- No DELETE/TRUNCATE operations planned
- Migrations should use ALTER TABLE ADD COLUMN (safe)

---

## 7. Ollama Status

### 7.1. Service Health

**Status:** Up 20 hours (healthy) ✅

**Models Installed:**
1. ✅ `embeddinggemma:300m-qat-q4_0` — 238 MB (modified 20 hours ago)
2. ✅ `qwen3:0.6b` — 522 MB (SLM model, modified 20 hours ago)

**Embedding Model Verification:** ✅ PRESENT

### 7.2. Embedding Dimension

**Expected:** 768 (configured in code and Docker Compose)

**Database Column:** `document_chunks.embedding vector(768)`

**Verification Needed:** Test actual embedding call to confirm dimension

### 7.3. API Endpoint

**URL:** `http://ai:11434` (Docker internal network)

**Endpoint:** `POST /api/embeddings`

**Request Format:**
```json
{
  "model": "embeddinggemma:300m-qat-q4_0",
  "prompt": "text to embed"
}
```

**Response Format:**
```json
{
  "embedding": [float array of 768 elements]
}
```

---

## 8. Test Suite Results

### 8.1. Unit Tests

**Last Execution:** 2026-11-20 (just verified)

**Command:**
```bash
cd tools
python -m pytest tests/ --ignore=tests/test_integration_postgres.py --ignore=tests/test_ollama_integration.py -q --tb=no
```

**Results:** ✅ **176 passed, 11 warnings in 3.55s**

**Breakdown:**
- Phase 1: 79 tests (foundation)
- Phase 2: 33 tests (database integration + HIGH remediation)
- Phase 3: 64 tests (crawler engine)

**Pass Rate:** 100%

**Warnings:** 11 (BeautifulSoup deprecation warnings, non-critical)

### 8.2. Integration Tests

**PostgreSQL Integration:** test_integration_postgres.py (456 lines)
- **Status:** ❌ NOT RUN (requires test database)
- **Blocker:** No separate test database configured

**Ollama Integration:** test_ollama_integration.py
- **Status:** ❌ NOT CREATED
- **Blocker:** Test file not implemented

### 8.3. Security Tests

**Security Test Coverage:** 18/18 PASSING ✅

**Verified Attack Vectors:**
- IPv4/IPv6 localhost blocking
- Private IP range blocking
- Decimal IP notation detection
- Userinfo bypass prevention
- Path traversal detection (Unix/Windows/encoded)
- URL scheme validation

---

## 9. Risks and Limitations

### 9.1. CRITICAL Risks

**RISK-001: Schema Mismatch**
- **Severity:** CRITICAL
- **Description:** Code expects columns that don't exist in database
- **Impact:** Worker will crash on startup or first job claim
- **Affected:** source_registry, revision_processor, job_manager
- **Mitigation:** Must apply migration 005 before experiment

**RISK-002: Untested Integration**
- **Severity:** HIGH
- **Description:** Integration tests not run on real PostgreSQL/Ollama
- **Impact:** Concurrency issues, race conditions may appear
- **Mitigation:** Monitor worker logs closely during experiment

**RISK-003: Worker Container Not Rebuilt**
- **Severity:** HIGH
- **Description:** Container running old placeholder code, not Phase 3 worker
- **Impact:** No crawling will occur until rebuild
- **Mitigation:** Must rebuild tools container before experiment

### 9.2. HIGH Risks

**RISK-004: No Staging Validation**
- **Severity:** HIGH
- **Description:** Running experiment directly on current environment
- **Impact:** Potential data corruption, service disruption
- **Decision:** Accepted by stakeholder as controlled risk

**RISK-005: Worker Role Unclear**
- **Severity:** HIGH
- **Description:** Unknown if utc_worker role exists or worker using default user
- **Impact:** Permissions may be too broad or too narrow
- **Mitigation:** Verify role before starting worker

### 9.3. MEDIUM Risks

**RISK-006: No Resource Limits**
- **Severity:** MEDIUM
- **Description:** Worker has no CPU/memory limits configured
- **Impact:** Could consume all system resources
- **Mitigation:** Monitor system resources during experiment

**RISK-007: No Monitoring Dashboard**
- **Severity:** MEDIUM
- **Description:** No metrics collection or visualization
- **Impact:** Hard to diagnose issues in real-time
- **Mitigation:** Rely on Docker logs and manual database queries

**RISK-008: No Crawl Sources Configured**
- **Severity:** MEDIUM
- **Description:** crawl_sources table may be empty
- **Impact:** No jobs to process even if worker functional
- **Mitigation:** Need to seed sources before experiment

### 9.4. LOW Risks

**RISK-009: Heuristic Metadata Inference**
- **Severity:** LOW
- **Description:** Campus/year inferred from URL patterns
- **Impact:** Metadata may be inaccurate
- **Mitigation:** Accept for experiment, validate in production

**RISK-010: No Conditional Request Support**
- **Severity:** LOW
- **Description:** Always fetches full content
- **Impact:** Higher bandwidth, slower crawls
- **Mitigation:** Hash-based change detection prevents re-embedding

---

## 10. Stakeholder Dependencies (Still Unanswered)

**From MASTER_CONTEXT.md:**

1. ❓ **UTC2 Sitemap URL** — Not confirmed
2. ❓ **Allowed Path Patterns** — Not confirmed
3. ❓ **Excluded Path Patterns** — Not confirmed
4. ❓ **Rate Limit Policies** — Not confirmed (using default 2s)
5. ❓ **Auto-Publish Decision** — Not confirmed (using manual review per AD-009)
6. ❓ **Model Upgrade Policy** — Not confirmed
7. ❓ **Deletion Detection Policy** — Not confirmed (AC-06 blocked)

**Impact on Experiment:**
- Can proceed with test fixtures or limited scope
- Cannot crawl real UTC/UTC2 without confirmation
- System uses safe defaults

---

## 11. Actions Required Before Experiment

### 11.1. BLOCKING Actions

**MUST DO (Cannot proceed without):**

1. ✅ **Apply Migration 005 Fully**
   - Add missing columns to crawl_sources and document_revisions
   - Add CHECK constraints
   - Add indexes
   - **Risk:** Medium (ALTER TABLE on existing data)

2. ✅ **Verify/Apply Migration 006**
   - Check if utc_worker role exists
   - Create role if missing
   - Verify permissions
   - **Risk:** Low (role creation safe)

3. ✅ **Rebuild Tools Container**
   - `docker compose build tools`
   - `docker compose up -d tools`
   - Verify new code running
   - **Risk:** Low (only affects worker service)

4. ✅ **Seed Crawl Sources**
   - Insert at least one test source
   - Set base_url, sitemap_url, enabled=true
   - **Risk:** Low (INSERT operation)

5. ✅ **Create Crawl Job**
   - Insert job for test source
   - Set status='PENDING', run_after=NOW()
   - **Risk:** Low (INSERT operation)

### 11.2. RECOMMENDED Actions

**SHOULD DO (Reduces risk):**

1. ⚠️ **Backup Database**
   - `docker compose exec database pg_dump -U utc utc_rag > backup_before_experiment.sql`
   - **Risk:** None (read-only operation)

2. ⚠️ **Verify Worker Connection**
   - Test database connection with worker credentials
   - Verify Ollama connection
   - **Risk:** None (read-only test)

3. ⚠️ **Check Existing Data**
   - Count rows in core tables
   - Verify no production data at risk
   - **Risk:** None (read-only query)

### 11.3. OPTIONAL Actions

**COULD DO (Nice to have):**

1. 💡 **Set Resource Limits**
   - Add CPU/memory limits to compose.yaml
   - **Risk:** Low

2. 💡 **Create Test Source**
   - Use controlled fixture instead of real UTC
   - **Risk:** None

---

## 12. Experiment Readiness Assessment

### 12.1. Code Readiness

**Status:** ✅ READY
- Implementation complete
- Tests passing
- Security verified
- Architecture sound

### 12.2. Environment Readiness

**Status:** ⚠️ PARTIAL
- PostgreSQL: Running but schema incomplete
- Ollama: Running and model present
- Worker: Running but old version
- Docker: Healthy services

### 12.3. Data Readiness

**Status:** ❓ UNKNOWN
- Need to check existing data
- Need to seed test sources
- Need to create test jobs

### 12.4. Overall Readiness

**Status:** ❌ **NOT READY**

**Blocking Issues:**
1. Schema mismatch (migration 005 incomplete)
2. Worker container not rebuilt
3. No crawl sources configured
4. No crawl jobs created

**Estimated Time to Ready:** 30-60 minutes
- Apply migrations: 10 min
- Rebuild container: 5 min
- Seed data: 5 min
- Verification: 10 min

---

## 13. Experiment Scope Recommendations

### 13.1. Minimal Viable Experiment

**Objective:** Verify worker can process one job end-to-end

**Scope:**
1. One test source (not real UTC)
2. One simple HTML page
3. One crawl job
4. Verify: claim → fetch → extract → embed → chunks → PENDING

**Duration:** 10-15 minutes

**Success Criteria:**
- Worker claims job
- Fetches content
- Creates revision
- Generates embeddings
- Inserts chunks
- Transitions to PENDING
- No crashes, no data loss

### 13.2. Extended Experiment

**Objective:** Verify multiple scenarios

**Additional Scope:**
- Unchanged content (no re-embedding)
- Changed content (new revision)
- Failed HTTP (retry logic)
- Worker restart (lease recovery)
- Multiple workers (concurrency)

**Duration:** 1-2 hours

---

## 14. Conclusion

**Development Status:** ✅ FROZEN

**Code Quality:** ✅ EXCELLENT (176/176 tests passing)

**Environment Status:** ⚠️ NOT READY (schema mismatch, worker not rebuilt)

**Experiment Readiness:** ❌ BLOCKED (see Section 11.1)

**Risk Level:** MEDIUM (acceptable with mitigation)

**Recommendation:** 
1. Apply migrations 005-006
2. Rebuild worker container
3. Seed test data
4. Proceed with minimal viable experiment
5. Monitor closely for issues

**Next Document:** `EXPERIMENT_REPORT.md` (after experiment execution)

---

**Baseline Date:** 2026-11-20  
**Baseline Author:** Kiro AI  
**Status:** COMPLETE  
**Next Phase:** Phase B - Environment Preparation

---

**END OF EXPERIMENT BASELINE**

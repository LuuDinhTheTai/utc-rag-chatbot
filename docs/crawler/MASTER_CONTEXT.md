# Master Context - UTC/UTC2 Crawler Project

**Last Updated**: 2026-11-20  
**Current Phase**: Architecture Design Completed (v6)  
**Status**: Ready for Implementation Approval

---

## 1. Project Objectives

### Primary Goal
Implement automated crawler to collect and synchronize admission data from UTC (utc.edu.vn) and UTC2 (utc2.edu.vn) into the RAG chatbot system without manual document upload.

### Acceptance Criteria
1. ✅ Crawler runs automatically on schedule (e.g., every 6 hours)
2. ✅ No manual document upload required
3. ✅ Unchanged content does not trigger re-embedding
4. ✅ New or updated documents appear in chatbot after successful sync
5. ❓ **NOT VERIFIED**: Actual execution and end-to-end testing

### Business Requirements
- Manage allowlist for official UTC/UTC2 admission pages
- Support HTML crawling
- Support PDF/DOCX document collection
- Follow sitemap/feed if available
- Respect rate limits and robots.txt
- Parse and store: URL, title, admission year, campus, update date, content hash
- Detect new, modified, and deleted sources
- Chunk documents and generate embeddings via Ollama
- Upsert data into PostgreSQL/pgvector
- Mark old sources appropriately

---

## 2. Repository Architecture (Verified)

### Backend (Java/Spring Boot)
**Location**: `backend/src/main/java/vn/edu/utc/rag/`

**Key Components**:
- `service/ingestion/OpsService.java` (line 61-74): Job creation API
  - `POST /api/v1/ops/crawl-jobs`
  - Requires `TECH_OPERATOR` role
  - Returns `CrawlJobResult` with job and `reused` flag
  - Uses `permissions.check(req, null, "TECH_OPERATOR")`

- `service/ingestion/DocumentService.java` (line 89-148): PDF upload
  - Hash computed: `MessageDigest.getInstance("SHA-256").digest(bytes)` (line 114)
  - Creates revision with `status='INDEXING'` (line 268)
  - **DOES NOT** transition INDEXING → PENDING (worker responsibility)

- `service/identity/IdentityService.java` (line 55-127): Authentication
  - Login: `POST /api/v1/auth/sessions`
  - Request: `{"email": string, "password": string}`
  - Response: User object with roles array
  - Session-based (HttpSession), not JWT

- `config/SecurityConfiguration.java` (line 38-45): Security
  - `/api/v1/**` has `permitAll()` for network access
  - BUT service-level authorization still enforced via `permissions.check()`
  - CSRF protection enabled: `HttpSessionCsrfTokenRepository`

**RAG Query Condition** (line 26-27 of `ChatQueryRepository.java`):
```sql
WHERE r.status='PUBLISHED' AND r.source_verified AND r.revoked_at IS NULL
```

### Database Schema (Verified)
**Location**: `database/init/`

**Core Tables** (`002-schema.sql`):
- `crawl_sources` (line 468-472): Source configuration
  - `base_url text NOT NULL UNIQUE`
  - `enabled boolean NOT NULL`
  
- `crawl_jobs` (line 474-483): Job queue
  - `source_id bigint NOT NULL`
  - `status text NOT NULL` (no CHECK constraint in base schema)
  - `run_after timestamptz NOT NULL`
  - `lease_until timestamptz` (unused in current code)
  - `claim_token uuid` (unused in current code)
  - `attempt_no integer NOT NULL`

- `knowledge_documents` (line 485-489): Document registry
  - `canonical_url text NOT NULL UNIQUE`
  - `crawl_source_id bigint`

- `document_revisions` (line 491-506): Document versions
  - `document_id bigint NOT NULL`
  - `crawl_job_id bigint`
  - `content_hash text NOT NULL`
  - `pipeline_key text NOT NULL`
  - `embedding_model text NOT NULL`
  - `status text NOT NULL` (no CHECK constraint)
  - `source_verified boolean NOT NULL DEFAULT false` (003-api-state.sql line 3)
  - `revoked_at timestamptz` (003-api-state.sql line 4)
  - UNIQUE: `(document_id, content_hash, pipeline_key, embedding_model)` (line 493)

- `document_chunks` (line 508-515): Embedded chunks
  - `revision_id bigint NOT NULL`
  - `position integer NOT NULL`
  - `body text NOT NULL`
  - `embedding vector(768) NOT NULL`
  - UNIQUE: `(revision_id, position)`

**Status Constraints** (`003-api-state.sql`):
- Line 26: `CREATE UNIQUE INDEX ON crawl_jobs (source_id) WHERE status IN ('PENDING', 'RUNNING');`
- No CHECK constraints enforcing valid status values in base schema

### Tools/Worker (NOT IMPLEMENTED)
**Location**: `tools/src/main.py`

**Current State**: Placeholder only (17 lines)
```python
def main() -> None:
    logging.basicConfig(level=logging.INFO)
    stopped = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopped.set())
    logging.info("Tools scaffold started; waiting for shutdown.")
    stopped.wait()
```

**Implementation Status**: 0% - No crawler, no scheduler, no job processor, no embedding client

### Ollama Integration (Verified)
- Model: `nomic-embed-text` (referenced in application config)
- Dimension: 768 (verified in schema: `vector(768)`)
- Backend has `OllamaClient` for embedding calls

---

## 3. Confirmed Schema & Migration Status

### Applied Migrations (Verified Present)
1. ✅ `002-schema.sql`: Base schema (48 tables)
2. ✅ `003-api-state.sql`: API state columns and indexes
3. ✅ `004-public-views.sql`: Public views

### Pending Migration (NOT APPLIED)
4. ❌ `005-crawler-complete.sql`: Crawler completion tracking (v6 final)
   - **Status**: SQL written, reviewed, NOT executed
   - **Adds**:
     - `crawl_jobs.completed_at timestamptz`
     - `crawl_jobs.error_message text`
     - `crawl_sources.sitemap_url text`
     - `crawl_sources.rate_limit_seconds numeric DEFAULT 2.0`
     - `crawl_sources.allowed_path_patterns text[] DEFAULT '{}'`
     - `crawl_sources.excluded_path_patterns text[] DEFAULT '{}'`
     - `document_revisions.expected_chunk_count integer`
     - `document_revisions.indexing_claim_token uuid`
     - CHECK constraints for status validation
     - Indexes: `idx_document_revisions_indexing_stuck`, `idx_crawl_jobs_pending_claim`
   - **Risk**: Data validation requires all existing statuses to be valid

5. ❌ `006-worker-role.sql`: Worker service account (v6 final)
   - **Status**: SQL written, reviewed, NOT executed
   - **Creates**: `utc_worker` role with LOGIN, minimal grants
   - **Security**: No password in migration, must set separately

---

## 4. Architectural Decisions (MUST NOT CHANGE)

### AD-001: Job Lifecycle State Machine
**Decision**: `PENDING → RUNNING → COMPLETED | FAILED`
- `COMPLETED`: Success terminal state (not DONE/SUCCESS)
- `FAILED`: Failure terminal state after max retries
- Rationale: Consistent with domain language (`personality_attempts` uses COMPLETED)

### AD-002: Lease-Based Job Claiming
**Decision**: PostgreSQL `SELECT FOR UPDATE SKIP LOCKED` with lease and token fencing
- Worker claims atomically via `FOR UPDATE SKIP LOCKED`
- Lease duration: 10 minutes with heartbeat every 3 minutes
- `claim_token` verifies job ownership on every operation
- Recovery reclaims expired leases

### AD-003: Revision Indexing State Machine
**Decision**: `INDEXING → PENDING → PUBLISHED → ARCHIVED`
- `INDEXING`: Worker processing (chunks being created)
- `PENDING`: Indexing complete, awaiting reviewer approval
- `PUBLISHED`: Reviewer approved, appears in RAG
- `ARCHIVED`: Replaced by newer revision
- `REVOKED`: Manually revoked (any state)

### AD-004: Worker Architecture - Direct PostgreSQL
**Decision**: Worker accesses PostgreSQL directly, NOT via Backend REST API
- Rationale:
  - `SELECT FOR UPDATE SKIP LOCKED` cannot work over REST
  - Bulk chunk insertion (500+ per document) would require 500+ HTTP requests
  - Worker crash recovery simpler without REST dependency
- Backend retains: Publication approval, source verification, revocation, user APIs

### AD-005: Service Account - utc_worker
**Decision**: PostgreSQL role `utc_worker` with LOGIN and minimal grants
- GRANT: SELECT (campuses, crawl_sources)
- GRANT: SELECT, UPDATE (crawl_jobs)
- GRANT: SELECT, INSERT, UPDATE (knowledge_documents, document_revisions)
- GRANT: SELECT, INSERT, DELETE (document_chunks)
- GRANT: INSERT (audit_events)
- REVOKE: DELETE (knowledge_documents, document_revisions, crawl_jobs)
- REVOKE: TRUNCATE (all tables)

### AD-006: Scheduler Architecture
**Decision**: Python cron script calls Backend API, NOT direct PostgreSQL
- Endpoint: `POST /api/v1/ops/crawl-jobs`
- Authentication: Session-based with service account user
- Login: `POST /api/v1/auth/sessions`
- CSRF: `GET /api/v1/auth/csrf` (after login)
- Rationale: Leverage existing duplicate check, audit log, rate limiting, authorization

### AD-007: Hash Strategy
**Decision**: Hash extracted TEXT content, not raw bytes
- HTML: Hash cleaned text (BeautifulSoup, strip tags)
- PDF: Hash extracted text (not binary PDF)
- DOCX: Hash extracted text
- Rationale: Metadata changes shouldn't trigger re-embedding

### AD-008: Pipeline Versioning
**Decision**: `pipeline_key` identifies extraction logic version
- `html-v1`: Current HTML extraction
- `pdf-v2`: Text-based hash (breaking change from `pdf-v1` bytes hash)
- `docx-v1`: DOCX extraction
- UNIQUE constraint allows multiple pipeline versions to coexist

### AD-009: Publication Workflow (Manual Review)
**Decision**: NO auto-publish without stakeholder approval
- Worker: INDEXING → PENDING (sets expected_chunk_count, verifies count, releases token)
- Reviewer: PENDING → PUBLISHED (via Backend, sets source_verified=true)
- Rationale: Content compliance, human verification required

### AD-010: Revision Ownership via indexing_claim_token
**Decision**: Separate revision-level token prevents concurrent processing
- `indexing_claim_token` UUID set when status=INDEXING
- Cleared when status changes from INDEXING
- Prevents race: Job A processing, Job B discovers same URL, both try to index
- Claim logic: Lock jobs in ascending ID order, verify old job dead, change ownership

### AD-011: Atomic Fencing Strategy
**Decision**: Embed OUTSIDE transaction, INSERT in short transaction with dual token verification
- Phase 1: Compute embeddings (no locks, 5-10s per batch)
- Phase 2: BEGIN → Lock job → Verify job token/lease → Lock revision → Verify revision token → INSERT chunks → COMMIT (< 1s)
- Verification on EVERY write: job.claim_token, job.status='RUNNING', job.lease_until > NOW(), revision.indexing_claim_token, revision.crawl_job_id

### AD-012: Chunk Integrity Verification
**Decision**: Cannot transition INDEXING → PENDING without comprehensive verification
- expected_chunk_count must be set before processing
- Verify: COUNT(*) = expected
- Verify: COUNT(DISTINCT position) = COUNT(*) (no duplicates)
- Verify: MIN(position) = 0, MAX(position) = expected - 1
- Verify: MAX - MIN + 1 = COUNT (no gaps)

### AD-013: Lock Ordering (Deadlock Prevention)
**Decision**: Always lock in ascending order
1. Lock `crawl_jobs` in ascending `id` order
2. Then lock `document_revisions`
- Applied in: claim job, claim revision, batch insert, complete indexing

---

## 5. Known Limitations & Constraints

### Verified Gaps
1. ❌ **No worker implementation**: tools/src/main.py is placeholder
2. ❌ **No migration applied**: Database missing crawler columns
3. ❌ **No scheduler**: No job creation automation
4. ❌ **No recovery process**: Expired lease handling not implemented
5. ❌ **No monitoring**: No dashboard for stuck revisions

### Technical Debt
1. Backend `DocumentService` creates revision with INDEXING but never transitions to PENDING (worker must do this)
2. `lease_until`, `claim_token`, `attempt_no` columns exist but unused by current code
3. No CHECK constraints on status columns in base schema (added in pending migration)

### Unverified Assumptions
1. ⚠️ **Clock synchronization**: All lease checks assume database clock is authoritative
2. ⚠️ **Ollama availability**: No fencing if Ollama service is down during embedding
3. ⚠️ **Network partitions**: Worker may lose DB connection while holding locks
4. ⚠️ **PostgreSQL replication**: Single-master assumed, not tested with replicas
5. ⚠️ **Disk space**: Index creation requires available disk space (not verified)

### Unresolved Business Questions (BLOCKING)
1. ❓ **Sitemap URLs**: UTC and UTC2 official sitemap locations unknown
2. ❓ **Allowed paths**: Specific path patterns for crawling not confirmed
3. ❓ **Rate limits**: Acceptable request frequency not confirmed with UTC IT
4. ❓ **Auto-publish**: Should trusted sources bypass manual review?
5. ❓ **Model upgrade**: Policy for re-embedding when embedding model changes

### Open Questions (NON-BLOCKING)
6. ❓ **Review SLA**: Expected time from PENDING to PUBLISHED (for monitoring/alerting only)

---

## 6. Explicit Warnings

### DO NOT
- ❌ Apply migration without database backup
- ❌ Assume integration tests pass without actual execution
- ❌ Mark tasks DONE without verification evidence
- ❌ Change architectural decisions without documenting rationale
- ❌ Auto-fix invalid status data without business approval
- ❌ Deploy worker with default/weak database password

### MUST DO BEFORE PRODUCTION
- ✅ Execute integration tests on real PostgreSQL
- ✅ Get stakeholder answers to 6 business questions
- ✅ Backup database before migration
- ✅ Validate all existing data before adding CHECK constraints
- ✅ Set strong password for utc_worker role
- ✅ Create scheduler service account in users table
- ✅ Test end-to-end flow: scheduler → worker → embedding → reviewer → RAG

---

## 7. Current Repository State (Verified 2026-11-20)

### Existing Files (Verified)
- ✅ `backend/src/main/java/vn/edu/utc/rag/service/ingestion/OpsService.java`
- ✅ `backend/src/main/java/vn/edu/utc/rag/service/ingestion/DocumentService.java`
- ✅ `backend/src/main/java/vn/edu/utc/rag/service/identity/IdentityService.java`
- ✅ `backend/src/main/java/vn/edu/utc/rag/config/SecurityConfiguration.java`
- ✅ `backend/src/main/java/vn/edu/utc/rag/persistence/repository/query/ChatQueryRepository.java`
- ✅ `database/init/002-schema.sql`
- ✅ `database/init/003-api-state.sql`
- ✅ `database/init/004-public-views.sql`
- ✅ `tools/src/main.py` (placeholder only)
- ✅ `tools/Dockerfile`
- ✅ `tools/requirements.txt`

### Created Documentation (This Session)
- ✅ `docs/crawler/MASTER_CONTEXT.md` (this file)
- ⏳ `docs/crawler/IMPLEMENTATION_CHECKLIST.md` (next)
- ⏳ `docs/crawler/ARCHITECTURE_DECISIONS.md` (next)
- ⏳ `docs/crawler/REVIEW_HISTORY.md` (next)
- ⏳ `docs/crawler/checkpoints/` (checkpoints to be created)

### Pending Files (Designed, NOT Created)
- ❌ `database/init/005-crawler-complete.sql` (v6 final design in v6 review)
- ❌ `database/init/006-worker-role.sql` (v6 final design in v6 review)
- ❌ `tools/src/config.py`
- ❌ `tools/src/database.py`
- ❌ `tools/src/job_manager.py`
- ❌ `tools/src/crawler.py`
- ❌ `tools/src/extractors/html.py`
- ❌ `tools/src/extractors/pdf.py`
- ❌ `tools/src/extractors/docx.py`
- ❌ `tools/src/chunker.py`
- ❌ `tools/src/embedder.py`
- ❌ `tools/src/indexer.py`
- ❌ `tools/src/scheduler.py`
- ❌ `tools/src/recovery.py`

---

## 8. Next Actions

### Immediate (This Session)
1. ✅ Create MASTER_CONTEXT.md
2. ⏳ Create IMPLEMENTATION_CHECKLIST.md
3. ⏳ Create ARCHITECTURE_DECISIONS.md
4. ⏳ Create REVIEW_HISTORY.md
5. ⏳ Create checkpoint: `checkpoints/2026-11-20_architecture-v6-final.md`

### Before Implementation
1. ❓ Get stakeholder confirmation on 6 business questions
2. ✅ Review and approve v6 architecture design
3. ❌ Apply migration 005 and 006 (after backup)
4. ❌ Execute integration tests on real database
5. ❌ Create scheduler service account

### Implementation Phase (NOT STARTED)
1. ❌ Implement Python worker modules (8-10 days estimate)
2. ❌ Implement scheduler (2 days estimate)
3. ❌ Integration testing (2 days estimate)
4. ❌ End-to-end validation
5. ❌ Production deployment

---

**END OF MASTER CONTEXT**

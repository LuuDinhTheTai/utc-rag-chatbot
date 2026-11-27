# Architecture Decision Records - UTC/UTC2 Crawler

**Project**: UTC RAG Chatbot - Automated Crawler  
**Last Updated**: 2026-11-20

---

## ADR Index

| ID | Title | Status | Date |
|----|-------|--------|------|
| AD-001 | Job Lifecycle State Machine | Accepted | 2026-11-19 |
| AD-002 | Lease-Based Job Claiming | Accepted | 2026-11-19 |
| AD-003 | Revision Indexing State Machine | Accepted | 2026-11-19 |
| AD-004 | Worker Architecture - Direct PostgreSQL | Accepted | 2026-11-19 |
| AD-005 | PostgreSQL Service Account | Accepted | 2026-11-20 |
| AD-006 | Scheduler Architecture | Accepted | 2026-11-20 |
| AD-007 | Hash Strategy for Content | Accepted | 2026-11-19 |
| AD-008 | Pipeline Versioning | Accepted | 2026-11-19 |
| AD-009 | Publication Workflow | Accepted | 2026-11-19 |
| AD-010 | Revision Ownership Token | Accepted | 2026-11-20 |
| AD-011 | Atomic Fencing Strategy | Accepted | 2026-11-20 |
| AD-012 | Chunk Integrity Verification | Accepted | 2026-11-20 |
| AD-013 | Lock Ordering for Deadlock Prevention | Accepted | 2026-11-20 |

---

## AD-001: Job Lifecycle State Machine

**Status**: Accepted  
**Date**: 2026-11-19  
**Context**: Need well-defined job states for crawler execution tracking.

### Decision

Use four states for `crawl_jobs.status`:
- `PENDING`: Job created, waiting to be claimed
- `RUNNING`: Job claimed by worker, actively processing
- `COMPLETED`: Job finished successfully
- `FAILED`: Job failed after maximum retry attempts

### Rationale

1. **COMPLETED vs DONE**: "COMPLETED" is consistent with domain language already used in `personality_attempts` table
2. **FAILED vs ERROR**: "FAILED" clearly indicates terminal failure state after retries
3. **No SUCCESS state**: Would be redundant with COMPLETED
4. **Terminal states**: Both COMPLETED and FAILED are terminal (job won't be reprocessed)

### Alternatives Considered

- **DONE/FAILED**: Rejected because DONE is less formal and not used elsewhere in codebase
- **SUCCESS/ERROR**: Rejected because not consistent with existing domain language
- **PENDING/RUNNING only**: Rejected because no way to track job completion without terminal states

### Consequences

- Backend needs updating to handle COMPLETED and FAILED states
- Need CHECK constraint to enforce valid states
- Migration must handle any existing jobs with invalid states

### Implementation

```sql
-- Migration 005
ALTER TABLE crawl_jobs ADD COLUMN completed_at timestamptz;
ALTER TABLE crawl_jobs ADD COLUMN error_message text;
ALTER TABLE crawl_jobs ADD CONSTRAINT crawl_jobs_status_check 
  CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'));
```

---

## AD-002: Lease-Based Job Claiming

**Status**: Accepted  
**Date**: 2026-11-19  
**Context**: Multiple workers must safely claim jobs without conflicts.

### Decision

Use PostgreSQL's `SELECT FOR UPDATE SKIP LOCKED` with:
- `lease_until` timestamp (10 minute lease)
- `claim_token` UUID for ownership verification
- Heartbeat every 3 minutes to extend lease
- Expired leases reset to PENDING by recovery process

### Rationale

1. **`FOR UPDATE SKIP LOCKED`**: PostgreSQL built-in atomic operation, prevents lock contention
2. **Lease duration**: 10 minutes allows processing while preventing indefinite locks
3. **claim_token**: UUID provides unforgeable proof of ownership
4. **Heartbeat**: 3 minute interval with 10 minute lease = 3 missed heartbeats before expiration
5. **Recovery process**: Handles worker crashes without manual intervention

### Alternatives Considered

- **Optimistic locking**: Rejected due to race conditions and retry overhead
- **Redis-based locks**: Rejected to avoid external dependency and maintain DB as single source of truth
- **Advisory locks**: Rejected because they don't survive connection drops
- **20 minute lease**: Rejected as too long (stuck jobs impact throughput)
- **1 minute lease**: Rejected as too short (excessive heartbeat overhead)

### Consequences

- Workers must implement heartbeat mechanism
- Recovery process must run periodically (e.g., every 2 minutes)
- Worker crash loses work but doesn't require manual intervention
- Maximum job processing time effectively limited by lease duration

### Implementation

```sql
-- Claim job
UPDATE crawl_jobs
SET status = 'RUNNING',
    lease_until = clock_timestamp() + INTERVAL '10 minutes',
    claim_token = gen_random_uuid(),
    attempt_no = attempt_no + 1
WHERE id = (
    SELECT id FROM crawl_jobs
    WHERE status = 'PENDING' AND run_after <= NOW()
    ORDER BY run_after, id LIMIT 1
    FOR UPDATE SKIP LOCKED
)
RETURNING *;
```

### Related Decisions

- AD-011: Atomic fencing uses claim_token for verification
- AD-013: Lock ordering prevents deadlocks

---

## AD-003: Revision Indexing State Machine

**Status**: Accepted  
**Date**: 2026-11-19  
**Context**: Document revisions need clear lifecycle from creation to publication.

### Decision

State machine for `document_revisions.status`:
```
INDEXING → PENDING → PUBLISHED → ARCHIVED
                   ↘           ↗
                     (stays PENDING if rejected)

Any state → REVOKED (manual action)
```

**States**:
- `INDEXING`: Worker creating chunks and embeddings
- `PENDING`: Indexing complete, awaiting reviewer approval
- `PUBLISHED`: Approved and visible in RAG queries
- `ARCHIVED`: Replaced by newer published revision
- `REVOKED`: Manually revoked due to policy violation

### Rationale

1. **INDEXING not PROCESSING**: More specific to the embedding operation
2. **PENDING not DRAFT**: Indicates complete work awaiting approval
3. **ARCHIVED not DELETED**: Preserves history for audit
4. **REVOKED separate**: Distinguishes policy violations from routine archiving

### Alternatives Considered

- **DRAFT state**: Rejected as confusing (revision is complete, not draft)
- **APPROVED state**: Redundant with PUBLISHED
- **DELETE revisions**: Rejected to preserve audit trail
- **Auto-archive on publish**: Accepted as simplifies lifecycle

### Consequences

- Worker responsible for INDEXING → PENDING transition
- Backend responsible for PENDING → PUBLISHED
- System automatically transitions PUBLISHED → ARCHIVED when new revision published
- RAG queries must filter: `status='PUBLISHED' AND source_verified=true AND revoked_at IS NULL`

### Implementation

```sql
-- Migration 005
ALTER TABLE document_revisions ADD CONSTRAINT document_revisions_status_check
  CHECK (status IN ('INDEXING', 'PENDING', 'PUBLISHED', 'ARCHIVED', 'REVOKED'));
```

### Related Decisions

- AD-009: Publication workflow defines transition rules
- AD-012: Chunk verification gates INDEXING → PENDING

---

## AD-004: Worker Architecture - Direct PostgreSQL Access

**Status**: Accepted  
**Date**: 2026-11-19  
**Context**: Worker needs to claim jobs, insert chunks, and manage revisions.

### Decision

Worker connects directly to PostgreSQL with dedicated `utc_worker` role, NOT via Backend REST API.

### Rationale

1. **Atomic operations**: `SELECT FOR UPDATE SKIP LOCKED` cannot work over HTTP
2. **Bulk operations**: Inserting 500 chunks per document would require 500 HTTP requests
3. **Performance**: Direct DB = ~1ms per query vs REST = ~50-100ms per request
4. **Simplicity**: Worker doesn't depend on Backend availability
5. **Transaction control**: Worker manages transaction boundaries for atomicity

### Alternatives Considered

- **Backend REST API**: 
  - Rejected: Would need 5-10 new endpoints
  - Rejected: Atomic job claiming impossible over HTTP
  - Rejected: Bulk insert overhead unacceptable
  - Rejected: Adds Backend as dependency/failure point

- **Mixed approach** (claim via API, insert via DB):
  - Rejected: Inconsistent, hard to reason about
  - Rejected: Still has API dependency for critical path

### Consequences

**Worker Responsibilities**:
- Job claiming, heartbeat, completion
- Revision creation and ownership
- Chunk insertion and verification
- Status transitions: INDEXING → PENDING
- Audit logging for worker actions

**Backend Retains**:
- Publication approval (PENDING → PUBLISHED)
- Source verification (source_verified flag)
- Revocation (→ REVOKED)
- All user-facing APIs
- CSRF protection and rate limiting

**Security**:
- Dedicated PostgreSQL role with minimal privileges (AD-005)
- Worker cannot publish, verify sources, or revoke
- Worker cannot delete documents or jobs

### Implementation

- Create `utc_worker` PostgreSQL role (see AD-005)
- Worker connects: `postgresql://utc_worker:password@postgres:5432/utc_rag`
- Audit events logged with `actor_user_id = WORKER_SERVICE_UUID`

### Related Decisions

- AD-005: Defines utc_worker permissions
- AD-011: Atomic fencing requires direct DB access

---

## AD-005: PostgreSQL Service Account

**Status**: Accepted  
**Date**: 2026-11-20  
**Context**: Worker needs database access with minimal privileges.

### Decision

Create PostgreSQL role `utc_worker` with:
- LOGIN capability
- Connection to `utc_rag` database
- Minimal table-level grants
- No elevated privileges (SUPERUSER, CREATEROLE, CREATEDB, REPLICATION)

### Grants

**Read-only**:
- `SELECT` on `campuses`, `crawl_sources`

**Read-write**:
- `SELECT, UPDATE` on `crawl_jobs`
- `SELECT, INSERT, UPDATE` on `knowledge_documents`, `document_revisions`
- `SELECT, INSERT, DELETE` on `document_chunks`
- `INSERT` on `audit_events`

**Sequences**:
- `USAGE, SELECT` on all related sequences

**Explicitly Revoked**:
- `DELETE` on `knowledge_documents`, `document_revisions`, `crawl_jobs`
- `TRUNCATE` on all tables

### Rationale

1. **Principle of least privilege**: Only grants necessary for worker operations
2. **No DELETE on documents**: Prevents accidental data loss
3. **DELETE on chunks**: Needed for recovery (delete partial chunks before reprocessing)
4. **No TRUNCATE**: Prevents catastrophic data loss
5. **INSERT on audit_events**: Allows worker action logging

### Alternatives Considered

- **Using postgres superuser**: Rejected as massive security risk
- **Using backend application user**: Rejected as mixing concerns
- **Granting DELETE on all tables**: Rejected as unnecessary and dangerous

### Consequences

- Worker can perform all necessary operations
- Worker cannot delete documents or jobs (must be done via Backend)
- Worker can clean up chunks during recovery
- Separate role makes audit trail clear

### Implementation

```sql
CREATE ROLE utc_worker WITH LOGIN NOPASSWORD 
  NOCREATEROLE NOCREATEDB NOREPLICATION NOSUPERUSER;
-- (Full grants in migration 006)
```

### Security Notes

- Password set separately after role creation (not in migration file)
- Connection limit: 10 concurrent connections
- Password must be strong random string
- Store password in secure vault, not in code

### Related Decisions

- AD-004: Worker uses this role for DB access

---

## AD-006: Scheduler Architecture

**Status**: Accepted  
**Date**: 2026-11-20  
**Context**: Jobs must be created automatically on schedule.

### Decision

Scheduler is Python script that calls Backend API `POST /api/v1/ops/crawl-jobs`:
- Runs every 6 hours (configurable)
- Authenticates via `POST /api/v1/auth/sessions` with service account
- Gets CSRF token via `GET /api/v1/auth/csrf`
- Creates job for each enabled source
- Logs results to stdout

### Rationale

1. **Use existing API**: Backend already has duplicate checking, audit logging, rate limiting
2. **Session-based auth**: Consistent with Backend security model
3. **Service account**: Dedicated user with TECH_OPERATOR role
4. **Via API not DB**: Respects Backend authorization and business logic
5. **Simple cron**: No complex scheduling framework needed

### Alternatives Considered

- **Scheduler writes directly to DB**: 
  - Rejected: Bypasses Backend duplicate check
  - Rejected: No audit trail
  - Rejected: Inconsistent with security model

- **Backend cron job**:
  - Rejected: Scheduler is operations concern, not application logic
  - Rejected: Harder to configure schedule without rebuild

- **Kubernetes CronJob**:
  - Accepted as future option if moving to K8s
  - Current: Simple Docker container with `while sleep` loop

### Consequences

- Need service account user in `users` table with TECH_OPERATOR role
- Scheduler has dependency on Backend availability
- Backend API must handle scheduler authentication
- Duplicate job creation prevented by Backend (returns existing job)

### Implementation

```python
# Authenticate
POST /api/v1/auth/sessions
Body: {"email": "scheduler@internal.utc", "password": "..."}

# Get CSRF
GET /api/v1/auth/csrf
Response: {"token": "...", "header_name": "X-CSRF-TOKEN"}

# Create jobs
for source in enabled_sources:
    POST /api/v1/ops/crawl-jobs
    Headers: {"X-CSRF-TOKEN": "..."}
    Body: {"source_id": source.id}
```

### Related Decisions

- AD-002: Jobs created in PENDING state, ready for claiming

---

## AD-007: Hash Strategy for Content

**Status**: Accepted  
**Date**: 2026-11-19  
**Context**: Need to detect content changes for incremental sync.

### Decision

Hash **extracted text content**, not raw bytes:
- **HTML**: Hash cleaned text (BeautifulSoup, strip tags/whitespace)
- **PDF**: Hash extracted text (not binary PDF)
- **DOCX**: Hash extracted text
- Algorithm: SHA-256 (hex format)

### Rationale

1. **Content-focused**: Metadata changes (PDF author, HTML meta tags) don't trigger re-embedding
2. **Stable**: Same content = same hash, regardless of format quirks
3. **Efficient**: Compare hash instead of full content comparison
4. **Existing unique constraint**: Matches `(document_id, content_hash, pipeline_key, embedding_model)`

### Alternatives Considered

- **Hash raw bytes** (current PDF upload behavior):
  - Rejected for crawler: PDF metadata changes trigger re-embedding
  - Kept for manual uploads: User expects byte-level deduplication

- **Hash normalized HTML**:
  - Rejected: Too aggressive (different HTML = same text is valid)

- **No hashing, always re-embed**:
  - Rejected: Wastes compute on unchanged content

### Consequences

- Breaking change from Backend's PDF upload (hashes bytes)
- Solution: Use different `pipeline_key` (`pdf-v2` vs `pdf-v1`)
- Worker and Backend can coexist with different hash strategies
- Hash function must be deterministic (same text → same hash)

### Implementation

```python
def compute_content_hash(content_type, raw_bytes):
    if content_type == 'text/html':
        text = extract_html_text(raw_bytes)  # BeautifulSoup
    elif content_type == 'application/pdf':
        text = extract_pdf_text(raw_bytes)  # PyPDF2
    elif content_type == 'application/vnd...docx':
        text = extract_docx_text(raw_bytes)  # python-docx
    
    return hashlib.sha256(text.encode('utf-8')).hexdigest()
```

### Related Decisions

- AD-008: Pipeline versioning handles hash strategy changes

---

## AD-008: Pipeline Versioning

**Status**: Accepted  
**Date**: 2026-11-19  
**Context**: Extraction and chunking logic will evolve over time.

### Decision

Use `pipeline_key` to identify extraction/chunking logic version:
- `html-v1`: Current HTML extraction
- `pdf-v1`: Backend byte-level hash (existing)
- `pdf-v2`: Worker text-level hash (new)
- `docx-v1`: Worker DOCX extraction

UNIQUE constraint includes `pipeline_key`, allowing multiple versions of same content.

### Rationale

1. **Versioning**: Can improve extraction without breaking existing data
2. **Coexistence**: Backend PDF upload and worker PDF crawl use different pipelines
3. **Reprocessing**: Can reprocess documents with improved logic
4. **Audit trail**: Know which version created each revision

### Alternatives Considered

- **No versioning**: Rejected, prevents evolution of extraction logic
- **Global version**: Rejected, forces reprocessing entire corpus on any change
- **Semantic versioning**: Rejected as overkill, simple incremental numbering sufficient

### Consequences

- Worker must set appropriate `pipeline_key` when creating revisions
- Migration to new pipeline requires reprocessing or gradual update
- Can have duplicate revisions with different pipelines (expected behavior)

### Implementation

```python
PIPELINE_HTML = "html-v1"
PIPELINE_PDF = "pdf-v2"
PIPELINE_DOCX = "docx-v1"

# When creating revision:
pipeline_key = PIPELINE_HTML if content_type == 'text/html' else ...
```

### Related Decisions

- AD-007: Text hashing is `pdf-v2`, byte hashing is `pdf-v1`

---

## AD-009: Publication Workflow

**Status**: Accepted  
**Date**: 2026-11-19  
**Context**: Balance between automation and content compliance.

### Decision

**Manual review by default** (no auto-publish without stakeholder approval):

1. Worker: Creates revision with `status='INDEXING'`
2. Worker: After chunks inserted, transitions to `status='PENDING'`
3. Reviewer: Reviews via Backend UI
4. Reviewer: Approves via Backend API → `status='PUBLISHED'`, `source_verified=true`
5. RAG: Only queries revisions with all three flags

**Conditions for RAG visibility**:
```sql
WHERE status='PUBLISHED' 
  AND source_verified=true 
  AND revoked_at IS NULL
```

### Rationale

1. **Compliance**: Human verification prevents inappropriate content
2. **Source trust**: `source_verified` flag indicates manual review
3. **Safety**: Worker cannot bypass review
4. **Flexibility**: Future auto-publish can be added per source if approved

### Alternatives Considered

- **Auto-publish all**: 
  - Rejected: No compliance check
  - Risk: Inappropriate content in chatbot

- **Auto-publish trusted sources**:
  - Deferred: Requires stakeholder decision
  - Can be added via `auto_publish` flag per source

- **No manual review**:
  - Rejected: Violates content compliance requirements

### Consequences

- **Latency**: Documents appear in chatbot only after manual approval
- **Queue**: PENDING revisions accumulate if reviewer unavailable
- **Monitoring**: Need alerts for PENDING queue depth
- **Process**: Reviewer SLA required (currently unspecified, see stakeholder questions)

### Future Enhancement (If Approved)

```sql
ALTER TABLE crawl_sources ADD COLUMN auto_publish boolean DEFAULT false;

-- Worker logic:
if source.auto_publish:
    call_backend_api_to_auto_approve(revision_id)
```

### Implementation

Worker transitions:
```python
# After all chunks inserted
db.execute("""
    UPDATE document_revisions
    SET status = 'PENDING', indexing_claim_token = NULL
    WHERE id = ? AND status = 'INDEXING'
""", [revision_id])
```

Reviewer approves (Backend only):
```java
e.setStatus("PUBLISHED");
e.setSourceVerified(true);
e.setReviewedBy(reviewer_uuid);
```

### Related Decisions

- AD-003: PENDING is a distinct state in lifecycle
- AD-010: Worker clears `indexing_claim_token` when transitioning to PENDING

---

## AD-010: Revision Ownership Token

**Status**: Accepted  
**Date**: 2026-11-20  
**Context**: Prevent concurrent processing of same revision by multiple workers.

### Decision

Add `indexing_claim_token` UUID column to `document_revisions`:
- Set when revision enters `INDEXING` state
- Verified on every chunk write
- Cleared when transitioning out of `INDEXING`
- Separate from job's `claim_token`

### Rationale

1. **Race condition**: Job A and Job B discover same URL → both try to index
2. **Ownership**: Token proves which worker owns revision processing
3. **Independence**: Revision can be reclaimed by different job if original job dies
4. **Fencing**: Invalid token prevents stale worker from writing chunks

### Alternatives Considered

- **Use job's claim_token only**:
  - Rejected: Revision can be reclaimed by different job
  - Rejected: Need revision-level ownership tracking

- **Lock by crawl_job_id only**:
  - Rejected: Job dies → revision stuck forever
  - Rejected: No way to verify ownership on write

### Consequences

- Revision claim requires locking jobs in correct order (AD-013)
- Worker must verify revision token on every write
- Token cleared on completion prevents accidental reuse

### Implementation

```sql
-- Migration 005
ALTER TABLE document_revisions ADD COLUMN indexing_claim_token uuid;

-- Claim revision
UPDATE document_revisions
SET crawl_job_id = :our_job_id,
    indexing_claim_token = gen_random_uuid()
WHERE id = :revision_id;

-- Write chunks (verify token)
SELECT ... FROM document_revisions WHERE id = ? FOR UPDATE;
IF result['indexing_claim_token'] != expected_token THEN ABORT;

-- Complete (clear token)
UPDATE document_revisions
SET status = 'PENDING', indexing_claim_token = NULL
WHERE id = ?;
```

### Related Decisions

- AD-011: Atomic fencing verifies both job and revision tokens
- AD-013: Lock ordering when claiming revision

---

## AD-011: Atomic Fencing Strategy

**Status**: Accepted  
**Date**: 2026-11-20  
**Context**: Worker must not write chunks after losing job or revision ownership.

### Decision

**Two-phase write**:
1. **Phase 1**: Compute embeddings OUTSIDE transaction (no locks held)
2. **Phase 2**: Short transaction with full fencing:
   - Lock job (verify token, status, lease)
   - Lock revision (verify token, job_id)
   - INSERT chunks
   - COMMIT (< 1 second)

**Verification on EVERY write**:
- `job.claim_token = expected`
- `job.status = 'RUNNING'`
- `job.lease_until > clock_timestamp()`
- `revision.crawl_job_id = job.id`
- `revision.indexing_claim_token = expected`
- `revision.status = 'INDEXING'`

### Rationale

1. **Embedding outside**: 5-10s per batch, holding locks is unacceptable
2. **Short transaction**: Minimizes lock contention (< 1s)
3. **Dual verification**: Both job and revision ownership checked
4. **Clock timestamp**: Detects lease expiration during transaction
5. **Abort on mismatch**: Any verification failure → ROLLBACK

### Alternatives Considered

- **Embed in transaction**:
  - Rejected: 5-10s lock duration unacceptable
  - Risk: Blocks other workers and recovery

- **Check tokens only once**:
  - Rejected: Race condition if lease expires or revision reclaimed
  - Risk: Stale worker writes chunks

- **Optimistic locking**:
  - Rejected: Race conditions, difficult error handling

### Consequences

- Worker must compute embeddings first, then open transaction
- If lease expires during embedding, transaction aborts (safe)
- If revision reclaimed during embedding, transaction aborts (safe)
- Lock held briefly (~500ms for 100 chunks)

### Implementation

```python
# Phase 1: Compute embeddings (NO LOCKS)
embedded_chunks = []
for chunk in chunks:
    embedding = call_ollama_embed(chunk['text'])  # 5-10s
    embedded_chunks.append({...})

# Phase 2: Write atomically (SHORT TRANSACTION)
db.begin()
try:
    # Lock and verify job
    job_state = db.query("SELECT ... FROM crawl_jobs WHERE id=? FOR UPDATE", [job_id])
    assert job_state['claim_token'] == job_token
    assert job_state['status'] == 'RUNNING'
    assert job_state['lease_until'] > db.query_scalar("SELECT clock_timestamp()")
    
    # Lock and verify revision
    rev_state = db.query("SELECT ... FROM document_revisions WHERE id=? FOR UPDATE", [rev_id])
    assert rev_state['indexing_claim_token'] == rev_token
    assert rev_state['crawl_job_id'] == job_id
    
    # Write chunks
    db.executemany("INSERT INTO document_chunks ...", embedded_chunks)
    
    db.commit()  # ~500ms total
except:
    db.rollback()
    raise
```

### Related Decisions

- AD-002: Lease and token mechanism
- AD-010: Revision token
- AD-013: Lock ordering

---

## AD-012: Chunk Integrity Verification

**Status**: Accepted  
**Date**: 2026-11-20  
**Context**: Prevent incomplete revisions from being marked PENDING.

### Decision

Before transitioning `INDEXING → PENDING`, verify:
1. `expected_chunk_count` is set (not NULL)
2. `COUNT(*) = expected_chunk_count`
3. `COUNT(DISTINCT position) = COUNT(*)` (no duplicates)
4. `MIN(position) = 0`
5. `MAX(position) = expected_chunk_count - 1`
6. `MAX(position) - MIN(position) + 1 = COUNT(*)` (no gaps)

**All verifications must pass** or transaction aborts.

### Rationale

1. **Completeness**: Ensure all chunks inserted (count check)
2. **Uniqueness**: No duplicate positions (distinct check)
3. **Contiguity**: Positions from 0 to N-1 without gaps
4. **Safety**: Invalid chunk set cannot become PENDING

### Alternatives Considered

- **Count only**:
  - Rejected: Doesn't catch duplicates or gaps
  - Example: positions [0, 1, 1, 3] has count=4 but missing position 2

- **Count + min/max**:
  - Rejected: Doesn't catch internal gaps
  - Example: positions [0, 2, 3, 4] has correct min/max but missing 1

- **Trust ON CONFLICT**:
  - Rejected: ON CONFLICT prevents duplicates but doesn't detect gaps

### Consequences

- Worker must set `expected_chunk_count` before processing chunks
- Worker cannot complete indexing with incomplete/invalid chunks
- Verification queries run once per document (acceptable overhead)

### Implementation

```sql
-- Set expected count
UPDATE document_revisions 
SET expected_chunk_count = :count
WHERE id = :rev_id;

-- Before marking PENDING, verify
SELECT 
    COUNT(*) as total,
    COUNT(DISTINCT position) as unique_positions,
    MIN(position) as min_pos,
    MAX(position) as max_pos
FROM document_chunks
WHERE revision_id = :rev_id;

-- Application checks:
assert total == expected_count
assert unique_positions == total
assert min_pos == 0
assert max_pos == expected_count - 1
assert max_pos - min_pos + 1 == total
```

### Related Decisions

- AD-009: Only complete revisions reach PENDING → reviewer

---

## AD-013: Lock Ordering for Deadlock Prevention

**Status**: Accepted  
**Date**: 2026-11-20  
**Context**: Multiple workers accessing jobs and revisions must avoid deadlocks.

### Decision

**Global lock order**:
1. Lock `crawl_jobs` table rows in ascending `id` order
2. Then lock `document_revisions` table rows

**Never lock in different order.**

### Rationale

1. **Deadlock prevention**: Consistent lock order eliminates circular waits
2. **Simplicity**: Easy to verify in code review
3. **Performance**: PostgreSQL row-level locks have minimal overhead

Example deadlock prevented:
```
Worker A: Lock Job 5 → Lock Job 10
Worker B: Lock Job 10 → Lock Job 5  ❌ DEADLOCK

With ordering:
Worker A: Lock Job 5 → Lock Job 10
Worker B: Lock Job 5 (waits for A) → Lock Job 10 ✓ NO DEADLOCK
```

### Alternatives Considered

- **No ordering**:
  - Rejected: High risk of deadlocks

- **Lock all jobs at start**:
  - Rejected: Unnecessary lock contention

- **Lock revision first**:
  - Rejected: Doesn't help if multiple jobs involved

### Consequences

- All operations must follow lock order:
  - `claim_job()`: Lock single job
  - `claim_revision()`: Lock jobs (sorted), then revision
  - `embed_and_insert_batch()`: Lock job, then revision
  - `complete_indexing()`: Lock job, then revision

- Code review must verify lock ordering

### Implementation

```python
# Example: Claim revision with multiple jobs
old_job_id = peek_revision_job()
our_job_id = worker.job_id

# Sort jobs by ID
jobs_to_lock = sorted([old_job_id, our_job_id])

# Lock in order
for job_id in jobs_to_lock:
    db.execute("SELECT * FROM crawl_jobs WHERE id=? FOR UPDATE", [job_id])

# Then lock revision
db.execute("SELECT * FROM document_revisions WHERE id=? FOR UPDATE", [rev_id])
```

### Related Decisions

- AD-011: Atomic fencing follows this lock order

---

**END OF ARCHITECTURE DECISIONS**

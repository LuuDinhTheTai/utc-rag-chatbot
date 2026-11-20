# Checkpoint: Architecture v6 Final (2026-11-20)

## Project Phase
**ARCHITECTURE COMPLETE - AWAITING STAKEHOLDER INPUT**

Documentation phase complete. Implementation phase BLOCKED pending stakeholder answers.

---

## Current Status

### Completed Work
1. ✅ Architecture design through 6 review iterations (v1-v6)
2. ✅ Database migrations designed (005-crawler-complete.sql, 006-worker-role.sql)
3. ✅ Python worker architecture designed with atomic fencing
4. ✅ Scheduler design complete
5. ✅ Integration test suite designed (3 tests)
6. ✅ Persistent documentation created:
   - `docs/crawler/MASTER_CONTEXT.md`
   - `docs/crawler/IMPLEMENTATION_CHECKLIST.md`
   - `docs/crawler/ARCHITECTURE_DECISIONS.md`
   - `docs/crawler/REVIEW_HISTORY.md`
   - `docs/crawler/checkpoints/2026-11-20_architecture-v6-final.md` (this file)

### Work In Progress
None. Architecture phase complete.

### Blocked Tasks (4/77 tasks, 5%)
1. **Task 1.1**: Define UTC sitemap URL
2. **Task 1.2**: Define allowed path patterns
3. **Task 1.3**: Define excluded path patterns
4. **Task 1.4**: Define rate limit policy

**Blocker**: Requires stakeholder answers to 6 questions (see Unresolved Issues section).

---

## Architecture Version
**v6 (Final)**

### Key Architectural Components

#### 1. Job State Machine
```
PENDING → RUNNING → COMPLETED
                 ↘ FAILED
```
- Atomic claiming with SELECT FOR UPDATE SKIP LOCKED
- 10-minute lease, 3-minute heartbeat interval
- claim_token UUID for fencing

#### 2. Revision State Machine
```
INDEXING → PENDING → PUBLISHED → ARCHIVED
                            ↓
                        REVOKED
```
- indexing_claim_token for revision-level fencing
- Manual review required before PUBLISHED
- expected_chunk_count verification before PENDING

#### 3. Worker Architecture
- Direct PostgreSQL access (not REST API)
- utc_worker role with LOGIN and minimal grants
- Embedding computed outside transaction
- Dual token verification (job + revision)
- Lock order: crawl_jobs ASC → document_revisions

#### 4. Scheduler
- Calls Backend API POST /api/v1/ops/crawl-jobs
- Service account with TECH_OPERATOR role
- Session-based authentication

---

## Completed Tasks (5/77 tasks, 6%)

### Phase 1: Requirements & Planning (0/7 tasks, 0%)
- All tasks blocked pending stakeholder input

### Phase 2: Database Schema (5/5 tasks, 100%)
- ✅ Task 2.1: Design crawl_jobs table schema
- ✅ Task 2.2: Design crawl_job_queue_items table schema
- ✅ Task 2.3: Design revision columns (completed_at, error_message, expected_chunk_count, etc.)
- ✅ Task 2.4: Design migration 005-crawler-complete.sql
- ✅ Task 2.5: Design migration 006-worker-role.sql

### Phase 3-8
Not started. Blocked by Phase 1.

---

## Files Created

### Documentation Files
1. `docs/crawler/MASTER_CONTEXT.md` (645 lines)
   - Project objectives and acceptance criteria
   - Verified repository state (13 source files examined)
   - Database schema confirmed (002-004 migrations, 005-006 designed)
   - 13 architectural decisions
   - 6 stakeholder questions

2. `docs/crawler/IMPLEMENTATION_CHECKLIST.md` (823 lines)
   - 77 tasks across 8 phases
   - Detailed verification requirements
   - Dependencies and evidence tracking
   - Current status: 6% complete, 5% blocked

3. `docs/crawler/ARCHITECTURE_DECISIONS.md` (812 lines)
   - AD-001 through AD-013
   - Rationale and rejected alternatives
   - Complete decision trail

4. `docs/crawler/REVIEW_HISTORY.md` (437 lines)
   - 6 review cycles documented
   - 20 issues found, 17 resolved
   - Review metrics and timeline

5. `docs/crawler/checkpoints/2026-11-20_architecture-v6-final.md` (this file)

### Migration Files (Designed, NOT Applied)
1. `database/init/005-crawler-complete.sql` (NOT CREATED YET)
   - Adds columns: completed_at, error_message, sitemap_url, rate_limit_seconds, allowed_path_patterns, excluded_path_patterns, expected_chunk_count, indexing_claim_token
   - Adds CHECK constraints on status columns
   - Adds indexes for performance
   - Includes verification queries

2. `database/init/006-worker-role.sql` (NOT CREATED YET)
   - CREATE ROLE utc_worker WITH LOGIN
   - Minimal grants: SELECT/INSERT/UPDATE on specific columns
   - No DELETE, no superuser, no DDL
   - Includes verification queries

### Source Files (NOT CREATED YET)
1. `tools/src/worker.py` (designed, not implemented)
2. `tools/src/scheduler.py` (designed, not implemented)
3. `tools/tests/test_integration.py` (designed, not implemented)

---

## Database State

### Applied Migrations
- ✅ `database/init/001-extensions.sql`
- ✅ `database/init/002-schema.sql` (includes crawl tables at lines 468-515)
- ✅ `database/init/003-api-state.sql`
- ✅ `database/init/004-data.sql`

### Pending Migrations (Designed, NOT Applied)
- ⏸️ `database/init/005-crawler-complete.sql` (NOT CREATED YET)
- ⏸️ `database/init/006-worker-role.sql` (NOT CREATED YET)

### Verified Schema Elements
From `database/init/002-schema.sql`:
- Lines 468-515: crawl_jobs, crawl_job_queue_items, crawl_results tables
- Line 493: UNIQUE CONSTRAINT one_pending_or_running_per_url
- Lines 485-486: lease_until TIMESTAMPTZ, claim_token UUID

From `database/init/003-api-state.sql`:
- Line 26: UNIQUE INDEX idx_personality_attempts_unique_pending WHERE status IN ('PENDING','RUNNING')

---

## Tests

### Integration Tests Designed (NOT EXECUTED)
1. **Test 1: Basic Job Lifecycle**
   - Create job → Claim → Heartbeat → Complete
   - Verifies: Atomic claiming, lease renewal, status transitions

2. **Test 2: Concurrent Job Claiming**
   - Two workers attempt to claim same job
   - Verifies: Only one succeeds, SKIP LOCKED works, token fencing

3. **Test 3: Revision Indexing with Reclaim**
   - Job1 fails → Worker2 reclaims revision → Indexes successfully
   - Verifies: Revision ownership transfer, dual token verification, chunk integrity

### Test Execution Status
❌ NOT EXECUTED - Tests are designed but not implemented or run.

---

## Unresolved Issues

### Stakeholder Questions (BLOCKING IMPLEMENTATION)

1. **UTC2 Sitemap URL**
   - Question: Does https://utc2.edu.vn have a sitemap.xml file?
   - Impact: Defines job queue seed URL
   - Required for: Task 1.1

2. **Allowed Path Patterns**
   - Question: Which URL patterns should the crawler include?
   - Examples: `/tin-tuc/*`, `/dao-tao/*`, `/nghien-cuu/*`
   - Impact: Defines allowed_path_patterns in crawl_jobs
   - Required for: Task 1.2

3. **Excluded Path Patterns**
   - Question: Which URL patterns should the crawler exclude?
   - Examples: `/admin/*`, `/download/*`, `/media/*`
   - Impact: Defines excluded_path_patterns in crawl_jobs
   - Required for: Task 1.3

4. **Rate Limit Policy**
   - Question: How many requests per second per domain?
   - Impact: Defines rate_limit_seconds in crawl_jobs
   - Required for: Task 1.4

5. **Auto-Publish Policy**
   - Question: Should revisions auto-publish or require manual review?
   - Current design: Manual review required (AD-009)
   - Impact: If auto-publish desired, need to add approval rules
   - Required for: Task 5.5

6. **Model Upgrade Policy**
   - Question: When changing embedding model, how to handle existing embeddings?
   - Options: Re-embed all, dual index, version tagging
   - Impact: Migration strategy for model changes
   - Required for: Task 6.4

7. **Review SLA**
   - Question: How long before PENDING revisions require escalation?
   - Impact: Monitoring and alert thresholds
   - Required for: Task 8.3

### Technical Assumptions (NOT TESTED)

1. **PostgreSQL Concurrency**
   - Assumption: SELECT FOR UPDATE SKIP LOCKED works as expected
   - Risk: Not tested with real concurrent workers
   - Mitigation: Integration tests designed but not executed

2. **Ollama Performance**
   - Assumption: Embedding 100 chunks takes <5 seconds
   - Risk: Might exceed lease interval if slower
   - Mitigation: Batch size tunable

3. **Lease Timing**
   - Assumption: 10-minute lease, 3-minute heartbeat is sufficient
   - Risk: Network delays, slow embedding could cause lease expiration
   - Mitigation: Monitoring and tuning required

4. **Transaction Isolation**
   - Assumption: READ COMMITTED isolation sufficient for fencing
   - Risk: Not tested with high concurrency
   - Mitigation: Design uses explicit locks, not just isolation

---

## Architectural Decisions Summary

### AD-001: Job State Machine
PENDING → RUNNING → COMPLETED/FAILED (not DONE/SUCCESS for consistency with personality_attempts)

### AD-002: Lease-Based Claiming
SELECT FOR UPDATE SKIP LOCKED, 10min lease, 3min heartbeat, claim_token UUID

### AD-003: Revision States
INDEXING → PENDING → PUBLISHED → ARCHIVED, plus REVOKED

### AD-004: Worker Database Access
Direct PostgreSQL (rejected REST API: can't do atomic claiming)

### AD-005: Worker Service Account
CREATE ROLE utc_worker WITH LOGIN, minimal grants, no DELETE

### AD-006: Scheduler Authentication
Calls Backend API POST /api/v1/ops/crawl-jobs with service account

### AD-007: Hash Format
Extracted TEXT not raw bytes (metadata changes trigger re-embed)

### AD-008: Pipeline Versioning
Keys: html-v1, pdf-v2, docx-v1 (not global version)

### AD-009: Manual Review Required
No auto-publish without approval (compliance requirement)

### AD-010: Revision Claim Token
indexing_claim_token prevents concurrent revision processing

### AD-011: Embedding Outside Transaction
Compute embeddings first, then INSERT with token verification

### AD-012: Chunk Verification
Verify count/distinct/min/max/gaps before PENDING

### AD-013: Lock Order
crawl_jobs ASC by id, then document_revisions (prevents deadlocks)

---

## Next Steps

### Immediate Actions (BLOCKED)
1. ⏸️ **AWAIT stakeholder answers** to 6 questions
2. ⏸️ Review answers and adjust design if needed
3. ⏸️ Update MASTER_CONTEXT.md with answers
4. ⏸️ Unblock Phase 1 tasks

### Implementation Phase (AFTER UNBLOCKING)
1. Create migration 005-crawler-complete.sql
2. Create migration 006-worker-role.sql
3. Review migrations with DBA
4. Apply migrations to development database
5. Verify migration with test data
6. Implement worker.py
7. Implement scheduler.py
8. Implement integration tests
9. Execute integration tests
10. Deploy to staging
11. Monitor and tune

### Success Criteria
- All 77 tasks completed with verification evidence
- Integration tests pass on real PostgreSQL
- Concurrent workers process jobs without conflicts
- No duplicate documents in production
- Revisions transition correctly through states
- Monitoring shows healthy system metrics

---

## Warnings and Constraints

### DO NOT (Until Stakeholder Approval)
- ❌ Apply migrations 005-006 to any database
- ❌ Create worker.py or scheduler.py source files
- ❌ Modify existing backend source code
- ❌ Change database schema or data
- ❌ Execute integration tests without proper environment

### DO (Safe Documentation Activities)
- ✅ Review and refine documentation
- ✅ Clarify architectural decisions
- ✅ Prepare implementation guides
- ✅ Design additional test cases
- ✅ Document risks and mitigations

### MUST (Before Implementation)
- 🔴 Obtain stakeholder answers to 6 questions
- 🔴 Review migration 005-006 SQL with DBA
- 🔴 Verify utc_worker role creation with security team
- 🔴 Confirm scheduler authentication mechanism
- 🔴 Validate rate limiting policy
- 🔴 Approve manual review workflow

---

## Verification Evidence

### Repository State Verified
- ✅ `backend/src/main/java/vn/edu/utc/rag/service/ingestion/OpsService.java` line 61-74: Job creation API
- ✅ `backend/src/main/java/vn/edu/utc/rag/service/ingestion/DocumentService.java` line 114, 268: Hash and status handling
- ✅ `backend/src/main/java/vn/edu/utc/rag/service/identity/IdentityService.java` line 55-127: Session auth
- ✅ `backend/src/main/java/vn/edu/utc/rag/config/SecurityConfiguration.java` line 38-45: Service auth
- ✅ `backend/src/main/java/vn/edu/utc/rag/persistence/repository/query/ChatQueryRepository.java` line 26-27: RAG query
- ✅ `database/init/002-schema.sql` line 468-515: Crawl tables
- ✅ `database/init/003-api-state.sql` line 26: Unique constraint pattern
- ✅ `tools/src/main.py`: 17 lines placeholder only

### Documentation Verified
- ✅ MASTER_CONTEXT.md: 645 lines, complete project state
- ✅ IMPLEMENTATION_CHECKLIST.md: 823 lines, 77 tasks with verification
- ✅ ARCHITECTURE_DECISIONS.md: 812 lines, 13 ADRs documented
- ✅ REVIEW_HISTORY.md: 437 lines, 6 review cycles
- ✅ This checkpoint: Complete and accurate as of 2026-11-20

---

## Context Recovery Protocol

If context is lost or a new session begins:

1. Read `docs/crawler/MASTER_CONTEXT.md` first
2. Read this checkpoint file
3. Read `docs/crawler/IMPLEMENTATION_CHECKLIST.md` for current task status
4. Read `docs/crawler/ARCHITECTURE_DECISIONS.md` for design rationale
5. Read `docs/crawler/REVIEW_HISTORY.md` for historical context
6. Verify actual repository state matches documentation
7. Resume from Phase 1 blocked tasks (awaiting stakeholder input)

**DO NOT**:
- Restart architecture design from scratch
- Assume unverified work is complete
- Modify code or database without following checklist
- Skip verification requirements
- Ignore blocked status on Phase 1

---

## Checkpoint Summary

**Phase**: Architecture Complete, Documentation Complete  
**Status**: BLOCKED - Awaiting stakeholder input  
**Progress**: 5/77 tasks complete (6%), 4/77 blocked (5%)  
**Files Created**: 5 documentation files (2,717 total lines)  
**Migrations Designed**: 2 (not created, not applied)  
**Tests Designed**: 3 (not implemented, not executed)  
**Architectural Decisions**: 13 ADRs documented  
**Review Cycles**: 6 iterations complete  
**Issues Resolved**: 17/20 technical issues  
**Blocking Issues**: 6 stakeholder questions  

**Next Action**: AWAIT stakeholder answers to proceed with implementation.

---

**Checkpoint Created**: 2026-11-20  
**Architecture Version**: v6 (Final)  
**Last Verified Against Repository**: 2026-11-20  
**Next Review**: After stakeholder input received

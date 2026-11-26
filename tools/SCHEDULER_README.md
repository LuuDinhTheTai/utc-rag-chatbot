# Automatic Crawler Scheduler

## Overview

The crawler scheduler automatically creates crawl jobs for enabled sources without manual intervention. This eliminates the need to manually trigger crawls via API.

## How It Works

1. **Scheduler Service** (`crawler-scheduler`): 
   - Runs continuously alongside the worker
   - Checks every 5 minutes if sources need crawling
   - Creates jobs for sources that haven't been crawled within their interval

2. **Worker Service** (`crawler-worker`):
   - Claims and processes jobs from the queue
   - Performs actual crawling, extraction, and embedding

## Configuration

### Source-Level Crawl Interval

Each source can have its own crawl interval in the `crawl_sources` table:

```sql
-- Set UTC main site to crawl every 6 hours (default)
UPDATE crawl_sources 
SET crawl_interval_hours = 6 
WHERE id = 1;

-- Set UTC2 site to crawl every 12 hours
UPDATE crawl_sources 
SET crawl_interval_hours = 12 
WHERE id = 2;

-- Set important pages to crawl every 2 hours
UPDATE crawl_sources 
SET crawl_interval_hours = 2 
WHERE id = 3;
```

**Default:** 6 hours if not specified

### Scheduler Timing

The scheduler checks for sources needing jobs every **5 minutes**. This is configured in `compose.yaml` but can be adjusted by modifying the `CrawlScheduler` initialization in `scheduler.py`:

```python
scheduler = CrawlScheduler(
    database=Database(config.database),
    default_interval_hours=6,
    check_interval_seconds=300,  # 5 minutes
)
```

## Usage

### Start Everything (Recommended)

```bash
docker compose up -d
```

This starts:
- `crawler-scheduler`: Creates jobs automatically
- `crawler-worker`: Processes jobs
- All other services (database, backend, frontend, AI)

### View Scheduler Logs

```bash
docker compose logs -f crawler-scheduler
```

Expected output:
```
crawler-scheduler-1 | Scheduler starting: checking every 300s, default interval 6h
crawler-scheduler-1 | Source 1 (UTC Main Site) has no previous jobs, scheduling
crawler-scheduler-1 | Created job 123 for source 1 (UTC Main Site)
crawler-scheduler-1 | Scheduled 1 new crawl jobs
```

### View Worker Logs

```bash
docker compose logs -f crawler-worker
```

### Check Job Status

Via API:
```bash
curl -X GET http://localhost:8080/api/v1/ops/crawl-jobs
```

Or via database:
```sql
SELECT 
    j.id,
    j.status,
    s.name as source_name,
    j.created_at,
    j.completed_at,
    j.error_message
FROM crawl_jobs j
JOIN crawl_sources s ON j.source_id = s.id
ORDER BY j.created_at DESC
LIMIT 10;
```

## Scheduling Logic

A new job is created for a source if **ALL** conditions are met:

1. ✅ Source is enabled (`enabled = true`)
2. ✅ No PENDING or RUNNING job exists for this source
3. ✅ Last completed job is older than `crawl_interval_hours`

### Example Timeline

**Source with 6-hour interval:**

- 00:00 - Scheduler creates Job #1
- 00:05 - Worker claims and starts Job #1
- 00:30 - Worker completes Job #1
- 01:00 - Scheduler checks: only 0.5h passed, skips
- 02:00 - Scheduler checks: only 1.5h passed, skips
- 06:30 - Scheduler checks: 6h passed, creates Job #2 ✅
- 06:35 - Worker claims and starts Job #2

## Manual Job Creation (Optional)

You can still create jobs manually via API if needed:

```bash
curl -X POST http://localhost:8080/api/v1/ops/crawl-jobs \
  -H "Content-Type: application/json" \
  -d '{"source_id": 1}'
```

The scheduler will skip sources with active jobs, so manual and automatic scheduling coexist safely.

## Troubleshooting

### No Jobs Being Created

1. **Check scheduler is running:**
   ```bash
   docker compose ps crawler-scheduler
   ```

2. **Check scheduler logs:**
   ```bash
   docker compose logs crawler-scheduler
   ```

3. **Verify sources are enabled:**
   ```sql
   SELECT id, name, enabled, crawl_interval_hours
   FROM crawl_sources;
   ```

4. **Check for active jobs:**
   ```sql
   SELECT source_id, status, created_at
   FROM crawl_jobs
   WHERE status IN ('PENDING', 'RUNNING');
   ```

### Jobs Created But Not Processed

This means the scheduler is working but the worker has issues:

1. **Check worker is running:**
   ```bash
   docker compose ps crawler-worker
   ```

2. **Check worker logs:**
   ```bash
   docker compose logs crawler-worker
   ```

3. **Check Ollama is accessible:**
   ```bash
   docker compose exec crawler-worker curl http://ai:11434/api/tags
   ```

### Jobs Failing Repeatedly

Check job error messages:
```sql
SELECT id, source_id, status, error_message, attempt_no
FROM crawl_jobs
WHERE status = 'FAILED'
ORDER BY completed_at DESC
LIMIT 10;
```

Failed jobs are automatically retried up to 3 times with exponential backoff (1h delay between retries).

## Architecture

```
┌─────────────────┐
│   Database      │
│  crawl_sources  │◄─────┐
│  crawl_jobs     │      │
└────────▲────────┘      │
         │               │
         │ creates       │ reads
         │ jobs          │ sources
         │               │
┌────────┴────────┐      │
│   Scheduler     │──────┘
│  (runs every    │
│   5 minutes)    │
└─────────────────┘

         ┌────────────────┐
         │   Job Queue    │
         │  (PENDING jobs)│
         └────────▲───────┘
                  │
                  │ claims jobs
                  │
         ┌────────┴───────┐
         │    Worker      │
         │  (crawls URLs, │
         │   generates    │
         │   embeddings)  │
         └────────────────┘
```

## Migration

The scheduler requires database migration `007-scheduler-support.sql` which adds:
- `crawl_sources.crawl_interval_hours` column
- `crawl_jobs.created_at` column

This migration runs automatically when you start the database container.

## Performance

- **Scheduler overhead:** Minimal - runs every 5 minutes, queries are indexed
- **Database load:** Each check runs 1-2 simple queries per enabled source
- **Recommended setup:** 
  - 1 scheduler instance (more is safe but unnecessary)
  - 1-3 worker instances depending on crawl volume

## See Also

- Worker documentation: `tools/README.md`
- Database schema: `database/init/002-schema.sql`
- Job management: `tools/src/job_manager.py`

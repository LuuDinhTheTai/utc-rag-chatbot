# Hướng dẫn Setup Crawler Tự động

## Tổng quan

Crawler hiện đã hoàn chỉnh với **scheduler tự động**:
- ✅ Scheduler tạo job định kỳ cho các nguồn được bật
- ✅ Worker xử lý job tự động
- ✅ Không cần chạy lệnh ingest thủ công
- ✅ Chỉ embed lại khi nội dung thay đổi

## Cấu trúc

```
├── crawler-scheduler  → Tạo job tự động mỗi 5 phút
├── crawler-worker     → Xử lý job từ queue
├── database           → Lưu sources, jobs, documents
├── ai (Ollama)        → Tạo embeddings
└── backend            → API quản lý (optional manual trigger)
```

## Bước 1: Khởi động hệ thống

```bash
# Khởi động tất cả services
docker compose up -d

# Kiểm tra status
docker compose ps
```

**Expected output:**
```
NAME                   STATUS          PORTS
crawler-scheduler-1    Up 10 seconds   
crawler-worker-1       Up 10 seconds   
database-1             Up 10 seconds   0.0.0.0:5432->5432/tcp
ai-1                   Up 10 seconds   0.0.0.0:11434->11434/tcp
backend-1              Up 10 seconds   0.0.0.0:8080->8080/tcp
frontend-1             Up 10 seconds   0.0.0.0:5173->5173/tcp
```

## Bước 2: Cấu hình nguồn crawl

### 2.1. Thêm nguồn mới

```sql
-- Thêm nguồn UTC main (crawl mỗi 6 giờ)
INSERT INTO crawl_sources (
    name, 
    base_url, 
    sitemap_url,
    enabled, 
    crawl_interval_hours,
    allowed_path_patterns,
    rate_limit_seconds
) VALUES (
    'UTC Tuyển sinh',
    'https://tuyensinh.utc.edu.vn',
    'https://tuyensinh.utc.edu.vn/sitemap.xml',
    true,
    6,
    ARRAY['/thong-tin-tuyen-sinh/%', '/nganh-tuyen-sinh/%', '/chi-tieu/%'],
    2.0
);

-- Thêm nguồn UTC2 (crawl mỗi 12 giờ)
INSERT INTO crawl_sources (
    name, 
    base_url,
    sitemap_url, 
    enabled, 
    crawl_interval_hours,
    rate_limit_seconds
) VALUES (
    'UTC2 Tuyển sinh',
    'https://tuyensinh.utc2.edu.vn',
    'https://tuyensinh.utc2.edu.vn/sitemap.xml',
    true,
    12,
    2.0
);
```

### 2.2. Cấu hình nguồn có sẵn

```sql
-- Xem các nguồn hiện tại
SELECT id, name, base_url, enabled, crawl_interval_hours
FROM crawl_sources
ORDER BY id;

-- Bật nguồn
UPDATE crawl_sources 
SET enabled = true, crawl_interval_hours = 6 
WHERE id = 1;

-- Tắt nguồn (scheduler sẽ bỏ qua)
UPDATE crawl_sources 
SET enabled = false 
WHERE id = 2;

-- Thay đổi tần suất crawl
UPDATE crawl_sources 
SET crawl_interval_hours = 2  -- Crawl mỗi 2 giờ
WHERE id = 1;
```

## Bước 3: Verify scheduler hoạt động

### 3.1. Xem logs

```bash
# Xem scheduler logs
docker compose logs -f crawler-scheduler

# Expected output:
# crawler-scheduler-1 | Scheduler starting: checking every 300s, default interval 6h
# crawler-scheduler-1 | Scheduler ready
# crawler-scheduler-1 | Source 1 (UTC Tuyển sinh) has no previous jobs, scheduling
# crawler-scheduler-1 | Created job 1 for source 1 (UTC Tuyển sinh)
# crawler-scheduler-1 | Scheduled 1 new crawl jobs
```

### 3.2. Xem worker logs

```bash
docker compose logs -f crawler-worker

# Expected output:
# crawler-worker-1 | Worker starting...
# crawler-worker-1 | Health checks passed
# crawler-worker-1 | Processing job 1 for source 1
# crawler-worker-1 | Crawling source 1: https://tuyensinh.utc.edu.vn
# crawler-worker-1 | Discovered 15 URLs from sitemap
# crawler-worker-1 | Job 1 completed: 15/15 successful
```

### 3.3. Kiểm tra database

```sql
-- Xem jobs được tạo
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

-- Xem documents được crawl
SELECT 
    d.id,
    d.canonical_url,
    r.title,
    r.status,
    r.created_at
FROM knowledge_documents d
JOIN document_revisions r ON r.document_id = d.id
WHERE r.status = 'PUBLISHED'
ORDER BY r.created_at DESC
LIMIT 10;

-- Xem chunks đã embed
SELECT COUNT(*) as total_chunks
FROM document_chunks;
```

## Bước 4: Test script (optional)

Chạy test script để xem scheduler sẽ làm gì:

```bash
# Vào container tools
docker compose exec crawler-worker bash

# Chạy test script
python test_scheduler_local.py
```

Script này sẽ show:
- Nguồn nào cần crawl
- Job nào đang chạy
- Thời gian còn lại đến lần crawl tiếp theo

## Timeline hoạt động

**Ví dụ với source có interval 6 giờ:**

```
00:00  Scheduler check → Tạo Job #1 (source chưa có job)
00:05  Worker claim Job #1 → Bắt đầu crawl
00:30  Worker hoàn thành Job #1
       ├─ 15 URLs crawled
       ├─ 150 chunks created
       └─ 150 embeddings generated

05:00  Scheduler check → Chưa đủ 6h, skip

06:30  Scheduler check → Đã 6h kể từ lúc hoàn thành → Tạo Job #2
06:35  Worker claim Job #2 → Crawl lại
       ├─ 10 URLs unchanged → Reuse embeddings
       ├─ 3 URLs changed → Re-embed
       └─ 2 URLs new → Embed new

12:30  Scheduler check → Tạo Job #3
...
```

## Troubleshooting

### Scheduler không tạo job

**1. Kiểm tra scheduler đang chạy:**
```bash
docker compose ps crawler-scheduler
# Should show: Up
```

**2. Xem logs:**
```bash
docker compose logs crawler-scheduler
```

**3. Kiểm tra nguồn enabled:**
```sql
SELECT id, name, enabled FROM crawl_sources;
-- enabled phải là true
```

**4. Kiểm tra có job active không:**
```sql
SELECT source_id, status FROM crawl_jobs 
WHERE status IN ('PENDING', 'RUNNING');
-- Scheduler skip source đang có job PENDING/RUNNING
```

### Worker không xử lý job

**1. Kiểm tra worker đang chạy:**
```bash
docker compose ps crawler-worker
```

**2. Xem worker logs:**
```bash
docker compose logs crawler-worker
```

**3. Kiểm tra Ollama:**
```bash
docker compose exec crawler-worker curl http://ai:11434/api/tags
# Should return list of models
```

**4. Kiểm tra database connection:**
```bash
docker compose exec crawler-worker python -c "
from src.database import Database
from src.config import load_config
db = Database(load_config().database)
db.connect()
print('✅ Database OK')
"
```

### Job thất bại liên tục

```sql
-- Xem lỗi
SELECT id, source_id, error_message, attempt_no
FROM crawl_jobs
WHERE status = 'FAILED'
ORDER BY created_at DESC
LIMIT 5;
```

Lỗi thường gặp:
- **Timeout**: Tăng `OLLAMA_TIMEOUT_SECONDS` hoặc `REQUEST_TIMEOUT_SECONDS`
- **Rate limit**: URL bị block, tăng `rate_limit_seconds` trong source
- **Robots.txt**: URL không được phép, cập nhật `allowed_path_patterns`

### Re-schedule job thủ công

```sql
-- Nếu cần crawl ngay không đợi scheduler
INSERT INTO crawl_jobs (source_id, status, run_after, attempt_no)
VALUES (1, 'PENDING', NOW(), 0);
```

## Cấu hình nâng cao

### Thay đổi tần suất check của scheduler

Mặc định scheduler check mỗi 5 phút. Để thay đổi, sửa `tools/src/scheduler.py`:

```python
scheduler = CrawlScheduler(
    database=Database(config.database),
    default_interval_hours=6,
    check_interval_seconds=600,  # 10 phút thay vì 5 phút
)
```

### Scale worker

```bash
# Chạy 3 workers song song (xử lý 3 jobs cùng lúc)
docker compose up -d --scale crawler-worker=3
```

### Tắt scheduler (chỉ manual trigger)

```bash
# Stop scheduler, giữ worker
docker compose stop crawler-scheduler

# Tạo job thủ công qua API
curl -X POST http://localhost:8080/api/v1/ops/crawl-jobs \
  -H "Content-Type: application/json" \
  -d '{"source_id": 1}'
```

## Monitoring

### Dashboard queries

```sql
-- Thống kê crawl hôm nay
SELECT 
    s.name,
    COUNT(*) FILTER (WHERE j.status = 'COMPLETED') as completed,
    COUNT(*) FILTER (WHERE j.status = 'FAILED') as failed,
    COUNT(*) FILTER (WHERE j.status = 'RUNNING') as running,
    COUNT(*) FILTER (WHERE j.status = 'PENDING') as pending
FROM crawl_sources s
LEFT JOIN crawl_jobs j ON j.source_id = s.id 
    AND j.created_at >= CURRENT_DATE
WHERE s.enabled = true
GROUP BY s.id, s.name;

-- Documents được crawl tuần này
SELECT 
    DATE_TRUNC('day', r.created_at) as date,
    COUNT(DISTINCT d.id) as new_documents,
    COUNT(*) as total_revisions
FROM document_revisions r
JOIN knowledge_documents d ON d.id = r.document_id
WHERE r.created_at >= CURRENT_DATE - INTERVAL '7 days'
GROUP BY DATE_TRUNC('day', r.created_at)
ORDER BY date DESC;

-- Top 10 documents nhiều chunks nhất
SELECT 
    d.canonical_url,
    r.title,
    COUNT(c.id) as chunk_count
FROM document_chunks c
JOIN document_revisions r ON r.id = c.revision_id
JOIN knowledge_documents d ON d.id = r.document_id
WHERE r.status = 'PUBLISHED'
GROUP BY d.id, d.canonical_url, r.title
ORDER BY chunk_count DESC
LIMIT 10;
```

## Kết luận

✅ **Scheduler hoàn chỉnh:**
- Tự động tạo job định kỳ
- Không cần manual trigger
- Chỉ re-embed khi content thay đổi

✅ **Đạt yêu cầu:**
1. Crawler tự lập lịch khi chạy Compose ✅
2. Không cần chạy lệnh ingest thủ công ✅
3. Chạy theo lịch cấu hình (mặc định 6h) ✅

Xem thêm:
- [Scheduler README](tools/SCHEDULER_README.md) - Chi tiết scheduler
- [Worker README](tools/README.md) - Chi tiết worker
- [Database Schema](database/init/002-schema.sql) - Schema details

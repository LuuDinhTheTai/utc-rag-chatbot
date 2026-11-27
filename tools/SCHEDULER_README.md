# Scheduler & Worker Tự động cho Crawler

## Tổng quan

Hệ thống crawler gồm 2 service chạy độc lập và phối hợp qua PostgreSQL bằng role giới hạn quyền (`utc_worker`):

1. **`crawler-scheduler`** (`python -m src.scheduler`):
   - Kiểm tra định kỳ (mặc định mỗi `300s` / 5 phút, cấu hình qua `SCHEDULER_CHECK_INTERVAL_SECONDS`).
   - Tự động tạo crawl job ở trạng thái `PENDING` cho các nguồn `enabled = true` khi đã quá chu kỳ `crawl_interval_hours`.
   - An toàn khi chạy nhiều instance song song nhờ partial unique index `crawl_jobs (source_id) WHERE status IN ('PENDING', 'RUNNING')` và `ON CONFLICT DO NOTHING`.

2. **`crawler-worker`** (`python -m src.main`):
   - Claim job nguyên tử bằng `FOR UPDATE SKIP LOCKED` kèm `claim_token` và `lease_until`.
   - Duy trì lease bằng background `LeaseKeeper` theo thời gian thực (`WORKER_HEARTBEAT_INTERVAL_SECONDS`, mặc định `120s`, lease mặc định `600s`).
   - Định kỳ thu hồi (reclaim) các job hết hạn lease từ worker bị treo/crash.
   - Thực hiện discovery, fetch (chống SSRF + DNS rebinding), trích xuất (HTML, PDF, DOCX), chunking, embedding qua Ollama `/api/embed` (768 chiều) và lưu vào PostgreSQL.

---

## Cấu hình

### 1. Crawl Interval theo từng nguồn

Mỗi nguồn trong `crawl_sources` có thể đặt chu kỳ riêng (đơn vị giờ, `> 0`). Nếu `NULL`, scheduler dùng `SCHEDULER_DEFAULT_INTERVAL_HOURS` (mặc định `6` giờ):

```sql
UPDATE crawl_sources
SET crawl_interval_hours = 6
WHERE id = 1;

UPDATE crawl_sources
SET crawl_interval_hours = 12
WHERE id = 2;
```

### 2. Biến môi trường chính (`.env` / `compose.yaml`)

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `POSTGRES_WORKER_USER` | `utc_worker` | Role PostgreSQL giới hạn quyền cho worker & scheduler |
| `POSTGRES_WORKER_PASSWORD` | *(bắt buộc)* | Mật khẩu của `utc_worker` |
| `SCHEDULER_CHECK_INTERVAL_SECONDS` | `300` | Chu kỳ kiểm tra nguồn của scheduler (giây) |
| `SCHEDULER_DEFAULT_INTERVAL_HOURS` | `6` | Khoảng cách mặc định giữa các lần crawl (giờ) |
| `WORKER_LEASE_DURATION_SECONDS` | `600` | Thời hạn lease của job (giây) |
| `WORKER_HEARTBEAT_INTERVAL_SECONDS` | `120` | Chu kỳ gửi heartbeat gia hạn lease (phải ≤ lease/2) |
| `JOB_MAX_ATTEMPTS` | `3` | Tổng số lần thử tối đa cho một job |
| `JOB_RETRY_BASE_DELAY_SECONDS` | `900` | Delay gốc cho retry đầu tiên (15 phút) |
| `JOB_RETRY_MAX_DELAY_SECONDS` | `21600` | Trần delay tối đa (6 giờ) |
| `JOB_RETRY_JITTER_RATIO` | `0.2` | Jitter ±20% tránh retry đồng loạt |
| `CRAWLER_RESPECT_ROBOTS` | `true` | Tuân thủ `robots.txt` và `Crawl-delay` |
| `CRAWLER_AUTO_PUBLISH` | `true` | Tự động publish (`PUBLISHED`, `source_verified=true`) và archive bản cũ ngay sau khi index xong (`false` để giữ duyệt tay `PENDING`) |
| `CRAWLER_RATE_LIMIT` | `2.0` | Rate limit mặc định giữa các request cùng domain (giây) |

---

## Logic lập lịch & Retry

### Điều kiện tạo job mới

Scheduler tạo job `PENDING` cho nguồn nếu **tất cả** điều kiện sau đúng:
1. `crawl_sources.enabled = true`.
2. Không có job `PENDING` hoặc `RUNNING` nào của nguồn đó.
3. Chưa từng có job `COMPLETED`/`FAILED` trước đó, **hoặc** khoảng thời gian từ `COALESCE(completed_at, created_at)` của job hoàn thành/thất bại gần nhất đến hiện tại (`clock_timestamp()`) ≥ `crawl_interval_hours`.

Khi worker hoàn tất job thành công, `complete_job()` luôn ghi `completed_at = clock_timestamp()`. Nhờ vậy chu kỳ crawl tiếp theo được tính chính xác từ thời điểm hoàn thành thực tế.

### Chính sách Retry & Reclaim

- `attempt_no` là số lần job đã được claim để thực thi (tăng khi `claim_job()`, hoàn lại nếu worker release do graceful shutdown).
- Khi job gặp lỗi tạm thời (mất mạng, discovery thất bại, Ollama lỗi, tất cả URL lỗi...):
  - Nếu `attempt_no < JOB_MAX_ATTEMPTS` (mặc định `3`), job quay về `PENDING` với `run_after = clock_timestamp() + delay`:
    $$\text{delay} = \min(\text{base} \times 2^{\text{attempt\_no}-1},\ \text{max}) \times (1 \pm \text{jitter})$$
    - Lần 1 thất bại → thử lại sau ~15 phút (`900s ± 20%`)
    - Lần 2 thất bại → thử lại sau ~30 phút (`1800s ± 20%`)
    - Lần 3 thất bại → chuyển sang `FAILED` vĩnh viễn và ghi `completed_at`.
  - Lỗi không thể retry (ví dụ nguồn không tồn tại, regex cấu hình sai) sẽ chuyển thẳng sang `FAILED` ngay lần đầu.
- Nguyên nhân lỗi được sanitize (che thông tin nhạy cảm, cắt gọn tối đa 2000 ký tự) và lưu vào `crawl_jobs.error_message` ở cả trường hợp retry lẫn `FAILED`.
- Job đang `RUNNING` nhưng hết hạn `lease_until` sẽ được `reclaim_expired_jobs()` thu hồi (xóa `claim_token` cũ; chuyển về `PENDING` nếu còn lượt hoặc `FAILED` nếu đã hết `JOB_MAX_ATTEMPTS`).

---

## Database Role & Migrations

### Quyền hạn của `utc_worker` (Least Privilege — Migration `008`)

| Bảng | Quyền của `utc_worker` |
|---|---|
| `crawl_sources` | `SELECT` |
| `crawl_jobs` | `SELECT, INSERT, UPDATE` |
| `knowledge_documents` | `SELECT, INSERT` |
| `document_revisions` | `SELECT, INSERT, UPDATE, DELETE` *(chỉ cleanup revision `INDEXING` do chính mình tạo)* |
| `document_chunks` | `SELECT, INSERT, DELETE` |
| Các bảng khác (`users`, `audit_events`, ...) | **Không có quyền** |

Role `utc_worker` có `LOGIN`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOREPLICATION`, `CONNECTION LIMIT 20`.

### Áp dụng Migration

**1. Volume PostgreSQL mới hoàn toàn:**
Các script trong `database/init/` (`001`..`010`) tự động chạy theo thứ tự, bao gồm `010-worker-password.sh` đặt mật khẩu cho `utc_worker` từ `POSTGRES_WORKER_PASSWORD`.

**2. Volume PostgreSQL đã tồn tại:**
`docker-entrypoint-initdb.d` **không tự chạy lại** khi volume đã có dữ liệu. Cần chạy các migration mới thủ công (tất cả đều idempotent):

```powershell
Get-Content -Raw database/init/005-crawler-complete.sql     | docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
Get-Content -Raw database/init/006-worker-role.sql          | docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
Get-Content -Raw database/init/007-scheduler-support.sql    | docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
Get-Content -Raw database/init/008-worker-permissions-fix.sql | docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
Get-Content -Raw database/init/009-scheduler-indexes.sql    | docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Sau đó đặt mật khẩu cho `utc_worker` khớp với `POSTGRES_WORKER_PASSWORD` trong `.env`:

```powershell
docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v pw="$POSTGRES_WORKER_PASSWORD" -c "ALTER ROLE utc_worker WITH LOGIN PASSWORD :'\''pw'\''"'
```

---

## Triển khai & Giám sát

### Khởi động với Docker Compose

```bash
cp .env.example .env
# Cập nhật POSTGRES_PASSWORD, POSTGRES_WORKER_PASSWORD, CURSOR_SECRET trong .env
docker compose up -d --build
```

### Xem logs

```bash
docker compose logs -f crawler-scheduler
docker compose logs -f crawler-worker
```

### Kiểm tra trạng thái Crawl Jobs

```sql
SELECT
    j.id,
    j.status,
    s.base_url AS source_url,
    j.attempt_no,
    j.created_at,
    j.run_after,
    j.lease_until,
    j.completed_at,
    j.error_message
FROM crawl_jobs j
JOIN crawl_sources s ON j.source_id = s.id
ORDER BY j.created_at DESC
LIMIT 20;
```

### Kiểm tra danh sách nguồn

```sql
SELECT id, base_url, enabled, crawl_interval_hours, rate_limit_seconds, sitemap_url
FROM crawl_sources
ORDER BY id;
```

### Kiểm tra trạng thái Document Revisions

Các trạng thái revision hợp lệ: `INDEXING`, `PENDING`, `PUBLISHED`, `ARCHIVED`, `REVOKED`.

```sql
SELECT status, COUNT(*)
FROM document_revisions
GROUP BY status;

-- Kiểm tra revision bị kẹt ở INDEXING quá 1 giờ
SELECT id, document_id, crawl_job_id, updated_at
FROM document_revisions
WHERE status = 'INDEXING'
  AND updated_at < NOW() - INTERVAL '1 hour';
```

### Khắc phục sự cố nhanh

1. **Scheduler không tạo job:** kiểm tra `docker compose logs crawler-scheduler`, xác nhận đã chạy migration `007`..`009`, kiểm tra `crawl_sources.enabled = true` và không có job `PENDING`/`RUNNING` đang treo.
2. **Worker không xử lý job:** kiểm tra `docker compose ps crawler-worker`, `docker compose logs crawler-worker`, xác nhận `utc_worker` đã được đặt mật khẩu và Ollama đang healthy (`docker compose exec ai ollama list`).
3. **Job `FAILED` liên tục:** xem cột `crawl_jobs.error_message` để biết nguyên nhân chi tiết (SSRF blocked, robots.txt disallowed/unavailable, HTTP timeout, site unreachable, lỗi regex cấu hình).

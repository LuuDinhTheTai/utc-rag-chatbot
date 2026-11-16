# Kiến trúc UTC RAG Chatbot

## Phạm vi

Scaffold chỉ có cấu hình hạ tầng và mã khởi động. Chưa có giao diện chatbot,
API nghiệp vụ, logic RAG/crawler, authentication, dữ liệu mẫu
hoặc thuật toán xử lý tài liệu. Database đã được khởi tạo theo ERD v2 gồm 48 bảng;
xem [hướng dẫn database](../database/README.md). Chưa có công cụ migration tự động.

```mermaid
flowchart TD
    Browser[Trình duyệt] --> Frontend[frontend:5173 - React / Vite]
    Frontend -->|proxy /api| Backend[backend:8080 - Spring Boot]
    Backend <--> Database[(database:5432 - PostgreSQL 17 + pgvector)]
    Backend <--> AI[ai:11434 - Ollama]
    Tools[tools - Python] <--> Database
    Tools <--> AI
    Tools --> UTC[Website tuyển sinh UTC]
```

Các mũi tên biểu diễn hướng kết nối khi phát triển. Hiện chưa có lời gọi API,
truy vấn database, client Ollama hoặc crawling.

## Năm services

| Service | Nền tảng và cấu hình | Vai trò tương lai |
| --- | --- | --- |
| `frontend` | React + TypeScript + Vite; tiêu đề tối thiểu, proxy `/api` | Website |
| `backend` | Java 21 + Spring Boot 3.5 + JDBC PostgreSQL | API và điều phối RAG |
| `tools` | Python 3.12; tiến trình chờ tín hiệu dừng | Crawler, xử lý và đồng bộ tài liệu |
| `database` | `pgvector/pgvector:0.8.7-pg17-trixie` | Dữ liệu nghiệp vụ và embeddings |
| `ai` | Một container `ollama/ollama:latest` | SLM và embedding chạy local |

Backend chưa có controller, repository hoặc AI client. JDBC đã có datasource
nhưng chưa có truy vấn. Tools chưa cài thư viện crawler/database/AI; thêm các
dependencies khi triển khai tính năng. Vite dùng làm development server local.

## Network và environment

Tất cả services dùng network Compose `utc`, driver `bridge`, phân giải DNS bằng
tên service. Không dùng `localhost` để gọi container khác. Network không đặt
`internal: true` để Tools có thể truy cập website UTC và Ollama tải model.
Chỉ frontend/backend publish cổng lên loopback `127.0.0.1` của host.

| Biến | Ý nghĩa |
| --- | --- |
| `FRONTEND_PORT`, `BACKEND_PORT` | Cổng host, mặc định 5173 và 8080 |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Database và credentials backend/tools |
| `SPRING_DATASOURCE_URL` | Compose tạo JDBC URL dùng `database:5432` |
| `POSTGRES_HOST`, `POSTGRES_PORT` | Tools dùng `database`, `5432` |
| `BACKEND_PROXY_TARGET` | Vite chuyển `/api` tới `http://backend:8080` |
| `OLLAMA_BASE_URL` | Backend/tools dùng `http://ai:11434` |
| `SLM_MODEL` | `qwen3:0.6b` |
| `EMBEDDING_MODEL` | `embeddinggemma:300m-qat-q4_0` |
| `EMBEDDING_DIMENSION` | Compose cố định 768 cho backend/tools |
| `UTC_SOURCE_URL` | Website tuyển sinh UTC; chưa có truy cập |

Trình duyệt sẽ gọi đường dẫn tương đối `/api` qua Vite proxy, không gọi hostname
Docker trực tiếp. Các biến nội bộ không được nhúng vào bundle frontend.
`.env` phục vụ Compose interpolation, không commit; `.env.example` là mẫu.

## Lưu trữ và khởi động

- `postgres_data`: `/var/lib/postgresql/data` cho PostgreSQL 17.
- `ollama_data`: `/root/.ollama`, lưu chung cả hai model.
- Thư mục SQL init mount read-only, bật extension `vector` và tạo 48 bảng theo ERD v2,
  không seed dữ liệu. Chỉ chạy tự động khi volume database rỗng; volume hiện có
  cần áp dụng schema một lần theo hướng dẫn database.
- Backend/Tools đợi database và AI healthy. Frontend đợi backend được khởi động;
  `service_started` không bảo đảm backend đã sẵn sàng nhận HTTP.
- PostgreSQL healthcheck dùng `pg_isready`; Ollama dùng `ollama list`.
  Healthcheck không kiểm tra đã tải model hoặc đã bật extension.
- Pull model thủ công vào chính `ai`, không thêm service thứ sáu. Mặc định CPU.
- Không mount source, không scheduler, không tự tải model. Build lại sau khi sửa mã.

Dimension 768 được cố định ở cột `document_chunks.embedding vector(768)`;
chưa có mã tạo embedding. Cần giữ thống nhất model/dimension khi phát triển.
Tag Ollama `latest` có thể thay đổi; nhóm có thể pin tag/digest sau khi xác nhận
phiên bản hoạt động. Runtime local sau khi tải images/dependencies/models;
crawler tương lai vẫn cần Internet để đọc website UTC.

## Tham khảo

- [pgvector Docker và extension](https://github.com/pgvector/pgvector)
- [EmbeddingGemma Q4](https://ollama.com/library/embeddinggemma:300m-qat-q4_0)
- [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)

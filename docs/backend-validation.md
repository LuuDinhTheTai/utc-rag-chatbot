# Kiểm tra triển khai backend — 2026-11-17

Refactor kiến trúc: `ArchitectureTest` bảo vệ ranh giới service/repository;
không còn `Db`, `JpaStore`, `SqlWrite`. Backend đã build lại và đạt trạng thái
healthy trên Docker. Chi tiết: [backend persistence](backend-persistence.md).

- Docker build Java 21 / Spring Boot 3.5.7 thành công.
- JUnit: **24 tests, 0 failures, 0 errors, 0 skipped**.
- 11 tests tích hợp MockMvc chạy trên PostgreSQL/pgvector thật trong database
  `utc_rag_backend_test_<random>` riêng; runner đã xóa database kiểm thử.
- 13 tests unit/contract/architecture kiểm tra Ollama HTTP client, kiểu JSON và đối chiếu
  **112 thao tác / 84 đường dẫn** với OpenAPI trong repository.
- HTTP runtime: frontend 200, backend readiness 200/UP, OpenAPI 200;
  cookie phiên có tên UTC_SESSION, thao tác ghi thiếu CSRF trả 403 CSRF_INVALID.
- Ollama runtime: qwen3:0.6b trả câu trả lời không rỗng;
  embeddinggemma:300m-qat-q4_0 tạo vector đúng 768 chiều. Hai model dùng cùng container.
- Docker Compose có đúng 5 services, đã khởi động cả 5; database/backend/AI healthy.

Trong kiểm thử tích hợp, Ollama được mock để thử lỗi/atomicity/idempotency một cách
xác định; bước thử model thật bên trên kiểm tra kết nối và khả năng inference,
không đánh giá độ chính xác tư vấn tuyển sinh trên bộ dữ liệu UTC.

Schema API còn thiếu trong tài liệu được tự xác lập theo yêu cầu của người dùng.
Các quyết định này và phạm vi công thức/chấm điểm xem [backend README](../backend/README.md).
Worker Tools cho crawler và index PDF chưa thuộc phần backend: job nhận vào vẫn
chờ worker xử lý thực tế, không có tác vụ hoặc dữ liệu mẫu được giả báo thành công.

Đã chuyển sang Spring Data JPA: Hibernate validate đủ 48 entity; repository CRUD, IdClass và vector 768 chiều round-trip thành công. Native SQL chạy qua EntityManager trong cùng JPA transaction.

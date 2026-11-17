# UTC RAG Backend

Java 21 · Spring Boot 3.5 · Spring Security · Spring Data JPA/Hibernate · PostgreSQL 17/pgvector · Ollama.
Backend triển khai 112 thao tác trên 84 đường dẫn trong hai tab đặc tả được cung cấp.
Schema request/response chưa có tệp nguồn đi kèm được xác lập trong
[`openapi.json`](src/main/resources/static/openapi.json), theo yêu cầu tự suy luận.
Đây là hợp đồng của mã hiện tại, cần nhóm duyệt trước khi tích hợp frontend.

## Cấu trúc backend

Backend tổ chức **theo tầng ở cấp ngoài, theo nghiệp vụ bên trong mỗi tầng**.
Ví dụ, muốn sửa quản trị tài khoản thì bắt đầu tại `controller/administration`,
đi tiếp tới `service/administration` và repository tương ứng trong `persistence`.

```text
backend/
├── pom.xml
├── Dockerfile
├── scripts/test.ps1
└── src/
    ├── main/
    │   ├── java/vn/edu/utc/rag/
    │   │   ├── UtcRagApplication.java
    │   │   ├── controller/            # HTTP endpoints, status, header, cookie
    │   │   │   ├── administration/
    │   │   │   ├── catalog/
    │   │   │   ├── chat/
    │   │   │   ├── editorial/
    │   │   │   ├── identity/
    │   │   │   ├── ingestion/
    │   │   │   ├── personality/
    │   │   │   └── scoring/
    │   │   ├── service/               # Nghiệp vụ và transaction
    │   │   │   ├── administration/
    │   │   │   ├── audit/
    │   │   │   ├── catalog/
    │   │   │   ├── chat/
    │   │   │   ├── editorial/
    │   │   │   ├── identity/
    │   │   │   ├── ingestion/
    │   │   │   ├── personality/
    │   │   │   └── scoring/
    │   │   ├── persistence/           # Toàn bộ truy cập database
    │   │   │   ├── entity/            # Entity và khóa ghép JPA
    │   │   │   ├── repository/        # Spring Data JPA interfaces
    │   │   │   │   └── query/         # Custom *QueryRepository
    │   │   │   ├── query/             # Cursor, QueryPage, CampusScope
    │   │   │   └── internal/          # NativeSqlExecutor
    │   │   ├── security/              # Phiên, quyền theo cơ sở, rate limit
    │   │   ├── config/                # Spring beans, JSON, Spring Security
    │   │   │   └── properties/        # OllamaProperties, RateLimitProperties
    │   │   ├── domain/
    │   │   │   └── editorial/         # Metadata chung của nghiệp vụ phiên bản
    │   │   ├── dto/                   # Dữ liệu trả về và kết quả nghiệp vụ
    │   │   │   ├── administration/
    │   │   │   ├── chat/
    │   │   │   ├── common/
    │   │   │   └── ingestion/
    │   │   ├── mapper/                # Chuyển đổi dữ liệu, không truy cập DB
    │   │   │   ├── administration/
    │   │   │   ├── common/
    │   │   │   └── editorial/
    │   │   ├── integration/           # Adapter thư viện và dịch vụ bên ngoài
    │   │   │   ├── ollama/
    │   │   │   └── pdf/
    │   │   ├── validation/            # Kiểm tra và chuẩn hóa đầu vào
    │   │   ├── exception/             # ApiException, GlobalExceptionHandler
    │   │   ├── web/
    │   │   │   ├── filter/            # Request ID
    │   │   │   └── pagination/        # Chuyển request thành QueryPage
    │   │   └── common/
    │   │       ├── http/              # ETag và kiểm tra If-Match
    │   │       └── json/              # JSON codec
    │   └── resources/
    │       ├── application.yaml
    │       └── static/openapi.json
    └── test/java/vn/edu/utc/rag/        # Integration, contract và architecture tests
```

### Ranh giới trách nhiệm

| Tầng | Trách nhiệm |
| --- | --- |
| Controller | Nhận request, gọi service, đặt HTTP status/Location/ETag và tạo response |
| Service | Kiểm tra luật nghiệp vụ/quyền, điều phối giao dịch và gọi repository trực tiếp cho CRUD |
| Repository | Truy cập database; ưu tiên derived query, dùng custom query cho truy vấn đặc thù |
| Security | Xác định người dùng, quyền theo cơ sở, CSRF và giới hạn tần suất |
| DTO/Mapper | Giữ hợp đồng dữ liệu và chuyển đổi entity/response, không gọi repository |
| Integration | Gọi Ollama, đọc PDF và dựng nội dung PDF; không tạo HTTP response |
| Config | Khởi tạo bean và đọc biến cấu hình |
| Validation/Exception/Web | Chuẩn hóa đầu vào, ánh xạ lỗi HTTP, filter và ngữ cảnh phân trang |

Service không trả `ResponseEntity` và không phụ thuộc controller. Controller không
truy cập repository hoặc trực tiếp sử dụng PDFBox/Ollama. Persistence không phụ
thuộc service/controller. `ArchitectureTest` kiểm tra các ranh giới này.

Các service hiện vẫn nhận `HttpServletRequest` cho phiên và quyền để giữ hành vi
API hiện có. Một số response linh hoạt vẫn là projection dạng `Map` qua
`EntityMapper`; response quản trị tài khoản dùng DTO có kiểu.

### Những luồng tiêu biểu

- Quản trị: `AdminController → AdminService → UsersRepository/RoleAssignmentsRepository`;
  `AccountMapper` tạo response DTO.
- Tài liệu: `DocumentsController → ProgramService/DocumentService`; controller quản
  lý HTTP response, `PdfInspector` kiểm tra định dạng PDF.
- Chat: `ChatController → ChatService → ChatPersistenceService`;
  `OllamaClient` nằm trong `integration/ollama`.
- Báo cáo: `PersonalityController → PersonalityReportService → PersonalityPdfRenderer`;
  service kiểm tra quyền, renderer tạo byte PDF, controller đặt header tải xuống.
- Điểm: `ScoringController → ScoreRuleService/ScoringService`;
  tra cứu quy tắc tách khỏi tính điểm.

Không thêm interface service chỉ để có một implementation. Chỉ tách service mới
khi có trách nhiệm riêng, ranh giới giao dịch riêng hoặc có thể tái sử dụng rõ ràng.
Chi tiết JPA/native query nằm trong [backend-persistence.md](../docs/backend-persistence.md).


## Khởi động

Từ thư mục gốc repository, tạo `.env` theo `.env.example`; đặt password và
`CURSOR_SECRET` ngẫu nhiên tối thiểu 32 ký tự. Với database đã init ERD v2,
áp dụng **một lần** hai file bổ sung, theo thứ tự:

```powershell
docker compose up -d database
Get-Content -Raw database/init/003-api-state.sql | docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
Get-Content -Raw database/init/004-public-views.sql | docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
docker compose up -d --build backend frontend tools
docker compose exec ai ollama pull qwen3:0.6b
docker compose exec ai ollama pull embeddinggemma:300m-qat-q4_0
```

Volume mới tự chạy toàn bộ `database/init/*.sql`. Không chạy lại file đã áp dụng;
DDL cố ý báo lỗi thay vì bỏ qua schema khác biệt. Không xóa volume để nâng cấp.
Các file 003/004 đã được áp dụng vào database local trong lượt triển khai này.

- API: `http://localhost:8080/api/v1`
- OpenAPI 3.1: `http://localhost:8080/openapi.json`
- Liveness: `/actuator/health/liveness`
- Readiness gồm kiểm tra database: `/actuator/health/readiness`
- Frontend gọi `/api/v1` cùng origin qua Vite proxy. Chưa xây giao diện ứng dụng.

## Phiên, CSRF và quyền

Gọi `GET /api/v1/auth/csrf` bằng client giữ cookie. Response có `token` và
`header_name`. Gửi `X-CSRF-TOKEN` cùng cookie `UTC_SESSION` cho mọi thao tác ghi.
Sau đăng ký, đăng nhập hoặc đăng xuất, lấy token mới. Cookie HttpOnly/SameSite=Lax;
`SESSION_COOKIE_SECURE=false` chỉ phục vụ HTTP loopback local.

Đăng ký tạo STUDENT, không tạo tài khoản mặc định. Để có ADMIN đầu tiên, đăng ký
một tài khoản rồi chạy script bootstrap bằng tài khoản PostgreSQL local:

```powershell
Get-Content -Raw database/scripts/bootstrap-admin.sql | docker compose exec -T database sh -c 'psql -v ON_ERROR_STOP=1 -v email="$1" -U "$POSTGRES_USER" -d "$POSTGRES_DB"' sh 'your-email@example.com'
```

Đăng nhập lại sau bootstrap. ADMIN không tự có quyền biên tập/duyệt; cấp riêng
STAFF_EDITOR, STAFF_REVIEWER khi cần. TECH_OPERATOR và ADMIN chỉ cấp toàn trường.
Khóa tài khoản hoặc sửa quyền tăng auth_epoch và ghi audit trong cùng transaction.
Không thể loại ADMIN toàn trường hoạt động cuối cùng.

Phiên lưu trong bộ nhớ servlet và mất khi backend restart. Phiên khách có hạn tuyệt
đối 24 giờ; không tự chuyển lịch sử khách vào tài khoản sau đăng nhập. Các tài nguyên
khách cũ vẫn truy cập qua thông tin phiên khách được giữ đến thời điểm hết hạn cũ.

## Quy ước API

- ID số trong JSON/URL là chuỗi thập phân; tài khoản/hội thoại/lần làm dùng UUID.
- JSON snake_case, thời điểm RFC 3339, điểm là chuỗi thập phân.
- Danh sách ngoài trả `{data, pagination: {next_cursor, has_more}}`; limit 20, tối đa 100.
  Cursor có HMAC và gắn route/người gọi/bộ lọc; thứ tự có khóa phụ ổn định.
- Lỗi `application/problem+json` có `code`, `request_id`, `errors`; response có
  `X-Request-ID`. Không trả SQL, stacktrace, password hash hoặc đường dẫn lưu PDF.
- GET bản nháp trả ETag `"version-N"`; GET publication trả `"publication-N"`.
  PUT tương ứng bắt buộc If-Match: thiếu → 428, cũ → 412.
- Một phiên bản PUBLISHED mỗi danh tính được bảo vệ bằng unique partial index.
- Chỉ nguồn đã xác minh và chưa thu hồi được dùng trong API công khai/phép tính.
  RAG còn yêu cầu tài liệu đang PUBLISHED. Thu hồi nguồn giữ lịch sử và tự loại
  nội dung phụ thuộc qua các view `api_*`.

## Các quyết định về phần đặc tả còn thiếu

1. **Biên tập:** năm nhận là `year` cho đợt/hướng dẫn/quy tắc; khóa nhập học dùng
   `entry_year`. Bản nháp là toàn bộ nội dung, arrays lồng được thay thế trong PUT.
   AdmissionDraft có `methods`, `choices[].options[].combinations/cutoffs`;
   các request chi tiết xem OpenAPI.
2. **Trắc nghiệm:** cấu hình `scoring_config` dùng
   `{"strategy":"WEIGHTED_SUM","profiles":{"PROFILE_CODE":{"dimension":"1.00"}}}`.
   Mỗi lựa chọn có `score_weights` ánh xạ dimension sang chuỗi điểm. Cộng điểm
   từng dimension rồi nhân trọng số từng profile; chọn điểm cao nhất, hòa thì
   theo profile_code tăng dần. Không có cấu hình hợp lệ thì trả 422.
   Bộ đề đã xuất bản và danh mục cơ sở/ngành/môn/phương thức do quy trình quản trị
   dữ liệu nạp vào PostgreSQL; hợp đồng hiện tại không có endpoint CRUD các danh mục đó.
3. **Quy đổi:** chỉ chạy `SUM`, `WEIGHTED_SUM`, `SUM_BANDS` của bản đã duyệt.
   `formula_config` hỗ trợ weights, offset, minimum, maximum, decimal_places.
   Các hệ số/giới hạn là chuỗi thập phân. Dải đóng ở hai đầu; dải chồng lấn hoặc
   thiếu dải khớp trả 422. Không có eval hoặc công thức tuyển sinh tự bịa.
   So sánh năm trước yêu cầu cùng cơ sở/ngành/phương thức/đợt/tổ hợp/thang điểm.
4. **Tài liệu:** PDF tối đa 20 MiB được kiểm tra bằng PDFBox và lưu vào volume
   `document_uploads`. API trả 202 INDEXING. Tools phụ trách extraction/chunk/embedding
   và chuyển sang PENDING; worker Tools/crawler vẫn chưa được triển khai trong task backend.
   Chỉ reviewer có phạm vi phù hợp xuất bản tài liệu PENDING có chunk 768 chiều.
   URL nguồn phải HTTPS dưới utc.edu.vn hoặc utc2.edu.vn.
5. **Crawler:** API tạo job PENDING từ nguồn đã cấu hình, không fetch URL từ request.
   Job cùng nguồn đang chờ/chạy trả 409 kèm Location của job hiện tại. Tools cần claim
   job bằng lease/claim_token và cập nhật trạng thái; API không giả báo job thành công.
6. **RAG:** cosine pgvector, tối đa 3 đoạn có khoảng cách < 0.6, mỗi đoạn 3000 ký tự.
   LLM dùng các đoạn này; không đủ nguồn trả INSUFFICIENT_EVIDENCE. Không gọi AI
   trong transaction ghi tin nhắn. Khóa hội thoại và kiểm tra lại nguồn trước khi
   lưu cặp USER/ASSISTANT. Cùng client_message_id/nội dung trả lại lượt đã lưu;
   đổi nội dung trả 409. Không ghi lượt dang dở nếu AI lỗi.
7. **Vận hành:** rate limiter trong bộ nhớ, không dùng X-Forwarded-For chưa xác minh.
   Sau Vite proxy, hạn mức IP nhìn thấy địa chỉ proxy. Khi triển khai ngoài local
   cần cấu hình trusted proxy và kho session/rate limit dùng chung.

## Kiểm thử

```powershell
powershell -ExecutionPolicy Bypass -File backend/scripts/test.ps1
```

Runner tạo database `utc_rag_backend_test_<random>` riêng, init schema, build
test image Java 21 rồi chạy JUnit/MockMvc trên PostgreSQL thật và xóa test database
trong finally. Không ghi fixture vào database phát triển. Ollama được mock ở các
kiểm thử tích hợp để tái lập lỗi, chiều vector và RAG mà không yêu cầu tải model.
Unit tests của OllamaClient dùng HTTP server giả lập, không gọi dịch vụ thật.
`ContractTest` đối chiếu 112 thao tác controller với OpenAPI.

Lệnh `mvn test` thông thường chỉ chạy unit/contract tests; nhóm kiểm thử database
chỉ bật khi `UTC_RUN_DB_TESTS=true`, có datasource đến tên database chứa `backend_test_`.
Không được coi lượt test đã skip nhóm này là đã kiểm chứng đầy đủ nghiệp vụ.

## Nguồn thiết kế

- [Sáu nhóm chức năng](https://docs.google.com/document/d/1kbIAin0fkSU_GHSKbgv844z5YnWNPqhKnPp23NAzFNc/edit?tab=t.3hyjgk32le47)
- [Thiết kế REST API](https://docs.google.com/document/d/1kbIAin0fkSU_GHSKbgv844z5YnWNPqhKnPp23NAzFNc/edit?tab=t.r0fv9kz01vjb)
- [Spring Security 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)
- [Spring Boot 3.5 health endpoints](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)

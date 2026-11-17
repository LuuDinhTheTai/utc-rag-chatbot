# Cấu trúc backend

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

## Ranh giới trách nhiệm

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

## Những luồng tiêu biểu

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
Chi tiết JPA/native query nằm trong [backend-persistence.md](backend-persistence.md).

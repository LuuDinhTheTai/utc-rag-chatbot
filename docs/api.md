# Thiết kế REST API – UTC RAG Chatbot

**Công nghệ/đặc tả:** Spring Boot · OpenAPI 3.1.0  
**Tiền tố API:** `/api/v1`

Hợp đồng cho sáu nhóm chức năng, dựa trên ERD v2 và kiến trúc Docker Local.

> Frontend gọi đường dẫn tương đối qua Vite proxy. Spring Boot thực thi quyền và nghiệp vụ. Backend đọc PostgreSQL và gọi Ollama. Tools xử lý đồng bộ qua PostgreSQL bằng tài khoản dịch vụ riêng.

## 1. Mô hình tài nguyên

| Tài nguyên | Quan hệ và cách dùng |
|---|---|
| Tài khoản, hội thoại | Một tài khoản hoặc phiên khách sở hữu hội thoại. Hội thoại có tin nhắn và trích dẫn. Tin nhắn trợ lý có tối đa một phản hồi. |
| Tuyển sinh | Đợt và phiên bản chứa phương thức, lựa chọn ngành, chỉ tiêu, tổ hợp môn và điểm chuẩn công bố. |
| Đào tạo | Ngành có chương trình theo cơ sở và khóa. Phiên bản khung gắn danh sách học phần, tín chỉ và chuẩn đầu ra. |
| Trắc nghiệm | Bộ đề có phiên bản. Lần làm gắn một phiên bản và nhóm kết quả. Nhóm kết quả ánh xạ sang ngành gợi ý. |
| Quy đổi điểm | Quy tắc theo năm, cơ sở và phương thức. Phiên bản đã duyệt chứa trường nhập và dải quy đổi. |
| Hướng dẫn và tài chính | Phiên bản hướng dẫn có mục checklist. Mốc lịch thuộc đợt hoặc hướng dẫn. Chính sách học phí, học bổng dùng chung. |
| Tài liệu và RAG | Nguồn và tác vụ sinh phiên bản tài liệu. Phiên bản có các đoạn index. Nội dung công khai gắn nguồn đã xác minh. |

### Quy ước dữ liệu

- ID PostgreSQL `bigint` dùng chuỗi thập phân trong JSON và URL.
- ID tài khoản, hội thoại và lần làm là UUID.
- Tên trường JSON dùng `snake_case`, URL dùng `kebab-case`.
- Thời điểm dùng RFC 3339.
- Điểm dùng chuỗi thập phân để giữ độ chính xác khi tính.
- GET công khai chỉ lấy dữ liệu đã xuất bản và thuộc nguồn đã xác minh.
- Dữ liệu có nguồn bị thu hồi bị loại khỏi API công khai, RAG và phép tính.
- Mỗi mục nghiệp vụ hoặc tài liệu có tối đa một phiên bản hiện hành.

## 2. Phiên đăng nhập và phân quyền

| Vai trò | Phạm vi và quyền |
|---|---|
| Khách | Xem dữ liệu công khai, gửi chat và phản hồi, làm trắc nghiệm tạm, quy đổi điểm. |
| `STUDENT` | Có quyền công khai và quản lý lịch sử, kết quả trắc nghiệm của chính tài khoản. |
| `STAFF_EDITOR` | Tạo và sửa bản nháp, nộp tài liệu theo cơ sở được giao. Danh mục ngành yêu cầu quyền toàn trường. |
| `STAFF_REVIEWER` | Đọc bản nháp, xác minh, xuất bản và thu hồi trong phạm vi cơ sở được giao. |
| `TECH_OPERATOR` | Theo dõi và tạo tác vụ đồng bộ từ nguồn đã cấu hình. Quyền vận hành cấp toàn trường. |
| `ADMIN` | Quản lý tài khoản và cấp quyền toàn trường. Cấp thêm vai trò biên tập hoặc duyệt riêng khi cần. |

### Cơ chế phiên và CSRF

1. `GET /auth/csrf` cấp hoặc khôi phục phiên khách và trả token kèm tên header.
2. Gửi cookie `UTC_SESSION` và header `X-CSRF-TOKEN` cho các request `POST`, `PUT`, `PATCH`, `DELETE` dùng phiên.
3. Spring Security xoay mã phiên sau đăng ký hoặc đăng nhập.
4. Sau đăng nhập hoặc đăng xuất, lấy lại token trước lần ghi tiếp theo.
5. Cookie là `HttpOnly`, `SameSite=Lax`, sử dụng `Secure` qua HTTPS. Cấu hình HTTP chỉ dùng trên loopback để phát triển.
6. React và Backend dùng cùng origin qua proxy.
7. Không đưa ID phiên, mật khẩu, khóa xác thực hay nội dung nhạy cảm vào response hoặc nhật ký.
8. Backend kiểm tra quyền theo thao tác, cơ sở và chủ sở hữu. `campus_code = null` trong quyền được cấp là toàn trường. Vai trò có thể kiêm nhiệm.
9. Mọi tài khoản chỉ đọc hội thoại và kết quả riêng của mình.
10. Thay đổi quyền hoặc khóa tài khoản tăng `auth_epoch` và ghi audit trong cùng giao dịch.
11. Phiên khách và dữ liệu tạm mặc định hết hạn sau 24 giờ.
12. Khi đăng nhập, các hội thoại và lần làm đã có của khách tiếp tục theo chính sách hết hạn của phiên cũ. Lịch sử tài khoản bắt đầu từ lúc đăng nhập.

## 3. Hợp đồng endpoint

Mỗi endpoint dưới đây dùng đường dẫn tương đối sau tiền tố `/api/v1`. Tất cả danh sách cấp ngoài dùng cursor và limit. Dấu `*` trong bộ lọc biểu thị tham số bắt buộc.

### 3.1. Tài khoản và phiên

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| GET | `/auth/csrf` | 200 | Lấy CSRF token và khởi tạo phiên khách khi cần. Công khai · Trả `CsrfToken`. |
| POST | `/auth/accounts` | 201 | Đăng ký tài khoản sinh viên. Phiên + CSRF · Gửi `Credentials` · Trả `SessionAccount`. |
| POST | `/auth/sessions` | 200 | Đăng nhập. Phiên + CSRF · Gửi `Credentials` · Trả `SessionAccount`. |
| DELETE | `/auth/sessions/current` | 204 | Đăng xuất. Tài khoản (chủ sở hữu). |
| GET | `/me` | 200 | Xem tài khoản và quyền hiện tại. Tài khoản (chủ sở hữu) · Trả `SessionAccount`. |
| GET | `/campuses` | 200 | Tra cứu cơ sở UTC. Công khai · Trả `CampusPage`. |

### 3.2. Chatbot AI

Một POST tin nhắn trả cả câu hỏi lẫn câu trả lời và trích dẫn. Nếu không đủ nguồn, vẫn trả câu trả lời với `INSUFFICIENT_EVIDENCE` và danh sách trích dẫn rỗng. Lưu cặp tin nhắn trong một giao dịch sau khi AI xử lý thành công. Dùng lại `client_message_id` để thử lại sau lỗi.

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| POST | `/conversations` | 201 | Tạo hội thoại mới. Phiên khách hoặc tài khoản (chủ sở hữu) · Gửi `ConversationCreate` · Trả `Conversation`. |
| GET | `/me/conversations` | 200 | Tìm và xem lịch sử hội thoại. Tài khoản (chủ sở hữu) · Trả `ConversationPage` · Lọc `q`. |
| GET | `/conversations/{conversation_id}` | 200 | Mở hội thoại. Phiên khách hoặc tài khoản (chủ sở hữu) · Trả `Conversation`. |
| DELETE | `/conversations/{conversation_id}` | 204 | Xóa hội thoại. Phiên khách hoặc tài khoản (chủ sở hữu). |
| GET | `/conversations/{conversation_id}/messages` | 200 | Xem tin nhắn và trích dẫn. Phiên khách hoặc tài khoản (chủ sở hữu) · Trả `MessagePage`. |
| POST | `/conversations/{conversation_id}/messages` | 201/200 | Gửi câu hỏi và nhận câu trả lời. Phiên khách hoặc tài khoản (chủ sở hữu) · Gửi `ChatMessageCreate` · Trả `ChatTurn`. |
| GET | `/conversations/{conversation_id}/messages/{message_id}` | 200 | Xem một tin nhắn. Phiên khách hoặc tài khoản (chủ sở hữu) · Trả `Message`. |
| PUT | `/conversations/{conversation_id}/messages/{message_id}/feedback` | 204 | Ghi hoặc sửa đánh giá câu trả lời. Phiên khách hoặc tài khoản (chủ sở hữu) · Gửi `Feedback`. |
| GET | `/chat-suggestions` | 200 | Xem câu hỏi gợi ý theo chủ đề. Công khai · Trả `ChatSuggestionPage` · Lọc `topic`. |

### 3.3. Tuyển sinh

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| GET | `/admission-cycles` | 200 | Tra cứu đợt tuyển sinh. Công khai · Trả `AdmissionCyclePage` · Lọc `campus_code`, `year`, `round_code`. |
| GET | `/admission-cycles/{cycle_id}` | 200 | Xem đợt tuyển sinh. Công khai · Trả `AdmissionCycle`. |
| GET | `/admission-cycles/{cycle_id}/methods` | 200 | Tra cứu phương thức và điều kiện. Công khai · Trả `AdmissionMethodPage`. |
| GET | `/admission-cycles/{cycle_id}/options` | 200 | Tra cứu ngành mở tuyển và chỉ tiêu. Công khai · Trả `AdmissionOptionPage` · Lọc `program_code`, `method_code`, `q`. |
| GET | `/admission-options/{option_id}/subject-combinations` | 200 | Tra cứu tổ hợp môn theo lựa chọn xét tuyển. Công khai · Trả `SubjectCombinationPage`. |
| GET | `/admission-cutoffs` | 200 | Tra cứu điểm chuẩn đã công bố. Công khai · Trả `AdmissionCutoffPage` · Lọc `campus_code`, `year`, `round_code`, `program_code`, `method_code`, `combination_code`, `scale_code`. |

### 3.4. Chương trình đào tạo

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| GET | `/programs` | 200 | Tra cứu ngành, chuyên ngành. Công khai · Trả `ProgramPage` · Lọc `campus_code`, `year`, `field_code`, `q`. |
| GET | `/programs/{program_id}` | 200 | Xem thông tin ngành. Công khai · Trả `Program`. |
| GET | `/program-offerings` | 200 | Tra cứu chương trình theo cơ sở và khóa. Công khai · Trả `ProgramOfferingPage` · Lọc `campus_code`, `year`, `program_id`. |
| GET | `/program-offerings/{offering_id}/curriculum` | 200 | Xem khung đào tạo đã công bố. Công khai · Trả `Curriculum`. |
| GET | `/program-offerings/{offering_id}/courses` | 200 | Xem học phần và tín chỉ. Công khai · Trả `CurriculumCoursePage` · Lọc `curriculum_id`, `group`, `term`. |

### 3.5. Khám phá tính cách

Cố định phiên bản bộ đề ngay khi tạo lần làm. Lưu dở và chấm điểm là hai thao tác. Backend kiểm tra câu hỏi, lựa chọn và số câu trước khi chấm. PDF chỉ xuất cho kết quả đã lưu của chủ tài khoản.

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| GET | `/questionnaires` | 200 | Chọn bộ trắc nghiệm đã công bố. Công khai · Trả `QuestionnairePage`. |
| GET | `/questionnaires/{questionnaire_id}/questions` | 200 | Đọc câu hỏi và lựa chọn. Công khai · Trả `QuestionPage` · Lọc `version_id`*. |
| POST | `/personality-attempts` | 201 | Bắt đầu một lần trắc nghiệm. Phiên khách hoặc tài khoản (chủ sở hữu) · Gửi `AttemptCreate` · Trả `Attempt`. |
| GET | `/personality-attempts/{attempt_id}` | 200 | Xem trạng thái lần làm. Phiên khách hoặc tài khoản (chủ sở hữu) · Trả `Attempt`. |
| GET | `/personality-attempts/{attempt_id}/questions` | 200 | Tải câu hỏi theo phiên bản đã gắn với lần làm. Phiên khách hoặc tài khoản (chủ sở hữu) · Trả `QuestionPage`. |
| PUT | `/personality-attempts/{attempt_id}/answers` | 204 | Lưu toàn bộ đáp án tạm thời. Phiên khách hoặc tài khoản (chủ sở hữu) · Gửi `AttemptAnswers`. |
| POST | `/personality-attempts/{attempt_id}/result` | 201 | Chấm điểm và tạo kết quả. Phiên khách hoặc tài khoản (chủ sở hữu) · Trả `PersonalityResult`. |
| GET | `/personality-attempts/{attempt_id}/result` | 200 | Xem kết quả trắc nghiệm. Phiên khách hoặc tài khoản (chủ sở hữu) · Trả `PersonalityResult`. |
| GET | `/personality-attempts/{attempt_id}/recommendations` | 200 | Xem ngành gợi ý và lý do. Phiên khách hoặc tài khoản (chủ sở hữu) · Trả `RecommendationPage`. |
| PUT | `/personality-attempts/{attempt_id}/saved-result` | 204 | Lưu kết quả đã hoàn tất vào tài khoản. Tài khoản (chủ sở hữu). |
| GET | `/me/personality-results` | 200 | Xem lịch sử kết quả đã lưu. Tài khoản (chủ sở hữu) · Trả `PersonalityResultPage`. |
| GET | `/personality-attempts/{attempt_id}/report` | 200 | Xuất PDF kết quả đã lưu. Tài khoản (chủ sở hữu) · Trả tệp PDF. |

### 3.6. Quy đổi điểm

Form trả `version_id` và kiểu đầu vào. Backend xác minh điều kiện, thang điểm và phạm vi của từng đầu vào. Gửi lại form khi gặp `RULE_VERSION_CHANGED`. Khi so sánh năm liền trước, chọn cùng cơ sở, ngành, đợt, phương thức và thang điểm, kèm tổ hợp nếu áp dụng. Nếu thiếu mốc tương thích, trả lý do và điểm đã tính.

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| GET | `/score-rules` | 200 | Tìm quy tắc theo năm, cơ sở và phương thức. Công khai · Trả `ScoreRulePage` · Lọc `campus_code`*, `year`*, `method_code`. |
| GET | `/score-rules/{rule_id}/form` | 200 | Lấy phiên bản và biểu mẫu điểm đã duyệt. Công khai · Trả `ScoreForm`. |
| POST | `/score-calculations` | 200 | Tính điểm và đối chiếu điểm chuẩn khi yêu cầu. Công khai · Gửi `ScoreCalculation` · Trả `ScoreCalculationResult`. |

### 3.7. Hướng dẫn và chính sách

Dùng chung nguồn học phí, học bổng giữa tuyển sinh và tân sinh viên. Checklist, lịch và liên hệ lọc theo phạm vi phù hợp. Tra cứu hồ sơ cá nhân chuyển sang hệ thống được UTC cho phép.

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| GET | `/guides` | 200 | Tra cứu hướng dẫn theo phạm vi và đối tượng. Công khai · Trả `GuidePage` · Lọc `campus_code`, `year`, `type`, `cycle_id`, `method_code`, `audience_code`, `q`. |
| GET | `/guides/{guide_id}` | 200 | Xem nội dung hướng dẫn. Công khai · Trả `Guide`. |
| GET | `/guides/{guide_id}/entries` | 200 | Xem checklist, bước thực hiện và đầu mối liên hệ. Công khai · Trả `GuideEntryPage`. |
| GET | `/schedule-events` | 200 | Tra cứu lịch tuyển sinh và nhập học. Công khai · Trả `ScheduleEventPage` · Lọc `campus_code`, `year`, `scope`, `cycle_id`, `guide_id`, `from_date`, `to_date`. |
| GET | `/financial-policies` | 200 | Tra cứu học phí và học bổng. Công khai · Trả `FinancialPolicyPage` · Lọc `campus_code`, `year`, `type`, `program_offering_id`. |
| GET | `/financial-policies/{policy_id}` | 200 | Xem chính sách và nguồn công bố. Công khai · Trả `FinancialPolicy`. |

### 3.8. Biên tập và xuất bản

Năm nhóm dưới đây dùng cùng hợp đồng phiên bản và xuất bản. Mỗi nhóm có URL và kiểu dữ liệu cụ thể trong OpenAPI.

| Base path | Kiểu tạo mới | Kiểu bản nháp |
|---|---|---|
| `/editorial/admission-cycles` | `CycleCreate` | `AdmissionDraft` |
| `/editorial/program-offerings` | `OfferingCreate` | `CurriculumDraft` |
| `/editorial/guide-items` | `GuideCreate` | `GuideDraft` |
| `/editorial/financial-policies` | `FinanceCreate` | `FinanceDraft` |
| `/editorial/score-rules` | `RuleCreate` | `ScoreRuleDraft` |

Các thao tác dùng chung:

| Method | Đường dẫn | Thành công | Mô tả |
|---|---|---:|---|
| GET | `{base}` | 200 | Liệt kê mục theo năm, cơ sở và phạm vi vai trò. Có phân trang. |
| POST | `{base}` | 201 | EDITOR tạo danh tính nghiệp vụ. ID công khai chỉ đọc được sau khi có bản đã duyệt. |
| GET | `{base}/{item_id}` | 200 | EDITOR hoặc REVIEWER xem mục và phiên bản đang công khai. |
| GET | `{base}/{item_id}/versions` | 200 | EDITOR hoặc REVIEWER xem các bản theo trạng thái. Có phân trang. |
| POST | `{base}/{item_id}/versions` | 201 | EDITOR gửi bản nháp và nhận `Location`, `ETag`. |
| GET | `{base}/{item_id}/versions/{version_id}` | 200 | EDITOR hoặc REVIEWER đọc bản chi tiết và `ETag`. |
| PUT | `{base}/{item_id}/versions/{version_id}` | 200 | EDITOR gửi toàn bộ nội dung nháp với `If-Match`. |
| GET | `{base}/{item_id}/publication` | 200 | EDITOR hoặc REVIEWER đọc trạng thái công khai và `ETag`. |
| PUT | `{base}/{item_id}/publication` | 200 | REVIEWER gửi `version_id` hoặc `null`, kèm `If-Match`. Backend đối chiếu nguồn và phạm vi trong cùng giao dịch. |

### 3.9. Quản lý tài liệu RAG

Tài liệu mới dùng `multipart/form-data` với PDF tối đa 20 MiB. Phiên bản đi từ `INDEXING` đến `PENDING` khi đã lập đủ chunk. Nguồn chính thức và phạm vi được kiểm tra trước khi công bố. Gỡ bản công khai giữ lịch sử đã xác minh. Thu hồi nguồn loại cả dữ liệu phụ thuộc.

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| POST | `/editorial/programs` | 201 | Tạo ngành hoặc chuyên ngành trong danh mục. `STAFF_EDITOR` theo cơ sở · Gửi `ProgramCreate` · Trả `Program`. |
| GET | `/editorial/programs/{program_id}` | 200 | Xem ngành trong danh mục biên tập. EDITOR hoặc REVIEWER theo cơ sở · Trả `Program`. |
| GET | `/editorial/documents` | 200 | Xem tài liệu theo phạm vi. EDITOR hoặc REVIEWER theo cơ sở · Trả `EditorialDocumentPage` · Lọc `campus_code`, `year`, `q`. |
| POST | `/editorial/document-revisions` | 202 | Nộp PDF và tạo phiên bản tài liệu. `STAFF_EDITOR` theo cơ sở · Gửi `DocumentUpload` · Trả `DocumentRevision`. |
| GET | `/editorial/document-revisions/{revision_id}` | 200 | Xem trạng thái xử lý tài liệu. EDITOR hoặc REVIEWER theo cơ sở · Trả `DocumentRevision`. |
| GET | `/editorial/documents/{document_id}/revisions` | 200 | Xem lịch sử phiên bản của tài liệu. EDITOR hoặc REVIEWER theo cơ sở · Trả `DocumentRevisionPage` · Lọc `campus_code`, `year`, `status`. |
| GET | `/editorial/documents/{document_id}/publication` | 200 | Xem bản tài liệu RAG đang công khai. EDITOR hoặc REVIEWER theo cơ sở · Trả `PublicationState`. |
| PUT | `/editorial/documents/{document_id}/publication` | 200 | Đặt hoặc gỡ bản tài liệu công khai khỏi RAG. `STAFF_REVIEWER` theo cơ sở · Gửi `PublicationChange` · Trả `PublicationState`. |
| PUT | `/editorial/document-revisions/{revision_id}/revocation` | 200 | Thu hồi nguồn tài liệu đã xác minh. `STAFF_REVIEWER` theo cơ sở · Gửi `SourceRevocation` · Trả `DocumentRevision`. |

### 3.10. Vận hành crawler

Tạo job từ `source_id` trong danh mục nguồn đã cấu hình. POST trả `202`, `Location` trỏ trạng thái để polling. Nếu cùng nguồn đã có job chờ hoặc đang chạy, hiển thị job hiện tại sau `409`. Crawler lỗi giữ bản dữ liệu đã công khai.

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| GET | `/ops/crawl-sources` | 200 | Xem nguồn crawler đã cấu hình. `TECH_OPERATOR` theo phạm vi vận hành · Trả `CrawlSourcePage`. |
| GET | `/ops/crawl-jobs` | 200 | Theo dõi tác vụ đồng bộ. `TECH_OPERATOR` theo phạm vi vận hành · Trả `CrawlJobPage` · Lọc `source_id`, `status`. |
| POST | `/ops/crawl-jobs` | 202 | Yêu cầu chạy crawler cho nguồn có sẵn. `TECH_OPERATOR` theo phạm vi vận hành · Gửi `CrawlJobCreate` · Trả `CrawlJob`. |
| GET | `/ops/crawl-jobs/{job_id}` | 200 | Xem trạng thái một tác vụ đồng bộ. `TECH_OPERATOR` theo phạm vi vận hành · Trả `CrawlJob`. |

### 3.11. Quản trị tài khoản

ADMIN toàn trường quản lý vai trò và tài khoản. Gán hoặc thu hồi quyền cập nhật phiên qua `auth_epoch`. Giữ tối thiểu một ADMIN toàn trường hoạt động.

| Method | Endpoint | Thành công | Mô tả / quyền |
|---|---|---:|---|
| GET | `/admin/accounts` | 200 | Tra cứu tài khoản. ADMIN toàn trường · Trả `AccountPage` · Lọc `status`, `q`. |
| GET | `/admin/accounts/{account_id}` | 200 | Xem tài khoản cần quản trị. ADMIN toàn trường · Trả `Account`. |
| PATCH | `/admin/accounts/{account_id}` | 200 | Khóa hoặc mở tài khoản. ADMIN toàn trường · Gửi `AccountStatus` · Trả `Account`. |
| GET | `/admin/accounts/{account_id}/role-assignments` | 200 | Xem vai trò được cấp. ADMIN toàn trường · Trả `RoleAssignmentPage` · Lọc `campus_code`. |
| POST | `/admin/accounts/{account_id}/role-assignments` | 201 | Cấp vai trò và phạm vi. ADMIN toàn trường · Gửi `RoleAssignmentCreate` · Trả `RoleAssignment`. |
| GET | `/admin/role-assignments/{assignment_id}` | 200 | Xem một quyền đã cấp. ADMIN toàn trường · Trả `RoleAssignment`. |
| DELETE | `/admin/role-assignments/{assignment_id}` | 204 | Thu hồi quyền. ADMIN toàn trường. |

## 4. Phân trang, lỗi và tiến hóa API

### 4.1. Danh sách và lọc

- Danh sách trả `data` và `pagination` gồm `{next_cursor, has_more}`.
- Mặc định `limit = 20`, tối đa `100`.
- Cursor mờ gắn với người gọi, bộ lọc và thứ tự.
- Đổi bộ lọc thì tải lại từ trang đầu.
- Cursor sai trả `400`. Trang cuối có `next_cursor = null`.
- Thứ tự luôn có khóa ổn định để xử lý các bản ghi trùng thời điểm.
- Danh sách lịch sử sắp xếp theo `updated_at DESC, id DESC`.
- Tin nhắn theo `position ASC`.
- Trình tự câu hỏi, học phần, các mốc và checklist dùng thứ tự nghiệp vụ kèm khóa duy nhất.
- API nguồn công khai trả URL, tiêu đề, năm và thời điểm cập nhật.
- Trích dẫn của hội thoại lưu URL, tiêu đề, năm và vị trí.

### 4.2. Định dạng lỗi

Lỗi dùng `application/problem+json` theo RFC 9457. `type` là URI ổn định. `code` cho biết nhánh lỗi, `request_id` giúp tra nhật ký và `errors` ghi các trường cần sửa. API luôn áp dụng quyền theo đối tượng trước khi trả chi tiết lỗi.

| HTTP | Code | Type | Ý nghĩa |
|---:|---|---|---|
| 400 | `BAD_REQUEST` | `/api/problems/bad-request` | JSON, tham số hoặc cursor sai cú pháp. |
| 401 | `UNAUTHENTICATED` | `/api/problems/unauthenticated` | Phiên không hợp lệ hoặc thông tin đăng nhập sai. |
| 403 | `FORBIDDEN` | `/api/problems/forbidden` | Thiếu quyền hoặc CSRF token không hợp lệ. |
| 404 | `NOT_FOUND` | `/api/problems/not-found` | Không tìm thấy tài nguyên trong phạm vi được phép. |
| 405 | `METHOD_NOT_ALLOWED` | `/api/problems/method-not-allowed` | Phương thức HTTP không được hỗ trợ cho tuyến này. |
| 409 | `CONFLICT` | `/api/problems/conflict` | Trạng thái xung đột hoặc dữ liệu đã đổi phiên bản. |
| 412 | `PRECONDITION_FAILED` | `/api/problems/precondition-failed` | ETag đã cũ. Đọc lại tài nguyên trước khi ghi. |
| 413 | `TOO_LARGE` | `/api/problems/payload-too-large` | Kích thước yêu cầu vượt giới hạn. |
| 415 | `UNSUPPORTED_MEDIA` | `/api/problems/unsupported-media-type` | Định dạng yêu cầu hoặc tệp không được hỗ trợ. |
| 422 | `VALIDATION_FAILED` | `/api/problems/validation-error` | Dữ liệu hợp lệ về cú pháp nhưng vi phạm quy tắc nghiệp vụ. |
| 428 | `PRECONDITION_REQUIRED` | `/api/problems/precondition-required` | Thiếu `If-Match` cho cập nhật có kiểm soát phiên bản. |
| 429 | `RATE_LIMITED` | `/api/problems/rate-limited` | Vượt hạn mức. Thử lại sau `Retry-After`. |
| 500 | `INTERNAL_ERROR` | `/api/problems/internal-error` | Không thể hoàn tất yêu cầu do lỗi hệ thống. |
| 503 | `SERVICE_UNAVAILABLE` | `/api/problems/service-unavailable` | Dịch vụ phụ thuộc tạm thời không sẵn sàng. |
| 504 | `UPSTREAM_TIMEOUT` | `/api/problems/upstream-timeout` | Dịch vụ AI không phản hồi kịp thời. |

Các mã lỗi chi tiết:

- `403 CSRF_INVALID`: token thiếu hoặc sai.
- `403 ROLE_REQUIRED`: đã xác thực nhưng thiếu quyền của thao tác.
- `404`: ID nằm ngoài phạm vi hoặc tài nguyên chưa công khai.
- `409 RULE_VERSION_CHANGED`: yêu cầu tải form lại.
- `409 CLIENT_MESSAGE_ID_CONFLICT`: ID tin nhắn đã được dùng với nội dung khác.

### 4.3. Giới hạn tần suất và cache

Giới hạn theo tài khoản hoặc phiên và IP cho đăng nhập, chat, quy đổi, tải nguồn và tạo job. `429` trả `Retry-After`.

Mức khởi điểm đề xuất cho local:

- 5 lần đăng nhập thất bại mỗi 15 phút theo tài khoản và IP.
- 10 lượt chat mỗi phút mỗi phiên.
- 30 lần tính điểm mỗi phút mỗi IP.

Điều chỉnh bằng cấu hình sau đo tải. Dữ liệu riêng và phiên trả `Cache-Control: no-store`.

### 4.4. Phiên bản API

- Thêm trường phản hồi hoặc tham số tùy chọn trong `/api/v1`.
- Thay đổi kiểu, quyền, trường bắt buộc hoặc hành vi đã cam kết dùng `/api/v2`.
- Đề xuất chạy song song tối thiểu 90 ngày trước khi tắt phiên bản cũ, kèm hướng dẫn chuyển đổi.
- Khi công bố ngừng hỗ trợ, dùng `Deprecation`, `Sunset` và `Link` theo RFC.

Tài liệu tham chiếu: RFC 9457 – Problem Details; RFC 9745 – Deprecation.

## 5. Ví dụ gửi và nhận

Các mã, điểm và tài liệu bên dưới là mẫu hợp đồng để kiểm thử.

### 5.1. Hỏi chatbot

`POST /api/v1/conversations/{conversation_id}/messages` → `201`

**Request**

```json
{
  "client_message_id": "c6450505-931d-47a5-8019-c882883f6612",
  "content": "Mình xem phương thức xét tuyển ở đâu?"
}
```

**Response**

```json
{
  "user_message": {
    "id": "c6450505-931d-47a5-8019-c882883f6612",
    "author": "USER",
    "content": "Mình xem phương thức xét tuyển ở đâu?",
    "created_at": "2026-11-16T03:00:00Z",
    "answer_status": null,
    "citations": []
  },
  "assistant_message": {
    "id": "9b5d375b-a2af-4d0c-8e3f-6b34f46688ad",
    "author": "ASSISTANT",
    "content": "Bạn có thể xem các phương thức trong thông báo tuyển sinh.",
    "created_at": "2026-11-16T03:00:00Z",
    "answer_status": "ANSWERED",
    "citations": [
      {
        "title": "Thông báo minh họa",
        "url": "https://utc-rag.example/sample/notice",
        "year": 2026
      }
    ]
  }
}
```

Thiếu nguồn trả `answer_status = INSUFFICIENT_EVIDENCE`. Thử lại với cùng `client_message_id` và cùng nội dung trả `200` kèm lượt đã lưu. Khi lỗi nguồn AI hoặc hết thời gian chờ, trả `503` hoặc `504` để client thử lại.

### 5.2. Tính điểm và so sánh

`POST /api/v1/score-calculations` → `200`

**Request**

```json
{
  "rule_version_id": "42",
  "admission_option_id": "88",
  "combination_code": "A00",
  "compare_previous_year": true,
  "inputs": [
    {
      "input_code": "THPT_MATH",
      "numeric_value": "8.50"
    },
    {
      "input_code": "REGION",
      "choice_code": "KV2"
    }
  ]
}
```

**Response**

```json
{
  "rule_version_id": "42",
  "total": "24.50",
  "scale_code": "SCALE_30",
  "parts": [
    {
      "kind": "BASE",
      "label": "Điểm cơ sở minh họa",
      "score": "24.50"
    }
  ],
  "comparison": {
    "status": "NO_COMPARABLE_CUTOFF",
    "reason": "NO_MATCHING_COMBINATION"
  },
  "source": {
    "title": "Thông báo minh họa",
    "url": "https://utc-rag.example/sample/notice",
    "year": 2026,
    "updated_at": "2026-11-16T03:00:00Z"
  },
  "disclaimer": "Kết quả chỉ để tham khảo. Đây là dữ liệu minh họa cho hợp đồng API."
}
```

### 5.3. Xuất bản

`PUT /api/v1/editorial/admission-cycles/8/publication` → `200`

**Request header**

```http
If-Match: "publication-3"
```

**Request body**

```json
{
  "version_id": "42"
}
```

**Response**

```json
{
  "version_id": "42",
  "published_at": "2026-11-16T03:00:00Z"
}
```

Reviewer đọc ETag từ GET cùng URI và gửi `If-Match`. Thiếu `If-Match` trả `428`. ETag đã cũ trả `412` và client đọc lại trạng thái trước khi thử lại.

### 5.4. Lỗi trường đầu vào

HTTP `422` · `application/problem+json`

```json
{
  "type": "https://utc-rag.example/api/problems/validation-error",
  "title": "Validation Failed",
  "status": 422,
  "code": "VALIDATION_FAILED",
  "detail": "Dữ liệu hợp lệ về cú pháp nhưng vi phạm quy tắc nghiệp vụ.",
  "request_id": "74ebdd1f-a9c1-49c1-bd13-510f8ec64d31",
  "errors": [
    {
      "field": "inputs[0].numeric_value",
      "code": "OUT_OF_RANGE",
      "message": "Giá trị vượt phạm vi cho phép của công thức."
    }
  ]
}
```

## 6. Đối chiếu tính năng và kiểm tra

### 6.1. Chatbot AI

| Mã chức năng | Endpoint liên quan |
|---|---|
| 1.1 | `POST /conversations/{conversation_id}/messages` |
| 1.2 | `GET /me/conversations` · `GET /conversations/{conversation_id}` |
| 1.3 | `POST /conversations` |
| 1.4 | `GET /me/conversations` · `GET /conversations/{conversation_id}` |
| 1.5 | `GET /chat-suggestions` |
| 1.6 | `GET /conversations/{conversation_id}/messages` · `POST /conversations/{conversation_id}/messages` |
| 1.7 | `PUT /conversations/{conversation_id}/messages/{message_id}/feedback` |

### 6.2. Tuyển sinh

| Mã chức năng | Endpoint liên quan |
|---|---|
| 2.1 | `GET /admission-cycles` · `GET /admission-cycles/{cycle_id}` |
| 2.2 | `GET /admission-cycles/{cycle_id}/methods` |
| 2.3 | `GET /admission-cycles/{cycle_id}/options` |
| 2.4 | `GET /admission-options/{option_id}/subject-combinations` |
| 2.5 | `GET /guides` · `GET /guides/{guide_id}` |
| 2.6 | `GET /schedule-events` |
| 2.7 | `GET /admission-cutoffs` |
| 2.8 | `GET /financial-policies` · `GET /financial-policies/{policy_id}` |
| 2.9 | `PUT /editorial/admission-cycles/{item_id}/publication` · `POST /editorial/document-revisions` |

### 6.3. Chương trình đào tạo

| Mã chức năng | Endpoint liên quan |
|---|---|
| 3.1 | `GET /programs` · `GET /programs/{program_id}` |
| 3.2 | `GET /programs` · `GET /program-offerings` |
| 3.3 | `GET /programs/{program_id}` · `GET /program-offerings/{offering_id}/curriculum` |
| 3.4 | `GET /program-offerings/{offering_id}/courses` |
| 3.5 | `GET /program-offerings/{offering_id}/curriculum` |
| 3.6 | `POST /editorial/program-offerings/{item_id}/versions` · `PUT /editorial/program-offerings/{item_id}/publication` |

### 6.4. Khám phá tính cách

| Mã chức năng | Endpoint liên quan |
|---|---|
| 4.1 | `GET /questionnaires` · `GET /questionnaires/{questionnaire_id}/questions` |
| 4.2 | `GET /questionnaires/{questionnaire_id}/questions` · `GET /personality-attempts/{attempt_id}/questions` |
| 4.3 | `POST /personality-attempts/{attempt_id}/result` · `GET /personality-attempts/{attempt_id}/result` |
| 4.4 | `GET /personality-attempts/{attempt_id}/recommendations` |
| 4.5 | `GET /personality-attempts/{attempt_id}` · `GET /personality-attempts/{attempt_id}/result` |

### 6.5. Quy đổi điểm

| Mã chức năng | Endpoint liên quan |
|---|---|
| 5.1 | `GET /score-rules` · `GET /score-rules/{rule_id}/form` |
| 5.2 | `GET /score-rules/{rule_id}/form` · `POST /score-calculations` |
| 5.3 | `GET /score-rules/{rule_id}/form` · `POST /score-calculations` |
| 5.4 | `GET /score-rules/{rule_id}/form` · `POST /score-calculations` |
| 5.5 | `GET /score-rules/{rule_id}/form` · `POST /score-calculations` |
| 5.6 | `POST /score-calculations` |
| 5.7 | `POST /score-calculations` |

### 6.6. Tân sinh viên

| Mã chức năng | Endpoint liên quan |
|---|---|
| 6.1 | `GET /guides` · `GET /guides/{guide_id}` |
| 6.2 | `GET /guides` · `GET /guides/{guide_id}/entries` |
| 6.3 | `GET /schedule-events` |
| 6.4 | `GET /financial-policies` · `GET /financial-policies/{policy_id}` |
| 6.5 | `GET /guides` · `GET /guides/{guide_id}` |
| 6.6 | `GET /guides` · `GET /guides/{guide_id}` |
| 6.7 | `GET /guides` · `GET /guides/{guide_id}` |

### 6.7. Thông tin về hợp đồng OpenAPI và kiểm thử được ghi trong tài liệu

- Tệp hợp đồng máy đọc được được tài liệu gốc gọi tên là `utc-rag-chatbot-openapi.yaml`.
- OpenAPI có 112 thao tác trên 84 đường dẫn.
- Các tham chiếu tính năng trong `x-features` bao phủ 41 mục của tab Tính năng.
- Redocly đã lint OpenAPI 3.1 với 0 lỗi và 1 cảnh báo metadata giấy phép.
- Đã kiểm tra 38 ví dụ theo schema.
- Prism chạy đạt 12 ca mock, gồm chat có và thiếu nguồn, gửi lại lượt chat, đăng ký lần trắc nghiệm, quy đổi lỗi đầu vào, xem lịch, tạo job và xuất bản.

### 6.8. Ca nghiệm thu khi triển khai Spring Boot

- Hội thoại của tài khoản khác trả `404`.
- Phiên khách hết hạn trả `401` sau khi cookie mất hiệu lực.
- Yêu cầu ghi với CSRF sai trả `403`.
- `STAFF_EDITOR` thiếu quyền duyệt nhận `403`.
- `STAFF_REVIEWER` ngoài cơ sở được giao nhận `404`.
- Phiên cũ mất hiệu lực sau khi thu hồi quyền hoặc khóa tài khoản.
- Thu hồi nguồn làm mất khả dụng dữ liệu phụ thuộc ở cả API công khai, RAG và quy đổi điểm.
- Hai bản công khai đồng thời phải bị chặn.
- Gửi lại cùng ID chat không tạo tin nhắn trùng.
- Lần trắc nghiệm đã tạo tiếp tục dùng đúng phiên bản đề.

---

## Ghi chú về phạm vi chuyển đổi

Tài liệu PDF API có nhắc đến tệp OpenAPI máy đọc được `utc-rag-chatbot-openapi.yaml`, nhưng tệp YAML này không nằm trong hai PDF được cung cấp. Vì vậy, nội dung Markdown này chuyển đổi phần đặc tả hiển thị trong PDF; không tự tạo thêm các schema OpenAPI chi tiết không có trong nguồn.

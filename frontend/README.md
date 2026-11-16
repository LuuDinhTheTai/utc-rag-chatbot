# UTC Frontend

React + TypeScript + Vite. Dashboard Navy Blue / trắng, dùng ảnh UTC do người dùng cung cấp tại `public/utc-logo.webp`.

## Chạy local

```powershell
cd frontend
npm ci
npm run dev
```

Mở http://localhost:5173. Backend phải chạy tại `http://127.0.0.1:8080`. Mọi request dùng đường dẫn tương đối `/api/v1`, cookie same-origin và Vite proxy. Nếu backend ở địa chỉ khác:

```powershell
$env:BACKEND_PROXY_TARGET = 'http://127.0.0.1:8081'
npm run dev
```

Docker Compose hiện có đã đặt `BACKEND_PROXY_TARGET=http://backend:8080`, không cần sửa backend hoặc compose. Production cần reverse proxy `/api` đến backend cùng origin; `vite preview` không thay thế cấu hình triển khai này.

## Giai đoạn

1. **Đã triển khai:** dashboard responsive; điều hướng tổng quan, đợt tuyển sinh, ngành đào tạo, lịch; lọc cơ sở/năm; tìm ngành theo tên/mã; liên kết nguồn; tải thêm bằng cursor; trạng thái tải/rỗng/lỗi và thử lại. Không dùng mock hoặc thống kê giả.
2. **Đã triển khai:** phiên khách/CSRF, đăng ký/đăng nhập/đăng xuất, chatbot, lịch sử có tìm kiếm/tải thêm, trích dẫn, phản hồi và xóa hội thoại có xác nhận.
3. **Đã triển khai:** chi tiết chương trình/học phần, phương thức/chỉ tiêu/tổ hợp/điểm chuẩn, trắc nghiệm và PDF, quy đổi/đối chiếu điểm, hướng dẫn/checklist và tài chính.
4. **Đã triển khai:** biên tập và duyệt 5 nhóm nội dung, danh mục ngành, tài liệu RAG, quản trị tài khoản/phân quyền, vận hành crawler.

Dừng sau mỗi giai đoạn, chỉ tiếp tục khi người dùng yêu cầu.

## API giai đoạn 1

| Endpoint | Sử dụng |
| --- | --- |
| `GET /api/v1/campuses` | Danh sách cơ sở, tải thêm nếu cần |
| `GET /api/v1/admission-cycles` | Đợt tuyển sinh; `campus_code`, `year` |
| `GET /api/v1/programs` | Ngành; `campus_code`, `year`, `q` |
| `GET /api/v1/schedule-events` | Lịch; `campus_code`, `year` |

Đọc đúng `{ data, pagination: { next_cursor, has_more } }` từ `Pages.java`. Giữ ID dạng chuỗi. Lịch theo thứ tự backend (ngày bắt đầu tăng dần), không mặc định gọi là “sắp tới”. Đổi bộ lọc sẽ hủy request cũ và xóa cursor. Nguồn chỉ mở URL HTTP(S), không render HTML từ dữ liệu.

## Kiểm tra

```powershell
npm run build
```

Kiểm tra thủ công với backend thật: các danh sách rỗng; dữ liệu đã xuất bản; lọc cơ sở/năm; tìm ngành; tải thêm; thay đổi bộ lọc khi đang tải; dừng backend và thử lại; menu trên màn hình nhỏ; điều hướng bàn phím; mở liên kết nguồn. Không cần Ollama cho giai đoạn 1.

## Giai đoạn 2

Mở **Trợ lý tuyển sinh AI** trong menu. Nhấn khu vực tài khoản ở góc phải để đăng nhập/đăng ký. Khách vẫn gửi được câu hỏi; lịch sử tài khoản chỉ gọi khi đã đăng nhập. Hội thoại khách không tự nhập vào lịch sử tài khoản. Frontend giữ hội thoại đang mở trong bộ nhớ khi chuyển màn; tải lại trang sẽ mở khung chat mới (người đăng nhập có thể mở lại qua lịch sử).

API thật đã nối: `GET /auth/csrf`, `GET /me`, `POST /auth/accounts`, `POST /auth/sessions`, `DELETE /auth/sessions/current`, `GET /chat-suggestions`, `POST /conversations`, `GET /me/conversations`, `GET /conversations/{id}`, `GET/POST /conversations/{id}/messages`, `DELETE /conversations/{id}`, `PUT /conversations/{id}/messages/{message_id}/feedback`, cùng tiền tố `/api/v1`.

- Cookie HttpOnly do backend quản lý; không lưu mật khẩu hoặc token trong localStorage. CSRF chỉ giữ trong bộ nhớ, xóa sau thay đổi phiên, lấy lại trước lần ghi tiếp theo.
- Không tự gửi lại request ghi. Nếu lỗi gửi chat, nút “Thử gửi lại” dùng nguyên nội dung và `client_message_id`. Không chỉnh câu hỏi đang chờ xác nhận; có thể bắt đầu hội thoại mới.
- Hội thoại được tải hết các trang tin nhắn trước khi cho gửi tiếp. API trả nguyên câu trả lời, không dùng streaming giả. Trạng thái `INSUFFICIENT_EVIDENCE` hiển thị riêng; không tự tạo nguồn hoặc gợi ý.
- Đổi tài khoản/hết phiên hủy tải chat và xóa dữ liệu riêng đang hiển thị; làm mới danh sách/cursor theo phiên mới. Nội dung chat hiển thị dạng văn bản, liên kết trích dẫn chỉ chấp nhận HTTP(S).
- Đánh giá có chọn hữu ích/chưa hữu ích, góp ý tùy chọn, thông báo lưu thành công; API hiện không trả đánh giá cũ nên frontend không giả định trạng thái đánh giá sau khi mở lại.

Kiểm thử lớp HTTP (Node 22.18+ hoặc Node 24):

```powershell
node --experimental-strip-types --test tests/client.test.mjs
```

Các test cô lập transport để kiểm tra CSRF đồng thời, làm mới token, không tự gửi lại request ghi, giữ payload khi thử lại, sự kiện hết phiên và hủy request. Frontend chạy thực tế không dùng mock API.

Kiểm tra tích hợp khi backend chạy: đăng ký/đăng nhập sai và đúng; đăng xuất; gửi chat khách/tài khoản; chọn gợi ý; tìm/mở lịch sử; hội thoại trên 100 tin; thử gửi lại sau mất kết nối; đánh giá và sửa đánh giá; hủy/xác nhận xóa; hết phiên; chuyển tài khoản. Khi không có tài liệu đã xác minh, backend trả câu trả lời thiếu nguồn; khi có tài liệu, cần Ollama và model theo cấu hình backend. Chưa kiểm thử end-to-end trong phiên triển khai vì cổng backend 8080 không hoạt động.

## Giai đoạn 3

- **Ngành đào tạo:** tìm theo tên/mã/lĩnh vực; mở ngành, chọn chương trình theo cơ sở/khóa; xem thời lượng, tín chỉ, mục tiêu, chuẩn đầu ra, nghề nghiệp; bảng học phần lọc nhóm/học kỳ và tải thêm.
- **Tuyển sinh:** mở đợt để xem phương thức/điều kiện, ngành mở tuyển, chỉ tiêu, tổ hợp môn, hướng dẫn hồ sơ và lịch. Điểm chuẩn có bộ lọc ngành, phương thức, đợt, tổ hợp và thang điểm ngoài cơ sở/năm.
- **Khám phá tính cách:** chọn bộ đề đã công bố, đọc hướng dẫn, bắt đầu lần làm gắn phiên bản, trả lời câu hỏi phân trang, lưu tạm, chấm, xem điểm/nhóm/gợi ý ngành. Cần tải hết các trang và trả lời đủ trước khi chấm. Kiểm tra trạng thái lần làm trước mỗi lần lưu/chấm để phục hồi trường hợp phản hồi chấm trước đó bị mất. Tài khoản có thể lưu kết quả, mở lịch sử và tải PDF; khách chỉ xem kết quả trong phiên.
- **Quy đổi điểm:** bắt buộc chọn cơ sở/năm, chọn phương thức từ các quy tắc có thật; biểu mẫu lấy trường, giới hạn và lựa chọn từ API. Gửi điểm dạng chuỗi thập phân, không tính tổng ở frontend. Đối chiếu chọn đợt/ngành cùng phương thức/cơ sở/năm, kèm tổ hợp nếu áp dụng. Hiển thị tổng, thành phần, thang điểm, chênh lệch hoặc lý do không có mốc tương thích. Khi `RULE_VERSION_CHANGED`, khóa biểu mẫu cũ và cho tải biểu mẫu mới, không tự gửi lại phép tính.
- **Hướng dẫn tân sinh viên:** tìm nội dung/đối tượng/loại, xem bài chi tiết, checklist, liên kết hướng dẫn/hỗ trợ và lịch. Checklist chỉ là đánh dấu tự theo dõi trên màn hình, không giả lập API lưu hồ sơ.
- **Học phí/học bổng:** lọc cơ sở/khóa/chương trình/loại; xem số tiền, đơn vị thu, điều kiện, thời hạn và nguồn. Chọn chương trình sẽ lọc chính sách riêng; chọn tất cả để xem cả chính sách chung.

Các màn hình trong `src/features/` dùng API thật với nguồn, trạng thái tải/rỗng/lỗi và phân trang. Chuyển cơ sở/năm sẽ hủy tải cũ và bỏ lựa chọn/kết quả theo phạm vi cũ. Trắc nghiệm giữ đáp án trong bộ nhớ khi chuyển màn; backend hiện **không có API đọc lại đáp án tạm**, nên tải lại trang không phục hồi bài làm dở. Đổi tài khoản hoặc hết phiên xóa phần riêng tư. Không thêm dữ liệu mẫu, endpoint hay công thức vào backend.

API bổ sung (đều có tiền tố `/api/v1`):

| Nhóm | Endpoint |
| --- | --- |
| Đào tạo | `/programs/{id}`, `/program-offerings`, `/program-offerings/{id}/curriculum`, `/program-offerings/{id}/courses` |
| Tuyển sinh | `/admission-cycles/{id}`, `/admission-cycles/{id}/methods`, `/admission-cycles/{id}/options`, `/admission-options/{id}/subject-combinations`, `/admission-cutoffs` |
| Trắc nghiệm | `/questionnaires`, `POST /personality-attempts`, `/personality-attempts/{id}`, `/questions`, `PUT /answers`, `POST/GET /result`, `/recommendations`, `PUT /saved-result`, `/report` (các đường dẫn con thuộc lần làm), `/me/personality-results` |
| Điểm | `/score-rules`, `/score-rules/{id}/form`, `POST /score-calculations` |
| Hướng dẫn | `/guides`, `/guides/{id}`, `/guides/{id}/entries`, `/schedule-events` lọc đợt/hướng dẫn |
| Tài chính | `/financial-policies`, `/financial-policies/{id}` |

Theo source backend, lọc học phần dùng `group`, `term`; hướng dẫn dùng `audience_code`, `type`, `q`, `cycle_id`; không gửi các bộ lọc lịch ghi trong tài liệu nhưng backend chưa thực hiện (`from_date`, `to_date`, `scope`).

Kiểm tra:

```powershell
npm run build
node --experimental-strip-types --test tests/client.test.mjs tests/features.test.mjs
```

Kiểm thử giai đoạn 3 bao phủ payload điểm/giới hạn/lựa chọn, thứ tự lưu đáp án trước chấm, phục hồi lần làm đã hoàn thành, chặn chấm khi chưa đủ câu và lỗi lưu, cùng xác thực PDF. Các test dùng transport cô lập; giao diện vẫn chỉ gọi API thật.

Cần kiểm tra end-to-end khi backend hoạt động: dữ liệu rỗng và nhiều trang; đổi bộ lọc trong lúc tải; nguồn bị thu hồi; quy tắc điểm đổi phiên bản; đối chiếu có/không có mốc; bài trắc nghiệm trên 100 câu; lưu kết quả/PDF; thay đổi tài khoản khi đang thao tác. Chưa chạy kiểm thử trình duyệt tự động hoặc end-to-end trong môi trường này; backend cổng 8080 chưa hoạt động tại thời điểm kiểm tra.

## Giai đoạn 4 — Không gian cán bộ

Đăng nhập tài khoản có quyền để thấy **Không gian cán bộ** trong menu. Giao diện giữ màu Navy Blue/trắng và logo đã cung cấp. Không tự tạo tài khoản, vai trò, nguồn crawler hoặc dữ liệu mẫu.

| Vai trò | Chức năng |
| --- | --- |
| `STAFF_EDITOR` | Tạo mục và tạo/sửa bản nháp thuộc cơ sở được giao; tải PDF. Quyền toàn trường mới được tạo ngành trong danh mục chung. |
| `STAFF_REVIEWER` | Đọc bản nháp, kiểm tra nguồn, duyệt/xuất bản, gỡ bản công khai và thu hồi nguồn theo cơ sở. |
| `TECH_OPERATOR` toàn trường | Xem nguồn đã cấu hình, tạo tác vụ, xem lịch sử và theo dõi crawler. |
| `ADMIN` toàn trường | Tìm tài khoản, khóa/mở khóa, xem/cấp/thu hồi vai trò và phạm vi. Không tự có quyền biên tập/duyệt. |

Backend vẫn là nơi thực thi quyền. Khi phiên hết hạn hoặc quyền bị thu hồi, request bị từ chối và giao diện riêng tư được gỡ. Với thay đổi quyền/trạng thái của chính tài khoản đang dùng, frontend chủ động kết thúc phần hiển thị phiên cũ sau khi API thành công.

### Biên tập và xuất bản

- Năm nhóm: tuyển sinh, chương trình đào tạo, hướng dẫn, tài chính và quy tắc điểm.
- Tạo danh tính nghiệp vụ theo cơ sở/năm, xem phiên bản và toàn bộ nội dung con.
- Biểu mẫu có trường riêng và danh sách thêm/bỏ mục; không yêu cầu nhập nguyên JSON. Có phương thức, ngành/chỉ tiêu, tổ hợp/điểm chuẩn, học phần, checklist/liên hệ, mốc lịch, trường điểm và dải quy đổi. Cấu hình trọng số dùng cặp mã đầu vào/giá trị.
- Chỉ sửa trạng thái `DRAFT`; bản đã công khai/lưu trữ chỉ đọc và có thể sao chép thành bản nháp mới khi có quyền.
- `PUT` bản nháp và xuất bản gửi đúng ETag đọc từ server qua `If-Match`. Không tự thử lại thao tác ghi khi xung đột. Nội dung đang nhập giữ nguyên; người dùng đọc bản mới để đối chiếu rồi chủ động chọn bỏ phần đang nhập. Thiếu ETag thì chặn lưu có điều kiện.
- Việc lưu bản nháp thay thế toàn bộ nội dung con, nên payload giữ đầy đủ các mục đang hiển thị và loại các khóa nội bộ chỉ đọc (`id`, khóa cha, trạng thái, `lock_version`). ID và điểm thập phân giữ dạng chuỗi; trường số nguyên được gửi dạng số.
- Chặn duyệt khi còn thay đổi chưa lưu. Xuất bản/gỡ công khai có bước xác nhận đúng phiên bản và phạm vi. Rời biểu mẫu đã sửa hoặc tải lại trang có nhắc phần chưa lưu.
- Danh mục ngành hỗ trợ tạo và tra cứu theo ID đúng hai API backend hiện có; không thêm thao tác sửa/xóa mà backend chưa triển khai.

**Danh mục tham chiếu:** backend chưa có API dành cho cán bộ để liệt kê lĩnh vực, phương thức gốc, học phần gốc hoặc tổ hợp gốc. Những trường tham chiếu này nhập ID hiện hữu; frontend không tự đặt mã hay sửa backend. Mã nguồn lấy từ phiên bản tài liệu trong tab Tài liệu RAG. Đây là ID trong hệ thống, khác mã ngành/mã tổ hợp hiển thị công khai.

### Tài liệu RAG

Tìm tài liệu theo tiêu đề/cơ sở/năm, xem các phiên bản và trạng thái. Tải PDF thật bằng multipart, kèm CSRF, tối đa 20 MiB; kiểm tra chữ ký PDF và URL HTTPS thuộc `utc.edu.vn`/`utc2.edu.vn` (kể cả tên miền con). Backend kiểm tra nội dung, mã hóa PDF, nguồn và phạm vi cuối cùng.

Phiên bản `INDEXING` được đọc trạng thái mỗi 5 giây. Khi rời trang, gặp lỗi hoặc đã ra khỏi `INDEXING`, polling dừng. Nút làm mới cho phép thử lại. Người duyệt mở nguồn chính thức rồi xác minh/xuất bản qua API publication; không có API xác minh riêng. Chỉ trạng thái phù hợp mới bật nút duyệt. Thu hồi nguồn yêu cầu lý do và xác nhận hậu quả đối với RAG/dữ liệu phụ thuộc; không hiển thị nút hoàn tác vì backend chưa có API này.

### Tài khoản và vận hành

- Tài khoản: tìm email/trạng thái, xem thông tin và vai trò có phân trang, khóa/mở khóa, cấp/thu hồi vai trò kèm phạm vi. `STUDENT`, `ADMIN`, `TECH_OPERATOR` chỉ cấp toàn trường. Các thay đổi có xác nhận; backend bảo vệ quản trị viên toàn trường cuối cùng.
- Crawler: liệt kê nguồn cấu hình sẵn, chỉ bật chạy cho nguồn `enabled`, tạo job thật và theo dõi. Nếu POST trả `409` kèm `job_id`, mở job hiện có thay vì tạo lại. Polling 5 giây chỉ khi `PENDING`/`RUNNING`, dừng khi lỗi hoặc kết thúc. Có lọc lịch sử theo nguồn/trạng thái và làm mới thủ công. Không hiển thị claim token hoặc thông tin khóa nội bộ.

### API đã nối

Tất cả dùng tiền tố `/api/v1`:

- `/editorial/{admission-cycles|program-offerings|guide-items|financial-policies|score-rules}`: danh sách, tạo mục, đọc mục, danh sách/tạo/đọc/sửa phiên bản, đọc/cập nhật publication.
- `POST /editorial/programs`, `GET /editorial/programs/{id}`.
- `/editorial/documents`, `/editorial/documents/{id}/revisions`, `/editorial/document-revisions` (multipart), `/editorial/document-revisions/{id}`, `/editorial/documents/{id}/publication`, `/editorial/document-revisions/{id}/revocation`.
- `/admin/accounts`, `/admin/accounts/{id}`, `/admin/accounts/{id}/role-assignments`, `/admin/role-assignments/{id}`.
- `/ops/crawl-sources`, `/ops/crawl-jobs`, `/ops/crawl-jobs/{id}`.

### Kiểm tra

```powershell
npm run build
node --experimental-strip-types --test tests/client.test.mjs tests/features.test.mjs tests/staff.test.mjs
```

Kiểm thử bao phủ phân quyền/cơ sở, URL nguồn, chuyển dữ liệu bản nháp lồng nhau, loại khóa chỉ đọc, bảo toàn ID/điểm, cấu hình công thức, ETag/412 không tự ghi lại, multipart/CSRF và `job_id` khi crawler xung đột; kèm toàn bộ kiểm thử các giai đoạn trước. Transport cô lập chỉ dùng trong tests, không có mock API trong ứng dụng.

Cần kiểm thử tích hợp khi có backend và tài khoản được cấp quyền: editor/reviewer cùng và khác cơ sở; ADMIN không có quyền biên tập; hai cửa sổ sửa cùng phiên bản; publish/gỡ/thu hồi; PDF hợp lệ/quá dung lượng/mã hóa; mất kết nối trong khi ghi; job đang chạy/trùng; admin cuối cùng; thay đổi quyền của chính mình. Backend cổng 8080 chưa hoạt động tại thời điểm bàn giao, nên chưa chạy các ca end-to-end này hoặc kiểm thử trình duyệt tự động. Không chỉnh sửa backend.

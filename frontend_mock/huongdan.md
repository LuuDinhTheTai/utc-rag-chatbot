# Hướng dẫn test giao diện UTC bằng mock API

## 1. Chạy ứng dụng

Bản này độc lập trong `frontend_mock`. Các tệp source, logo và giao diện được sao chép từ `frontend`; chỉ bản sao được gắn mock API. Không cần Java, PostgreSQL, Docker hoặc Ollama.

```powershell
cd C:\Users\TamNguyen\source\repos\utc-rag-chatbot\frontend_mock
npm ci
npm run dev
```

Mở **http://127.0.0.1:5174**. Dùng nhất quán địa chỉ này; `localhost` có vùng localStorage khác. Nếu cổng đã dùng, dừng tiến trình đang chạy cổng đó hoặc chạy `npm run dev -- --port 5175` và dùng URL mới. Mỗi cổng có dữ liệu thử riêng.

Chạy production preview nếu cần:

```powershell
npm run build
npx vite preview --host 127.0.0.1 --port 5174
```

Mọi lời gọi `/api/v1/...` được xử lý bởi mock trong trình duyệt, không chuyển về backend thật. Vì đây là mock ở lớp `fetch`, DevTools Network không nhất thiết có các request HTTP `/api`; các liên kết nguồn HTTP(S) vẫn mở trang ngoài khi bạn chủ động bấm.

## 2. Dữ liệu và tài khoản

Mọi nội dung, mốc lịch, số tiền, chỉ tiêu, điểm chuẩn, công thức, kết quả trắc nghiệm đều là **dữ liệu minh họa**, không phải thông tin tuyển sinh thật. Mật khẩu chung: **`UtcDemo123!`**.

| Email | Quyền | Kịch bản chính |
| --- | --- | --- |
| `student@demo.utc.test` | STUDENT | Chat, lịch sử, trắc nghiệm, PDF |
| `editor@demo.utc.test` | STAFF_EDITOR tại HN | Tạo và sửa nháp Hà Nội, upload PDF HN |
| `reviewer@demo.utc.test` | STAFF_REVIEWER tại HN | Đọc nháp, duyệt, gỡ, thu hồi nguồn HN |
| `operator@demo.utc.test` | TECH_OPERATOR toàn trường | Nguồn crawler và tác vụ |
| `admin@demo.utc.test` | ADMIN toàn trường | Tài khoản và quyền; không có biên tập/duyệt |
| `all@demo.utc.test` | Tất cả vai trò toàn trường | Thử toàn bộ các màn hình nhanh nhất |

Bạn cũng có thể đăng ký email mới; tài khoản mới chỉ có STUDENT. Nhấn khu vực **Khách / tài khoản** góc phải để đăng ký/đăng nhập/đăng xuất.

Dữ liệu gồm 2 cơ sở (`HN`, `HCM`), 16 ngành, chương trình khóa 2026, đợt MAIN năm 2025/2026, 2 phương thức (`THPT`, `DGNL`), tổ hợp `A00`, `A01`, hướng dẫn, tài chính, bản nháp/đã công khai cho cả 5 nhóm biên tập. Tài khoản student có sẵn 14 hội thoại để thử phân trang; các tài khoản khác bắt đầu chưa có hội thoại.

Thay đổi được lưu bằng localStorage trong trình duyệt: đăng ký, chat, feedback, bài làm/kết quả, nháp, tài liệu và job. Sau khi tải lại trang có thể cần mở lại đối tượng qua danh sách. Đáp án dở vẫn có giới hạn của giao diện gốc: không có màn mở lại bài làm dở. Không nhập mật khẩu hoặc dữ liệu cá nhân thật vào bản demo.

## 3. Bảng điều khiển mock

Nhấn **🧪 MOCK API · Kịch bản test** ở góc dưới phải. Thu gọn bảng để tránh che nội dung.

| Điều khiển | Cách thử và kết quả mong đợi |
| --- | --- |
| Độ trễ 2 giây | Chọn rồi chuyển màn hình hoặc bấm Làm mới/Thử lại: thấy trạng thái tải và nút chờ. |
| Danh sách rỗng | Bật rồi thực hiện tìm kiếm/đổi bộ lọc/chuyển màn hình có tải mới: danh sách trống. Cơ sở và bộ đề vẫn giữ để không chặn điều hướng. Tắt rồi tải lại danh sách. |
| Chat thiếu nguồn | Bật rồi gửi câu hỏi: câu trả lời `INSUFFICIENT_EVIDENCE`, không có trích dẫn. Có thể gõ câu chứa “thiếu nguồn” để thử cùng kết quả. |
| Bộ đề 104 câu | Bật **trước khi bắt đầu lần làm mới**. Trang đầu có 100 câu và nút Tải thêm; hoàn tất chỉ bật sau tải và trả lời đủ 104 câu. |
| Lỗi 503 một lần | Yêu cầu API kế tiếp lỗi dịch vụ; nhấn Thử lại để phục hồi. |
| Lỗi 429 một lần | Yêu cầu kế tiếp bị giới hạn; kiểm tra thông báo và thao tác lại. Mock không bắt chờ đủ một phút. |
| Xung đột phiên bản | Lần ghi có ETag hoặc tính điểm kế tiếp lỗi 412/409. Request GET không tiêu thụ tùy chọn này. |
| Hết phiên | Xóa phiên mock, phát sự kiện hết phiên; phần riêng tư đóng và có thông báo kết nối/đăng nhập lại. |
| Đặt lại dữ liệu | Xác nhận để phục hồi seed ban đầu, xóa toàn bộ thay đổi thử và tải lại trang. |
| Tải PDF mẫu | Tải `demo-upload.pdf`, dùng tệp này khi test upload tài liệu. |

Các tùy chọn kịch bản chỉ giữ trong bộ nhớ: tải lại trang sẽ về mặc định. Dữ liệu thao tác vẫn được lưu. Lỗi “một lần” áp dụng cho **request kế tiếp**, nên polling tài liệu/job cũng có thể nhận lỗi trước thao tác bạn định thử. Để dễ kiểm soát, thử trên màn hình không có polling hoặc chờ polling kết thúc.

## 4. Khách — tổng quan và tra cứu

### TC01 — Dashboard và responsive

1. Đặt lại dữ liệu; vào Tổng quan, chưa đăng nhập.
2. Kiểm tra logo UTC, sidebar Navy Blue, hero, các thẻ truy cập nhanh, ngành và lịch có dữ liệu.
3. Nhấn các thẻ Tuyển sinh / Ngành đào tạo / Lịch tuyển sinh và quay lại Tổng quan.
4. Thu nhỏ cửa sổ khoảng 390 px; mở/đóng menu bằng nút ở đầu trang và bấm vùng nền ngoài menu.
5. Dùng Tab/Shift+Tab để đi qua nút, liên kết và bộ lọc.

Mong đợi: không tràn ngang toàn trang, bảng dài cuộn ngang trong vùng bảng, chữ và logo rõ, menu di động hoạt động, focus nhìn thấy.

### TC02 — Cơ sở/năm và dữ liệu rỗng

1. Chọn Hà Nội (`HN`), nhập `2026`, nhấn Áp dụng.
2. Sang Tuyển sinh: thấy đợt MAIN 2026 của HN.
3. Đổi năm `2030`: không có đợt/ngành chương trình phù hợp.
4. Nhấn Đặt lại: dữ liệu trở về phạm vi tất cả.

### TC03 — Ngành, khung đào tạo và học phần

1. Vào Ngành đào tạo, tìm `Công nghệ thông tin`; bấm Lọc.
2. Xem chương trình → chọn HN khóa 2026 → Xem khung đào tạo.
3. Kiểm tra 48 tháng, 150 tín chỉ, mục tiêu, chuẩn đầu ra, nghề nghiệp và học phần.
4. Lọc nhóm `GENERAL`, kỳ `1`; thấy Giải tích. Đặt lại để thấy tất cả.
5. Quay về danh sách; để trống từ khóa, bấm Lọc, rồi Tải thêm: 16 ngành được hiển thị qua nhiều trang.
6. Lọc lĩnh vực `TECH`, `TRANSPORT` hoặc `ECON`; thử từ khóa không tồn tại.

### TC04 — Tuyển sinh, chỉ tiêu, tổ hợp, điểm chuẩn

1. Chọn HN, năm 2026 → Tuyển sinh → mở đợt MAIN.
2. Kiểm tra THPT/DGNL, điều kiện, ngành mở tuyển, chỉ tiêu, nguồn.
3. Lọc phương thức `THPT`, từ khóa `Công nghệ thông tin`.
4. Bấm Xem tổ hợp môn: hiển thị A00 và A01 cùng môn/điều kiện mẫu.
5. Mở hướng dẫn hồ sơ trong đợt; kiểm tra lịch liên quan.
6. Ở bảng điểm chuẩn, thử mã ngành `7480101`, phương thức `THPT`, đợt `MAIN`, tổ hợp `A00`, thang `30`; kiểm tra dữ liệu và nguồn.
7. Đổi tổ hợp `A01`: seed không có điểm chuẩn A01, bảng rỗng.

### TC05 — Hướng dẫn, checklist và tài chính

1. Đặt lại phạm vi. Vào Hướng dẫn tân sinh viên, tìm `nhập học`, chọn đối tượng `ALL` (mã loại có thể để trống).
2. Mở hướng dẫn, đánh dấu checklist, thử liên kết hỗ trợ và xem lịch.
3. Quay lại hoặc tải lại: checklist không được lưu như hồ sơ cá nhân.
4. Vào Học phí và học bổng, chọn HN/2026, chương trình Công nghệ thông tin; lọc loại `TUITION`, rồi `SCHOLARSHIP`.
5. Mở chính sách, kiểm tra số tiền, đơn vị thu, điều kiện, hạn và nguồn. Không mặc định tất cả ngành đều có chính sách trong seed.

## 5. Chat, tài khoản và trắc nghiệm

### TC06 — Chat khách, trích dẫn và feedback

1. Chưa đăng nhập, vào Trợ lý tuyển sinh AI.
2. Chọn câu gợi ý, bấm Gửi câu hỏi. Chờ phản hồi và xem trích dẫn.
3. Chọn Hữu ích, nhập nhận xét, Lưu đánh giá; đổi Chưa hữu ích và lưu lại.
4. Bật Chat thiếu nguồn rồi gửi câu khác: thấy trạng thái thiếu bằng chứng, không có nguồn.
5. Tắt thiếu nguồn. Bật Lỗi 503 một lần rồi gửi câu hỏi.
6. Sau khi báo lỗi, bấm Thử gửi lại: trả lời thành công, không tạo hai lượt trùng cho cùng mã tin nhắn.
7. Tạo cuộc trò chuyện mới; thử xóa hội thoại với Hủy và Xác nhận.

### TC07 — Đăng ký/đăng nhập và lịch sử

1. Đăng ký `new@demo.utc.test` với mật khẩu `UtcDemo123!`, thử xác nhận mật khẩu sai trước.
2. Đăng xuất, thử đăng nhập mật khẩu sai; sau đó đăng nhập `student@demo.utc.test` đúng mật khẩu.
3. Vào Chat, kiểm tra 14 hội thoại mẫu, Tải thêm, tìm `Học phí`.
4. Mở một hội thoại, gửi tiếp, quay màn khác rồi trở lại; kiểm tra hội thoại đang mở còn giữ trong phiên giao diện.
5. Đăng xuất → đăng nhập `admin@demo.utc.test`: không thấy lịch sử riêng của student.
6. Trở về student: hội thoại vừa gửi còn trong danh sách. Tải lại trang: phiên và dữ liệu mock vẫn còn.

### TC08 — Trắc nghiệm 8 câu và PDF

1. Đăng nhập student, vào Khám phá tính cách.
2. Chọn bộ đề → đọc hướng dẫn → Bắt đầu.
3. Trả lời 2 câu, Lưu tạm: thấy thông báo; nút Hoàn tất chưa bật.
4. Trả lời đủ 8 câu, Hoàn tất: thấy nhóm, mô tả, điểm và 3 ngành gợi ý.
5. Lưu vào tài khoản → Tải báo cáo PDF; mở tệp kiểm tra có một trang báo cáo DEMO.
6. Mở lại từ Kết quả đã lưu; bấm Tìm hiểu ngành để đến chi tiết ngành.
7. Làm lại với lựa chọn khác: điểm/nhóm mô phỏng thay đổi theo lựa chọn.
8. Có thể lặp lại khi là khách: không có nút lưu vào tài khoản hoặc PDF.

PDF có nội dung tiếng Anh tối giản để kiểm tra tải/mở tệp, không phải báo cáo chính thức. Mock trắc nghiệm dùng phép tổng hợp mẫu, không mô phỏng mô hình tâm lý được chuẩn hóa.

### TC09 — Phân trang câu hỏi

1. Bật Bộ đề 104 câu trước khi bắt đầu lần mới.
2. Trang đầu hiển thị 100 câu, có nhắc còn câu hỏi và nút Tải thêm.
3. Bấm Tải thêm để có đủ 104; kiểm tra thứ tự và lựa chọn không mất.
4. Có thể chỉ kiểm tra phân trang; nếu muốn chấm phải trả lời đủ 104 câu. Không có tự chọn đáp án.

### TC10 — Quy đổi và đối chiếu

1. Vào Quy đổi điểm khi chưa chọn cơ sở/năm: có nhắc chọn phạm vi.
2. Chọn HN/2026 → Áp dụng → THPT.
3. Nhập Toán `8.5`, Vật lý `8.5`, Hóa học `8.5`, ưu tiên NONE: tổng mẫu `25.50`.
4. Chọn KV1: tổng mẫu `26.25`. Thử nhập môn >10, bỏ trường bắt buộc hoặc số âm: có chặn/nhắc hợp lệ.
5. Bật so sánh → đợt MAIN → một ngành → A00: mốc mẫu `24.00` và chênh lệch tương ứng.
6. Đổi A01 hoặc Không áp dụng tổ hợp: có thông báo không có một mốc tương thích duy nhất.
7. Bật Xung đột phiên bản → Tính: có thông báo quy tắc đổi và nút tải biểu mẫu mới. Tải biểu mẫu rồi nhập lại và tính.

DGNL trong mock dùng cùng biểu mẫu 3 môn để thử màn hình; không phải công thức thi DGNL thực. Các số trên chỉ là kết quả của công thức DEMO.

## 6. Cán bộ — biên tập và duyệt

### TC11 — Kiểm tra vai trò

1. Đăng nhập `editor`: menu có Không gian cán bộ → Biên tập & xuất bản, Tài liệu RAG.
2. Danh sách chỉ có HN; lọc HCM trả rỗng. Đọc được bản nháp nhưng không có nút duyệt; không thấy danh mục ngành toàn trường, quản trị hoặc crawler.
3. Đăng nhập `reviewer`: đọc và duyệt HN, không được tạo/sửa nháp.
4. Đăng nhập `admin`: chỉ có quản trị tài khoản; không tự có quyền biên tập/duyệt.
5. Đăng nhập `operator`: chỉ có đồng bộ dữ liệu.
6. Dùng `all` cho các kịch bản tiếp theo.

### TC12 — Xem, sửa, sao chép bản nháp cả 5 nhóm

1. Không gian cán bộ → Biên tập & xuất bản. Lần lượt chọn Tuyển sinh, Chương trình đào tạo, Hướng dẫn, Tài chính, Quy tắc điểm.
2. Mở một mục → bản số 1 đã PUBLISHED: chỉ đọc; bản số 2 là DRAFT: sửa được.
3. Kiểm tra dữ liệu lồng nhau: phương thức/ngành/tổ hợp/điểm chuẩn, học phần, checklist/lịch, tài chính, trường điểm/dải quy đổi/trọng số.
4. Sửa nội dung, thêm/bỏ một mục con; nhấn Lưu bản nháp. Mở lại: dữ liệu vừa lưu còn nguyên.
5. Mở bản đã lưu, sao chép thành bản nháp mới, chỉnh và lưu: xuất hiện bản mới trong danh sách.
6. Sửa một trường rồi chuyển tab hoặc rời Không gian cán bộ: có nhắc phần chưa lưu. Hủy để tiếp tục sửa.
7. Khi đang sửa chưa lưu, không được duyệt/sao chép bản đang xem.

### TC13 — Xung đột ETag

1. Mở bản DRAFT, sửa tiêu đề/nội dung.
2. Bật Xung đột phiên bản trong bảng mock rồi bấm Lưu.
3. Thấy lỗi 412, phần đang nhập vẫn còn; nút lưu cũ bị chặn.
4. Bấm Đọc bản mới để đối chiếu. Xem phần server bên dưới.
5. Bấm Bỏ phần đang nhập, dùng bản mới; sửa lại và lưu thành công.

Mock tăng phiên bản khóa để tạo xung đột; không cần mở hai cửa sổ. Các tab trình duyệt không đồng bộ thay đổi localStorage tức thời như hệ thống nhiều người dùng thật.

### TC14 — Duyệt, gỡ công khai

1. Mở bản đã lưu của một mục. Bấm Duyệt và xuất bản đúng ID.
2. Hủy: trạng thái không đổi. Lặp lại và Xác nhận: bản được chọn thành PUBLISHED, bản công khai trước đó thành ARCHIVED.
3. Quay danh sách phiên bản để kiểm tra.
4. Gỡ bản công khai → Xác nhận: publication không còn ID công khai.
5. Có thể bật xung đột ngay trước Xác nhận để kiểm tra nút đọc lại trạng thái publication.

Phần công khai trong mock dùng các bộ dữ liệu minh họa riêng. Mock cho thử thao tác, lưu và trạng thái phiên bản; không tái hiện đầy đủ việc dựng lại public view/RAG/chỉ tiêu/nguồn phụ thuộc sau mọi thay đổi. Với hướng dẫn/tài chính/quy tắc điểm, một số nội dung được cập nhật vào fixture công khai; gỡ/thu hồi không phải mô phỏng hoàn chỉnh truy vấn PostgreSQL.

### TC15 — Tạo mục mới và mã tham chiếu

| Nhóm | Giá trị gợi ý |
| --- | --- |
| Tuyển sinh | HN, năm 2026, mã đợt `EXTRA` |
| Chương trình | HN, ID ngành `1`, khóa 2027 |
| Hướng dẫn | HN, năm 2026, loại `ENROLLMENT`, đối tượng `ALL`, slug `huong-dan-test` |
| Tài chính | HN, khóa 2026, loại `TUITION`, mã `FEE-DEMO`, ID chương trình `1` nếu cần |
| Quy tắc điểm | HN, năm 2026, ID phương thức `1` |

Sau khi tạo mục, tạo bản nháp và điền trường bắt buộc. Có thể dùng:

- Nguồn đã xác minh: ID **`1`** (HN).
- Ngành: ID `1`; lĩnh vực `1`; chương trình HN `1` hoặc HCM `101`.
- Phương thức: ID `1` = THPT, `2` = DGNL.
- Tổ hợp: ID `1` = A00, `2` = A01.
- Học phần: ID `1` = Giải tích, `2` = Nhập môn ngành.
- Thời gian: `2026-08-01T08:00:00+07:00`.
- Quy tắc điểm: `SUM`, thang `30`; thêm đầu vào loại `SUBJECT`, mã `math`, nhãn Toán, bắt buộc Có, min 0/max 10. Cấu hình chữ số thập phân 2.

ID là định danh trong hệ thống, khác mã ngành công khai. Danh mục ngành toàn trường: tra ID `1` hoặc tạo ngành với lĩnh vực `1`, mã ngành riêng và tên mới. Mock kiểm tra quyền và một số hợp lệ chính; không tái hiện tất cả ràng buộc khóa ngoại/unique của BE.

## 7. Tài liệu RAG

### TC16 — Upload → lập chỉ mục → duyệt → thu hồi

1. Dùng all hoặc editor. Mở bảng mock và tải PDF mẫu.
2. Tài liệu RAG → Tải tài liệu PDF. Nhập tiêu đề, URL `https://www.utc.edu.vn/demo-upload`, năm 2026, cơ sở HN; chọn PDF mẫu.
3. Tải lên: mở phiên bản mới trạng thái INDEXING.
4. Chờ khoảng 10 giây: polling mỗi 5 giây đưa trạng thái sang PENDING (mock chuyển sau 6 giây).
5. Nếu dùng editor: đăng xuất và đăng nhập reviewer/all, tìm tài liệu, mở phiên bản.
6. Kiểm tra tên/năm/nguồn, bấm duyệt và Xác nhận: PUBLISHED, nguồn đã xác minh.
7. Thử gỡ công khai, rồi duyệt lại.
8. Thu hồi nguồn → bỏ trống lý do: có lỗi; nhập lý do và Xác nhận: REVOKED, hiện thời gian/lý do, nút xuất bản bị khóa.
9. Nhấn Làm mới trạng thái để kiểm tra dữ liệu vẫn còn. Đặt lại mock khi muốn khôi phục nguồn.

Ca lỗi: dùng tệp văn bản đổi đuôi `.pdf` (FE kiểm tra `%PDF-`), chọn tệp >20 MiB hoặc URL ngoài miền UTC → có thông báo từ chối. Mock không thực sự parse PDF, lập vector hoặc phát hiện mã hóa; đây vẫn là kiểm tra hiển thị và luồng thao tác.

## 8. Quản trị và crawler

### TC17 — Khóa/mở khóa và cấp/thu hồi quyền

1. Đăng nhập admin. Không gian cán bộ → Tài khoản & quyền.
2. Tìm email `student`, mở tài khoản: xem trạng thái và vai trò.
3. Khóa tài khoản → xác nhận. Student đăng nhập sẽ báo lỗi. Trở lại admin và mở khóa.
4. Cấp STAFF_EDITOR phạm vi HN cho student; xác nhận rồi kiểm tra danh sách quyền.
5. Thu hồi quyền vừa cấp, kiểm tra đúng email, vai trò và phạm vi trong xác nhận.
6. Với ADMIN/TECH_OPERATOR/STUDENT, form không cho chọn cơ sở.
7. Khi thay đổi quyền của chính admin đang đăng nhập, giao diện yêu cầu đăng nhập lại.
8. Nếu muốn kiểm tra quản trị viên cuối cùng: seed có admin và all; khóa một tài khoản quản trị bằng tài khoản còn lại, rồi thử khóa/thu hồi ADMIN của quản trị viên cuối. Mock từ chối 409. Sau test đặt lại dữ liệu.

### TC18 — Crawler và job trùng

1. Đăng nhập operator. Mở Đồng bộ dữ liệu: có 3 nguồn, nguồn 3 tắt nên không bấm chạy được.
2. Bấm chạy nguồn 1 → xác nhận: tạo job PENDING và mở phần theo dõi.
3. Ngay lập tức tạo lại job nguồn 1 trong vòng khoảng 12 giây: nhận 409 và mở đúng job hiện có.
4. Chờ polling: PENDING → RUNNING → SUCCEEDED; thời gian mẫu khoảng 15 giây để giao diện quan sát đủ các bước.
5. Lọc lịch sử theo source `1`, trạng thái `SUCCEEDED`; thử Làm mới danh sách.
6. Trong khi polling, bật lỗi 503 một lần: thông báo lỗi, polling dừng; bấm làm mới để tiếp tục.

Crawler chỉ mô phỏng trạng thái theo thời gian; không tải dữ liệu trường thật. Job không có worker nền, trạng thái được tính khi đọc lại API.

## 9. Lỗi, hết phiên và khôi phục

### TC19 — Trạng thái rỗng/lỗi/tải

1. Độ trễ 2 giây, chuyển một màn hình có gọi API mới: thấy tải.
2. Bật rỗng, thay bộ lọc: thấy thông báo chưa có dữ liệu. Tắt rỗng, đổi bộ lọc hoặc tải lại để phục hồi.
3. Ở màn không polling, bật 503, bấm Lọc: thấy lỗi. Bấm Thử lại: tải thành công.
4. Lặp lại 429. Kiểm tra thông báo không làm vỡ bố cục.
5. Nhớ rằng đổi màn nào đã được giữ trong bộ nhớ có thể không gọi API mới; dùng nút Lọc/Làm mới để kích hoạt chắc chắn.

### TC20 — Hết phiên và đặt lại

1. Đăng nhập all, mở nội dung riêng hoặc chat.
2. Bấm Hết phiên trong bảng mock: dữ liệu riêng đang hiển thị được gỡ và có thông báo.
3. Kết nối lại để dùng khách hoặc đăng nhập lại tài khoản demo.
4. Bấm Đặt lại dữ liệu: app về seed và trạng thái khách, các sửa đổi thử bị xóa.

## 10. Kiểm tra kỹ thuật

```powershell
npm run build
npm run test:mock
node --experimental-strip-types --test tests/client.test.mjs tests/features.test.mjs tests/staff.test.mjs tests/mock.test.mjs
```

Node 22.18+ / Node 24 phù hợp lệnh test TypeScript. Kiểm thử mock bao phủ dữ liệu màn hình/phân trang, tài khoản/CSRF, chat/feedback, trắc nghiệm/PDF, tính điểm, 5 nhóm biên tập/ETag, quyền cơ sở, upload, quản trị và crawler.

Code mock ở `src/mock/`; `src/main.tsx` cài transport trước khi render React. `original-hashes.json` ghi mã băm các tệp frontend gốc tại lúc sao chép để đối chiếu. `node_modules` và `dist` không được sao chép từ frontend gốc; dependency/build được tạo độc lập trong frontend_mock.

**Giới hạn:** mock xác nhận được giao diện và thao tác demo; không chứng minh bảo mật, tính toán tuyển sinh, AI, PostgreSQL, concurrency nhiều người hoặc tính tương thích BE thật. Bản này không dùng để triển khai tuyển sinh chính thức. Muốn quay về API thật hãy chạy thư mục `frontend` gốc, không phải sửa/tắt mock trong bản demo.

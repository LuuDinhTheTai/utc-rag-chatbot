# BẢNG PHÂN TÍCH 6 NHÓM CHỨC NĂNG – UTC RAG CHATBOT

## Phạm vi

Website chatbot hỗ trợ tuyển sinh UTC. Đây là các chức năng đề xuất để thiết kế và phát triển hệ thống, không phải khẳng định tất cả đã có trên website chính thức.

## Vai trò

- **Khách:** thí sinh/phụ huynh.
- **SV:** người dùng đăng nhập.
- **Giảng viên/cán bộ:** người được phân quyền cập nhật nội dung.
- **Nhà phát triển:** phụ trách kỹ thuật và vận hành.

## 1. Chatbot AI

| Mã | Chức năng con | Mô tả | Cách hoạt động / người dùng | Kết quả |
|---|---|---|---|---|
| 1.1 | Hỏi đáp | Trả lời câu hỏi về tuyển sinh UTC bằng ngôn ngữ tự nhiên. | Khách/SV nhập câu hỏi; hệ thống tìm thông tin trong kho tri thức UTC, tạo câu trả lời có nguồn. | Hiển thị câu trả lời và liên kết tham khảo. |
| 1.2 | Lưu lịch sử trò chuyện | Cho phép người dùng xem lại các cuộc tư vấn trước. | SV đăng nhập; hệ thống lưu nội dung theo tài khoản, thời gian và mã hội thoại. | Danh sách hội thoại đã lưu, có thể mở lại. |
| 1.3 | Tạo cuộc trò chuyện mới | Bắt đầu chủ đề tư vấn khác, không lẫn ngữ cảnh. | Khách/SV nhấn Trò chuyện mới; hệ thống tạo phiên hoặc hội thoại mới. | Khung chat trống và sẵn sàng nhận câu hỏi. |
| 1.4 | Xem và tìm lịch sử | Tìm lại nội dung đã được chatbot trả lời. | SV mở Lịch sử, tìm theo từ khóa hoặc chọn cuộc hội thoại. | Hiển thị đúng tin nhắn và thời điểm đã trao đổi. |
| 1.5 | Gợi ý câu hỏi | Hỗ trợ người chưa biết nên hỏi gì. | Hệ thống hiển thị câu hỏi mẫu theo ngành, phương thức, học phí, nhập học; người dùng chọn. | Câu hỏi được gửi nhanh đến chatbot. |
| 1.6 | Hiển thị nguồn trả lời | Giúp kiểm chứng thông tin và hạn chế AI bịa. | Hệ thống RAG trích đoạn tài liệu UTC, gắn tên nguồn, năm và đường dẫn. | Người dùng mở được văn bản gốc liên quan. |
| 1.7 | Đánh giá phản hồi | Ghi nhận câu trả lời sai hoặc chưa hữu ích. | Khách/SV chọn Hữu ích/Không hữu ích và gửi góp ý; hệ thống ghi nhận phản hồi. | Phản hồi được lưu để cải thiện chatbot. |

## 2. Quản lý thông tin tuyển sinh

| Mã | Chức năng con | Mô tả | Cách hoạt động / người dùng | Kết quả |
|---|---|---|---|---|
| 2.1 | Tra cứu thông tin tuyển sinh | Cung cấp thông tin tuyển sinh theo năm và cơ sở UTC. | Khách/SV chọn năm, cơ sở Hà Nội/TP.HCM rồi nhập nội dung cần tìm. | Hiển thị thông tin phù hợp kèm nguồn chính thức. |
| 2.2 | Tra cứu phương thức xét tuyển | Giải thích các hình thức và điều kiện xét tuyển đang áp dụng. | Người dùng chọn/hỏi phương thức; hệ thống truy xuất đề án tuyển sinh theo năm. | Danh sách phương thức, điều kiện và hướng dẫn. |
| 2.3 | Tra cứu chỉ tiêu tuyển sinh | Cho biết chỉ tiêu của ngành/chương trình. | Chọn năm, cơ sở và ngành; hệ thống tìm dữ liệu chỉ tiêu đã công bố. | Hiển thị chỉ tiêu, đơn vị và nguồn. |
| 2.4 | Tra cứu tổ hợp môn | Giúp thí sinh xác định tổ hợp xét tuyển phù hợp. | Người dùng chọn ngành và phương thức; hệ thống lọc tổ hợp từ dữ liệu UTC. | Danh sách tổ hợp môn và điều kiện nếu có. |
| 2.5 | Hướng dẫn hồ sơ đăng ký | Hướng dẫn các giấy tờ và bước đăng ký cần thiết. | Người dùng chọn đối tượng/phương thức; hệ thống tổng hợp checklist từ thông báo. | Danh sách thủ tục, giấy tờ và nơi nộp. |
| 2.6 | Tra cứu mốc thời gian | Giúp thí sinh theo dõi hạn đăng ký và xét tuyển. | Chọn năm, đợt tuyển sinh; hệ thống tải các mốc thời gian có nguồn. | Hiển thị lịch và thông báo thời hạn. |
| 2.7 | Tra cứu điểm chuẩn | Cung cấp điểm chuẩn các năm đã được công bố. | Chọn năm, cơ sở, ngành, phương thức; hệ thống lọc dữ liệu lịch sử. | Bảng điểm chuẩn có năm và nguồn tham chiếu. |
| 2.8 | Tra cứu học phí và học bổng | Giúp người học dự trù chi phí và chính sách hỗ trợ. | Hỏi/tra theo ngành, khóa, năm; hệ thống lấy văn bản học phí/học bổng tương ứng. | Thông tin chi phí, điều kiện hỗ trợ và nguồn. |
| 2.9 | Cập nhật thông tin tuyển sinh | Cho phép người được phân quyền quản lý kho dữ liệu. | Giảng viên/cán bộ được giao tải thông báo, gắn năm/cơ sở, duyệt và xuất bản. | Nội dung mới được chatbot dùng sau kiểm duyệt. |

## 3. Quản lý chương trình đào tạo

| Mã | Chức năng con | Mô tả | Cách hoạt động / người dùng | Kết quả |
|---|---|---|---|---|
| 3.1 | Tra cứu ngành đào tạo | Giúp người dùng tìm ngành, chuyên ngành ở UTC. | Khách/SV nhập tên/mã ngành hoặc chọn nhóm lĩnh vực. | Danh sách ngành đào tạo phù hợp. |
| 3.2 | Lọc theo cơ sở và năm | Tránh nhầm ngành/chương trình giữa các cơ sở, khóa. | Chọn Hà Nội/TP.HCM và năm tuyển sinh; hệ thống lọc dữ liệu. | Hiển thị danh sách đúng phạm vi. |
| 3.3 | Xem chi tiết chương trình | Giới thiệu mục tiêu, thời lượng và định hướng đào tạo. | Người dùng mở ngành; hệ thống lấy hồ sơ chương trình đã công bố. | Trang chi tiết chương trình đào tạo. |
| 3.4 | Tra cứu học phần, tín chỉ | Cho biết cấu trúc các môn học và số tín chỉ. | Chọn chương trình/khóa; hệ thống tải khung chương trình tương ứng. | Bảng học phần, số tín chỉ và phân nhóm. |
| 3.5 | Chuẩn đầu ra, cơ hội nghề nghiệp | Hỗ trợ chọn ngành phù hợp với mục tiêu cá nhân. | Người dùng xem mục tiêu, năng lực đầu ra và định hướng nghề nghiệp từ tài liệu. | Thông tin tham khảo có nguồn về đầu ra. |
| 3.6 | Cập nhật dữ liệu chương trình | Đảm bảo dữ liệu ngành/chương trình không lỗi thời. | Cán bộ được phân quyền thêm/sửa tài liệu, kiểm tra phiên bản trước khi xuất bản. | Kho chương trình mới được cập nhật. |

## 4. Quản lý khám phá tính cách

| Mã | Chức năng con | Mô tả | Cách hoạt động / người dùng | Kết quả |
|---|---|---|---|---|
| 4.1 | Bắt đầu trắc nghiệm tính cách | Cho người dùng khám phá sở thích và khuynh hướng cá nhân. | Khách/SV chọn bắt đầu; hệ thống hiển thị hướng dẫn và lưu phiên tạm. | Mở bộ câu hỏi trắc nghiệm. |
| 4.2 | Trả lời bộ câu hỏi | Thu thập lựa chọn để tổng hợp đặc điểm cá nhân. | Người dùng chọn mức độ đồng ý cho từng câu; hệ thống kiểm tra đủ câu. | Lưu bộ câu trả lời hợp lệ. |
| 4.3 | Tính kết quả tính cách | Tổng hợp điểm theo mô hình trắc nghiệm được cấu hình. | Hệ thống áp dụng quy tắc chấm điểm, phân nhóm đặc điểm; không dùng như chẩn đoán. | Bản mô tả nhóm tính cách tham khảo. |
| 4.4 | Gợi ý ngành phù hợp | Liên hệ sở thích với ngành đào tạo UTC. | Hệ thống đối chiếu kết quả với mô tả ngành, giải thích lý do gợi ý. | Danh sách ngành gợi ý, không thay thế tư vấn. |
| 4.5 | Xem lại kết quả | Cho phép người dùng tham khảo kết quả trước. | SV đăng nhập lưu/kết xuất kết quả hoặc thực hiện lại; khách chỉ xem trong phiên. | Lịch sử kết quả hoặc báo cáo hiện tại. |

## 5. Quản lý quy đổi điểm

| Mã | Chức năng con | Mô tả | Cách hoạt động / người dùng | Kết quả |
|---|---|---|---|---|
| 5.1 | Chọn phương thức xét tuyển | Xác định công thức tính điểm phù hợp. | Người dùng chọn năm, cơ sở và phương thức có trong quy chế UTC. | Mở đúng biểu mẫu nhập điểm. |
| 5.2 | Nhập điểm thi THPT | Tính điểm theo tổ hợp môn xét tuyển. | Nhập điểm môn theo thang quy định; hệ thống kiểm tra hợp lệ và tổ hợp. | Dữ liệu điểm hợp lệ để tính. |
| 5.3 | Nhập điểm đánh giá năng lực/tư duy | Hỗ trợ phương thức xét tuyển bổ sung khi UTC có áp dụng. | Chọn kỳ thi được quy định trong năm, nhập điểm và kiểm tra phạm vi. | Điểm đầu vào được ghi nhận phù hợp quy chế. |
| 5.4 | Nhập chứng chỉ/giải ưu tiên | Tính yếu tố chứng chỉ hoặc ưu tiên khi đủ điều kiện. | Người dùng nhập chứng chỉ/giải theo danh mục được phép; hệ thống đối chiếu quy tắc. | Điểm hoặc điều kiện ưu tiên tương ứng. |
| 5.5 | Tính điểm ưu tiên khu vực/đối tượng | Áp dụng chính sách cộng ưu tiên đúng quy định. | Chọn khu vực, đối tượng; hệ thống kiểm tra mức ưu tiên và quy tắc giới hạn. | Điểm ưu tiên hợp lệ. |
| 5.6 | Quy đổi và tính tổng điểm | Tính điểm tham khảo theo bảng quy đổi chính thức. | Bấm Tính; backend áp dụng phiên bản công thức theo năm, phương thức, cơ sở. | Điểm quy đổi/tổng điểm và chi tiết phép tính. |
| 5.7 | So sánh với điểm chuẩn | Giúp thí sinh tự đánh giá mức độ cạnh tranh. | Người dùng chọn ngành; hệ thống đối chiếu điểm vừa tính với điểm chuẩn năm trước. | Mức chênh lệch tham khảo, không bảo đảm trúng tuyển. |

## 6. Quản lý thông tin tân sinh viên

| Mã | Chức năng con | Mô tả | Cách hoạt động / người dùng | Kết quả |
|---|---|---|---|---|
| 6.1 | Hướng dẫn thủ tục nhập học | Cho người trúng tuyển biết các bước nhập học. | Người dùng chọn năm, cơ sở; chatbot lấy hướng dẫn nhập học đúng đợt. | Danh sách bước và đường dẫn nhập học. |
| 6.2 | Tra cứu giấy tờ nhập học | Hạn chế thiếu giấy tờ bắt buộc. | Chọn đối tượng; hệ thống hiển thị danh mục hồ sơ theo thông báo. | Checklist giấy tờ cần chuẩn bị. |
| 6.3 | Lịch nhập học và sinh hoạt đầu khóa | Giúp sắp xếp thời gian đến trường. | Người dùng chọn cơ sở/khóa; hệ thống truy xuất lịch thông báo chính thức. | Các mốc ngày, địa điểm, lưu ý. |
| 6.4 | Thông tin học phí, học bổng | Giới thiệu khoản thu và hỗ trợ tài chính. | Chọn chương trình/khóa; truy xuất biểu phí, chính sách và mốc nộp. | Thông tin học phí/học bổng kèm nguồn. |
| 6.5 | Ký túc xá và đời sống sinh viên | Giải đáp nhu cầu chỗ ở và sinh hoạt. | Chọn chủ đề ký túc xá, CLB, tiện ích; hệ thống hiển thị hướng dẫn. | Thông tin liên hệ, điều kiện đăng ký nếu có. |
| 6.6 | Hướng dẫn học vụ ban đầu | Giới thiệu tài khoản sinh viên và quy trình học tập. | Tra cứu đăng ký học phần, thời khóa biểu, quy định đào tạo từ nguồn cập nhật. | Hướng dẫn các bước học vụ cơ bản. |
| 6.7 | Kênh liên hệ hỗ trợ | Chuyển thắc mắc vượt khả năng chatbot. | Nhấn liên hệ phòng ban, hotline hoặc kênh hỗ trợ đã xác minh. | Mở đúng trang/đầu mối hỗ trợ UTC. |

## Ghi chú triển khai

- Dữ liệu tuyển sinh, chương trình đào tạo, học phí và quy đổi điểm phải gắn năm, cơ sở, nguồn chính thức và ngày cập nhật.
- Chỉ tính điểm khi có công thức tuyển sinh đã được kiểm duyệt.
- Kết quả khám phá tính cách và đối chiếu điểm chuẩn chỉ mang tính tham khảo.
- Tra cứu hồ sơ cá nhân phải qua hệ thống được UTC cho phép.

# UTC Admissions RAG Chatbot - Frontend 🎓

Giao diện người dùng (Frontend) cho hệ thống **Chatbot RAG Tư vấn Tuyển sinh - Trường Đại học Giao thông Vận tải (UTC)**. Ứng dụng hỗ trợ học sinh, phụ huynh và sinh viên tra cứu thông tin tuyển sinh, điểm chuẩn, học phí, các ngành đào tạo và quy chế xét tuyển thông qua tương tác hỏi đáp thông minh với phản hồi thời gian thực.

---

## 🚀 Công nghệ sử dụng (Tech Stack)

| Công nghệ | Vai trò |
| :--- | :--- |
| **[Next.js 16 (App Router)](https://nextjs.org/)** | Framework React hiệu năng cao cho ứng dụng web |
| **[TypeScript](https://www.typescriptlang.org/)** | Định kiểu an toàn, phát hiện lỗi sớm và dễ bảo trì |
| **[Tailwind CSS v4](https://tailwindcss.com/)** | Utility-first CSS framework thiết kế giao diện linh hoạt |
| **[shadcn/ui](https://ui.shadcn.com/)** | Bộ component giao diện tùy biến (sử dụng Base UI) |
| **[Zustand](https://zustand-demo.pmnd.rs/)** | Quản lý state toàn cục nhẹ gọn, hỗ trợ lưu trữ phiên hội thoại vào `localStorage` |
| **[React Markdown](https://github.com/remarkjs/react-markdown)** | Hiển thị nội dung Markdown phong phú (bảng, danh sách, trích dẫn) |
| **[rehype-highlight & remark-gfm](https://github.com/remarkjs)** | Hỗ trợ GitHub Flavored Markdown (bảng, checklist) và highlight cú pháp code |
| **[Server-Sent Events (SSE)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)** | Nhận dữ liệu streaming từ backend RAG thời gian thực |
| **[next-themes](https://github.com/pacocoursey/next-themes)** | Chuyển đổi giao diện Sáng / Tối (Light / Dark Mode) |
| **[Lucide React](https://lucide.dev/)** | Hệ thống icon hiện đại |

---

## ✨ Tính năng nổi bật

- ⚡ **Streaming thời gian thực (SSE):** Câu trả lời hiển thị từng từ theo thời gian thực tương tự ChatGPT. Có nút **Dừng phát sinh** (AbortController) bất cứ lúc nào.
- 📚 **Trích dẫn nguồn tài liệu (RAG Sources):** Hiển thị các văn bản, đề án tuyển sinh được hệ thống tham chiếu kèm độ tương đồng (relevance score).
- 📝 **Markdown & Hiển thị mã nguồn:** Định dạng bảng điểm chuẩn, danh sách, khối code có nút sao chép tiện lợi.
- 🗂️ **Quản lý lịch sử hội thoại:**
  - Tự động gom nhóm theo mốc thời gian: *Hôm nay*, *Hôm qua*, *7 ngày trước*, *Cũ hơn*.
  - Hỗ trợ đổi tên phiên chat, xóa từng phiên hoặc xóa toàn bộ lịch sử.
  - Tự động lưu và khôi phục từ `localStorage`.
- 💡 **Màn hình chào mừng & Câu hỏi gợi ý:** Cung cấp các câu hỏi nhanh về điểm chuẩn, học phí, phương thức xét tuyển và ngành đào tạo UTC.
- 🌓 **Giao diện Light / Dark mode:** Tông màu xanh thương hiệu UTC, chuyển đổi mượt mà.
- 📱 **Thiết kế Responsive hoàn chỉnh:** Tối ưu hiển thị trên cả máy tính để bàn (Sidebar cố định) và điện thoại di động (Sheet drawer).
- 🧪 **Tích hợp sẵn Mock SSE:** Cho phép chạy thử nghiệm và phát triển frontend ngay cả khi backend chưa sẵn sàng.

---

## 📂 Cấu trúc thư mục (Project Structure)

```text
frontend/
├── public/                     # Tài nguyên tĩnh (ảnh, favicon, logo)
├── src/
│   ├── app/                    # Next.js App Router
│   │   ├── globals.css         # Cấu hình Tailwind CSS, biến CSS theme & typography
│   │   ├── layout.tsx          # Root Layout (Theme Provider, Tooltip Provider, Metadata)
│   │   └── page.tsx            # Trang chính chứa giao diện chat và sidebar
│   ├── components/
│   │   ├── chat/               # Các component liên quan đến khung chat
│   │   │   ├── ChatArea.tsx        # Vùng hiển thị tin nhắn và khung nhập liệu
│   │   │   ├── ChatInput.tsx       # Ô nhập tin nhắn, auto-resize, nút gửi/dừng
│   │   │   ├── ChatMessage.tsx     # Hiển thị từng tin nhắn (Markdown, Code copy)
│   │   │   ├── SourceCard.tsx      # Danh sách và chi tiết tài liệu trích dẫn
│   │   │   ├── TypingIndicator.tsx # Hiệu ứng chờ phản hồi
│   │   │   └── WelcomeScreen.tsx   # Màn hình chào mừng và câu hỏi gợi ý
│   │   ├── sidebar/            # Các component sidebar
│   │   │   ├── ConversationItem.tsx # Item phiên chat (hỗ trợ đổi tên, xóa)
│   │   │   └── Sidebar.tsx          # Danh sách hội thoại gom nhóm theo ngày
│   │   ├── ui/                 # Các component cơ bản từ shadcn/ui
│   │   ├── theme-provider.tsx  # Provider quản lý Dark/Light mode
│   │   └── theme-toggle.tsx    # Nút chuyển đổi Dark/Light mode
│   ├── hooks/
│   │   └── use-chat.ts         # Custom hook kết nối Zustand store và luồng SSE
│   ├── lib/
│   │   ├── sse-client.ts       # Client giao tiếp SSE (cả kết nối thật và mock data)
│   │   └── utils.ts            # Hàm tiện ích (classnames helper, ...)
│   ├── stores/
│   │   └── chat-store.ts       # Zustand store lưu trữ trạng thái chat và lịch sử
│   └── types/
│       └── chat.ts             # Định nghĩa kiểu dữ liệu TypeScript (Message, Source, ...)
├── .env.example                # File mẫu cấu hình biến môi trường
├── components.json             # Cấu hình shadcn/ui
├── package.json                # Danh sách thư viện và scripts
├── tsconfig.json               # Cấu hình TypeScript
└── README.md                   # Tài liệu hướng dẫn dự án
```

---

## ⚙️ Biến môi trường (Environment Variables)

Tạo file `.env.local` ở thư mục gốc `frontend/` (hoặc sao chép từ `.env.example`):

```bash
# Địa chỉ URL của Backend API (FastAPI / Flask / Express)
NEXT_PUBLIC_API_URL=http://localhost:8000

# Bật hoặc tắt Mock Data:
# - "true"  : Sử dụng dữ liệu giả lập có sẵn (không cần bật backend)
# - "false" : Gọi trực tiếp API backend thông qua SSE
NEXT_PUBLIC_USE_MOCK=true
```

---

## 🔌 Chuẩn kết nối Backend (SSE API Contract)

Khi cấu hình `NEXT_PUBLIC_USE_MOCK=false`, frontend sẽ gửi request tới endpoint:

- **Endpoint:** `POST ${NEXT_PUBLIC_API_URL}/api/v1/chat`
- **Headers:**
  - `Content-Type: application/json`
  - `Accept: text/event-stream`
- **Request Body:**
  ```json
  {
    "message": "Điểm chuẩn ngành Công nghệ thông tin năm 2025 là bao nhiêu?",
    "conversation_id": "c_lxyz123"
  }
  ```

### Định dạng luồng dữ liệu trả về (Server-Sent Events):

Frontend hỗ trợ các chunk dữ liệu dạng `data: <JSON>` hoặc kết thúc bằng `data: [DONE]`:

1. **Nội dung câu trả lời (Text Chunk):**
   ```text
   data: {"type": "content", "data": "Điểm chuẩn ngành Công nghệ thông tin..."}
   ```
2. **Nguồn tài liệu trích dẫn (Sources):**
   ```text
   data: {"type": "sources", "sources": [{"title": "Đề án tuyển sinh UTC 2025", "content": "Nội dung trích đoạn...", "url": "https://utc.edu.vn", "score": 0.95}]}
   ```
3. **Báo lỗi từ máy chủ:**
   ```text
   data: {"type": "error", "data": "Không thể truy vấn cơ sở dữ liệu tri thức"}
   ```
4. **Kết thúc stream:**
   ```text
   data: {"type": "done"}
   ```
   *hoặc:*
   ```text
   data: [DONE]
   ```

---

## 🛠️ Hướng dẫn cài đặt và chạy ứng dụng

### 1. Yêu cầu hệ thống
- **Node.js:** phiên bản `18.18.0` trở lên (khuyên dùng Node.js 20 LTS)
- Quản lý gói: `npm`, `yarn`, `pnpm` hoặc `bun`

### 2. Cài đặt các gói phụ thuộc (Dependencies)
Tại thư mục `frontend`:

```bash
npm install
# hoặc
pnpm install
# hoặc
yarn install
```

### 3. Chạy ở môi trường phát triển (Development)

```bash
npm run dev
```

Mở trình duyệt và truy cập: [http://localhost:3000](http://localhost:3000)

### 4. Kiểm tra mã nguồn (Linting) & Đóng gói (Build)

Kiểm tra cú pháp và quy chuẩn code:
```bash
npm run lint
```

Đóng gói ứng dụng để triển khai production:
```bash
npm run build
```

Chạy bản production đã build:
```bash
npm run start
```

---

## 🎨 Giao diện & Trải nghiệm người dùng

- **Chủ đề màu sắc:** Sử dụng tông xanh navy đậm kết hợp vàng kim đặc trưng cho khối trường kỹ thuật - giao thông.
- **Tối ưu trải nghiệm gõ:** Tự động điều chỉnh kích thước ô nhập liệu, hỗ trợ tổ hợp phím `Enter` để gửi và `Shift + Enter` để xuống dòng.
- **Tương thích Mobile:** Tự động ẩn thanh bên thành nút menu mở dạng trượt mượt mà.

---

## 📄 Bản quyền

Dự án phục vụ mục đích học tập và nghiên cứu trong khuôn khổ đồ án / môn học CNTT - Trường Đại học Giao thông Vận tải.

# UTC RAG Chatbot - Backend

Đây là hệ thống Backend API cho ứng dụng **UTC RAG Chatbot** (Hệ thống hỏi đáp tự động dành cho Trường Đại học Giao thông Vận tải). Backend được xây dựng bằng **FastAPI**, kết hợp với **Supabase** (lưu trữ dữ liệu và vector) và **Gemini API** (mô hình ngôn ngữ lớn và mô hình nhúng - LLM & Embeddings).

## 🌟 Các tính năng chính

* **RAG (Retrieval-Augmented Generation)**: Kết hợp tìm kiếm vector với sức mạnh sinh văn bản của LLM để trả lời câu hỏi dựa trên ngữ cảnh.
* **Xử lý tài liệu đa định dạng**: Hỗ trợ upload và bóc tách dữ liệu từ PDF, DOCX, TXT, và Markdown.
* **Lưu trữ & Tìm kiếm Vector**: Tự động chia nhỏ văn bản (chunking) và lưu trữ dưới dạng vector nhúng vào PostgreSQL (thông qua Supabase `pgvector`), cho phép tìm kiếm ngữ nghĩa siêu tốc.
* **Streaming Responses**: Trả về câu trả lời cho frontend theo dạng Server-Sent Events (SSE) giúp tối ưu trải nghiệm người dùng.
* **Quản trị tài liệu**: Cung cấp các API Admin để quản lý tri thức của Chatbot.

## 🛠 Công nghệ sử dụng

* **Khung ứng dụng (Framework)**: FastAPI
* **Cơ sở dữ liệu & Lưu trữ**: Supabase (PostgreSQL + pgvector + Storage)
* **AI & LLM**: Google Gemini API (`gemini-2.0-flash` cho chat, `gemini-embedding-001` cho embeddings)
* **Xử lý file**: `pypdf`, `python-docx`
* **Tiện ích**: `structlog` (logging), `pydantic` (data validation)

---

## 📋 Yêu cầu hệ thống

* Python 3.12 trở lên
* Tài khoản & Project trên Supabase (https://supabase.com/)
* API Key của Google Gemini (https://aistudio.google.com/)

---

## 🚀 Hướng dẫn cài đặt chi tiết

### 1. Khởi tạo môi trường ảo

```bash
# Di chuyển vào thư mục backend
cd backend

# Tạo virtual environment
python -m venv .venv
```

Kích hoạt môi trường ảo:
* **Windows**:
  ```bash
  .\.venv\Scripts\activate
  ```
* **Linux/macOS**:
  ```bash
  source .venv/bin/activate
  ```

### 2. Cài đặt các thư viện phụ thuộc

```bash
pip install -r requirements.txt
```

### 3. Cấu hình biến môi trường

Tạo file `.env` từ file mẫu `.env.example`:

```bash
cp .env.example .env
```

Mở file `.env` và điền các thông tin:

* `SUPABASE_URL` và `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE_KEY`: Lấy trong phần Project Settings -> API của Supabase.
* `GEMINI_API_KEY`: API Key lấy từ Google AI Studio.
* Cấu hình RAG & Chunking có thể giữ nguyên mặc định.
* Đảm bảo `CORS_ORIGINS` bao gồm URL của frontend (mặc định là `http://localhost:3000`).

### 4. Thiết lập Supabase Database

1. Đăng nhập vào giao diện quản trị của Supabase Project.
2. Bật extension `vector` trong mục **Database -> Extensions**.
3. Mở **SQL Editor**, sau đó sao chép và chạy lần lượt các script trong thư mục `migrations/` theo thứ tự:
   * `001_initial_schema.sql`: Khởi tạo các bảng cơ sở.
   * `002_vector_search_function.sql`: Tạo hàm để truy vấn vector similarity.
   * `003_rls_policies.sql`: Thiết lập Row Level Security (nếu cần).
   * `004_auth_trigger.sql`: Khởi tạo trigger cho auth (tuỳ chọn).
4. Vào mục **Storage**, tạo một bucket có tên là `knowledge` để lưu trữ các file tài liệu người dùng tải lên. Thiết lập quyền truy cập cho phù hợp.

---

## 💻 Chạy ứng dụng

Chạy server ở chế độ phát triển (auto-reload khi code thay đổi):

```bash
uvicorn app.main:app --reload
```

* **Server API:** `http://localhost:8000`
* **Tài liệu API tương tác (Swagger UI):** `http://localhost:8000/docs`
* **Tài liệu Redoc:** `http://localhost:8000/redoc`

---

## 📁 Cấu trúc thư mục

```text
backend/
├── app/
│   ├── api/          # Các endpoint API (v1, routes: chat, health, admin)
│   ├── core/         # Cấu hình hệ thống (settings), bảo mật, phụ thuộc (dependencies)
│   ├── parsers/      # Module xử lý bóc tách text từ file (PDF, DOCX, TXT)
│   ├── schemas/      # Khai báo cấu trúc dữ liệu Request / Response (Pydantic models)
│   ├── services/     # Logic nghiệp vụ (RAG Service, Vector Service, Gemini Service)
│   ├── utils/        # Các tiện ích dùng chung (Text chunking, cấu hình logger)
│   └── main.py       # Điểm khởi chạy của FastAPI application
├── migrations/       # Các file SQL để setup database trên Supabase
├── .env.example      # File mẫu cấu hình biến môi trường
└── requirements.txt  # Danh sách các thư viện Python cần thiết
```

---

## 🔌 Tóm tắt các API chính

* **`GET /api/v1/health`**: Kiểm tra trạng thái hoạt động của server.
* **`POST /api/v1/chat`**: API để nhắn tin với bot, nhận response stream và danh sách sources.
* **`POST /api/v1/admin/documents`**: API upload tài liệu mới vào hệ thống (PDF, Word, Text). Hệ thống sẽ tự động bóc tách, chia nhỏ, vector hoá và lưu vào Supabase.

---

## 🔧 Các lỗi thường gặp (Troubleshooting)

1. **Lỗi không kết nối được Supabase (Auth/Vector)**:
   * Kiểm tra lại các biến môi trường `SUPABASE_URL` và `SUPABASE_SERVICE_ROLE_KEY`.
   * Đảm bảo đã chạy các file SQL migration, đặc biệt là bật extension `pgvector`.
2. **Lỗi liên quan đến Gemini API**:
   * Xác nhận `GEMINI_API_KEY` có hợp lệ và chưa vượt quá giới hạn quota của Google.
3. **Lỗi CORS khi gọi từ Frontend**:
   * Kiểm tra `CORS_ORIGINS` trong file `.env` đã có URL đang chạy frontend (ví dụ `http://localhost:3000`).

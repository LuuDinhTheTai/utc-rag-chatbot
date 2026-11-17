# UTC Frontend Mock

Bản sao độc lập của frontend với mock API trong trình duyệt để kiểm tra giao diện và thao tác khi không chạy backend.

```powershell
npm ci
npm run dev
```

Mở http://127.0.0.1:5174. Tài khoản thử đầy đủ quyền: `all@demo.utc.test`, mật khẩu `UtcDemo123!`.

Xem **[huongdan.md](./huongdan.md)** để biết toàn bộ tài khoản, kịch bản test giao diện, dữ liệu mẫu, upload PDF, lỗi và xung đột phiên bản.

`npm run build` kiểm tra bản build; `npm run test:mock` kiểm thử mock API. Chỉ dùng dữ liệu minh họa, không kết nối backend/Ollama. Các liên kết nguồn chỉ mở khi được bấm.

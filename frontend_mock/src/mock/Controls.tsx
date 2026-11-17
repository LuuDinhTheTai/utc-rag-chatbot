import { useState } from 'react';
import { mock } from './browser';
import { demoUsers, password } from './fixtures';
import './mock.css';
export default function MockControls() {
  const [open, setOpen] = useState(false); const [, update] = useState(0); const [notice, setNotice] = useState('');
  const settings = mock.settings;
  return <aside className="mock-tools"><button className="mock-toggle" onClick={() => setOpen(!open)}>🧪 MOCK API · {open ? 'Thu gọn' : 'Kịch bản test'}</button>{open && <div className="mock-controls"><h2>Chế độ thử giao diện</h2><p>Tất cả dữ liệu đều là minh họa. Không kết nối backend hoặc Ollama.</p><p>Mật khẩu chung: <code>{password}</code></p><details><summary>Tài khoản mẫu</summary>{demoUsers.map(([key, label]) => <div key={key}><code>{key}@demo.utc.test</code><small>{label}</small></div>)}</details>
    <label>Độ trễ phản hồi<select value={settings.delay} onChange={e => { settings.delay = Number(e.target.value); update(n => n + 1); }}><option value={0}>Không trễ</option><option value={250}>250 ms</option><option value={2000}>2 giây</option></select></label>
    <label><input type="checkbox" checked={settings.empty} onChange={e => { settings.empty = e.target.checked; update(n => n + 1); }}/>Danh sách rỗng (trừ cơ sở và bộ đề)</label>
    <label><input type="checkbox" checked={settings.noEvidence} onChange={e => { settings.noEvidence = e.target.checked; update(n => n + 1); }}/>Chat trả lời thiếu nguồn</label>
    <label><input type="checkbox" checked={settings.longQuiz} onChange={e => { settings.longQuiz = e.target.checked; update(n => n + 1); }}/>Bộ đề 104 câu cho lần làm mới</label>
    <div className="mock-buttons"><button onClick={() => { settings.nextError = 503; setNotice('Yêu cầu API kế tiếp sẽ lỗi 503.'); }}>Lỗi 503 một lần</button><button onClick={() => { settings.nextError = 429; setNotice('Yêu cầu API kế tiếp sẽ lỗi 429.'); }}>Lỗi 429 một lần</button><button onClick={() => { settings.nextConflict = true; setNotice('Lần lưu ETag hoặc tính điểm kế tiếp sẽ xung đột.'); }}>Xung đột phiên bản</button><button onClick={() => { mock.expire(); window.dispatchEvent(new Event('utc-session-expired')); setNotice('Đã kết thúc phiên.'); }}>Hết phiên</button><button onClick={() => { if (window.confirm('Xóa toàn bộ dữ liệu thử và khôi phục ban đầu?')) { mock.reset(); window.location.reload(); } }}>Đặt lại dữ liệu</button></div>
    <a href="/demo-upload.pdf" download>Tải PDF mẫu để test upload</a><p role="status">{notice}</p><p className="mock-hint">Đổi tùy chọn rồi chuyển màn hình hoặc bấm Thử lại/Làm mới để gọi API. Tùy chọn kịch bản trở về mặc định khi tải lại trang. Dữ liệu thao tác được lưu trong localStorage của cổng 5174.</p>
  </div>}</aside>;
}

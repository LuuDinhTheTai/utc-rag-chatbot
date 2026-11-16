import { useState, type ReactNode } from 'react';
import { AccountPanel, useSession } from './Account';
import Chat from './Chat';
import { Admissions, Programs, Guides, Finances } from './features/Catalog';
import Scoring from './features/Scoring';
import Personality from './features/Personality';
import Staff from './staff/Staff';
import { staffAccess } from './staff/model';
import { confirmLeaveStaff } from './staff/shared';
import './features/features.css';
import { query, usePage, type Campus, type Program, type ScheduleEvent, type Source } from './api';

type View = 'overview' | 'admissions' | 'programs' | 'schedule' | 'chat' | 'account' | 'personality' | 'scoring' | 'guides' | 'finances' | 'staff';
const navigation: { id: View; title: string; icon: string }[] = [
  { id: 'overview', title: 'Tổng quan', icon: 'grid' },
  { id: 'chat', title: 'Trợ lý tuyển sinh AI', icon: 'chat' },
  { id: 'admissions', title: 'Thông tin tuyển sinh', icon: 'school' },
  { id: 'programs', title: 'Ngành đào tạo', icon: 'book' },
  { id: 'schedule', title: 'Lịch tuyển sinh', icon: 'calendar' },
  { id: 'personality', title: 'Khám phá tính cách', icon: 'spark' },
  { id: 'scoring', title: 'Quy đổi điểm', icon: 'grid' },
  { id: 'guides', title: 'Hướng dẫn tân sinh viên', icon: 'book' },
  { id: 'finances', title: 'Học phí và học bổng', icon: 'school' },
  { id: 'staff', title: 'Không gian cán bộ', icon: 'user' },
];

function Icon({ name, size = 20 }: { name: string; size?: number }) {
  const paths: Record<string, ReactNode> = {
    grid: <><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></>,
    school: <><path d="m2 9 10-6 10 6-10 6L2 9Zm4 3v6c4 3 8 3 12 0v-6M22 9v8"/></>,
    book: <><path d="M12 5v16M3 4c4-1 6 0 9 2 3-2 5-3 9-2v15c-4-1-6 0-9 2-3-2-5-3-9-2V4Z"/></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M7 3v4m10-4v4M3 11h18m-14 4h3m4 0h3m-10 3h3"/></>,
    arrow: <path d="M5 12h14m-5-5 5 5-5 5"/>,
    search: <><circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/></>,
    chat: <path d="M21 11a9 9 0 0 1-9 9H3l1.5-5A9 9 0 1 1 21 11ZM8 10h8m-8 4h5"/>,
    pin: <><path d="M19 9c0 5-7 12-7 12S5 14 5 9a7 7 0 0 1 14 0Z"/><circle cx="12" cy="9" r="2"/></>,
    user: <><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></>,
    menu: <path d="M4 6h16M4 12h16M4 18h16"/>,
    spark: <path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z"/>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name] ?? paths.grid}</svg>;
}

function SourceLink({ source }: { source?: Source }) {
  if (!source) return null;
  let safe = false;
  try { safe = ['https:', 'http:'].includes(new URL(source.url).protocol); } catch { /* Unavailable source URL. */ }
  return <div className="source">{safe ? <a href={source.url} target="_blank" rel="noopener noreferrer">{source.title || 'Nguồn công bố'} ↗</a> : <span>{source.title}</span>}{source.year && <span> · {source.year}</span>}{source.updated_at && <span> · Cập nhật {formatDate(source.updated_at)}</span>}</div>;
}
function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Chưa có thời gian' : new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'Asia/Ho_Chi_Minh' }).format(date);
}

interface ListState { data: unknown[]; loading: boolean; error: string; cursor: string | null; reload: () => void; loadMore: () => void }
function Results({ state, children }: { state: ListState; children: ReactNode }) {
  return <div aria-busy={state.loading}>
    {children}
    {state.loading && <div className="loading" role="status"><span className="spinner"/> Đang tải dữ liệu…</div>}
    {state.error && <div className="error" role="alert"><span>{state.error}</span><button className="text-button" onClick={state.data.length ? state.loadMore : state.reload}>Thử lại</button></div>}
    {!state.loading && !state.error && state.data.length === 0 && <div className="empty"><span className="empty-icon"><Icon name="book" size={26}/></span><strong>Chưa có dữ liệu</strong><p>Thông tin sẽ hiển thị khi có nội dung được công bố phù hợp với bộ lọc.</p></div>}
    {state.cursor && !state.loading && !state.error && <div className="load-more"><button className="secondary" onClick={state.loadMore}>Tải thêm <Icon name="arrow" size={16}/></button></div>}
  </div>;
}

export default function App() {
  const session = useSession();
  const canStaff = !!session.account && staffAccess(session.account.roles);
  const [view, setView] = useState<View>('overview');
  const [mobileOpen, setMobileOpen] = useState(false);
  const [campus, setCampus] = useState('');
  const [yearInput, setYearInput] = useState('');
  const [year, setYear] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [programId, setProgramId] = useState('');
  const campuses = usePage<Campus>('/campuses?limit=100', session.ready, session.version);
  const scope = { campus_code: campus, year };
  const programs = usePage<Program>(`/programs?${query({ ...scope, q: search })}`, session.ready, session.version);
  const events = usePage<ScheduleEvent>(`/schedule-events?${query(scope)}`, session.ready, session.version);
  const go = (next: View) => { if (view === 'staff' && next !== view && !confirmLeaveStaff()) return; setView(next); setMobileOpen(false); if (next === 'programs') setProgramId(''); window.scrollTo({ top: 0, behavior: 'smooth' }); };
  const campusName = (code: string) => campuses.data.find(item => item.code === code)?.name ?? code;
  const reset = () => { setCampus(''); setYear(''); setYearInput(''); setSearch(''); setSearchInput(''); };
  const titles: Record<View, [string, string]> = {
    staff: ['Quản lý thông tin UTC', 'Biên tập, duyệt nội dung và vận hành theo phạm vi được phân quyền.'],
    personality: ['Hiểu bản thân, chọn hướng đi', 'Khám phá sở thích và tham khảo những ngành học phù hợp với bạn.'],
    scoring: ['Quy đổi điểm xét tuyển', 'Tính điểm theo quy tắc đã duyệt và đối chiếu điểm chuẩn tương thích.'],
    guides: ['Sẵn sàng trở thành sinh viên UTC', 'Tra cứu thủ tục, giấy tờ, học vụ và các kênh hỗ trợ.'],
    finances: ['Học phí và học bổng', 'Tìm hiểu các khoản thu, điều kiện hỗ trợ và thời hạn theo khóa học.'],
    chat: ['Trợ lý tuyển sinh AI', 'Hỏi đáp về UTC với thông tin tham khảo và nguồn trích dẫn.'],
    account: ['Tài khoản của bạn', 'Quản lý phiên đăng nhập và lưu lịch sử tư vấn cá nhân.'],
    overview: ['Cùng bạn vững bước vào UTC', 'Khám phá ngành học, tìm hiểu tuyển sinh và chuẩn bị cho hành trình đại học.'],
    admissions: ['Thông tin tuyển sinh', 'Các đợt tuyển sinh đã công bố theo cơ sở và năm tuyển sinh.'],
    programs: ['Tìm ngành học của bạn', 'Tra cứu ngành đào tạo và nguồn thông tin được công bố.'],
    schedule: ['Lịch tuyển sinh', 'Theo dõi các mốc thời gian tuyển sinh và nhập học.'],
  };

  return <div className="app">
    <a className="skip-link" href="#main">Đến nội dung chính</a>
    {mobileOpen && <button className="scrim" aria-label="Đóng menu" onClick={() => setMobileOpen(false)}/>}
    <aside className={`sidebar ${mobileOpen ? 'open' : ''}`}>
      <a className="brand" href="#" onClick={event => { event.preventDefault(); go('overview'); }}><img src="/utc-logo.webp" alt="Logo Trường Đại học Giao thông vận tải"/><span><strong>UTC</strong><small>CỔNG TƯ VẤN TUYỂN SINH</small></span></a>
      <div className="nav-label">KHÁM PHÁ UTC</div>
      <nav aria-label="Điều hướng chính">{navigation.filter(item => item.id !== 'staff' || canStaff).map(item => <button key={item.id} className={`nav-item ${view === item.id ? 'active' : ''}`} aria-current={view === item.id ? 'page' : undefined} onClick={() => go(item.id)}><Icon name={item.icon}/>{item.title}{view === item.id && <span className="nav-dot"/>}</button>)}</nav>
      <div className="sidebar-note"><span className="note-line"/><strong>Mỗi lựa chọn hôm nay,<br/>một hành trình ngày mai.</strong><p>Đồng hành cùng bạn trên con đường đến giảng đường.</p><Icon name="school" size={34}/></div>
      <div className="sidebar-footer">TRƯỜNG ĐẠI HỌC<br/><strong>GIAO THÔNG VẬN TẢI</strong></div>
    </aside>
    <div className="workspace">
      <header className="topbar"><div className="breadcrumb"><button className="menu-button" aria-label="Mở menu" aria-expanded={mobileOpen} onClick={() => setMobileOpen(!mobileOpen)}><Icon name="menu"/></button><span>Cổng tuyển sinh</span><span className="divider">/</span><strong>{view === 'account' ? 'Tài khoản' : navigation.find(item => item.id === view)?.title}</strong></div><button className="guest account-trigger" onClick={() => go('account')}><span className="guest-avatar"><Icon name="user" size={18}/></span><span>{session.account?.email ?? 'Khách'}<small>{session.account ? 'Quản lý tài khoản' : 'Đăng nhập / Đăng ký'}</small></span></button></header>
      <main id="main" tabIndex={-1}>
        <div className="page-heading"><div><div className="eyebrow">ĐẠI HỌC GIAO THÔNG VẬN TẢI</div><h1>{titles[view][0]}</h1><p>{titles[view][1]}</p></div><span className="page-marker">UTC / TUYỂN SINH</span></div>
        {session.error && <div className="error" role="alert">{session.error}<button className="text-button" onClick={session.reconnect}>Kết nối lại</button></div>}
        {view === 'account' && <AccountPanel account={session.account} onChange={session.change}/>}
        <div hidden={view !== 'chat'}>{session.ready ? <Chat key={session.version} account={session.account} onLogin={() => go('account')}/> : view === 'chat' && !session.error && <p role="status" className="loading">Đang khởi tạo phiên…</p>}</div>
        <div hidden={view !== 'personality'}>{session.ready && <Personality key={session.version} account={session.account} onProgram={id => { reset(); go('programs'); setProgramId(id); }}/>}</div>
        {view === 'staff' && (session.ready && canStaff && session.account ? <Staff key={session.version} account={session.account}/> : <div className="panel"><p>Đăng nhập bằng tài khoản được cấp quyền để mở không gian cán bộ.</p><button className="text-button" onClick={() => go('account')}>Đến tài khoản</button></div>)}
        {view !== 'chat' && view !== 'account' && view !== 'personality' && view !== 'staff' && <>
        <form className="filters" onSubmit={event => { event.preventDefault(); setYear(yearInput); }}><div className="filter-caption"><Icon name="pin"/><strong>Phạm vi tra cứu</strong></div><label>Cơ sở<select value={campus} onChange={event => setCampus(event.target.value)} disabled={campuses.loading && !campuses.data.length}><option value="">Tất cả cơ sở</option>{campuses.data.map(item => <option key={item.id} value={item.code}>{item.name}</option>)}</select></label><label>Năm tuyển sinh<input aria-label="Năm tuyển sinh" type="number" min="1900" max="9999" step="1" placeholder="Tất cả năm" value={yearInput} onChange={event => setYearInput(event.target.value)}/></label><button className="primary" type="submit">Áp dụng</button><button className="text-button" type="button" onClick={reset}>Đặt lại</button></form>
        {campuses.error && <div className="error" role="alert">Không tải được danh sách cơ sở. <button className="text-button" onClick={campuses.reload}>Thử lại</button></div>}
        {campuses.cursor && <button className="text-button" onClick={campuses.loadMore} disabled={campuses.loading}>Tải thêm cơ sở</button>}
        <div className="scope-label">Đang xem: <strong>{campus ? campusName(campus) : 'Tất cả cơ sở'}</strong><span>·</span><strong>{year || 'Tất cả năm'}</strong></div>
        {session.ready && <div key={`${campus}:${year}:${session.version}`}>
          {view === 'programs' && <Programs key={programId} scope={scope} initialId={programId}/>}
          {view === 'admissions' && <Admissions scope={scope}/>}
          {view === 'scoring' && <Scoring scope={scope}/>}
          {view === 'guides' && <Guides scope={scope}/>}
          {view === 'finances' && <Finances scope={scope}/>}
        </div>}
        {view === 'overview' && <>
          <section className="hero"><div className="hero-content"><span className="hero-tag"><span/> HÀNH TRÌNH ĐẾN GIẢNG ĐƯỜNG</span><h2>Hiểu ngành học.<br/>Vững lựa chọn tương lai.</h2><p>Tìm hiểu thông tin tuyển sinh và ngành đào tạo<br className="desktop-break"/> để chuẩn bị cho bước ngoặt tiếp theo của bạn.</p><button className="white-button" onClick={() => go('programs')}>Khám phá ngành đào tạo <Icon name="arrow" size={18}/></button></div><div className="hero-art" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="logo-disc"><img src="/utc-logo.webp" alt=""/></div><span className="art-label">UNIVERSITY OF<br/>TRANSPORT AND COMMUNICATIONS</span></div></section>
          <div className="shortcuts">{navigation.filter(item => ['admissions', 'programs', 'schedule'].includes(item.id)).map((item, i) => <button className="shortcut" key={item.id} onClick={() => go(item.id)}><span className="shortcut-icon"><Icon name={item.icon} size={24}/></span><span><strong>{item.title}</strong><small>{['Đợt tuyển sinh & nguồn công bố', 'Tra cứu ngành theo tên hoặc mã', 'Các mốc thời gian cần ghi nhớ'][i]}</small></span><Icon name="arrow" size={18}/></button>)}</div>
        </>}
        <div className={view === 'overview' ? 'content-grid' : ''}>
          <div className="main-column">
            {(view === 'overview') && <section className="panel"><div className="panel-heading"><div><span className="section-kicker">CHỌN NGÀNH · CHỌN TƯƠNG LAI</span><h2>Ngành đào tạo</h2></div><span className="section-icon"><Icon name="book"/></span></div><form className="search-form" onSubmit={event => { event.preventDefault(); setSearch(searchInput.trim()); }}><Icon name="search" size={19}/><input aria-label="Tên hoặc mã ngành" placeholder="Tìm theo tên hoặc mã ngành…" value={searchInput} onChange={event => setSearchInput(event.target.value)}/><button type="submit">Tìm kiếm</button></form>{search && <p className="search-caption">Kết quả cho “{search}” <button className="text-button" onClick={() => { setSearch(''); setSearchInput(''); }}>Xóa tìm kiếm</button></p>}<Results state={programs}><div className="program-list">{programs.data.map(program => <article key={program.id} className="program-row"><div className="program-symbol"><Icon name="school"/></div><div><span className="code">MÃ NGÀNH {program.code}</span><h3>{program.name}</h3><SourceLink source={program.source}/></div></article>)}</div></Results></section>}
          </div>
          {(view === 'overview' || view === 'schedule') && <section className="panel schedule-panel"><div className="panel-heading"><div><span className="section-kicker">ĐỪNG BỎ LỠ</span><h2>Mốc thời gian</h2></div><span className="section-icon"><Icon name="calendar"/></span></div><p className="panel-description">Lịch tuyển sinh và nhập học theo thời gian công bố.</p><Results state={events}>{events.data.map(event => <article className="event" key={event.id}><span className="timeline-dot"/><time dateTime={event.starts_at}>{formatDate(event.starts_at)}{event.ends_at && ` – ${formatDate(event.ends_at)}`}</time><h3>{event.title}</h3><p>{campusName(event.campus_code)}{event.location && ` · ${event.location}`}</p>{event.notes && <p className="event-notes">{event.notes}</p>}<SourceLink source={event.source}/></article>)}</Results></section>}
        </div>
        {view === 'overview' && <section className="admission-strip"><span className="strip-icon"><Icon name="school" size={26}/></span><div><h3>Chuẩn bị cho kỳ tuyển sinh của bạn</h3><p>Xem các đợt tuyển sinh và tài liệu công bố theo từng cơ sở.</p></div><button className="secondary" onClick={() => go('admissions')}>Xem tuyển sinh <Icon name="arrow" size={17}/></button></section>}
        </>}
        <footer className="footer"><span>UTC · Cổng thông tin tư vấn tuyển sinh</span><span>Thông tin tham khảo từ nội dung được công bố</span></footer>
      </main>
    </div>
  </div>;
}

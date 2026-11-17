import { useState } from 'react';
import { query, usePage, type Campus } from '../api';
import { request } from '../client';
import { Fields, FilterForm, ListStatus, value, type Row } from '../features/shared';
import { Confirm, RemoteStatus, useRemote } from './shared';

const labels: Record<string, string> = { STUDENT: 'Sinh viên', STAFF_EDITOR: 'Biên tập', STAFF_REVIEWER: 'Duyệt nội dung', TECH_OPERATOR: 'Vận hành kỹ thuật', ADMIN: 'Quản trị tài khoản' };
export default function Administration({ currentId, campuses }: { currentId: string; campuses: Campus[] }) {
  const [filters, setFilters] = useState<Record<string, string>>({}); const [selected, select] = useState('');
  const accounts = usePage<Row>(`/admin/accounts?${query(filters)}`);
  return <section className="panel feature-panel"><div className="panel-heading"><h2>Quản trị tài khoản</h2>{selected && <button className="secondary" onClick={() => select('')}>← Danh sách tài khoản</button>}</div><p className="feature-hint">Thay đổi trạng thái hoặc quyền sẽ làm mất hiệu lực phiên hiện tại của tài khoản đó. Hệ thống phải giữ ít nhất một quản trị viên toàn trường đang hoạt động.</p>
    {selected ? <AccountDetail key={selected} id={selected} self={selected === currentId} campuses={campuses} onChanged={accounts.reload}/> : <><FilterForm fields={[["q", "Email"], ["status", "Trạng thái (ACTIVE / LOCKED)"]]} onApply={setFilters}/>{accounts.data.map(row => <div className="feature-row row-between" key={row.id}><div><h3>{value(row.email)}</h3><p>{value(row.status)}{row.id === currentId && ' · Tài khoản của bạn'}</p></div><button className="secondary" onClick={() => select(row.id)}>Quản lý</button></div>)}<ListStatus state={accounts}/></>}
  </section>;
}
function AccountDetail({ id, self, campuses, onChanged }: { id: string; self: boolean; campuses: Campus[]; onChanged: () => void }) {
  const state = useRemote<Row>(`/admin/accounts/${id}`);
  const assignments = usePage<Row>(`/admin/accounts/${id}/role-assignments?limit=20`);
  const [role, setRole] = useState('STUDENT'); const [campus, setCampus] = useState('');
  const [grant, setGrant] = useState<{ role_code: string; campus_code: string | null } | null>(null);
  const [status, setStatus] = useState<string | null>(null); const [revoke, setRevoke] = useState('');
  const changed = () => { if (self) { window.dispatchEvent(new Event('utc-session-expired')); return; } setGrant(null); setStatus(null); setRevoke(''); state.reload(); assignments.reload(); onChanged(); };
  return <><RemoteStatus state={state}/>{state.data && <><Fields row={state.data} fields={[["email", "Email"], ["status", "Trạng thái"], ["created_at", "Ngày tạo"]]}/>{!status && <button className="secondary" disabled={!!grant || !!revoke} onClick={() => setStatus(state.data?.status === 'ACTIVE' ? 'LOCKED' : 'ACTIVE')}>{state.data.status === 'ACTIVE' ? 'Khóa tài khoản' : 'Mở khóa tài khoản'}</button>}
    {status && <Confirm title={`${status === 'LOCKED' ? 'Khóa' : 'Mở khóa'} tài khoản ${value(state.data.email)}?`} onCancel={() => setStatus(null)} onConfirm={signal => request(`/admin/accounts/${id}`, { method: 'PATCH', body: { status }, signal })} done={changed}><p>{self ? 'Bạn đang thay đổi chính tài khoản của mình và sẽ cần đăng nhập lại.' : 'Các phiên đăng nhập hiện tại của tài khoản sẽ hết hiệu lực.'}</p></Confirm>}
    <h3 className="staff-section-title">Vai trò và phạm vi đã cấp</h3>{assignments.data.map(row => <div className="feature-row row-between" key={row.id}><div><strong>{labels[value(row.role_code)] ?? value(row.role_code)}</strong><p>{row.campus_code == null ? 'Toàn trường' : value(row.campus_code)}</p></div><button className="text-button danger" disabled={!!grant || !!status || !!revoke} onClick={() => setRevoke(row.id)}>Thu hồi</button></div>)}<ListStatus state={assignments}/>
    {revoke && <Revoke key={revoke} id={revoke} accountId={id} email={value(state.data.email)} onCancel={() => setRevoke('')} onChanged={changed}/>}
    <form className="feature-filters" onSubmit={event => { event.preventDefault(); setGrant({ role_code: role, campus_code: ['STAFF_EDITOR', 'STAFF_REVIEWER'].includes(role) ? campus || null : null }); }}><label>Vai trò<select value={role} onChange={event => { setRole(event.target.value); setCampus(''); }}>{Object.entries(labels).map(([code, label]) => <option key={code} value={code}>{label}</option>)}</select></label>{['STAFF_EDITOR', 'STAFF_REVIEWER'].includes(role) && <label>Phạm vi<select value={campus} onChange={event => setCampus(event.target.value)}><option value="">Toàn trường</option>{campuses.map(item => <option key={item.id} value={item.code}>{item.name}</option>)}</select></label>}<button className="primary" disabled={!!grant || !!status || !!revoke}>Cấp vai trò</button></form>
    {grant && <Confirm title={`Cấp quyền cho ${value(state.data.email)}?`} onCancel={() => setGrant(null)} onConfirm={signal => request(`/admin/accounts/${id}/role-assignments`, { method: 'POST', body: grant, signal })} done={changed}><p>{labels[grant.role_code]} · {grant.campus_code ?? 'Toàn trường'}</p>{self && <p>Phiên của bạn sẽ hết hiệu lực sau khi cấp quyền.</p>}</Confirm>}</>}
  </>;
}
function Revoke({ id, accountId, email, onCancel, onChanged }: { id: string; accountId: string; email: string; onCancel: () => void; onChanged: () => void }) {
  const state = useRemote<Row>(`/admin/role-assignments/${id}`);
  return <><RemoteStatus state={state}/>{state.data && <Confirm title={`Thu hồi quyền của ${email}?`} onCancel={onCancel} onConfirm={async signal => { if (value(state.data?.user_id) !== accountId) throw new Error('Quyền không thuộc tài khoản đang xem.'); await request(`/admin/role-assignments/${id}`, { method: 'DELETE', signal }); }} done={onChanged}><p>{labels[value(state.data.role_code)]} · {value(state.data.campus_code) || 'Toàn trường'}</p></Confirm>}</>;
}

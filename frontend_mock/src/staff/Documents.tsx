import { useState } from 'react';
import { query, usePage, type Campus } from '../api';
import { request } from '../client';
import { Fields, FilterForm, ListStatus, Notice, safeUrl, useAction, value, type Row } from '../features/shared';
import { officialUrl, permitted, type Role } from './model';
import { CampusSelect, Confirm, RemoteStatus, confirmLeaveStaff, useDirty, useRemote } from './shared';
import Publication from './Publication';

const indexing = (row: Row) => row.status === 'INDEXING';
export default function Documents({ roles, campuses }: { roles: Role[]; campuses: Campus[] }) {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const documents = usePage<Row>(`/editorial/documents?${query(filters)}`);
  const [selection, select] = useState<{ document: string; revision?: string } | null>(null);
  const [upload, setUpload] = useState(false);
  return <section className="panel feature-panel"><div className="panel-heading"><h2>Tài liệu RAG</h2>{selection || upload ? <button className="secondary" onClick={() => { if (confirmLeaveStaff()) { select(null); setUpload(false); } }}>← Danh sách tài liệu</button> : permitted(roles, 'STAFF_EDITOR') && <button className="primary" onClick={() => setUpload(true)}>Tải tài liệu PDF</button>}</div>
    {upload ? <Upload roles={roles} campuses={campuses} onUploaded={row => { setUpload(false); select({ document: value(row.document_id), revision: row.id }); documents.reload(); }}/> : selection ? <DocumentDetail key={selection.document} id={selection.document} initialRevision={selection.revision} roles={roles} campuses={campuses}/> : <><FilterForm fields={[["q", "Tiêu đề"], ["campus_code", "Mã cơ sở"], ["year", "Năm"]]} onApply={setFilters}/>{documents.data.map(row => <div className="feature-row row-between" key={row.id}><div><h3>Tài liệu {row.id}</h3>{safeUrl(row.canonical_url) && <a className="source" href={safeUrl(row.canonical_url)} target="_blank" rel="noopener noreferrer">{value(row.canonical_url)} ↗</a>}</div><button className="secondary" onClick={() => select({ document: row.id })}>Xem các phiên bản</button></div>)}<ListStatus state={documents}/></>}
  </section>;
}
function Upload({ roles, campuses, onUploaded }: { roles: Role[]; campuses: Campus[]; onUploaded: (row: Row) => void }) {
  const [campus, setCampus] = useState(''); const action = useAction();
  const [dirty, setDirty] = useState(false); useDirty(dirty);
  return <form onChange={() => setDirty(true)} onSubmit={event => { event.preventDefault(); const form = new FormData(event.currentTarget); void action.run(async signal => {
    if (!permitted(roles, 'STAFF_EDITOR', campus || null)) throw new Error('Chọn phạm vi cơ sở được cấp quyền.');
    const file = form.get('file'); if (!(file instanceof File) || file.size === 0 || file.size > 20 * 1024 * 1024) throw new Error('Chọn tệp PDF có dữ liệu, tối đa 20 MiB.');
    if (await file.slice(0, 5).text() !== '%PDF-') throw new Error('Tệp phải là PDF hợp lệ.');
    if (!officialUrl(String(form.get('official_url')))) throw new Error('Nguồn phải dùng HTTPS tại utc.edu.vn hoặc utc2.edu.vn (bao gồm tên miền con).');
    if (campus) form.set('campus_code', campus); if (!form.get('year')) form.delete('year');
    const row = await request<Row>('/editorial/document-revisions', { method: 'POST', body: form, signal }); if (!signal.aborted) onUploaded(row);
  }); }}><fieldset className="feature-fieldset" disabled={action.busy}><div className="staff-fields"><label className="wide-field">Tiêu đề *<input name="title" required maxLength={1000}/></label><label className="wide-field">URL nguồn chính thức *<input name="official_url" type="url" required placeholder="https://…utc.edu.vn/…"/></label><label>Năm<input name="year" type="number" min={1900} max={2200}/></label><label>PDF (tối đa 20 MiB) *<input name="file" type="file" accept="application/pdf,.pdf" required/></label></div><CampusSelect campuses={campuses} roles={roles} role="STAFF_EDITOR" selected={campus} onChange={setCampus} allowGlobal/><p className="feature-hint">Tệp được lập chỉ mục trước khi người duyệt xác minh và xuất bản. PDF mã hóa hoặc không có trang sẽ bị từ chối.</p><button className="primary">{action.busy ? 'Đang tải lên…' : 'Tải lên và lập chỉ mục'}</button></fieldset><Notice error={action.error}/></form>;
}
function DocumentDetail({ id, initialRevision, roles, campuses }: { id: string; initialRevision?: string; roles: Role[]; campuses: Campus[] }) {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const versions = usePage<Row>(`/editorial/documents/${id}/revisions?${query(filters)}`);
  const [selected, select] = useState(initialRevision ?? '');
  const [revision, revise] = useState(0);
  return <><h3>Tài liệu {id}</h3><FilterForm fields={[["status", "Trạng thái"], ["campus_code", "Mã cơ sở"], ["year", "Năm"]]} onApply={setFilters}/>{versions.data.map(row => <div className="feature-row row-between" key={row.id}><div><h3>{value(row.title)}</h3><p>Phiên bản {row.id} · {value(row.status)} · {value(row.admission_year)} · {row.source_verified ? 'Nguồn đã xác minh' : 'Chưa xác minh'}</p></div><button className="secondary" onClick={() => select(row.id)}>Kiểm tra phiên bản</button></div>)}<ListStatus state={versions}/>{selected && <Revision key={`${selected}:${revision}`} id={selected} document={id} roles={roles} campuses={campuses} onChanged={() => { versions.reload(); revise(n => n + 1); }}/>}</>;
}
function Revision({ id, document, roles, campuses, onChanged }: { id: string; document: string; roles: Role[]; campuses: Campus[]; onChanged: () => void }) {
  const state = useRemote<Row>(`/editorial/document-revisions/${id}`, indexing);
  const [revoking, setRevoking] = useState(false); const [reason, setReason] = useState('');
  const row = state.data;
  const campus = row?.campus_id == null ? null : campuses.find(item => String(item.id) === String(row.campus_id))?.code;
  const canReview = !!row && (row.campus_id == null || campus !== undefined) && permitted(roles, 'STAFF_REVIEWER', campus);
  return <section className="detail-box"><h2>Phiên bản tài liệu {id}</h2><RemoteStatus state={state}/>{row && <><Fields row={row} fields={[["title", "Tiêu đề"], ["status", "Trạng thái"], ["admission_year", "Năm"], ["updated_at", "Cập nhật"], ["revoked_at", "Thu hồi lúc"], ["revocation_reason", "Lý do thu hồi"]]}/><p>Phạm vi: {campus ?? (row.campus_id == null ? 'Toàn trường' : `Cơ sở ID ${value(row.campus_id)}`)} · {row.source_verified ? 'Nguồn đã xác minh' : 'Nguồn chưa xác minh'}</p>{safeUrl(row.official_url) && <a href={safeUrl(row.official_url)} target="_blank" rel="noopener noreferrer">Mở văn bản nguồn chính thức ↗</a>}{row.status === 'INDEXING' && <p role="status" className="feature-hint">Đang lập chỉ mục; trạng thái cập nhật mỗi 5 giây.</p>}<button className="text-button" onClick={state.reload}>Làm mới trạng thái</button>
    <Publication path={`/editorial/documents/${document}/publication`} selected={id} canReview={canReview} allowPublish={!state.error && !row.revoked_at && ['PENDING', 'PUBLISHED', 'ARCHIVED'].includes(value(row.status))} onChanged={onChanged}/>
    {canReview && row.source_verified === true && !row.revoked_at && !revoking && <button className="secondary danger" onClick={() => setRevoking(true)}>Thu hồi nguồn đã xác minh</button>}
    {revoking && <Confirm title={`Thu hồi nguồn phiên bản ${id}?`} onCancel={() => setRevoking(false)} onConfirm={async signal => { if (!reason.trim()) throw new Error('Vui lòng nhập lý do thu hồi.'); await request(`/editorial/document-revisions/${id}/revocation`, { method: 'PUT', body: { reason: reason.trim() }, signal }); }} done={onChanged}><p>Nguồn bị thu hồi sẽ không còn được dùng cho dữ liệu công khai, RAG và quy đổi điểm. API hiện không có thao tác hoàn tác thu hồi.</p><label className="feature-label">Lý do<textarea value={reason} onChange={event => setReason(event.target.value)} required/></label></Confirm>}</>}
  </section>;
}

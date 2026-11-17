import { useEffect, useState } from 'react';
import { query, usePage, type Campus } from '../api';
import { ApiError, request } from '../client';
import { Fields, FilterForm, ListStatus, Notice, date, useAction, value, type Row } from '../features/shared';
import { editable, kinds, payload, permitted, programFields, type Data, type Kind, type Role } from './model';
import { CampusSelect, EditorFields, RemoteStatus, confirmLeaveStaff, useDirty, useRemote, versioned } from './shared';
import Publication from './Publication';

export default function Editorial({ roles, campuses }: { roles: Role[]; campuses: Campus[] }) {
  const [kind, setKind] = useState(kinds[0].key);
  return <><div className="staff-tabs">{kinds.map(item => <button className={kind === item.key ? 'selected' : ''} key={item.key} onClick={() => { if (confirmLeaveStaff()) setKind(item.key); }}>{item.label}</button>)}{(permitted(roles, 'STAFF_EDITOR', null) || permitted(roles, 'STAFF_REVIEWER', null)) && <button className={kind === 'programs' ? 'selected' : ''} onClick={() => { if (confirmLeaveStaff()) setKind('programs'); }}>Danh mục ngành</button>}</div>{kind === 'programs' ? <Programs roles={roles}/> : <KindPanel key={kind} kind={kinds.find(item => item.key === kind)!} roles={roles} campuses={campuses}/>}</>;
}
function KindPanel({ kind, roles, campuses }: { kind: Kind; roles: Role[]; campuses: Campus[] }) {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [item, setItem] = useState<Row | null>(null);
  const [creating, setCreating] = useState(false);
  const rows = usePage<Row>(`/editorial/${kind.key}?${query(filters)}`);
  return <section className="panel feature-panel"><div className="panel-heading"><h2>{kind.label}</h2>{item || creating ? <button className="secondary" onClick={() => { if (confirmLeaveStaff()) { setItem(null); setCreating(false); } }}>← Danh sách</button> : permitted(roles, 'STAFF_EDITOR') && <button className="primary" onClick={() => setCreating(true)}>Tạo mục mới</button>}</div>
    {item ? <ItemPanel key={item.id} kind={kind} item={item} roles={roles}/> : creating ? <IdentityForm kind={kind} roles={roles} campuses={campuses} onCreated={row => { rows.reload(); setItem(row); setCreating(false); }}/> : <><FilterForm fields={[["campus_code", "Mã cơ sở"], ["year", "Năm / khóa"]]} onApply={setFilters}/>{rows.data.map(row => <div className="feature-row row-between" key={row.id}><div><h3>Mục {row.id} · {value(row.campus_code)}</h3><p>{value(row.admission_year ?? row.entry_year)} · {value(row.round_code ?? row.guide_slug ?? row.policy_code ?? row.program_id ?? row.method_id)}</p></div><button className="secondary" onClick={() => setItem(row)}>Xem phiên bản</button></div>)}<ListStatus state={rows}/></>}
  </section>;
}
function IdentityForm({ kind, roles, campuses, onCreated }: { kind: Kind; roles: Role[]; campuses: Campus[]; onCreated: (row: Row) => void }) {
  const [campus, setCampus] = useState(''); const [data, setData] = useState<Data>({}); const action = useAction();
  useDirty(Object.keys(data).length > 0);
  return <form onSubmit={event => { event.preventDefault(); void action.run(async signal => { if (!campus || !permitted(roles, 'STAFF_EDITOR', campus)) throw new Error('Chọn cơ sở được cấp quyền biên tập.'); const row = await request<Row>(`/editorial/${kind.key}`, { method: 'POST', body: { ...payload(kind.identity, data), campus_code: campus }, signal }); if (!signal.aborted) onCreated({ ...row, campus_code: campus }); }); }}><fieldset disabled={action.busy} className="feature-fieldset"><CampusSelect campuses={campuses} roles={roles} role="STAFF_EDITOR" selected={campus} onChange={setCampus}/><EditorFields fields={kind.identity} data={data} onChange={setData}/><p className="feature-hint">Mã định danh là ID trong hệ thống. Mục mới chưa hiển thị công khai; cần tạo bản nháp và được duyệt.</p><button className="primary" disabled={!campus}>Tạo mục</button></fieldset><Notice error={action.error}/></form>;
}
function ItemPanel({ kind, item, roles }: { kind: Kind; item: Row; roles: Role[] }) {
  const base = `/editorial/${kind.key}/${item.id}`;
  const detail = useRemote<Row>(base);
  const versions = usePage<Row>(`${base}/versions?limit=20`);
  const [selection, select] = useState<{ id?: string; base?: Data } | null>(null);
  const [editorKey, setEditorKey] = useState(0);
  const canEdit = permitted(roles, 'STAFF_EDITOR', value(item.campus_code));
  const canReview = permitted(roles, 'STAFF_REVIEWER', value(item.campus_code));
  const changed = () => { versions.reload(); detail.reload(); };
  return <><p className="scope-banner">Mục {item.id} · Cơ sở {value(item.campus_code)}</p><RemoteStatus state={detail}/>{detail.data && <Fields row={detail.data} fields={kind.identity.map(field => [field.key, field.label])}/>}
    {!selection && <><Publication path={`${base}/publication`} canReview={canReview} onChanged={changed}/><div className="row-between"><h3>Các phiên bản</h3>{canEdit && <button className="primary" onClick={() => select({})}>Tạo bản nháp</button>}</div>{versions.data.map(version => <div className="feature-row row-between" key={version.id}><div><h3>Bản {value(version.version_no)} · ID {version.id}</h3><p>{value(version.status)} · {date(version.updated_at)}</p></div><button className="secondary" onClick={() => select({ id: version.id })}>Mở nội dung</button></div>)}<ListStatus state={versions}/></>}
    {selection && <><button className="text-button" onClick={() => { if (window.confirm('Rời biểu mẫu? Những thay đổi chưa lưu sẽ bị bỏ.')) select(null); }}>← Quay lại các phiên bản</button>{selection.id ? <LoadedVersion key={`${selection.id}:${editorKey}`} path={`${base}/versions/${selection.id}`} kind={kind} base={base} canEdit={canEdit} canReview={canReview} onSaved={changed} onPublished={() => { changed(); setEditorKey(n => n + 1); }} onCopy={data => { select({ base: data }); setEditorKey(n => n + 1); }}/> : <DraftEditor key={`new:${editorKey}`} kind={kind} base={base} initial={selection.base ?? {}} canEdit={canEdit} onSaved={row => { changed(); select({ id: row.id }); }}/>}</>}
  </>;
}
function LoadedVersion({ path, kind, base, canEdit, canReview, onSaved, onPublished, onCopy }: { path: string; kind: Kind; base: string; canEdit: boolean; canReview: boolean; onSaved: () => void; onPublished: () => void; onCopy: (data: Data) => void }) {
  const remote = useRemote<Row>(path);
  const [unsaved, setUnsaved] = useState(false);
  return <><RemoteStatus state={remote}/>{remote.data && <><p className="scope-banner">Bản {value(remote.data.version_no)} · {value(remote.data.status)}</p><DraftEditor key={remote.etag ?? remote.data.id} kind={kind} base={base} initial={remote.data} initialTag={remote.etag} canEdit={canEdit && remote.data.status === 'DRAFT'} onDirty={setUnsaved} onSaved={() => { onSaved(); remote.reload(); }}/>{canEdit && <button type="button" className="secondary" disabled={unsaved} onClick={() => onCopy(editable(kind.draft, remote.data!))}>Sao chép bản đã lưu thành bản nháp mới</button>}{unsaved && <p className="feature-hint">Lưu thay đổi trước khi sao chép hoặc duyệt xuất bản.</p>}<Publication path={`${base}/publication`} selected={remote.data.id} canReview={canReview && !unsaved} onChanged={onPublished}/></>}</>;
}
function DraftEditor({ kind, base, initial, initialTag = null, canEdit, onSaved, onDirty }: { kind: Kind; base: string; initial: Data; initialTag?: string | null; canEdit: boolean; onSaved: (row: Row) => void; onDirty?: (dirty: boolean) => void }) {
  const [data, setData] = useState(() => editable(kind.draft, initial));
  const [etag, setEtag] = useState(initialTag); const [dirty, setDirty] = useState(false); const [conflict, setConflict] = useState(false);
  const [latest, setLatest] = useState<{ data: Row; etag: string | null } | null>(null);
  const action = useAction(); const reading = useAction();
  const id = value(initial.id); useDirty(dirty);
  useEffect(() => { onDirty?.(dirty); return () => onDirty?.(false); }, [dirty, onDirty]);
  return <><form onSubmit={event => { event.preventDefault(); void action.run(async signal => {
    if (!canEdit || conflict || (id && !etag)) throw new Error('Hãy đọc lại phiên bản trước khi lưu.');
    try { const result = await versioned<Row>(`${base}/versions${id ? `/${id}` : ''}`, { method: id ? 'PUT' : 'POST', body: payload(kind.draft, data), ifMatch: etag ?? undefined, signal }); if (!signal.aborted) { setData(editable(kind.draft, result.data)); setEtag(result.etag); setDirty(false); action.setMessage('Đã lưu bản nháp.'); onSaved(result.data); } }
    catch (error) { if (error instanceof ApiError && [412, 428, 409].includes(error.status)) setConflict(true); throw error; }
  }); }}><fieldset className="feature-fieldset" disabled={action.busy}><EditorFields fields={kind.draft} data={data} readonly={!canEdit} onChange={next => { setData(next); setDirty(true); action.setMessage(''); }}/>{canEdit && <><p className="feature-hint">Mã nguồn lấy từ phiên bản trong Tài liệu RAG. Lưu bản nháp thay thế toàn bộ các mục con đang hiển thị.</p><button className="primary" disabled={conflict || (!!id && !etag)}>{action.busy ? 'Đang lưu…' : 'Lưu bản nháp'}</button></>}</fieldset><Notice error={action.error}/>{action.message && <p className="success-note" role="status">{action.message}</p>}</form>
    {conflict && <section className="confirm-box"><h3>Giữ lại phần bạn đang nhập</h3><p>Bản trên máy chủ đã thay đổi hoặc không còn được sửa. Không tự ghi đè.</p><button className="secondary" disabled={reading.busy} onClick={() => void reading.run(async signal => { const result = await versioned<Row>(`${base}/versions/${id}`, { signal }); if (!signal.aborted) setLatest(result); })}>Đọc bản mới để đối chiếu</button><Notice error={reading.error}/>{latest && <><h3>Bản hiện tại trên máy chủ · {value(latest.data.status)}</h3><EditorFields fields={kind.draft} data={editable(kind.draft, latest.data)} readonly onChange={() => {}}/><button className="secondary" disabled={latest.data.status !== 'DRAFT' || !latest.etag} onClick={() => { setData(editable(kind.draft, latest.data)); setEtag(latest.etag); setConflict(false); setLatest(null); setDirty(false); action.setError(''); }}>Bỏ phần đang nhập, dùng bản mới</button></>}</section>}
  </>;
}
function Programs({ roles }: { roles: Role[] }) {
  const [data, setData] = useState<Data>({}); const [id, setId] = useState(''); const [selected, setSelected] = useState(''); const action = useAction();
  useDirty(Object.keys(data).length > 0);
  return <section className="panel"><h2>Danh mục ngành toàn trường</h2><form className="feature-filters" onSubmit={event => { event.preventDefault(); if (/^[1-9]\d*$/.test(id)) setSelected(id); }}><label>Mã định danh ngành<input required pattern="[1-9][0-9]*" value={id} onChange={event => setId(event.target.value)}/></label><button className="secondary">Tra cứu</button></form>{selected && <ProgramRecord key={selected} id={selected}/>}{permitted(roles, 'STAFF_EDITOR', null) && <form onSubmit={event => { event.preventDefault(); void action.run(async signal => { const row = await request<Row>('/editorial/programs', { method: 'POST', body: payload(programFields, data), signal }); if (!signal.aborted) { setSelected(row.id); setData({}); action.setMessage('Đã tạo ngành.'); } }); }}><h3>Tạo ngành</h3><fieldset className="feature-fieldset" disabled={action.busy}><EditorFields fields={programFields} data={data} onChange={setData}/><button className="primary">Tạo ngành</button></fieldset><Notice error={action.error}/>{action.message && <p role="status">{action.message}</p>}</form>}</section>;
}
function ProgramRecord({ id }: { id: string }) { const state = useRemote<Row>(`/editorial/programs/${id}`); return <><RemoteStatus state={state}/>{state.data && <Fields row={state.data} fields={programFields.map(field => [field.key, field.label])}/>}</>; }

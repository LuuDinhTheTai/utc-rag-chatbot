import { useEffect, useState, type ReactNode } from 'react';
import { request, errorText, type RequestOptions } from '../client';
import { Notice, useAction, value } from '../features/shared';
import type { Campus } from '../api';
import { permitted, type Data, type Field, type Role } from './model';

export async function versioned<T>(path: string, options: RequestOptions = {}) {
  let etag: string | null = null;
  const data = await request<T>(path, { ...options, onResponse: headers => { etag = headers.get('ETag'); } });
  return { data, etag };
}
export function useRemote<T>(path: string, pollWhile?: (data: T) => boolean) {
  const [revision, revise] = useState(0);
  const [state, setState] = useState<{ path: string; data: T | null; etag: string | null; loading: boolean; error: string }>({ path, data: null, etag: null, loading: true, error: '' });
  useEffect(() => {
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout> | undefined;
    setState({ path, data: null, etag: null, loading: true, error: '' });
    async function load() {
      try {
        const response = await versioned<T>(path, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setState({ path, ...response, loading: false, error: '' });
        if (pollWhile?.(response.data)) timer = setTimeout(load, 5000);
      } catch (error) { if (!controller.signal.aborted) setState(old => ({ ...old, loading: false, error: errorText(error) })); }
    }
    void load(); return () => { controller.abort(); clearTimeout(timer); };
  }, [path, revision, pollWhile]);
  return { ...(state.path === path ? state : { data: null, etag: null, loading: true, error: '' }), reload: () => revise(n => n + 1) };
}
export function RemoteStatus({ state }: { state: { loading: boolean; error: string; reload: () => void } }) {
  return <>{state.loading && <p className="loading" role="status">Đang tải…</p>}<Notice error={state.error} retry={state.reload}/></>;
}
export function CampusSelect({ campuses, roles, role, selected, onChange, allowGlobal = false }: { campuses: Campus[]; roles: Role[]; role: string; selected: string; onChange: (code: string) => void; allowGlobal?: boolean }) {
  return <label className="feature-label">Phạm vi cơ sở<select required={!allowGlobal} value={selected} onChange={event => onChange(event.target.value)}><option value="">{allowGlobal && permitted(roles, role, null) ? 'Toàn trường' : 'Chọn cơ sở'}</option>{campuses.filter(campus => permitted(roles, role, campus.code)).map(campus => <option key={campus.id} value={campus.code}>{campus.name}</option>)}</select></label>;
}
export function EditorFields({ fields, data, onChange, readonly = false }: { fields: Field[]; data: Data; onChange: (next: Data) => void; readonly?: boolean }) {
  const change = (key: string, v: unknown) => onChange({ ...data, [key]: v });
  return <div className="staff-fields">{fields.map(field => {
    const raw = data[field.key];
    if (field.type === 'list') {
      const rows = Array.isArray(raw) ? raw as Data[] : [];
      return <fieldset className="staff-repeat" key={field.key}><legend>{field.label} ({rows.length})</legend>{rows.map((row, i) => <div className="staff-repeat-row" key={i}><div className="row-between"><strong>Mục {i + 1}</strong>{!readonly && <button className="text-button danger" type="button" onClick={() => change(field.key, rows.filter((_, n) => n !== i))}>Bỏ mục</button>}</div><EditorFields fields={field.fields!} data={row} readonly={readonly} onChange={next => change(field.key, rows.map((old, n) => n === i ? next : old))}/></div>)}{!readonly && <button className="secondary" type="button" onClick={() => change(field.key, [...rows, {}])}>＋ Thêm {field.label.toLowerCase()}</button>}</fieldset>;
    }
    if (field.type === 'object') return <fieldset className="staff-repeat" key={field.key}><legend>{field.label}</legend><EditorFields fields={field.fields!} data={(raw ?? {}) as Data} readonly={readonly} onChange={next => change(field.key, next)}/></fieldset>;
    if (field.type === 'weights') {
      const weights = (raw ?? {}) as Data;
      return <fieldset className="staff-repeat" key={field.key}><legend>{field.label}</legend>{Object.entries(weights).map(([code, weight], i) => <div className="weight-row" key={i}><input aria-label="Mã đầu vào" placeholder="Mã đầu vào" disabled={readonly} value={code} onChange={event => { const nextCode = event.target.value; if (nextCode !== code && Object.hasOwn(weights, nextCode)) return; change(field.key, Object.fromEntries(Object.entries(weights).map(([k, v]) => [k === code ? nextCode : k, v]))); }}/><input aria-label="Trọng số" inputMode="decimal" disabled={readonly} value={value(weight)} onChange={event => change(field.key, { ...weights, [code]: event.target.value })}/>{!readonly && <button type="button" className="text-button" onClick={() => change(field.key, Object.fromEntries(Object.entries(weights).filter(([k]) => k !== code)))}>Bỏ</button>}</div>)}{!readonly && <button type="button" className="secondary" disabled={Object.hasOwn(weights, '')} onClick={() => change(field.key, { ...weights, '': '' })}>Thêm trọng số</button>}</fieldset>;
    }
    return <label key={field.key} className={field.type === 'long' ? 'wide-field' : ''}>{field.label}{!field.optional && ' *'}{field.type === 'bool' ? <select disabled={readonly} value={raw == null || raw === '' ? '' : String(raw)} required={!field.optional} onChange={event => change(field.key, event.target.value === '' ? '' : event.target.value === 'true')}><option value="">Chưa chọn</option><option value="true">Có</option><option value="false">Không</option></select> : field.type === 'select' ? <select disabled={readonly} required={!field.optional} value={value(raw)} onChange={event => change(field.key, event.target.value)}><option value="">Chọn</option>{field.options?.map(option => <option key={option}>{option}</option>)}</select> : field.type === 'long' ? <textarea readOnly={readonly} rows={5} required={!field.optional} value={value(raw)} onChange={event => change(field.key, event.target.value)}/> : <input readOnly={readonly} required={!field.optional} type={field.type === 'int' ? 'number' : 'text'} min={field.type === 'int' ? 0 : undefined} max={field.key === 'decimal_places' ? 8 : field.type === 'int' ? 1000000 : undefined} step={field.type === 'int' ? 1 : undefined} inputMode={['id', 'money'].includes(field.type ?? '') ? 'numeric' : field.type === 'decimal' ? 'decimal' : undefined} value={value(raw)} placeholder={field.type === 'time' ? '2026-08-01T08:00:00+07:00' : undefined} onChange={event => change(field.key, event.target.value)}/>}</label>;
  })}</div>;
}
export function Confirm({ title, children, onConfirm, onCancel, done }: { title: string; children?: ReactNode; onConfirm: (signal: AbortSignal) => Promise<void>; onCancel: () => void; done?: () => void }) {
  const action = useAction();
  return <section className="confirm-box" role="group" aria-label={title}><h3>{title}</h3>{children}<div className="feature-actions"><button className="primary" disabled={action.busy} onClick={() => void action.run(async signal => { await onConfirm(signal); if (!signal.aborted) done?.(); })}>{action.busy ? 'Đang xử lý…' : 'Xác nhận'}</button><button className="secondary" disabled={action.busy} onClick={onCancel}>Hủy</button></div><Notice error={action.error}/></section>;
}
const dirtyEditors = new Set<symbol>();
export function confirmLeaveStaff() { return dirtyEditors.size === 0 || window.confirm('Có thay đổi chưa lưu. Rời biểu mẫu và bỏ các thay đổi này?'); }
export function useDirty(dirty: boolean) {
  useEffect(() => { if (!dirty) return; const token = Symbol(); dirtyEditors.add(token); const block = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; }; window.addEventListener('beforeunload', block); return () => { dirtyEditors.delete(token); window.removeEventListener('beforeunload', block); }; }, [dirty]);
}

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { query, usePage, type Source } from '../api';
import { errorText, request } from '../client';

export type Row = { id: string; source?: Source; [key: string]: unknown };
export type Scope = { campus_code: string; year: string };
export const value = (input: unknown) => typeof input === 'string' || typeof input === 'number' ? String(input) : '';
export function safeUrl(input: unknown) {
  try { const url = new URL(value(input)); return ['https:', 'http:'].includes(url.protocol) ? url.href : undefined; } catch { return undefined; }
}
export function date(input: unknown) {
  if (!input) return '';
  const d = new Date(value(input));
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' });
}
export function SourceNote({ source }: { source?: Source }) {
  if (!source) return null;
  return <div className="source">{safeUrl(source.url) ? <a href={safeUrl(source.url)} target="_blank" rel="noopener noreferrer">{source.title || 'Nguồn công bố'} ↗</a> : source.title}{source.year && ` · ${source.year}`}{source.updated_at && ` · Cập nhật ${date(source.updated_at)}`}</div>;
}
export function Notice({ error, retry }: { error: string; retry?: () => void }) {
  return error ? <div className="error" role="alert">{error}{retry && <button type="button" className="text-button" onClick={retry}>Thử lại</button>}</div> : null;
}
export function useResource<T>(path: string) {
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<{ path: string; data: T | null; error: string; loading: boolean }>({ path, data: null, error: '', loading: true });
  useEffect(() => {
    const controller = new AbortController();
    setState({ path, data: null, loading: true, error: '' });
    request<T>(path, { signal: controller.signal }).then(data => { if (!controller.signal.aborted) setState({ path, data, loading: false, error: '' }); }).catch(error => { if (!controller.signal.aborted) setState({ path, data: null, loading: false, error: errorText(error) }); });
    return () => controller.abort();
  }, [path, revision]);
  return { ...(state.path === path ? state : { data: null, error: '', loading: true }), reload: () => setRevision(n => n + 1) };
}
export function Resource<T>({ path, children }: { path: string; children: (data: T) => ReactNode }) {
  const state = useResource<T>(path);
  return <><Notice error={state.error} retry={state.reload}/>{state.loading && <p className="loading" role="status">Đang tải chi tiết…</p>}{state.data && children(state.data)}</>;
}
export function ListStatus({ state }: { state: { loading: boolean; error: string; data: unknown[]; cursor: string | null; reload: () => void; loadMore: () => void } }) {
  return <>{state.loading && <p className="loading" role="status">Đang tải dữ liệu…</p>}<Notice error={state.error} retry={state.data.length && state.cursor ? state.loadMore : state.reload}/>{!state.loading && !state.error && !state.data.length && <div className="feature-empty">Chưa có dữ liệu được công bố phù hợp.</div>}{state.cursor && !state.error && <button type="button" className="secondary" disabled={state.loading} onClick={state.loadMore}>Tải thêm</button>}</>;
}
export function Listing({ path, title, children }: { path: string; title?: string; children: (row: Row) => ReactNode }) {
  const state = usePage<Row>(path);
  return <section className="feature-section">{title && <h3>{title}</h3>}{state.data.map(row => <div className="feature-row" key={row.id}>{children(row)}</div>)}<ListStatus state={state}/></section>;
}
export function Table({ path, title, columns }: { path: string; title: string; columns: [string, string][] }) {
  const state = usePage<Row>(path);
  return <section className="feature-section"><h3>{title}</h3>{state.data.length > 0 && <div className="table-scroll"><table><caption className="sr-only">{title}</caption><thead><tr>{columns.map(([key, label]) => <th key={key} scope="col">{label}</th>)}<th scope="col">Nguồn</th></tr></thead><tbody>{state.data.map(row => <tr key={row.id}>{columns.map(([key]) => <td key={key}>{value(row[key])}</td>)}<td><SourceNote source={row.source}/></td></tr>)}</tbody></table></div>}<ListStatus state={state}/></section>;
}
export function Fields({ row, fields }: { row: Row; fields: [string, string][] }) {
  return <dl className="detail-fields">{fields.map(([key, label]) => row[key] != null && row[key] !== '' && <div key={key}><dt>{label}</dt><dd>{value(row[key])}</dd></div>)}</dl>;
}
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const active = useRef<AbortController | null>(null);
  const locked = useRef(false);
  useEffect(() => () => active.current?.abort(), []);
  async function run(action: (signal: AbortSignal) => Promise<void>) {
    if (locked.current) return;
    const controller = new AbortController(); active.current = controller; locked.current = true;
    setBusy(true); setError(''); setMessage('');
    try { await action(controller.signal); } catch (error) { if (!controller.signal.aborted) setError(errorText(error)); }
    finally { locked.current = false; if (!controller.signal.aborted) setBusy(false); }
  }
  return { busy, error, message, setError, setMessage, run };
}
export function FilterForm({ fields, onApply }: { fields: [string, string][]; onApply: (filters: Record<string, string>) => void }) {
  return <form className="feature-filters" onSubmit={event => { event.preventDefault(); const form = new FormData(event.currentTarget); onApply(Object.fromEntries(fields.map(([key]) => [key, String(form.get(key) ?? '').trim()]))); }}>{fields.map(([key, label]) => <label key={key}>{label}<input name={key}/></label>)}<button className="secondary">Lọc</button><button className="text-button" type="reset" onClick={() => onApply({})}>Đặt lại</button></form>;
}
export function Events({ filters }: { filters: Record<string, string> }) {
  return <Listing title="Lịch và mốc thời gian" path={`/schedule-events?${query(filters)}`}>{row => <><h3>{value(row.title)}</h3><p>{date(row.starts_at)}{row.ends_at ? ` – ${date(row.ends_at)}` : ''}</p><p>{value(row.location)}</p><p className="prose">{value(row.notes)}</p><SourceNote source={row.source}/></>}</Listing>;
}

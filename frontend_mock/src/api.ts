import { useEffect, useRef, useState } from 'react';
import { request } from './client';

export interface Source { title: string; url: string; year?: number; updated_at?: string }
export interface Campus { id: string; code: string; name: string }
export interface Program { id: string; code: string; name: string; source?: Source }
export interface Cycle { id: string; admission_year: number; round_code: string; campus_code: string; source?: Source }
export interface ScheduleEvent { id: string; title: string; starts_at: string; ends_at?: string; location?: string; notes?: string; campus_code: string; source?: Source }
interface Page<T> { data: T[]; pagination: { next_cursor: string | null; has_more: boolean } }

export async function getPage<T>(path: string, signal: AbortSignal): Promise<Page<T>> {
  const page = await request<Page<T>>(path, { signal });
  if (!Array.isArray(page.data) || !page.pagination) throw new Error('Dữ liệu trả về không đúng định dạng.');
  return page;
}

export function usePage<T extends { id: string }>(path: string, enabled = true, identity = 0) {
  const [state, setState] = useState<{ path: string; data: T[]; cursor: string | null; loading: boolean; error: string }>({ path, data: [], cursor: null, loading: true, error: '' });
  const [revision, setRevision] = useState(0);
  const active = useRef<AbortController | null>(null);
  useEffect(() => {
    if (!enabled) { setState({ path, data: [], cursor: null, loading: false, error: '' }); return; }
    const controller = new AbortController();
    active.current?.abort(); active.current = controller;
    setState({ path, data: [], cursor: null, loading: true, error: '' });
    getPage<T>(path, controller.signal).then(page => {
      if (!controller.signal.aborted) setState({ path, data: page.data, cursor: page.pagination.has_more ? page.pagination.next_cursor : null, loading: false, error: '' });
    }).catch(error => {
      if (!controller.signal.aborted) setState({ path, data: [], cursor: null, loading: false, error: error instanceof TypeError ? 'Không thể kết nối đến máy chủ. Vui lòng thử lại.' : error.message });
    });
    return () => { controller.abort(); active.current?.abort(); };
  }, [path, revision, enabled, identity]);
  const current = state.path === path ? state : { path, data: [], cursor: null, loading: true, error: '' };
  async function loadMore() {
    if (!current.cursor || current.loading) return;
    const controller = new AbortController();
    active.current?.abort(); active.current = controller;
    setState(s => ({ ...s, loading: true, error: '' }));
    try {
      const page = await getPage<T>(`${path}&cursor=${encodeURIComponent(current.cursor)}`, controller.signal);
      if (!controller.signal.aborted) setState(s => ({ ...s, data: [...s.data, ...page.data.filter(item => !s.data.some(existing => existing.id === item.id))], cursor: page.pagination.has_more ? page.pagination.next_cursor : null, loading: false }));
    } catch (error) {
      if (!controller.signal.aborted) setState(s => ({ ...s, loading: false, error: error instanceof Error ? error.message : 'Không tải được dữ liệu.' }));
    }
  }
  return { ...current, reload: () => setRevision(n => n + 1), loadMore };
}

export function query(filters: Record<string, string>, limit = 12) {
  const params = new URLSearchParams({ limit: String(limit) });
  Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value); });
  return params.toString();
}

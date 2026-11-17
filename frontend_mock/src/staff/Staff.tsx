import { useEffect, useState } from 'react';
import type { Account } from '../Account';
import { getPage, type Campus } from '../api';
import { errorText } from '../client';
import { Notice } from '../features/shared';
import { permitted } from './model';
import Editorial from './Editorial';
import Documents from './Documents';
import Operations from './Operations';
import Administration from './Administration';
import { confirmLeaveStaff } from './shared';
import './staff.css';

export default function Staff({ account }: { account: Account }) {
  const roles = account.roles;
  const editorial = permitted(roles, 'STAFF_EDITOR') || permitted(roles, 'STAFF_REVIEWER');
  const tabs = [ ...(editorial ? [{ id: 'editorial', label: 'Biên tập & xuất bản' }, { id: 'documents', label: 'Tài liệu RAG' }] : []), ...(permitted(roles, 'TECH_OPERATOR', null) ? [{ id: 'ops', label: 'Đồng bộ dữ liệu' }] : []), ...(permitted(roles, 'ADMIN', null) ? [{ id: 'admin', label: 'Tài khoản & quyền' }] : []) ];
  const [tab, setTab] = useState(tabs[0]?.id ?? '');
  const [campuses, setCampuses] = useState<Campus[]>([]); const [error, setError] = useState(''); const [loading, setLoading] = useState(true); const [retry, setRetry] = useState(0);
  useEffect(() => { const controller = new AbortController(); setLoading(true); setError('');
    (async () => { try { let cursor: string | null = null; const all: Campus[] = []; do { const page: { data: Campus[]; pagination: { has_more: boolean; next_cursor: string | null } } = await getPage<Campus>(`/campuses?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, controller.signal); all.push(...page.data); cursor = page.pagination.has_more ? page.pagination.next_cursor : null; } while (cursor); if (!controller.signal.aborted) setCampuses(all); } catch (error) { if (!controller.signal.aborted) setError(errorText(error)); } finally { if (!controller.signal.aborted) setLoading(false); } })(); return () => controller.abort();
  }, [retry]);
  return <div className="staff-workspace"><div className="scope-banner"><strong>Không gian cán bộ</strong><span>{account.email}</span></div><p className="feature-hint">Các chức năng hiển thị theo vai trò được cấp; quyền quản trị tài khoản không thay thế quyền biên tập hoặc duyệt.</p><nav className="staff-tabs" aria-label="Chức năng cán bộ">{tabs.map(item => <button key={item.id} className={tab === item.id ? 'selected' : ''} aria-current={tab === item.id ? 'page' : undefined} onClick={() => { if (confirmLeaveStaff()) setTab(item.id); }}>{item.label}</button>)}</nav><Notice error={error} retry={() => setRetry(n => n + 1)}/>{loading && <p className="loading" role="status">Đang tải phạm vi cơ sở…</p>}{!loading && !error && <>{tab === 'editorial' && editorial && <Editorial roles={roles} campuses={campuses}/>} {tab === 'documents' && editorial && <Documents roles={roles} campuses={campuses}/>} {tab === 'ops' && permitted(roles, 'TECH_OPERATOR', null) && <Operations/>} {tab === 'admin' && permitted(roles, 'ADMIN', null) && <Administration currentId={account.id} campuses={campuses}/>}</>}</div>;
}

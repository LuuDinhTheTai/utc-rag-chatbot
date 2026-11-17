import { useState } from 'react';
import { query, usePage } from '../api';
import { ApiError, request } from '../client';
import { Fields, FilterForm, ListStatus, safeUrl, value, type Row } from '../features/shared';
import { Confirm, RemoteStatus, useRemote } from './shared';

const running = (row: Row) => ['PENDING', 'RUNNING'].includes(value(row.status));
export default function Operations() {
  const sources = usePage<Row>('/ops/crawl-sources?limit=20');
  const [filters, setFilters] = useState<Record<string, string>>({});
  const jobs = usePage<Row>(`/ops/crawl-jobs?${query(filters)}`);
  const [source, setSource] = useState<Row | null>(null); const [job, setJob] = useState(''); const [message, setMessage] = useState('');
  return <section className="panel feature-panel"><h2>Vận hành đồng bộ dữ liệu</h2><h3>Nguồn đã cấu hình</h3>{sources.data.map(row => <div className="feature-row row-between" key={row.id}><div><h3>Nguồn {row.id}</h3>{safeUrl(row.base_url) && <a href={safeUrl(row.base_url)} target="_blank" rel="noopener noreferrer">{value(row.base_url)} ↗</a>}<p>{row.enabled ? 'Đang bật' : 'Đã tắt'}</p></div><button className="primary" disabled={!row.enabled || !!source} onClick={() => setSource(row)}>Chạy đồng bộ</button></div>)}<ListStatus state={sources}/>
    {source && <Confirm title={`Tạo tác vụ cho nguồn ${source.id}?`} onCancel={() => setSource(null)} onConfirm={async signal => {
      try { const created = await request<Row>('/ops/crawl-jobs', { method: 'POST', body: { source_id: source.id }, signal }); if (!signal.aborted) { setJob(created.id); setMessage('Đã tạo tác vụ đồng bộ.'); } }
      catch (error) { if (error instanceof ApiError && error.status === 409 && /^[1-9]\d*$/.test(value(error.details.job_id))) { setJob(value(error.details.job_id)); setMessage('Nguồn đã có tác vụ đang chờ/chạy. Đang hiển thị tác vụ hiện tại.'); } else throw error; }
    }} done={() => { setSource(null); jobs.reload(); }}><p>{value(source.base_url)}</p></Confirm>}
    {message && <p role="status" className="success-note">{message}</p>}{job && <Job key={job} id={job}/>}
    <h3>Lịch sử tác vụ</h3><FilterForm fields={[["source_id", "Mã nguồn"], ["status", "Trạng thái"]]} onApply={setFilters}/><button className="text-button" onClick={jobs.reload}>Làm mới danh sách</button>{jobs.data.map(row => <div className="feature-row row-between" key={row.id}><div><h3>Tác vụ {row.id} · Nguồn {value(row.source_id)}</h3><p>{value(row.status)}</p></div><button className="secondary" onClick={() => setJob(row.id)}>Theo dõi</button></div>)}<ListStatus state={jobs}/>
  </section>;
}
function Job({ id }: { id: string }) {
  const state = useRemote<Row>(`/ops/crawl-jobs/${id}`, running);
  return <section className="detail-box"><h3>Tác vụ {id}</h3><RemoteStatus state={state}/>{state.data && <><Fields row={state.data} fields={[["source_id", "Mã nguồn"], ["status", "Trạng thái"], ["attempt_no", "Số lần thực thi"], ["run_after", "Thời điểm chạy dự kiến"]]}/>{running(state.data) && <p className="feature-hint" role="status">Tự cập nhật mỗi 5 giây khi đang chờ/chạy.</p>}</>}<button className="text-button" onClick={state.reload}>Làm mới trạng thái</button></section>;
}

import { useState } from 'react';
import { ApiError, request } from '../client';
import { date } from '../features/shared';
import { Confirm, RemoteStatus, useRemote } from './shared';

export default function Publication({ path, selected, canReview, allowPublish = true, onChanged }: { path: string; selected?: string; canReview: boolean; allowPublish?: boolean; onChanged: () => void }) {
  const state = useRemote<{ version_id: string | null; published_at: string | null }>(path);
  const [target, setTarget] = useState<{ id: string | null; etag: string } | null>(null);
  const [stale, setStale] = useState(false);
  const reload = () => { setTarget(null); setStale(false); state.reload(); };
  return <section className="publication-box"><h3>Trạng thái công khai</h3><RemoteStatus state={state}/>{state.data && <p>{state.data.version_id ? `Phiên bản đang công khai: ${state.data.version_id} · ${date(state.data.published_at)}` : 'Chưa có phiên bản công khai.'}</p>}
    {canReview && <div className="feature-actions">{selected && <button type="button" className="primary" disabled={!!target || !state.etag || state.loading || !!state.error || stale || !allowPublish} onClick={() => setTarget({ id: selected, etag: state.etag! })}>Duyệt và xuất bản bản {selected}</button>}{state.data?.version_id && <button type="button" className="secondary" disabled={!!target || !state.etag || state.loading || !!state.error || stale} onClick={() => setTarget({ id: null, etag: state.etag! })}>Gỡ bản công khai</button>}</div>}
    {target && <Confirm title={target.id ? `Xuất bản phiên bản ${target.id}?` : 'Gỡ phiên bản đang công khai?'} onCancel={() => setTarget(null)} onConfirm={async signal => {
      if (!canReview || (target.id !== null && !allowPublish)) throw new Error('Lưu nội dung đang sửa và kiểm tra quyền/trạng thái trước khi xuất bản.');
      if (stale) throw new Error('Trạng thái đã đổi. Đọc lại trạng thái trước khi xác nhận mới.');
      try { await request(path, { method: 'PUT', body: { version_id: target.id }, ifMatch: target.etag, signal }); }
      catch (error) { if (error instanceof ApiError && [412, 428].includes(error.status)) setStale(true); throw error; }
    }} done={() => { reload(); onChanged(); }}><p>{target.id ? 'Nội dung sẽ thay thế bản công khai hiện tại. Hãy kiểm tra toàn bộ nội dung, nguồn và phạm vi trước khi xác nhận.' : 'Nội dung sẽ không còn được cung cấp như bản công khai. Lịch sử phiên bản được giữ lại.'}</p></Confirm>}
    {stale && <button type="button" className="secondary" onClick={reload}>Đọc trạng thái mới và đối chiếu lại</button>}
    {!state.loading && !state.error && !state.etag && <p className="error">Máy chủ chưa cung cấp ETag; chưa thể cập nhật xuất bản.</p>}
  </section>;
}

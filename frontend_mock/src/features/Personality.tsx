import { useState } from 'react';
import type { Account } from '../Account';
import { usePage } from '../api';
import { request } from '../client';
import { Listing, ListStatus, Notice, Resource, date, useAction, value, type Row } from './shared';
import { saveAttempt, type Attempt } from './personality-service';

interface Questionnaire { id: string; title?: string; name?: string; version_id: string; instructions: string }
interface Question { id: string; position: number; body: string; options: { id: string; label: string }[] | null }
interface Result { attempt_id: string; profile: { name: string; description: string }; scores: Record<string, string | number>; completed_at: string; disclaimer: string }

export default function Personality({ account, onProgram }: { account: Account | null; onProgram: (id: string) => void }) {
  const questionnaires = usePage<Questionnaire>('/questionnaires?limit=12');
  const history = usePage<Row>('/me/personality-results?limit=12', !!account);
  const [selected, select] = useState<Questionnaire | null>(null);
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [historyId, setHistoryId] = useState('');
  const [restart, setRestart] = useState(false);
  const action = useAction();
  const start = () => action.run(async signal => {
    if (!selected) return;
    const next = await request<Attempt>('/personality-attempts', { method: 'POST', body: { questionnaire_version_id: selected.version_id }, signal });
    if (!signal.aborted) { setAttempt(next); setHistoryId(''); }
  });
  return <div className="personality-layout"><section className="panel feature-panel"><div className="panel-heading"><h2>Khám phá tính cách</h2>{attempt && <button className="secondary" onClick={() => setRestart(true)}>Chọn bài khác</button>}</div><p className="feature-hint">Kết quả chỉ mang tính tham khảo, không phải chẩn đoán hay bảo đảm lựa chọn ngành.</p>
    {restart && <div className="delete-confirm"><span>Rời lần làm hiện tại? Bạn sẽ không thể mở lại đáp án đang nhập từ giao diện này.</span><button className="secondary" onClick={() => { setAttempt(null); setRestart(false); }}>Rời lần làm</button><button className="text-button" onClick={() => setRestart(false)}>Tiếp tục làm</button></div>}
    {!attempt ? <><div className="feature-cards">{questionnaires.data.map(item => <button disabled={action.busy} className={`feature-card ${selected?.id === item.id ? 'chosen' : ''}`} key={item.id} onClick={() => select(item)}><strong>{item.title || item.name || 'Bộ trắc nghiệm'}</strong><span>Xem hướng dẫn →</span></button>)}</div><ListStatus state={questionnaires}/>{selected && <div className="detail-box"><h3>{selected.title || selected.name}</h3><p className="prose">{selected.instructions}</p><p className="feature-hint">{account ? 'Bạn có thể lưu kết quả vào tài khoản sau khi hoàn tất.' : 'Bạn đang làm với tư cách khách. Kết quả không tự chuyển vào tài khoản khi đăng nhập.'}</p><button className="primary" disabled={action.busy} onClick={start}>{action.busy ? 'Đang bắt đầu…' : 'Bắt đầu trắc nghiệm'}</button></div>}<Notice error={action.error}/></> : <AttemptForm key={attempt.id} attempt={attempt} account={account} onSaved={history.reload} onProgram={onProgram}/>}
  </section>{account && <section className="panel feature-panel"><h2>Kết quả đã lưu</h2>{history.data.map(item => <button className="history-item" key={item.id} onClick={() => setHistoryId(item.id)}><strong>Kết quả ngày {date(item.completed_at)}</strong><span>Xem kết quả →</span></button>)}<ListStatus state={history}/>{historyId && <ResultPanel key={historyId} id={historyId} account={account} initiallySaved onSaved={history.reload} onProgram={onProgram}/>}</section>}</div>;
}
function AttemptForm({ attempt, account, onSaved, onProgram }: { attempt: Attempt; account: Account | null; onSaved: () => void; onProgram: (id: string) => void }) {
  const questions = usePage<Question>(`/personality-attempts/${attempt.id}/questions?limit=100`);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [completed, setCompleted] = useState(attempt.status === 'COMPLETED');
  const action = useAction();
  const count = questions.data.filter(question => !!answers[question.id]).length;
  const full = !questions.loading && !questions.error && !questions.cursor && questions.data.length > 0 && count === questions.data.length;
  const save = (finish: boolean) => action.run(async signal => {
    const status = await saveAttempt(attempt.id, answers, finish, full, signal);
    if (signal.aborted) return;
    if (status === 'COMPLETED') setCompleted(true);
    else action.setMessage('Đã lưu đáp án tạm thời trên máy chủ.');
  });
  if (completed) return <ResultPanel id={attempt.id} account={account} initiallySaved={!!attempt.saved_at} onSaved={onSaved} onProgram={onProgram}/>;
  return <><div className="scope-banner"><strong>Đã trả lời {count}/{questions.data.length} câu đã tải</strong>{questions.cursor && <span>Còn câu hỏi ở trang tiếp theo</span>}</div><p className="feature-hint">Giữ trang này mở để tiếp tục làm. API hiện chưa hỗ trợ đọc lại đáp án tạm sau khi tải lại trang.</p>
    <fieldset disabled={action.busy} className="feature-fieldset">{questions.data.map(question => <fieldset key={question.id} className="question"><legend>{question.position}. {question.body}</legend>{(question.options ?? []).map(option => <label key={option.id}><input type="radio" name={`question-${question.id}`} checked={answers[question.id] === option.id} onChange={() => { setAnswers(old => ({ ...old, [question.id]: option.id })); action.setMessage(''); }}/>{option.label}</label>)}</fieldset>)}<ListStatus state={questions}/><div className="feature-actions"><button className="secondary" disabled={!count || questions.loading || !!questions.error} onClick={() => void save(false)}>Lưu tạm</button><button className="primary" disabled={!full} onClick={() => void save(true)}>{action.busy ? 'Đang xử lý…' : 'Hoàn tất và xem kết quả'}</button></div></fieldset><Notice error={action.error}/>{action.message && <p role="status" className="success-note">{action.message}</p>}</>;
}
function ResultPanel({ id, account, initiallySaved, onSaved, onProgram }: { id: string; account: Account | null; initiallySaved: boolean; onSaved: () => void; onProgram: (id: string) => void }) {
  const [saved, setSaved] = useState(initiallySaved);
  const action = useAction();
  const download = () => action.run(async signal => {
    const blob = await request<Blob>(`/personality-attempts/${id}/report`, { signal, responseType: 'pdf' });
    if (signal.aborted) return;
    const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = 'ket-qua-tinh-cach-utc.pdf'; document.body.appendChild(a); a.click(); a.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    action.setMessage('Đã tải báo cáo PDF.');
  });
  return <section className="detail-box"><Resource<Result> path={`/personality-attempts/${id}/result`}>{result => <><span className="section-kicker">KẾT QUẢ CỦA BẠN</span><h2>{result.profile.name}</h2><p className="prose">{result.profile.description}</p><p className="feature-hint">Hoàn tất: {date(result.completed_at)}</p><dl className="detail-fields">{Object.entries(result.scores).map(([label, score]) => <div key={label}><dt>{label}</dt><dd>{score}</dd></div>)}</dl><p>{result.disclaimer}</p>
    {account && <div className="feature-actions">{!saved && <button className="primary" disabled={action.busy} onClick={() => void action.run(async signal => { await request(`/personality-attempts/${id}/saved-result`, { method: 'PUT', signal }); if (!signal.aborted) { setSaved(true); onSaved(); action.setMessage('Đã lưu kết quả vào tài khoản.'); } })}>Lưu vào tài khoản</button>}{saved && <button className="secondary" disabled={action.busy} onClick={download}>Tải báo cáo PDF</button>}</div>}
    <Listing title="Ngành học gợi ý" path={`/personality-attempts/${id}/recommendations?limit=12`}>{row => <><h3>{value(row.code)} · {value(row.name)}</h3><p className="prose">{value(row.rationale)}</p><button className="text-button" onClick={() => onProgram(row.id)}>Tìm hiểu ngành →</button></>}</Listing></>}</Resource><Notice error={action.error}/>{action.message && <p className="success-note" role="status">{action.message}</p>}</section>;
}

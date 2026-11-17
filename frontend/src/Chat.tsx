import { useEffect, useRef, useState } from 'react';
import { query, usePage } from './api';
import { errorText, request } from './client';
import type { Account } from './Account';

interface Conversation { id: string; display_title: string | null; updated_at: string }
interface Citation { title: string; url: string; year?: number; locator?: string }
interface Message { id: string; author: 'USER' | 'ASSISTANT'; content: string; created_at: string; answer_status?: string; citations: Citation[] }
interface Page<T> { data: T[]; pagination: { next_cursor: string | null; has_more: boolean } }
interface Pending { client_message_id: string; content: string }
function timestamp(value: string) { return new Date(value).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' }); }
function citationUrl(value: string) { try { return ['http:', 'https:'].includes(new URL(value).protocol) ? value : undefined; } catch { return undefined; } }

function Feedback({ conversation, message }: { conversation: string; message: string }) {
  const [helpful, setHelpful] = useState<boolean | null>(null);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const lock = useRef(false);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  return <form className="feedback" onSubmit={async event => {
    event.preventDefault(); if (helpful === null || lock.current) return;
    lock.current = true; setBusy(true); setError(''); setStatus(''); controller.current = new AbortController();
    try { await request(`/conversations/${conversation}/messages/${message}/feedback`, { method: 'PUT', signal: controller.current.signal, body: { is_helpful: helpful, ...(comment.trim() ? { comment_text: comment.trim() } : {}) } }); setStatus('Đã lưu đánh giá.'); }
    catch (error) { setError(errorText(error)); } finally { lock.current = false; setBusy(false); }
  }}><div className="feedback-actions"><span>Câu trả lời có hữu ích?</span><button type="button" disabled={busy} aria-pressed={helpful === true} onClick={() => { setHelpful(true); setStatus(''); }}>Hữu ích</button><button type="button" disabled={busy} aria-pressed={helpful === false} onClick={() => { setHelpful(false); setStatus(''); }}>Chưa hữu ích</button></div>
    {helpful !== null && <div className="feedback-input"><input aria-label="Góp ý về câu trả lời" placeholder="Góp ý thêm (không bắt buộc)" value={comment} disabled={busy} onChange={event => { setComment(event.target.value); setStatus(''); }}/><button className="secondary" disabled={busy}>{busy ? 'Đang lưu…' : 'Lưu đánh giá'}</button></div>}
    {status && <p role="status">{status}</p>}{error && <p role="alert" className="feedback-error">{error}</p>}
  </form>;
}

export default function Chat({ account, onLogin }: { account: Account | null; onLogin: () => void }) {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const history = usePage<Conversation>(`/me/conversations?${query({ q: search })}`, !!account);
  const suggestions = usePage<{ id: string; question_text: string }>('/chat-suggestions?limit=6');
  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState<Pending | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [loadError, setLoadError] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState(false);
  const operation = useRef<AbortController | null>(null);
  const locked = useRef(false);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => () => operation.current?.abort(), []);
  useEffect(() => { end.current?.scrollIntoView({ block: 'nearest' }); }, [messages.length, busy]);
  async function open(item: Conversation) {
    if (locked.current) return;
    operation.current?.abort(); const controller = new AbortController(); operation.current = controller;
    setConversation(item); setMessages([]); setPending(null); setDraft(''); setError(''); setLoadError(''); setDeleteConfirm(false); setLoading(true);
    try {
      const detail = await request<Conversation>(`/conversations/${item.id}`, { signal: controller.signal });
      const all: Message[] = []; let cursor: string | null = null;
      do {
        const page: Page<Message> = await request(`/conversations/${item.id}/messages?${query({ cursor: cursor ?? '' }, 100)}`, { signal: controller.signal });
        all.push(...page.data); cursor = page.pagination.has_more ? page.pagination.next_cursor : null;
      } while (cursor);
      if (!controller.signal.aborted) { setConversation(detail); setMessages(all); }
    } catch (error) { if (!controller.signal.aborted) setLoadError(errorText(error)); }
    finally { if (!controller.signal.aborted) setLoading(false); }
  }
  function newChat() {
    if (locked.current) return;
    operation.current?.abort(); setConversation(null); setMessages([]); setPending(null); setDraft(''); setError(''); setLoadError(''); setLoading(false); setDeleteConfirm(false);
  }
  async function send(retry?: Pending) {
    if (locked.current || loading || loadError) return;
    const content = draft.trim(); if (!retry && (!content || content.length > 4000)) return;
    const turn = retry ?? { client_message_id: crypto.randomUUID(), content };
    locked.current = true; setBusy(true); setError(''); setPending(turn);
    operation.current?.abort(); const controller = new AbortController(); operation.current = controller;
    try {
      let current = conversation;
      if (!current) {
        current = await request<Conversation>('/conversations', { method: 'POST', body: {}, signal: controller.signal });
        if (controller.signal.aborted) return;
        setConversation(current);
      }
      const result = await request<{ user_message: Message; assistant_message: Message }>(`/conversations/${current.id}/messages`, { method: 'POST', body: turn, signal: controller.signal });
      if (controller.signal.aborted) return;
      setMessages(old => [...old.filter(item => item.id !== result.user_message.id && item.id !== result.assistant_message.id), result.user_message, result.assistant_message]);
      setPending(null); setDraft(''); setConversation({ ...current, display_title: current.display_title || turn.content.slice(0, 100) });
      if (account) history.reload();
    } catch (error) { if (!controller.signal.aborted) setError(errorText(error)); }
    finally { locked.current = false; if (!controller.signal.aborted) setBusy(false); }
  }
  async function remove() {
    if (!conversation || locked.current) return;
    locked.current = true; setBusy(true); setError('');
    const controller = new AbortController(); operation.current?.abort(); operation.current = controller;
    try { await request(`/conversations/${conversation.id}`, { method: 'DELETE', signal: controller.signal }); if (!controller.signal.aborted) { locked.current = false; newChat(); if (account) history.reload(); } }
    catch (error) { if (!controller.signal.aborted) setError(errorText(error)); }
    finally { locked.current = false; if (!controller.signal.aborted) setBusy(false); }
  }
  return <div className="chat-layout"><aside className="panel chat-history"><button className="primary" disabled={busy} onClick={newChat}>＋ Cuộc trò chuyện mới</button><h2>Lịch sử tư vấn</h2>
    {account ? <><form className="search-form" onSubmit={event => { event.preventDefault(); setSearch(searchInput.trim()); }}><input aria-label="Tìm lịch sử" placeholder="Tìm cuộc trò chuyện…" value={searchInput} onChange={event => setSearchInput(event.target.value)}/><button>Tìm</button></form>
      {history.data.map(item => <button className={`history-item ${conversation?.id === item.id ? 'selected' : ''}`} disabled={busy} key={item.id} onClick={() => void open(item)}><strong>{item.display_title || 'Cuộc trò chuyện mới'}</strong><time>{timestamp(item.updated_at)}</time></button>)}
      {history.loading && <p role="status">Đang tải lịch sử…</p>}{history.error && <div role="alert" className="error">{history.error}<button className="text-button" onClick={history.reload}>Thử lại</button></div>}
      {!history.loading && !history.error && !history.data.length && <p className="history-empty">Chưa có cuộc trò chuyện phù hợp.</p>}
      {history.cursor && <button className="text-button" disabled={history.loading} onClick={history.loadMore}>Tải thêm lịch sử</button>}
    </> : <div className="history-empty"><p>Đăng nhập để lưu và tìm lại lịch sử tư vấn.</p><button className="text-button" onClick={onLogin}>Đăng nhập / Đăng ký →</button><p>Hội thoại khách có thời hạn theo phiên và không tự chuyển vào tài khoản.</p></div>}
  </aside><section className="panel chat-panel"><div className="chat-heading"><div><span className="section-kicker">TRỢ LÝ TUYỂN SINH UTC</span><h2>{conversation?.display_title || 'Bạn muốn tìm hiểu điều gì?'}</h2></div>{conversation && <button className="text-button" disabled={busy || loading} onClick={() => setDeleteConfirm(true)}>Xóa hội thoại</button>}</div>
    {deleteConfirm && <div className="delete-confirm" role="group" aria-label="Xác nhận xóa hội thoại"><span>Xóa toàn bộ tin nhắn trong hội thoại này?</span><button className="secondary" disabled={busy} onClick={remove}>Xóa</button><button className="text-button" disabled={busy} onClick={() => setDeleteConfirm(false)}>Hủy</button></div>}
    <div className="messages" aria-busy={busy || loading}>
      {!conversation && !messages.length && <div className="chat-welcome"><img src="/utc-logo.webp" alt="UTC"/><h3>Đồng hành cùng lựa chọn của bạn</h3><p>Đặt câu hỏi về tuyển sinh, ngành học hoặc hành trình nhập học tại UTC.</p><div className="suggestions">{suggestions.data.map(item => <button key={item.id} disabled={busy} onClick={() => setDraft(item.question_text.slice(0, 4000))}>{item.question_text} ↗</button>)}</div>{suggestions.loading && <p role="status">Đang tải gợi ý…</p>}{suggestions.error && <div className="error" role="alert">Không tải được gợi ý.<button className="text-button" onClick={suggestions.reload}>Thử lại</button></div>}{suggestions.cursor && <button className="text-button" disabled={suggestions.loading} onClick={suggestions.loadMore}>Thêm gợi ý</button>}</div>}
      {loading && <p className="loading" role="status">Đang tải hội thoại…</p>}
      {loadError && <div className="error" role="alert">{loadError}<button className="text-button" onClick={() => conversation && void open(conversation)}>Thử lại</button></div>}
      {messages.map(message => <article key={message.id} className={`message ${message.author === 'USER' ? 'user-message' : 'assistant-message'}`}><div className="message-meta"><strong>{message.author === 'USER' ? 'Bạn' : 'Trợ lý UTC'}</strong><time dateTime={message.created_at}>{timestamp(message.created_at)}</time></div><div className="message-body">{message.content}</div>
        {message.answer_status === 'INSUFFICIENT_EVIDENCE' && <p className="evidence-note">Chưa có đủ nguồn được xác minh cho câu hỏi này.</p>}
        {!!message.citations?.length && <div className="citations"><strong>Nguồn tham khảo</strong>{message.citations.map((source, index) => <div key={index}>{citationUrl(source.url) ? <a href={citationUrl(source.url)} target="_blank" rel="noopener noreferrer">{index + 1}. {source.title} ↗</a> : <span>{source.title}</span>}{source.year && <span> · {source.year}</span>}{source.locator && <p>{source.locator}</p>}</div>)}</div>}
        {message.author === 'ASSISTANT' && conversation && <Feedback conversation={conversation.id} message={message.id}/>}
      </article>)}
      {pending && <div className="pending-turn"><strong>{busy ? 'Đang gửi câu hỏi…' : 'Câu hỏi chưa được xác nhận'}</strong><p>{pending.content}</p>{!busy && <button className="secondary" onClick={() => void send(pending)}>Thử gửi lại</button>}</div>}
      {busy && <p className="loading" role="status"><span className="spinner"/>Đang xử lý yêu cầu…</p>}<div ref={end}/>
    </div>{error && <div className="error" role="alert">{error}</div>}
    <form className="composer" onSubmit={event => { event.preventDefault(); void send(); }}><label htmlFor="chat-question">Câu hỏi của bạn</label><textarea id="chat-question" placeholder="Nhập câu hỏi về UTC…" rows={3} maxLength={4000} value={draft} disabled={busy || loading || !!pending || !!loadError} onChange={event => setDraft(event.target.value)}/><div><span>{draft.length}/4000 · Câu trả lời có thể cần thời gian xử lý.</span><button className="primary" disabled={busy || loading || !!pending || !!loadError || !draft.trim()}>Gửi câu hỏi →</button></div></form>
    <p className="chat-footnote">Đối chiếu nguồn trích dẫn và thông báo chính thức trước khi quyết định.</p>
  </section></div>;
}

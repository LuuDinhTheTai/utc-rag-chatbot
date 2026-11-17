import { useEffect, useRef, useState } from 'react';
import { ApiError, ensureCsrf, errorText, request, resetCsrf } from './client';

export interface Account { id: string; email: string; roles: { role_code: string; campus_code: string | null }[] }
export function useSession() {
  const [account, setAccount] = useState<Account | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [version, setVersion] = useState(0);
  const [retry, setRetry] = useState(0);
  const generation = useRef(0);
  useEffect(() => {
    const current = ++generation.current;
    let cancelled = false;
    setReady(false); setError('');
    (async () => {
      try {
        await ensureCsrf();
        const user = await request<Account>('/me').catch(error => { if (error instanceof ApiError && error.status === 401) return null; throw error; });
        if (!cancelled && current === generation.current) { setAccount(user); setReady(true); }
      } catch (error) { if (!cancelled && current === generation.current) setError(errorText(error)); }
    })();
    return () => { cancelled = true; };
  }, [retry]);
  useEffect(() => {
    const expired = () => { generation.current++; resetCsrf(); setAccount(null); setVersion(n => n + 1); setReady(false); setError('Phiên đã hết hạn. Vui lòng kết nối lại hoặc đăng nhập.'); };
    window.addEventListener('utc-session-expired', expired);
    return () => window.removeEventListener('utc-session-expired', expired);
  }, []);
  const change = (user: Account | null) => { generation.current++; resetCsrf(); setAccount(user); setVersion(n => n + 1); setReady(true); setError(''); };
  return { account, ready, error, version, change, reconnect: () => { resetCsrf(); setRetry(n => n + 1); } };
}
export function AccountPanel({ account, onChange }: { account: Account | null; onChange: (account: Account | null) => void }) {
  const [register, setRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const [error, setError] = useState('');
  async function submit() {
    if (lock.current) return;
    setError('');
    if (password.length < 8 || new TextEncoder().encode(password).length > 72) { setError('Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte UTF-8.'); return; }
    if (register && password !== confirm) { setError('Mật khẩu xác nhận chưa trùng khớp.'); return; }
    lock.current = true; setBusy(true);
    try {
      const user = await request<Account>(register ? '/auth/accounts' : '/auth/sessions', { method: 'POST', body: { email: email.trim(), password } });
      setPassword(''); setConfirm(''); onChange(user);
    } catch (error) { setError(error instanceof ApiError && error.status === 409 && register ? 'Email có thể đã được đăng ký. Vui lòng dùng email khác hoặc đăng nhập.' : errorText(error)); }
    finally { lock.current = false; setBusy(false); }
  }
  async function logout() {
    if (lock.current) return;
    lock.current = true; setBusy(true); setError('');
    try { await request('/auth/sessions/current', { method: 'DELETE' }); onChange(null); }
    catch (error) { setError(errorText(error)); }
    finally { lock.current = false; setBusy(false); }
  }
  return <section className="panel account-panel"><span className="section-kicker">TÀI KHOẢN UTC</span><h2>{account ? 'Thông tin tài khoản' : register ? 'Tạo tài khoản' : 'Chào mừng bạn trở lại'}</h2>
    {account ? <><p className="account-email">{account.email}</p><p>Lịch sử hội thoại được lưu riêng cho tài khoản của bạn.</p><button className="secondary" disabled={busy} onClick={logout}>{busy ? 'Đang đăng xuất…' : 'Đăng xuất'}</button></> : <>
      <p>Đăng nhập để lưu và tìm lại những cuộc tư vấn của bạn.</p>
      <form onSubmit={event => { event.preventDefault(); void submit(); }}>
        <fieldset disabled={busy}><label>Email<input type="email" required maxLength={254} autoComplete="username" value={email} onChange={event => setEmail(event.target.value)}/></label>
        <label>Mật khẩu<input type="password" required minLength={8} autoComplete={register ? 'new-password' : 'current-password'} value={password} onChange={event => setPassword(event.target.value)}/></label>
        {register && <label>Nhập lại mật khẩu<input type="password" required minLength={8} autoComplete="new-password" value={confirm} onChange={event => setConfirm(event.target.value)}/></label>}
        <button className="primary" type="submit">{busy ? 'Đang xử lý…' : register ? 'Đăng ký' : 'Đăng nhập'}</button></fieldset>
      </form><button disabled={busy} className="text-button" onClick={() => { setRegister(!register); setError(''); setPassword(''); setConfirm(''); }}>{register ? 'Đã có tài khoản? Đăng nhập' : 'Chưa có tài khoản? Đăng ký'}</button>
      <p className="account-note">Hội thoại khách không tự chuyển vào lịch sử tài khoản khi đăng nhập.</p>
    </>}{error && <div className="error" role="alert">{error}</div>}
  </section>;
}

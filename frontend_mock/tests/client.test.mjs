import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { request, resetCsrf, ensureCsrf, ApiError } from '../src/client.ts';

const originalFetch = globalThis.fetch;
const originalWindow = globalThis.window;
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => { resetCsrf(); globalThis.window = new EventTarget(); });
afterEach(() => { globalThis.fetch = originalFetch; globalThis.window = originalWindow; });

test('concurrent writes share CSRF initialization and include cookie/header', async () => {
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return url.endsWith('/auth/csrf') ? json({ token: 'token-a', header_name: 'X-CSRF-TOKEN' }) : new Response(null, { status: 204 });
  };
  await Promise.all([request('/one', { method: 'PUT', body: { is_helpful: true } }), request('/two', { method: 'DELETE' })]);
  assert.equal(calls.filter(call => call.url.endsWith('/auth/csrf')).length, 1);
  for (const { options } of calls.slice(1)) {
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.headers['X-CSRF-TOKEN'], 'token-a');
  }
});

test('CSRF failure does not automatically replay a write; next explicit attempt refreshes token', async () => {
  let tokens = 0, writes = 0;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/csrf')) return json({ token: `token-${++tokens}`, header_name: 'X-CSRF-TOKEN' });
    writes++;
    if (writes === 1) return json({ code: 'CSRF_INVALID' }, 403);
    assert.equal(options.headers['X-CSRF-TOKEN'], 'token-2');
    return new Response(null, { status: 204 });
  };
  await assert.rejects(request('/one', { method: 'POST', body: {} }), error => error instanceof ApiError && error.code === 'CSRF_INVALID');
  assert.equal(writes, 1);
  await request('/one', { method: 'POST', body: {} });
  assert.equal(tokens, 2);
});

test('network failure preserves caller message ID on manual retry and never auto-resends', async () => {
  const bodies = [];
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/csrf')) return json({ token: 'a', header_name: 'X-CSRF-TOKEN' });
    bodies.push(options.body);
    if (bodies.length === 1) throw new TypeError('offline');
    return json({});
  };
  const turn = { client_message_id: 'persisted-id', content: 'Tuyển sinh UTC' };
  await assert.rejects(request('/conversations/one/messages', { method: 'POST', body: turn }));
  assert.equal(bodies.length, 1);
  await request('/conversations/one/messages', { method: 'POST', body: turn });
  assert.equal(bodies[0], bodies[1]);
});

test('expired private requests notify session owner, invalid login does not', async () => {
  let expired = 0;
  window.addEventListener('utc-session-expired', () => expired++);
  globalThis.fetch = async url => url.endsWith('/auth/csrf') ? json({ token: 'a', header_name: 'X-CSRF-TOKEN' }) : json({ code: 'UNAUTHENTICATED' }, 401);
  await assert.rejects(request('/auth/sessions', { method: 'POST', body: {} }));
  assert.equal(expired, 0);
  await assert.rejects(request('/me/conversations?limit=12'));
  assert.equal(expired, 1);
});

test('session transition invalidates cached CSRF token', async () => {
  let tokens = 0;
  globalThis.fetch = async () => json({ token: String(++tokens), header_name: 'X-CSRF-TOKEN' });
  assert.equal((await ensureCsrf()).token, '1');
  resetCsrf();
  assert.equal((await ensureCsrf()).token, '2');
});

test('aborted write is not sent after CSRF resolves', async () => {
  const controller = new AbortController();
  controller.abort();
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/csrf')) return json({ token: 'a', header_name: 'X-CSRF-TOKEN' });
    options.signal.throwIfAborted();
    assert.fail('aborted request must not be sent');
  };
  await assert.rejects(request('/conversations', { method: 'POST', body: {}, signal: controller.signal }), { name: 'AbortError' });
});

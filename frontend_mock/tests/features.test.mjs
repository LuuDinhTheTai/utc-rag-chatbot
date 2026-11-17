import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { scoreInputs } from '../src/features/score-model.ts';
import { saveAttempt } from '../src/features/personality-service.ts';
import { request, resetCsrf } from '../src/client.ts';

const numeric = { id: '1', input_kind: 'SUBJECT', input_code: 'math', label: 'Toán', is_required: true, min_value: '0', max_value: '10', choices: [] };
const choice = { ...numeric, id: '2', input_code: 'region', label: 'Khu vực', choices: [{ match_code: 'KV1' }, { match_code: 'KV2' }] };
test('score payload preserves decimal text, chooses reviewed codes and omits empty optional values', () => {
  assert.deepEqual(scoreInputs([numeric, choice, { ...numeric, input_code: 'optional', is_required: false }], { math: '8.2500', region: 'KV1' }), [
    { input_code: 'math', numeric_value: '8.2500' }, { input_code: 'region', choice_code: 'KV1' },
  ]);
});
test('score validation rejects missing, out-of-range and invalid inputs', () => {
  for (const value of ['', '-1', '10.01', 'NaN', '1e2', '8,5']) assert.throws(() => scoreInputs([numeric], { math: value }));
  assert.throws(() => scoreInputs([choice], { region: 'invented' }));
  assert.deepEqual(scoreInputs([numeric], { math: '0' }), [{ input_code: 'math', numeric_value: '0' }]);
});

const originalFetch = globalThis.fetch;
beforeEach(() => resetCsrf());
afterEach(() => { globalThis.fetch = originalFetch; });
const json = body => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
function transport(status = 'IN_PROGRESS') {
  const calls = [];
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/csrf')) return json({ token: 'test-token', header_name: 'X-CSRF-TOKEN' });
    calls.push({ url, method: options.method, body: options.body && JSON.parse(options.body) });
    if (options.method === 'GET') return json({ id: 'attempt', status });
    return new Response(null, { status: 204 });
  };
  return calls;
}
test('personality completion saves all selected answers before requesting server scoring', async () => {
  const calls = transport();
  const state = await saveAttempt('attempt', { '9007199254740993': '9007199254740994', '2': '3' }, true, true, new AbortController().signal);
  assert.equal(state, 'COMPLETED');
  assert.deepEqual(calls.map(call => call.method), ['GET', 'PUT', 'POST']);
  assert.equal(calls[1].body.answers.find(answer => answer.question_id === '9007199254740993').option_id, '9007199254740994');
  assert.match(calls[2].url, /\/result$/);
});
test('already-completed attempt is recovered without overwriting answers or scoring twice', async () => {
  const calls = transport('COMPLETED');
  assert.equal(await saveAttempt('attempt', {}, true, false, new AbortController().signal), 'COMPLETED');
  assert.deepEqual(calls.map(call => call.method), ['GET']);
});
test('incomplete question pages cannot be graded; partial answers may be saved', async () => {
  let calls = transport();
  await assert.rejects(saveAttempt('attempt', { '1': '2' }, true, false, new AbortController().signal));
  assert.equal(calls.length, 1);
  calls = transport();
  assert.equal(await saveAttempt('attempt', { '1': '2' }, false, false, new AbortController().signal), 'IN_PROGRESS');
  assert.deepEqual(calls.map(call => call.method), ['GET', 'PUT']);
});
test('failed answer save must not submit scoring', async () => {
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push(options.method);
    if (url.endsWith('/auth/csrf')) return json({ token: 't', header_name: 'X-CSRF-TOKEN' });
    if (options.method === 'GET') return json({ status: 'IN_PROGRESS' });
    throw new TypeError('offline');
  };
  await assert.rejects(saveAttempt('attempt', { '1': '2' }, true, true, new AbortController().signal));
  assert.equal(calls.includes('POST'), false);
});
test('PDF downloads validate content type instead of saving an error page as a report', async () => {
  globalThis.fetch = async () => new Response('<html>Gateway error</html>', { headers: { 'Content-Type': 'text/html' } });
  await assert.rejects(request('/report', { responseType: 'pdf' }));
  globalThis.fetch = async () => new Response('%PDF-1.4', { headers: { 'Content-Type': 'application/pdf' } });
  const blob = await request('/report', { responseType: 'pdf' });
  assert.equal(blob.type, 'application/pdf');
});

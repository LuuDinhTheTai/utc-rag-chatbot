import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { permitted, staffAccess, officialUrl, editable, payload, kinds } from '../src/staff/model.ts';
import { request, resetCsrf, ApiError } from '../src/client.ts';

test('roles are independent and campus permissions cannot expand to global or other campuses', () => {
  const roles = [{ role_code: 'STAFF_EDITOR', campus_code: 'HN' }, { role_code: 'STAFF_REVIEWER', campus_code: 'HCM' }];
  assert.equal(permitted(roles, 'STAFF_EDITOR', 'HN'), true);
  assert.equal(permitted(roles, 'STAFF_EDITOR', 'HCM'), false);
  assert.equal(permitted(roles, 'STAFF_EDITOR', null), false);
  assert.equal(permitted(roles, 'STAFF_REVIEWER', 'HN'), false);
  assert.equal(permitted([{ role_code: 'ADMIN', campus_code: null }], 'STAFF_EDITOR'), false);
  assert.equal(staffAccess([{ role_code: 'STUDENT', campus_code: null }]), false);
  assert.equal(staffAccess([{ role_code: 'ADMIN', campus_code: 'HN' }]), false);
  assert.equal(permitted([{ role_code: 'STAFF_REVIEWER', campus_code: null }], 'STAFF_REVIEWER', 'HCM'), true);
});
test('official source check rejects deceptive domains, non-HTTPS and credentials', () => {
  for (const url of ['https://utc.edu.vn/a', 'https://tuyensinh.utc.edu.vn/a', 'https://utc2.edu.vn']) assert.equal(officialUrl(url), true);
  for (const url of ['http://utc.edu.vn', 'https://utc.edu.vn.evil.test', 'https://evilutc.edu.vn', 'https://user@utc.edu.vn', 'javascript:alert(1)']) assert.equal(officialUrl(url), false);
});
test('nested admission drafts keep business fields and strip internal DB/version identifiers', () => {
  const schema = kinds.find(kind => kind.key === 'admission-cycles').draft;
  const original = { id: '9', status: 'DRAFT', lock_version: 7, source_revision_id: '9007199254740993', methods: [{ id: '8', cycle_version_id: '9', method_id: '12', eligibility_text: 'Đủ điều kiện', source_revision_id: null }], choices: [{ id: '7', program_offering_id: '5', total_quota: 0, options: [{ id: '6', method_id: '12', method_quota: 0, combinations: [{ option_id: '6', combination_id: '3', conditions_text: 'Điều kiện' }], cutoffs: [{ id: '4', option_id: '6', score: '27.5000', scale_code: '30', source_revision_id: '9007199254740993' }] }] }], schedule_events: [] };
  const body = payload(schema, editable(schema, original));
  assert.equal(body.id, undefined); assert.equal(body.lock_version, undefined);
  assert.equal(body.source_revision_id, '9007199254740993');
  assert.equal(body.methods[0].cycle_version_id, undefined);
  assert.equal(body.choices[0].options[0].id, undefined);
  assert.equal(body.choices[0].total_quota, 0);
  assert.equal(body.choices[0].options[0].cutoffs[0].score, '27.5000');
  assert.equal(body.choices[0].options[0].combinations[0].combination_id, '3');
  assert.deepEqual(body.schedule_events, []);
});
test('score rule fields preserve booleans, decimal weights and omit unused optional configuration', () => {
  const schema = kinds.find(kind => kind.key === 'score-rules').draft;
  const body = payload(schema, editable(schema, { source_revision_id: '2', formula_key: 'WEIGHTED_SUM', output_scale_code: '30', formula_config: { decimal_places: 2, weights: { MATH: '2.00' } }, inputs: [{ id: '8', input_kind: 'SUBJECT', input_code: 'MATH', label: 'Toán', is_required: false, bands: [] }] }));
  assert.equal(body.inputs[0].is_required, false);
  assert.equal(body.inputs[0].id, undefined);
  assert.equal(body.formula_config.weights.MATH, '2.00');
  assert.equal(body.formula_config.decimal_places, 2);
  assert.equal(Object.hasOwn(body.formula_config, 'maximum'), false);
  assert.equal(Object.hasOwn(body.formula_config, 'offset'), false);
});
test('structured form serializer rejects invalid IDs, timestamps, integers and money', () => {
  const bad = [['id', '-1'], ['time', '2026-08-01T08:00'], ['int', '1.5'], ['money', '100.5'], ['decimal', '1e2']];
  for (const [type, raw] of bad) assert.throws(() => payload([{ key: 'v', label: 'Giá trị', type }], { v: raw }));
  assert.deepEqual(payload([{ key: 'v', label: 'Thời điểm', type: 'time' }], { v: '2026-08-01T08:00:00+07:00' }), { v: '2026-08-01T08:00:00+07:00' });
});

const originalFetch = globalThis.fetch;
beforeEach(() => resetCsrf());
afterEach(() => { globalThis.fetch = originalFetch; });
const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
test('ETag metadata is exposed and writes use exact If-Match without retrying 412', async () => {
  let writes = 0; let tag;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/csrf')) return json({ token: 'csrf', header_name: 'X-CSRF-TOKEN' });
    if (options.method === 'GET') return json({ id: '1' }, 200, { ETag: '"version-7"' });
    writes++; assert.equal(options.headers['If-Match'], '"version-7"'); assert.equal(options.headers['X-CSRF-TOKEN'], 'csrf');
    return json({ code: 'PRECONDITION_FAILED' }, 412);
  };
  await request('/editorial/guide-items/1/versions/2', { onResponse: headers => { tag = headers.get('ETag'); } });
  await assert.rejects(request('/editorial/guide-items/1/versions/2', { method: 'PUT', body: {}, ifMatch: tag }), error => error instanceof ApiError && error.status === 412);
  assert.equal(writes, 1);
});
test('multipart PDF transport lets browser set boundary and keeps CSRF/session', async () => {
  const form = new FormData(); form.append('title', 'Tài liệu UTC'); form.append('file', new Blob(['%PDF-1.4'], { type: 'application/pdf' }), 'utc.pdf');
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/csrf')) return json({ token: 'csrf', header_name: 'X-CSRF-TOKEN' });
    assert.equal(options.body, form); assert.equal(options.headers['Content-Type'], undefined);
    assert.equal(options.headers['X-CSRF-TOKEN'], 'csrf'); assert.equal(options.credentials, 'same-origin');
    return json({ id: '3', status: 'INDEXING' }, 202);
  };
  assert.equal((await request('/editorial/document-revisions', { method: 'POST', body: form })).status, 'INDEXING');
});
test('crawler conflict retains existing job ID and location without a duplicate POST', async () => {
  let posts = 0;
  globalThis.fetch = async url => {
    if (url.endsWith('/auth/csrf')) return json({ token: 'csrf', header_name: 'X-CSRF-TOKEN' });
    posts++; return json({ code: 'CONFLICT', job_id: '9007199254740993' }, 409, { Location: '/api/v1/ops/crawl-jobs/9007199254740993' });
  };
  await assert.rejects(request('/ops/crawl-jobs', { method: 'POST', body: { source_id: '1' } }), error => error instanceof ApiError && error.details.job_id === '9007199254740993' && error.details.location.endsWith('/9007199254740993'));
  assert.equal(posts, 1);
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMock } from '../src/mock/server.ts';
import { seed, password } from '../src/mock/fixtures.ts';
function client() {
  const mock = createMock(); mock.settings.delay = 0;
  const send = (path, method = 'GET', body, ifMatch) => mock.fetch(`/api/v1${path}`, { method, headers: { 'X-CSRF-TOKEN': mock.database.csrf, ...(ifMatch ? { 'If-Match': ifMatch } : {}) }, body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body) });
  const login = key => send('/auth/sessions', 'POST', { email: `${key}@demo.utc.test`, password });
  return { mock, send, login };
}
test('all public lists, detail pages and pagination resolve with real-shaped fixtures', async () => {
  const { send } = client();
  for (const path of ['/campuses','/programs','/program-offerings','/admission-cycles','/admission-cycles/1/methods','/admission-cycles/1/options','/admission-options/10011/subject-combinations','/admission-cutoffs','/guides','/guides/1/entries','/schedule-events','/financial-policies','/score-rules','/questionnaires','/chat-suggestions']) {
    const res = await send(path); assert.equal(res.status, 200, path); const page = await res.json(); assert.ok(page.data.length, path); assert.ok(page.pagination, path);
  }
  for (const path of ['/programs/1','/program-offerings/1/curriculum','/program-offerings/1/courses','/admission-cycles/1','/guides/1','/financial-policies/1','/score-rules/11/form']) assert.equal((await send(path)).status, 200, path);
  const first = await (await send('/programs?limit=12')).json(); assert.equal(first.data.length, 12); assert.equal(first.pagination.has_more, true);
  const second = await (await send(`/programs?limit=12&cursor=${first.pagination.next_cursor}`)).json(); assert.equal(second.data.length, 4);
  assert.equal((await (await send('/programs?year=2030')).json()).data.length, 0);
});
test('all six login roles and registration/logout work with CSRF rotation', async () => {
  const { send, login, mock } = client();
  assert.equal((await send('/me')).status, 401);
  for (const key of ['student','editor','reviewer','operator','admin','all']) { const old = mock.database.csrf; assert.equal((await login(key)).status, 200); assert.notEqual(mock.database.csrf, old); assert.equal((await send('/me')).status, 200); }
  assert.equal((await send('/auth/sessions','POST',{email:'all@demo.utc.test',password:'wrong'})).status, 401);
  assert.equal((await send('/auth/accounts','POST',{email:'new@demo.utc.test',password})).status,201);
  assert.equal((await send('/auth/sessions/current','DELETE')).status,204);
});
test('chat saves messages, replays idempotently, records feedback and isolates users', async () => {
  const { send, login, mock } = client(); await login('student');
  const c = await (await send('/conversations','POST',{})).json();
  const body = { client_message_id: 'client-one', content:'Tôi muốn tìm ngành học' };
  const turn = await (await send(`/conversations/${c.id}/messages`,'POST',body)).json(); assert.ok(turn.assistant_message.citations.length);
  await send(`/conversations/${c.id}/messages`,'POST',body);
  assert.equal((await (await send(`/conversations/${c.id}/messages`)).json()).data.length,2);
  assert.equal((await send(`/conversations/${c.id}/messages/${turn.assistant_message.id}/feedback`,'PUT',{is_helpful:true})).status,204);
  mock.settings.noEvidence = true;
  const missing = await (await send(`/conversations/${c.id}/messages`,'POST',{...body,client_message_id:'client-two'})).json(); assert.equal(missing.assistant_message.answer_status,'INSUFFICIENT_EVIDENCE');
  await login('admin'); assert.equal((await send(`/conversations/${c.id}`)).status,404);
});
test('personality supports 104 questions, save/grade/history and valid PDF', async () => {
  const { send, login, mock } = client(); await login('student'); mock.settings.longQuiz = true;
  const a = await (await send('/personality-attempts','POST',{questionnaire_version_id:'1'})).json();
  const first = await (await send(`/personality-attempts/${a.id}/questions?limit=100`)).json(); assert.equal(first.data.length,100); assert.equal(first.pagination.has_more,true);
  const last = await (await send(`/personality-attempts/${a.id}/questions?limit=100&cursor=100`)).json(); assert.equal(last.data.length,4);
  await send(`/personality-attempts/${a.id}/answers`,'PUT',{answers:[...first.data,...last.data].map(q=>({question_id:q.id,option_id:q.options[0].id}))});
  assert.equal((await send(`/personality-attempts/${a.id}/result`,'POST')).status,201);
  await send(`/personality-attempts/${a.id}/saved-result`,'PUT');
  assert.equal((await (await send('/me/personality-results')).json()).data.length,1);
  const pdf = await send(`/personality-attempts/${a.id}/report`); assert.equal(pdf.headers.get('Content-Type'),'application/pdf'); assert.match(await pdf.text(),/%PDF-1.4/);
});
test('score form and calculation support compatible and missing cutoffs and version changes', async () => {
  const { send, mock } = client(); const form = await (await send('/score-rules/11/form')).json();
  const option = mock.database.options.find(o=>o.cycle_id==='1'&&o.method_code==='THPT');
  const body = { rule_version_id:form.version_id, admission_option_id:option.id, compare_previous_year:true, combination_code:'A00', inputs:['math','physics','chemistry'].map(input_code=>({input_code,numeric_value:'8.5'})) };
  const result = await (await send('/score-calculations','POST',body)).json(); assert.equal(result.total,'25.50'); assert.equal(result.comparison.status,'COMPARED');
  assert.equal((await (await send('/score-calculations','POST',{...body,combination_code:'A01'})).json()).comparison.status,'NO_COMPARABLE_CUTOFF');
  mock.settings.nextConflict=true; assert.equal((await send('/score-calculations','POST',body)).status,409);
});
test('all five editorial groups support drafts, ETags, publish and unpublish', async () => {
  const { send, login, mock } = client(); await login('all');
  for (const kind of Object.keys(mock.database.identities)) {
    const base = `/editorial/${kind}`; const parent = mock.database.identities[kind][0];
    assert.equal((await send(base)).status,200); assert.equal((await send(`${base}/${parent.id}`)).status,200);
    const source = mock.database.versions[kind].find(v=>v.item_id===parent.id&&v.status==='DRAFT');
    const read = await send(`${base}/${parent.id}/versions/${source.id}`); const tag = read.headers.get('ETag');
    assert.equal((await send(`${base}/${parent.id}/versions/${source.id}`,'PUT',{title:'Updated'},tag)).status,200);
    assert.equal((await send(`${base}/${parent.id}/versions/${source.id}`,'PUT',{},tag)).status,412);
    const created = await (await send(`${base}/${parent.id}/versions`,'POST',{source_revision_id:'1',title:'New demo draft'})).json();
    const pub = await send(`${base}/${parent.id}/publication`);
    assert.equal((await send(`${base}/${parent.id}/publication`,'PUT',{version_id:created.id},pub.headers.get('ETag'))).status,200);
    const reread = await send(`${base}/${parent.id}/publication`);
    assert.equal((await send(`${base}/${parent.id}/publication`,'PUT',{version_id:null},reread.headers.get('ETag'))).status,200);
  }
});
test('permissions respect campus and ADMIN does not gain editor privileges', async () => {
  const { send, login } = client(); await login('admin'); assert.equal((await send('/editorial/admission-cycles')).status,403);
  await login('editor'); const list = await (await send('/editorial/admission-cycles')).json(); assert.ok(list.data.every(r=>r.campus_code==='HN'));
  assert.equal((await send('/editorial/admission-cycles/2')).status,404); assert.equal((await send('/editorial/programs','POST',{})).status,403);
  const pub = await send('/editorial/admission-cycles/1/publication'); assert.equal((await send('/editorial/admission-cycles/1/publication','PUT',{version_id:null},pub.headers.get('ETag'))).status,403);
});
test('PDF upload indexing and source revocation produce usable document screens', async () => {
  const { send, login, mock } = client(); await login('all');
  const form = new FormData(); form.set('file',new Blob(['%PDF-1.4'],{type:'application/pdf'}),'test.pdf'); form.set('title','Upload demo'); form.set('official_url','https://www.utc.edu.vn/test'); form.set('campus_code','HN'); form.set('year','2026');
  const row = await (await send('/editorial/document-revisions','POST',form)).json(); assert.equal(row.status,'INDEXING');
  mock.database.revisions.find(r=>r.id===row.id).started -= 7000;
  assert.equal((await (await send(`/editorial/document-revisions/${row.id}`)).json()).status,'PENDING');
  const base = `/editorial/documents/${row.document_id}/publication`; const pub = await send(base);
  assert.equal((await send(base,'PUT',{version_id:row.id},pub.headers.get('ETag'))).status,200);
  assert.equal((await (await send(`/editorial/document-revisions/${row.id}/revocation`,'PUT',{reason:'Test'})).json()).status,'REVOKED');
});
test('admin grants/revokes scopes and lock/unlocks another user', async () => {
  const { send, login, mock } = client(); await login('admin'); const student=mock.database.accounts[0]; const base=`/admin/accounts/${student.id}`;
  const role=await (await send(`${base}/role-assignments`,'POST',{role_code:'STAFF_EDITOR',campus_code:'HN'})).json();
  assert.equal((await (await send(`/admin/role-assignments/${role.id}`)).json()).user_id,student.id);
  assert.equal((await send(`/admin/role-assignments/${role.id}`,'DELETE')).status,204);
  await send(base,'PATCH',{status:'LOCKED'}); assert.equal((await (await send(base)).json()).status,'LOCKED');
  await send(base,'PATCH',{status:'ACTIVE'}); assert.equal((await (await send(base)).json()).status,'ACTIVE');
});
test('crawler returns existing job on 409 and progresses to completion', async () => {
  const { send, login, mock }=client(); await login('operator');
  const j=await (await send('/ops/crawl-jobs','POST',{source_id:'1'})).json();
  const duplicate=await send('/ops/crawl-jobs','POST',{source_id:'1'}); assert.equal(duplicate.status,409); assert.equal((await duplicate.json()).job_id,j.id);
  mock.database.jobs.find(job=>job.id===j.id).started-=13000;
  assert.equal((await (await send(`/ops/crawl-jobs/${j.id}`)).json()).status,'SUCCEEDED');
});
test('scenario controls are one-shot, empty lists and persistence roundtrip work', async () => {
  const { send, mock }=client(); mock.settings.empty=true; assert.equal((await (await send('/programs')).json()).data.length,0);
  mock.settings.empty=false; mock.settings.nextError=503; assert.equal((await send('/programs')).status,503); assert.equal((await send('/programs')).status,200);
  const restored=createMock(JSON.parse(JSON.stringify(seed()))); restored.settings.delay=0; assert.equal((await restored.fetch('/api/v1/programs')).status,200);
});

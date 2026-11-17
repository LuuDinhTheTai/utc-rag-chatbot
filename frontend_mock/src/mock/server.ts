import { seed, now, type Database, type Row } from './fixtures.ts';
export interface Settings { empty: boolean; delay: number; nextError: number; nextConflict: boolean; longQuiz: boolean; noEvidence: boolean }
export const defaults: Settings = { empty: false, delay: 250, nextError: 0, nextConflict: false, longQuiz: false, noEvidence: false };
class Failure extends Error {
  status: number; code: string; extra: Row;
  constructor(status: number, code: string, extra: Row = {}) { super(code); this.status = status; this.code = code; this.extra = extra; }
}
export function createMock(initial?: Database, save: (db: Database) => void = () => {}) {
  let db = initial ?? seed(); let sequence = Date.now();
  const settings = { ...defaults };
  const id = () => String(++sequence);
  const fail = (status: number, code: string, extra?: Row): never => { throw new Failure(status, code, extra); };
  const account = () => db.accounts.find(a => a.id === db.currentUser && a.status === 'ACTIVE');
  const owner = () => account()?.id ?? 'guest';
  const user = () => account() ?? fail(401, 'UNAUTHENTICATED');
  const has = (role: string, campus?: string | null) => account()?.roles.some((r: Row) => r.role_code === role && (campus === undefined || r.campus_code === null || (campus !== null && campus === r.campus_code)));
  const check = (roles: string[], campus?: string | null) => { user(); if (!roles.some(role => has(role, campus))) fail(403, 'ROLE_REQUIRED'); };
  const visible = (row: Row, roles = ['STAFF_EDITOR', 'STAFF_REVIEWER']) => roles.some(role => has(role, row.campus_code ?? db.campuses.find(c => c.id === String(row.campus_id))?.code ?? null));
  const own = (rows: Row[], key: string) => rows.find(row => row.id === key && row.owner === owner()) ?? fail(404, 'NOT_FOUND');
  const one = (rows: Row[], key: string) => rows.find(row => String(row.id) === key) ?? fail(404, 'NOT_FOUND');
  const json = (data: unknown, status = 200, headers: Record<string, string> = {}) => new Response(status === 204 ? null : JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json', ...headers } });
  const cleanAccount = (a: Row) => ({ id: a.id, email: a.email, status: a.status, roles: a.roles, created_at: a.created_at });
  const persist = () => save(db);
  function page(rows: Row[], params: URLSearchParams, path: string) {
    if (settings.empty && !['/campuses', '/questionnaires'].includes(path)) rows = [];
    const offset = Number(params.get('cursor') ?? '0');
    if (!Number.isInteger(offset) || offset < 0) fail(400, 'BAD_REQUEST');
    const limit = Math.min(Number(params.get('limit') ?? 12), 100);
    const next = offset + limit;
    return json({ data: rows.slice(offset, next), pagination: { has_more: next < rows.length, next_cursor: next < rows.length ? String(next) : null } });
  }
  function filter(rows: Row[], params: URLSearchParams, aliases: Record<string, string> = {}) {
    return rows.filter(row => [...params].every(([key, val]) => {
      if (['limit', 'cursor'].includes(key) || !val) return true;
      if (key === 'q') return JSON.stringify(row).toLocaleLowerCase().includes(val.toLocaleLowerCase());
      return String(row[aliases[key] ?? key] ?? '') === val;
    }));
  }
  function revision(key: string) { const row = one(db.revisions, key); check(['STAFF_EDITOR', 'STAFF_REVIEWER']); if (!visible(row)) fail(404, 'NOT_FOUND'); if (row.status === 'INDEXING' && Date.now() - row.started > 6000) row.status = 'PENDING'; return row; }
  function match(headers: Headers, etag: string, bump: () => void) {
    if (!headers.get('If-Match')) fail(428, 'PRECONDITION_REQUIRED');
    if (settings.nextConflict) { settings.nextConflict = false; bump(); persist(); fail(412, 'PRECONDITION_FAILED'); }
    if (headers.get('If-Match') !== etag) fail(412, 'PRECONDITION_FAILED');
  }
  function pdf(text: string) {
    const safe = text.replace(/[^\x20-\x7e]/g, ' ').replace(/[()\\]/g, '');
    const stream = `BT /F1 16 Tf 40 760 Td (${safe}) Tj ET`;
    const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>', '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`];
    let output = '%PDF-1.4\n'; const offsets = [0];
    objects.forEach((obj, i) => { offsets.push(output.length); output += `${i + 1} 0 obj\n${obj}\nendobj\n`; });
    const xref = output.length; output += `xref\n0 6\n0000000000 65535 f \n${offsets.slice(1).map(n => String(n).padStart(10, '0') + ' 00000 n \n').join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
    return new Response(output, { headers: { 'Content-Type': 'application/pdf' } });
  }
  async function handle(input: string | URL | Request, init: RequestInit = {}): Promise<Response> {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, 'http://mock.local');
    const path = url.pathname.replace(/^\/api\/v1/, ''); const params = url.searchParams;
    const method = init.method ?? 'GET'; const headers = new Headers(init.headers); const signal = init.signal;
    if (settings.delay) await new Promise<void>((resolve, reject) => { const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')); }; const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, settings.delay); if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true }); });
    signal?.throwIfAborted();
    try {
      if (settings.nextError) { const status = settings.nextError; settings.nextError = 0; fail(status, status === 429 ? 'RATE_LIMITED' : 'SERVICE_UNAVAILABLE'); }
      if (method !== 'GET' && headers.get('X-CSRF-TOKEN') !== db.csrf) fail(403, 'CSRF_INVALID');
      const body: Row = typeof init.body === 'string' ? JSON.parse(init.body) : {};
      const result = await route(); persist(); return result;
      async function route(): Promise<Response> {
        if (path === '/auth/csrf') return json({ token: db.csrf, header_name: 'X-CSRF-TOKEN' });
        if (path === '/me') return json(cleanAccount(user()));
        if (path === '/auth/accounts' || path === '/auth/sessions') {
          let a = db.accounts.find(a => a.email.toLowerCase() === String(body.email).toLowerCase());
          if (path === '/auth/accounts') {
            if (a) fail(409, 'CONFLICT');
            if (String(body.password).length < 8) fail(422, 'VALIDATION_FAILED');
            a = { id: id(), email: body.email, password: body.password, roles: [{ id: id(), role_code: 'STUDENT', campus_code: null }], status: 'ACTIVE', created_at: now() }; db.accounts.push(a);
          } else if (!a || a.password !== body.password || a.status !== 'ACTIVE') fail(401, 'UNAUTHENTICATED');
          db.currentUser = a!.id; db.csrf = `csrf-${id()}`; return json(cleanAccount(a!), path === '/auth/accounts' ? 201 : 200);
        }
        if (path === '/auth/sessions/current' && method === 'DELETE') { user(); db.currentUser = null; db.csrf = `csrf-${id()}`; return json(null, 204); }
        if (path === '/campuses') return page(db.campuses, params, path);
        if (path === '/chat-suggestions') return page(db.suggestions, params, path);
        if (path === '/me/conversations') { user(); return page(filter(db.conversations.filter(c => c.owner === owner()).slice().reverse(), params), params, path); }
        if (path === '/conversations' && method === 'POST') { const c = { id: id(), owner: owner(), display_title: body.display_title ?? null, created_at: now(), updated_at: now() }; db.conversations.push(c); return json(c, 201); }
        const chat = path.match(/^\/conversations\/([^/]+)(?:\/messages(?:\/([^/]+)(\/feedback)?)?)?$/);
        if (chat) {
          const c = own(db.conversations, chat[1]);
          if (chat[3] && method === 'PUT') { const m = one(db.messages, chat[2]); if (m.conversation_id !== c.id || m.author !== 'ASSISTANT') fail(404, 'NOT_FOUND'); m.feedback = body; return json(null, 204); }
          if (path.endsWith('/messages') && method === 'POST') {
            const old = db.messages.find(m => m.id === body.client_message_id);
            if (old) { if (old.conversation_id !== c.id || old.content !== body.content) fail(409, 'CLIENT_MESSAGE_ID_CONFLICT'); return json({ user_message: old, assistant_message: db.messages.find(m => m.reply_to_id === old.id) }); }
            const missing = settings.noEvidence || String(body.content).toLowerCase().includes('thiếu nguồn');
            const u = { id: body.client_message_id, conversation_id: c.id, author: 'USER', content: body.content, citations: [], created_at: now() };
            const a = { id: id(), conversation_id: c.id, reply_to_id: u.id, author: 'ASSISTANT', content: missing ? 'Hiện chưa có đủ nguồn được xác minh để trả lời câu hỏi này. [Phản hồi mô phỏng]' : `Bạn đang hỏi: “${body.content}”\n\n[DEMO] UTC có nhiều nhóm ngành kỹ thuật, công nghệ và kinh tế vận tải. Bạn có thể tra cứu ngành, phương thức xét tuyển và hướng dẫn nhập học trong menu.\n\nĐây là nội dung mẫu, không phải kết quả AI hoặc tư vấn tuyển sinh thực tế.`, created_at: now(), answer_status: missing ? 'INSUFFICIENT_EVIDENCE' : 'ANSWERED', citations: missing ? [] : [{ ...db.source, locator: 'Mục minh họa' }] };
            db.messages.push(u, a); c.display_title ??= String(body.content).slice(0, 100); c.updated_at = now(); return json({ user_message: u, assistant_message: a }, 201);
          }
          if (path.endsWith('/messages')) return page(db.messages.filter(m => m.conversation_id === c.id), params, path);
          if (chat[2]) { const m = one(db.messages, chat[2]); if (m.conversation_id !== c.id) fail(404, 'NOT_FOUND'); return json(m); }
          if (method === 'DELETE') { db.conversations = db.conversations.filter(r => r.id !== c.id); db.messages = db.messages.filter(m => m.conversation_id !== c.id); return json(null, 204); }
          return json(c);
        }
        if (path === '/admission-cycles') return page(filter(db.cycles, params, { year: 'admission_year' }), params, path);
        const cycle = path.match(/^\/admission-cycles\/([^/]+)(?:\/(methods|options))?$/);
        if (cycle) { one(db.cycles, cycle[1]); return cycle[2] === 'methods' ? page(db.methods, params, path) : cycle[2] === 'options' ? page(filter(db.options.filter(o => o.cycle_id === cycle[1]), params), params, path) : json(one(db.cycles, cycle[1])); }
        if (/^\/admission-options\/[^/]+\/subject-combinations$/.test(path)) return page(db.combinations, params, path);
        if (path === '/admission-cutoffs') return page(filter(db.cutoffs, params), params, path);
        if (path === '/programs') { let rows = db.programs; if (params.get('campus_code') || params.get('year')) rows = rows.filter(p => db.offerings.some(o => o.program_id === p.id && (!params.get('campus_code') || o.campus_code === params.get('campus_code')) && (!params.get('year') || String(o.entry_year) === params.get('year')))); const p = new URLSearchParams(params); p.delete('campus_code'); p.delete('year'); return page(filter(rows, p), params, path); }
        if (/^\/programs\/[^/]+$/.test(path)) return json(one(db.programs, path.split('/')[2]));
        if (path === '/program-offerings') return page(filter(db.offerings, params, { year: 'entry_year' }), params, path);
        const offering = path.match(/^\/program-offerings\/([^/]+)\/(curriculum|courses)$/);
        if (offering) {
          one(db.offerings, offering[1]); const published = db.versions['program-offerings'].find(v => v.item_id === offering[1] && v.status === 'PUBLISHED'); if (!published) fail(404, 'NOT_FOUND');
          if (offering[2] === 'curriculum') return json({ ...published, source: db.source });
          return page(filter(published!.courses.map((c: Row) => ({ ...c, id: c.course_id, code: `HP${c.course_id}`, source: db.source })), params, { group: 'course_group', term: 'suggested_term' }), params, path);
        }
        if (path === '/guides') return page(filter(db.guides, params, { year: 'admission_year', type: 'guide_type' }), params, path);
        const guide = path.match(/^\/guides\/([^/]+)(\/entries)?$/);
        if (guide) return guide[2] ? page(db.entries.filter(e => e.guide_id === guide[1]), params, path) : json(one(db.guides, guide[1]));
        if (path === '/schedule-events') return page(filter(db.events, params), params, path);
        if (path === '/financial-policies') return page(filter(db.finances, params, { year: 'entry_year', type: 'policy_type' }), params, path);
        if (/^\/financial-policies\/[^/]+$/.test(path)) return json(one(db.finances, path.split('/')[2]));
        if (path === '/score-rules') return page(filter(db.rules, params), params, path);
        const scoreForm = path.match(/^\/score-rules\/([^/]+)\/form$/); if (scoreForm) return json(one(db.rules, scoreForm[1]));
        if (path === '/score-calculations' && method === 'POST') {
          const rule = db.rules.find(r => r.version_id === body.rule_version_id) ?? fail(409, 'RULE_VERSION_CHANGED');
          if (settings.nextConflict) { settings.nextConflict = false; rule.version_id = id(); fail(409, 'RULE_VERSION_CHANGED'); }
          const parts = (body.inputs ?? []).map((input: Row) => { const definition = rule.inputs.find((r: Row) => r.input_code === input.input_code); if (!definition) fail(422, 'VALIDATION_FAILED'); const n = input.choice_code ? input.choice_code === 'KV1' ? 0.75 : 0 : Number(input.numeric_value); if (!Number.isFinite(n) || n < 0 || n > 10) fail(422, 'VALIDATION_FAILED'); return { label: definition!.label, score: n.toFixed(2) }; });
          const total = Math.min(30, parts.reduce((sum: number, p: Row) => sum + Number(p.score), 0));
          const target = db.options.find(o => o.id === body.admission_option_id);
          if (body.compare_previous_year && (!target || target.campus_code !== rule.campus_code || target.method_code !== rule.method_code || target.year !== rule.year)) fail(422, 'VALIDATION_FAILED');
          return json({ rule_version_id: rule.version_id, total: total.toFixed(2), scale_code: rule.output_scale_code, parts, comparison: !body.compare_previous_year ? null : body.combination_code === 'A00' ? { status: 'COMPARED', cutoff: '24.00', difference: (total - 24).toFixed(2) } : { status: 'NO_COMPARABLE_CUTOFF', reason: 'NO_UNIQUE_MATCHING_CUTOFF' }, source: db.source, disclaimer: 'Kết quả mô phỏng chỉ để thử giao diện, không có giá trị xét tuyển.' });
        }
        if (path === '/questionnaires') return page(db.questionnaires, params, path);
        if (/^\/questionnaires\/[^/]+\/questions$/.test(path)) return page(quiz(), params, path);
        if (path === '/personality-attempts' && method === 'POST') { const a = { id: id(), questionnaire_version_id: body.questionnaire_version_id, owner: owner(), status: 'IN_PROGRESS', answers: [], saved_at: null, longQuiz: settings.longQuiz }; db.attempts.push(a); return json(a, 201); }
        if (path === '/me/personality-results') { user(); return page(db.attempts.filter(a => a.owner === owner() && a.saved_at).slice().reverse(), params, path); }
        const attempt = path.match(/^\/personality-attempts\/([^/]+)(?:\/(questions|answers|result|recommendations|saved-result|report))?$/);
        if (attempt) {
          const a = own(db.attempts, attempt[1]); const part = attempt[2];
          if (!part) return json(a);
          if (part === 'questions') return page(quiz(a.longQuiz), params, path);
          if (part === 'answers' && method === 'PUT') { if (a.status !== 'IN_PROGRESS') fail(409, 'CONFLICT'); a.answers = body.answers; return json(null, 204); }
          if (part === 'result') {
            if (method === 'POST') { if (a.status === 'COMPLETED') fail(409, 'CONFLICT'); if (a.answers.length !== quiz(a.longQuiz).length) fail(422, 'VALIDATION_FAILED'); a.status = 'COMPLETED'; a.completed_at = now(); }
            if (a.status !== 'COMPLETED') fail(409, 'CONFLICT');
            const score = a.answers.reduce((n: number, answer: Row) => n + (4 - Number(String(answer.option_id).slice(-1))), 0);
            return json({ attempt_id: a.id, questionnaire_version_id: a.questionnaire_version_id, profile: { name: score >= a.answers.length * 2 ? 'Nhóm khám phá kỹ thuật [DEMO]' : 'Nhóm tổ chức và kết nối [DEMO]', description: 'Mô tả minh họa: bạn quan tâm đến giải pháp, làm việc nhóm và học hỏi. Kết quả phụ thuộc lựa chọn thử nghiệm, không phải phép đo tính cách thực.' }, scores: { Kỹ_thuật: score, Kết_nối: a.answers.length * 3 - score }, completed_at: a.completed_at, disclaimer: 'Bộ đề và kết quả mô phỏng, không phải chẩn đoán hoặc tư vấn nghề nghiệp.' }, method === 'POST' ? 201 : 200);
          }
          if (part === 'recommendations') return page(db.programs.slice(0, 3).map(p => ({ ...p, rationale: 'Ngành gợi ý minh họa dựa trên kết quả thử nghiệm.' })), params, path);
          if (part === 'saved-result') { user(); if (a.status !== 'COMPLETED') fail(409, 'CONFLICT'); a.saved_at = now(); return json(null, 204); }
          if (part === 'report') { user(); if (!a.saved_at) fail(404, 'NOT_FOUND'); return pdf(`UTC MOCK - Personality report - Attempt ${a.id}`); }
        }
        if (path.startsWith('/editorial/')) return editorial();
        if (path.startsWith('/admin/')) return admin();
        if (path.startsWith('/ops/')) return ops();
        return fail(404, 'MOCK_ROUTE_NOT_IMPLEMENTED', { path, method });
      }
      function quiz(long = settings.longQuiz) { return long ? Array.from({ length: 104 }, (_, i) => ({ ...db.questions[i % 8], id: String(i + 1), position: i + 1, body: `[Câu ${i + 1}] ${db.questions[i % 8].body}`, options: [1, 2, 3].map(n => ({ id: `${i + 1}${n}`, label: ['Rất phù hợp', 'Khá phù hợp', 'Chưa phù hợp'][n - 1] })) })) : db.questions; }
      function editorial(): Response {
        check(['STAFF_EDITOR', 'STAFF_REVIEWER']);
        if (path === '/editorial/programs' && method === 'POST') { check(['STAFF_EDITOR'], null); const row = { ...body, id: id(), source: db.source }; db.programs.push(row); return json(row, 201); }
        const program = path.match(/^\/editorial\/programs\/([^/]+)$/); if (program) { check(['STAFF_EDITOR', 'STAFF_REVIEWER'], null); return json(one(db.programs, program[1])); }
        if (path === '/editorial/documents') return page(filter(db.documents.filter(d => db.revisions.some(r => r.document_id === d.id && visible(r) && filter([{ ...r, campus_code: campusCode(r) }], params, { year: 'admission_year' }).length)), new URLSearchParams()), params, path);
        if (path === '/editorial/document-revisions' && method === 'POST') return upload();
        const rev = path.match(/^\/editorial\/document-revisions\/([^/]+)(\/revocation)?$/);
        if (rev) { const r = revision(rev[1]); if (rev[2]) { check(['STAFF_REVIEWER'], campusCode(r)); if (!r.source_verified || !String(body.reason ?? '').trim()) fail(409, 'CONFLICT'); r.status = 'REVOKED'; r.revoked_at = now(); r.revocation_reason = body.reason; r.lock_version++; db.publications[`documents/${r.document_id}`] = (db.publications[`documents/${r.document_id}`] ?? 0) + 1; } return json(r, 200, { ETag: `"version-${r.lock_version}"` }); }
        const doc = path.match(/^\/editorial\/documents\/([^/]+)\/(revisions|publication)$/);
        if (doc) { const rows = db.revisions.filter(r => r.document_id === doc[1]); if (!rows.some(r => visible(r))) fail(404, 'NOT_FOUND'); if (doc[2] === 'revisions') return page(filter(rows.filter(r => visible(r)).map(r => ({ ...r, campus_code: campusCode(r) })), params, { year: 'admission_year' }), params, path); return publication(`documents/${doc[1]}`, rows, true); }
        const route = path.match(/^\/editorial\/([^/]+)(?:\/([^/]+))?(?:\/(versions|publication))?(?:\/([^/]+))?$/);
        if (!route || !db.identities[route[1]]) fail(404, 'NOT_FOUND');
        const [, kind, itemId, part, versionId] = route!; const items = db.identities[kind];
        if (!itemId) {
          if (method === 'POST') { check(['STAFF_EDITOR'], body.campus_code); const campus = db.campuses.find(c => c.code === body.campus_code) ?? fail(422, 'VALIDATION_FAILED'); const row = { ...body, id: id(), campus_id: campus.id }; items.push(row); return json(row, 201); }
          return page(filter(items.filter(r => visible(r)), params, { year: kind === 'program-offerings' || kind === 'financial-policies' ? 'entry_year' : 'admission_year' }), params, path);
        }
        const item = one(items, itemId); if (!visible(item)) fail(404, 'NOT_FOUND');
        const versions = db.versions[kind].filter(v => v.item_id === itemId);
        if (part === 'publication') return publication(`${kind}/${itemId}`, versions, false, item.campus_code, kind, item);
        if (!part) return json(item);
        if (!versionId && method === 'GET') return page(filter(versions, params), params, path);
        if (!versionId && method === 'POST') { check(['STAFF_EDITOR'], item.campus_code); const row = { ...body, id: id(), item_id: itemId, status: 'DRAFT', version_no: versions.length + 1, lock_version: 0, updated_at: now() }; db.versions[kind].push(row); return json(row, 201, { ETag: '"version-0"' }); }
        const version = one(versions, versionId);
        if (method === 'PUT') { check(['STAFF_EDITOR'], item.campus_code); match(headers, `"version-${version.lock_version}"`, () => { version.lock_version++; version.updated_at = now(); }); if (version.status !== 'DRAFT') fail(409, 'CONFLICT'); Object.assign(version, body, { lock_version: version.lock_version + 1, updated_at: now() }); }
        return json(version, 200, { ETag: `"version-${version.lock_version}"` });
      }
      function campusCode(row: Row) { return row.campus_code ?? db.campuses.find(c => c.id === String(row.campus_id))?.code ?? null; }
      function publication(key: string, rows: Row[], document: boolean, campus?: string, kind?: string, item?: Row): Response {
        const count = db.publications[key] ?? 0;
        if (method === 'PUT') {
          const active = rows.find(r => r.status === 'PUBLISHED'); const chosen = body.version_id == null ? null : one(rows, body.version_id);
          if (active) check(['STAFF_REVIEWER'], document ? campusCode(active) : campus);
          check(['STAFF_REVIEWER'], document ? campusCode(chosen ?? rows[0]) : campus);
          match(headers, `"publication-${count}"`, () => { db.publications[key] = count + 1; });
          if (chosen && (chosen.revoked_at || (document && !['PENDING', 'PUBLISHED', 'ARCHIVED'].includes(chosen.status)))) fail(409, 'CONFLICT');
          if (chosen && !document) { const source = one(db.revisions, String(chosen.source_revision_id)); if (!source.source_verified || source.revoked_at) fail(409, 'CONFLICT'); }
          rows.filter(r => r.status === 'PUBLISHED').forEach(r => { r.status = 'ARCHIVED'; r.lock_version++; });
          if (chosen) { chosen.status = 'PUBLISHED'; chosen.published_at = now(); chosen.lock_version++; if (document) chosen.source_verified = true; }
          db.publications[key] = count + 1;
          if (!document && chosen && item) {
            const publicRows = kind === 'guide-items' ? db.guides : kind === 'financial-policies' ? db.finances : kind === 'score-rules' ? db.rules : null;
            if (publicRows) { const existing = publicRows.find(r => r.id === item.id); const projection = { ...item, ...bodyFields(chosen), id: item.id, source: db.source }; if (existing) Object.assign(existing, projection); else publicRows.push(projection as any); }
          }
        }
        const active = rows.find(r => r.status === 'PUBLISHED');
        return json({ version_id: active?.id ?? null, published_at: active?.published_at ?? null }, 200, { ETag: `"publication-${db.publications[key] ?? 0}"` });
      }
      function bodyFields(row: Row) { const { id: _id, item_id: _item, status: _status, ...rest } = row; return rest; }
      function upload(): Response {
        check(['STAFF_EDITOR']); const form = init.body as FormData; const campus = String(form.get('campus_code') ?? '') || null; check(['STAFF_EDITOR'], campus);
        const file = form.get('file'); if (!(file instanceof Blob) || file.size === 0 || file.size > 20 * 1024 * 1024) fail(413, 'TOO_LARGE');
        const url = String(form.get('official_url')); let d = db.documents.find(d => d.canonical_url === url); if (!d) { d = { id: id(), canonical_url: url }; db.documents.push(d); }
        const row = { id: id(), document_id: d.id, title: String(form.get('title')), official_url: url, campus_id: campus ? db.campuses.find(c => c.code === campus)?.id : null, admission_year: Number(form.get('year')) || null, status: 'INDEXING', source_verified: false, revoked_at: null, lock_version: 0, started: Date.now(), updated_at: now() }; db.revisions.push(row); return json(row, 202);
      }
      function admin(): Response {
        check(['ADMIN'], null);
        if (path === '/admin/accounts') return page(filter(db.accounts.map(cleanAccount), params), params, path);
        const aRoute = path.match(/^\/admin\/accounts\/([^/]+)(\/role-assignments)?$/);
        if (aRoute) {
          const a = one(db.accounts, aRoute[1]);
          if (aRoute[2]) {
            if (method === 'POST') { if (body.campus_code && !['STAFF_EDITOR', 'STAFF_REVIEWER'].includes(body.role_code)) fail(422, 'VALIDATION_FAILED'); if (a.roles.some((r: Row) => r.role_code === body.role_code && r.campus_code === body.campus_code)) fail(409, 'CONFLICT'); const r = { id: id(), ...body, assigned_at: now() }; a.roles.push(r); if (a.id === db.currentUser) db.currentUser = null; return json(r, 201); }
            return page(filter(a.roles, params), params, path);
          }
          if (method === 'PATCH') { if (body.status === 'LOCKED' && a.roles.some((r: Row) => r.role_code === 'ADMIN') && db.accounts.filter(r => r.status === 'ACTIVE' && r.roles.some((x: Row) => x.role_code === 'ADMIN' && x.campus_code === null)).length <= 1) fail(409, 'CONFLICT'); a.status = body.status; if (a.id === db.currentUser) db.currentUser = null; }
          return json(cleanAccount(a));
        }
        const rRoute = path.match(/^\/admin\/role-assignments\/([^/]+)$/); if (rRoute) {
          const a = db.accounts.find(a => a.roles.some((r: Row) => r.id === rRoute[1])) ?? fail(404, 'NOT_FOUND'); const r = a.roles.find((r: Row) => r.id === rRoute[1]);
          if (method === 'DELETE') { if (r.role_code === 'ADMIN' && r.campus_code === null && db.accounts.filter(a => a.status === 'ACTIVE' && a.roles.some((r: Row) => r.role_code === 'ADMIN' && r.campus_code === null)).length <= 1) fail(409, 'CONFLICT'); a.roles = a.roles.filter((x: Row) => x.id !== r.id); if (a.id === db.currentUser) db.currentUser = null; return json(null, 204); }
          return json({ ...r, user_id: a.id });
        }
        return fail(404, 'NOT_FOUND');
      }
      function ops(): Response {
        check(['TECH_OPERATOR'], null);
        const advance = (job: Row) => { if (!job.started) return; const elapsed = Date.now() - job.started; job.status = elapsed > 12000 ? 'SUCCEEDED' : elapsed > 4000 ? 'RUNNING' : 'PENDING'; };
        db.jobs.forEach(advance);
        if (path === '/ops/crawl-sources') return page(db.sources, params, path);
        if (path === '/ops/crawl-jobs') {
          if (method === 'POST') { const source = one(db.sources, body.source_id); if (!source.enabled) fail(409, 'CONFLICT'); const existing = db.jobs.find(j => j.source_id === body.source_id && ['PENDING', 'RUNNING'].includes(j.status)); if (existing) fail(409, 'CONFLICT', { job_id: existing.id, location: `/api/v1/ops/crawl-jobs/${existing.id}` }); const row = { id: id(), source_id: body.source_id, status: 'PENDING', attempt_no: 1, run_after: now(), started: Date.now() }; db.jobs.push(row); return json(row, 202); }
          return page(filter(db.jobs.slice().reverse(), params), params, path);
        }
        return json(one(db.jobs, path.split('/')[3]));
      }
    } catch (error) {
      persist(); if (!(error instanceof Failure)) { console.error('[UTC MOCK]', error); return json({ code: 'MOCK_INTERNAL_ERROR' }, 500); }
      return json({ code: error.code, status: error.status, ...error.extra }, error.status, error.extra.location ? { Location: error.extra.location } : {});
    }
  }
  return { fetch: handle, settings, get database() { return db; }, reset() { db = seed(); persist(); }, expire() { db.currentUser = null; db.csrf = `expired-${id()}`; persist(); } };
}

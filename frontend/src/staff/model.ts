export interface Role { role_code: string; campus_code: string | null }
export function permitted(roles: Role[], role: string, campus?: string | null) {
  return roles.some(item => item.role_code === role && (campus === undefined || item.campus_code === null || (campus !== null && item.campus_code === campus)));
}
export const staffAccess = (roles: Role[]) => roles.some(role => ['STAFF_EDITOR', 'STAFF_REVIEWER'].includes(role.role_code)) || permitted(roles, 'ADMIN', null) || permitted(roles, 'TECH_OPERATOR', null);
export function officialUrl(url: string) {
  try { const u = new URL(url); return u.protocol === 'https:' && !u.username && !u.password && ['utc.edu.vn', 'utc2.edu.vn'].some(domain => u.hostname === domain || u.hostname.endsWith(`.${domain}`)); } catch { return false; }
}
export const ongoing = (status: unknown) => ['INDEXING', 'PENDING', 'RUNNING'].includes(String(status));

export type Data = Record<string, unknown>;
export interface Field { key: string; label: string; type?: 'text' | 'long' | 'id' | 'int' | 'decimal' | 'money' | 'time' | 'bool' | 'select' | 'list' | 'object' | 'weights'; optional?: boolean; options?: string[]; fields?: Field[] }
const f = (key: string, label: string, type: Field['type'] = 'text', optional = false): Field => ({ key, label, type, optional });
const list = (key: string, label: string, fields: Field[]): Field => ({ key, label, type: 'list', fields });
const source = f('source_revision_id', 'Mã phiên bản nguồn tài liệu', 'id');
const events = list('schedule_events', 'Mốc thời gian', [f('title', 'Tiêu đề'), f('starts_at', 'Bắt đầu (RFC 3339)', 'time'), f('ends_at', 'Kết thúc (RFC 3339)', 'time', true), f('location', 'Địa điểm', 'text', true), f('notes', 'Ghi chú', 'long', true)]);
export interface Kind { key: string; label: string; identity: Field[]; draft: Field[] }
export const kinds: Kind[] = [
  { key: 'admission-cycles', label: 'Tuyển sinh', identity: [f('admission_year', 'Năm tuyển sinh', 'int'), f('round_code', 'Mã đợt')], draft: [source,
    list('methods', 'Phương thức xét tuyển', [f('method_id', 'Mã định danh phương thức', 'id'), f('eligibility_text', 'Điều kiện xét tuyển', 'long'), { ...source, optional: true }]),
    list('choices', 'Ngành và chỉ tiêu', [f('program_offering_id', 'Mã chương trình theo khóa', 'id'), f('total_quota', 'Tổng chỉ tiêu', 'int', true), { ...source, optional: true },
      list('options', 'Lựa chọn xét tuyển', [f('method_id', 'Mã định danh phương thức', 'id'), f('method_quota', 'Chỉ tiêu phương thức', 'int', true), f('additional_conditions', 'Điều kiện bổ sung', 'long', true),
        list('combinations', 'Tổ hợp môn', [f('combination_id', 'Mã định danh tổ hợp', 'id'), f('conditions_text', 'Điều kiện', 'long', true)]),
        list('cutoffs', 'Điểm chuẩn', [f('score', 'Điểm', 'decimal'), f('scale_code', 'Thang điểm'), source, f('combination_id', 'Mã định danh tổ hợp', 'id', true)])])]), events] },
  { key: 'program-offerings', label: 'Chương trình đào tạo', identity: [f('program_id', 'Mã định danh ngành', 'id'), f('entry_year', 'Khóa tuyển sinh', 'int')], draft: [source, f('duration_months', 'Thời lượng (tháng)', 'int'), f('total_credits', 'Tổng tín chỉ', 'int'), f('objectives', 'Mục tiêu', 'long'), f('learning_outcomes', 'Chuẩn đầu ra', 'long'), f('career_paths', 'Nghề nghiệp', 'long'), list('courses', 'Học phần', [f('course_id', 'Mã định danh học phần', 'id'), f('course_title', 'Tên học phần'), f('credits', 'Tín chỉ', 'int'), f('course_group', 'Nhóm học phần'), f('suggested_term', 'Học kỳ dự kiến', 'int', true)])] },
  { key: 'guide-items', label: 'Hướng dẫn', identity: [f('admission_year', 'Năm', 'int'), f('guide_type', 'Loại hướng dẫn'), f('audience_code', 'Mã đối tượng'), f('guide_slug', 'Định danh hướng dẫn'), f('cycle_id', 'Mã đợt tuyển sinh', 'id', true), f('method_id', 'Mã phương thức', 'id', true)], draft: [source, f('title', 'Tiêu đề'), f('body', 'Nội dung', 'long'), list('entries', 'Bước thực hiện / checklist / liên hệ', [f('position', 'Thứ tự', 'int'), f('entry_type', 'Loại mục'), f('label', 'Tên mục'), f('instructions', 'Hướng dẫn', 'long', true), f('link_url', 'Liên kết', 'text', true), f('is_required', 'Bắt buộc', 'bool', true)]), events] },
  { key: 'financial-policies', label: 'Tài chính', identity: [f('entry_year', 'Khóa', 'int'), f('policy_type', 'Loại chính sách'), f('policy_code', 'Mã chính sách'), f('program_offering_id', 'Mã chương trình', 'id', true)], draft: [source, f('title', 'Tiêu đề'), f('body', 'Nội dung', 'long'), f('amount_vnd', 'Số tiền (VND)', 'money', true), f('charge_unit', 'Đơn vị thu', 'text', true), f('eligibility_text', 'Điều kiện', 'long', true), f('due_at', 'Hạn nộp (RFC 3339)', 'time', true)] },
  { key: 'score-rules', label: 'Quy tắc điểm', identity: [f('admission_year', 'Năm tuyển sinh', 'int'), f('method_id', 'Mã phương thức', 'id')], draft: [source, { ...f('formula_key', 'Công thức', 'select'), options: ['SUM', 'WEIGHTED_SUM', 'SUM_BANDS'] }, f('output_scale_code', 'Thang điểm đầu ra'), { key: 'formula_config', label: 'Cấu hình công thức', type: 'object', fields: [f('weights', 'Trọng số theo mã đầu vào', 'weights', true), f('offset', 'Điểm điều chỉnh', 'decimal', true), f('minimum', 'Điểm tối thiểu', 'decimal', true), f('maximum', 'Điểm tối đa', 'decimal', true), f('decimal_places', 'Số chữ số thập phân (0–8)', 'int', true)] },
    list('inputs', 'Trường nhập và dải quy đổi', [f('input_kind', 'Loại đầu vào'), f('input_code', 'Mã đầu vào'), f('label', 'Nhãn hiển thị'), f('is_required', 'Bắt buộc', 'bool'), f('min_value', 'Giá trị nhỏ nhất', 'decimal', true), f('max_value', 'Giá trị lớn nhất', 'decimal', true), list('bands', 'Dải quy đổi', [f('converted_value', 'Điểm quy đổi', 'decimal'), f('match_code', 'Mã lựa chọn', 'text', true), f('lower_bound', 'Cận dưới', 'decimal', true), f('upper_bound', 'Cận trên', 'decimal', true)])])] },
];
export const programFields: Field[] = [f('field_id', 'Mã định danh lĩnh vực', 'id'), f('parent_program_id', 'Mã ngành cha', 'id', true), f('code', 'Mã ngành'), f('name', 'Tên ngành')];
export function editable(fields: Field[], input: Data): Data {
  return Object.fromEntries(fields.map(field => {
    const raw = input[field.key];
    if (field.type === 'list') return [field.key, Array.isArray(raw) ? raw.map(item => editable(field.fields!, item)) : []];
    if (field.type === 'object') return [field.key, editable(field.fields!, raw && typeof raw === 'object' ? raw as Data : {})];
    return [field.key, raw ?? (field.type === 'weights' ? {} : field.type === 'bool' && !field.optional ? false : '')];
  }));
}
export function payload(fields: Field[], input: Data): Data {
  const output: Data = {};
  for (const field of fields) {
    const raw = input[field.key];
    if (field.type === 'list') { output[field.key] = (Array.isArray(raw) ? raw : []).map(row => payload(field.fields!, row)); continue; }
    if (field.type === 'object') { output[field.key] = payload(field.fields!, (raw ?? {}) as Data); continue; }
    if (field.type === 'weights') {
      const entries = Object.entries((raw ?? {}) as Data);
      if (entries.some(([key, val]) => !key.trim() || !/^-?\d+(\.\d+)?$/.test(String(val)))) throw new Error('Trọng số phải có mã đầu vào và giá trị thập phân.');
      output[field.key] = Object.fromEntries(entries.map(([key, val]) => [key, String(val)])); continue;
    }
    if (raw === undefined || raw === null || raw === '') {
      if (!field.optional) throw new Error(`Cần nhập ${field.label}.`);
      // Formula config optional properties must be absent, not null (backend tests contains/get).
      continue;
    }
    if (field.type === 'bool') { if (typeof raw !== 'boolean') throw new Error(`Giá trị ${field.label} không hợp lệ.`); output[field.key] = raw; continue; }
    const text = String(raw).trim();
    if (!text) throw new Error(`Cần nhập ${field.label}.`);
    if (field.type === 'int') { if (!/^\d+$/.test(text) || Number(text) > (field.key === 'decimal_places' ? 8 : 1000000)) throw new Error(`${field.label} nằm ngoài khoảng số nguyên cho phép.`); output[field.key] = Number(text); }
    else {
      if (field.type === 'id' && !/^[1-9]\d*$/.test(text)) throw new Error(`${field.label} phải là mã định danh số dương.`);
      if (field.type === 'decimal' && !/^-?\d+(\.\d+)?$/.test(text)) throw new Error(`${field.label} phải là số thập phân.`);
      if (field.type === 'money' && !/^\d{1,15}$/.test(text)) throw new Error('Số tiền VND phải là chuỗi số nguyên, tối đa 15 chữ số.');
      if (field.type === 'select' && !field.options?.includes(text)) throw new Error(`Chọn ${field.label} hợp lệ.`);
      if (field.type === 'time' && (!/^\d{4}-\d{2}-\d{2}T.*(Z|[+-]\d{2}:\d{2})$/.test(text) || Number.isNaN(Date.parse(text)))) throw new Error(`${field.label}: dùng dạng 2026-08-01T08:00:00+07:00.`);
      output[field.key] = text;
    }
  }
  return output;
}

import { useState } from 'react';
import { query, usePage } from '../api';
import { Events, Fields, FilterForm, Listing, ListStatus, Resource, SourceNote, Table, safeUrl, value, type Row, type Scope } from './shared';

export function Programs({ scope, initialId = '' }: { scope: Scope; initialId?: string }) {
  const [selected, select] = useState(initialId);
  const [filters, setFilters] = useState<Record<string, string>>({});
  return <section className="panel feature-panel"><div className="panel-heading"><h2>Ngành và chương trình đào tạo</h2>{selected && <button className="secondary" onClick={() => select('')}>← Danh sách ngành</button>}</div>
    {selected ? <ProgramDetail key={selected} id={selected} scope={scope}/> : <><FilterForm fields={[["q", "Tên hoặc mã ngành"], ["field_code", "Mã lĩnh vực"]]} onApply={setFilters}/><Listing path={`/programs?${query({ ...scope, ...filters })}`}>{row => <><h3>{value(row.name)}</h3><p>Mã ngành: {value(row.code)}</p><SourceNote source={row.source}/><button className="text-button" onClick={() => select(row.id)}>Xem chương trình →</button></>}</Listing></>}
  </section>;
}
function ProgramDetail({ id, scope }: { id: string; scope: Scope }) {
  const [offering, setOffering] = useState('');
  return <><Resource<Row> path={`/programs/${id}`}>{row => <><h2>{value(row.name)}</h2><p>Mã ngành: {value(row.code)}</p><SourceNote source={row.source}/></>}</Resource>
    <Listing title="Chương trình theo cơ sở và khóa" path={`/program-offerings?${query({ ...scope, program_id: id })}`}>{row => <div className="row-between"><div><h3>{value(row.program_name)}</h3><p>Cơ sở {value(row.campus_code)} · Khóa {value(row.entry_year)}</p><SourceNote source={row.source}/></div><button className="secondary" aria-pressed={offering === row.id} onClick={() => setOffering(row.id)}>Xem khung đào tạo</button></div>}</Listing>
    {offering && <Curriculum key={offering} id={offering}/>}
  </>;
}
function Curriculum({ id }: { id: string }) {
  const [filters, setFilters] = useState<Record<string, string>>({});
  return <section className="detail-box"><h2>Khung chương trình</h2><Resource<Row> path={`/program-offerings/${id}/curriculum`}>{row => <><Fields row={row} fields={[["version_no", "Phiên bản"], ["duration_months", "Thời lượng (tháng)"], ["total_credits", "Tổng tín chỉ"], ["objectives", "Mục tiêu đào tạo"], ["learning_outcomes", "Chuẩn đầu ra"], ["career_paths", "Cơ hội nghề nghiệp"]]}/><SourceNote source={row.source}/></>}</Resource>
    <FilterForm fields={[["group", "Nhóm học phần"], ["term", "Học kỳ"]]} onApply={setFilters}/><Table title="Danh sách học phần" path={`/program-offerings/${id}/courses?${query(filters)}`} columns={[["code", "Mã học phần"], ["course_title", "Học phần"], ["credits", "Tín chỉ"], ["course_group", "Nhóm"], ["suggested_term", "Học kỳ dự kiến"]]}/>
  </section>;
}

export function Admissions({ scope }: { scope: Scope }) {
  const [selected, select] = useState('');
  const [cutoffFilters, setCutoffFilters] = useState<Record<string, string>>({});
  return <section className="panel feature-panel"><div className="panel-heading"><h2>Thông tin tuyển sinh</h2>{selected && <button className="secondary" onClick={() => select('')}>← Các đợt tuyển sinh</button>}</div>
    {selected ? <CycleDetail key={selected} id={selected}/> : <Listing title="Các đợt đã công bố" path={`/admission-cycles?${query(scope)}`}>{row => <div className="row-between"><div><h3>Đợt {value(row.round_code)} · {value(row.admission_year)}</h3><p>Cơ sở {value(row.campus_code)}</p><SourceNote source={row.source}/></div><button className="secondary" onClick={() => select(row.id)}>Xem chi tiết</button></div>}</Listing>}
    <section className="detail-box"><h2>Tra cứu điểm chuẩn</h2><p>Chọn cơ sở và năm ở bộ lọc phía trên. Điểm chuẩn các năm chỉ có giá trị tham khảo.</p><FilterForm fields={[["program_code", "Mã ngành"], ["method_code", "Mã phương thức"], ["round_code", "Mã đợt"], ["combination_code", "Tổ hợp"], ["scale_code", "Thang điểm"]]} onApply={setCutoffFilters}/><Table title="Điểm chuẩn đã công bố" path={`/admission-cutoffs?${query({ ...scope, ...cutoffFilters })}`} columns={[["year", "Năm"], ["campus_code", "Cơ sở"], ["program_code", "Mã ngành"], ["method_code", "Phương thức"], ["round_code", "Đợt"], ["combination_code", "Tổ hợp"], ["score", "Điểm chuẩn"], ["scale_code", "Thang điểm"]]}/></section>
  </section>;
}
function CycleDetail({ id }: { id: string }) {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [option, setOption] = useState('');
  return <><Resource<Row> path={`/admission-cycles/${id}`}>{row => <><h2>Đợt {value(row.round_code)} · {value(row.admission_year)}</h2><p>Cơ sở {value(row.campus_code)}</p><SourceNote source={row.source}/></>}</Resource>
    <Listing title="Phương thức và điều kiện xét tuyển" path={`/admission-cycles/${id}/methods?limit=20`}>{row => <><h3>{value(row.code)} · {value(row.name)}</h3><p className="prose">{value(row.eligibility_text)}</p><SourceNote source={row.source}/></>}</Listing>
    <FilterForm fields={[["q", "Tìm ngành mở tuyển"], ["method_code", "Mã phương thức"]]} onApply={next => { setFilters(next); setOption(''); }}/>
    <Listing title="Ngành mở tuyển và chỉ tiêu" path={`/admission-cycles/${id}/options?${query(filters)}`}>{row => <><h3>{value(row.program_code)} · {value(row.program_name)}</h3><Fields row={row} fields={[["method_code", "Phương thức"], ["total_quota", "Tổng chỉ tiêu ngành"], ["method_quota", "Chỉ tiêu phương thức"], ["additional_conditions", "Điều kiện bổ sung"]]}/><SourceNote source={row.source}/><button className="text-button" onClick={() => setOption(row.id)}>Xem tổ hợp môn →</button>{option === row.id && <Combinations key={option} id={option}/>}</>}</Listing>
    <Listing title="Hướng dẫn hồ sơ của đợt" path={`/guides?${query({ cycle_id: id })}`}>{row => <details><summary>{value(row.title)}</summary><GuideDetail id={row.id}/></details>}</Listing>
    <Events filters={{ cycle_id: id }}/>
  </>;
}
export function Combinations({ id }: { id: string }) {
  return <Listing title="Tổ hợp xét tuyển" path={`/admission-options/${id}/subject-combinations?limit=20`}>{row => <><h3>{value(row.code)}</h3><p>{Array.isArray(row.subjects) ? row.subjects.map(item => value(item.subject_code)).join(' · ') : ''}</p><p className="prose">{value(row.conditions_text)}</p></>}</Listing>;
}

export function Guides({ scope }: { scope: Scope }) {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [selected, select] = useState('');
  return <section className="panel feature-panel"><div className="panel-heading"><h2>Hướng dẫn và hỗ trợ sinh viên</h2>{selected && <button className="secondary" onClick={() => select('')}>← Danh sách hướng dẫn</button>}</div>
    {selected ? <GuideDetail key={selected} id={selected}/> : <><p>Tìm thủ tục, giấy tờ, ký túc xá, học vụ hoặc kênh liên hệ trong nội dung đã công bố.</p><FilterForm fields={[["q", "Từ khóa"], ["audience_code", "Mã đối tượng"], ["type", "Mã loại hướng dẫn"]]} onApply={setFilters}/><Listing path={`/guides?${query({ ...scope, ...filters })}`}>{row => <><h3>{value(row.title)}</h3><p>{value(row.campus_code)} · {value(row.admission_year)} · {value(row.audience_code)}</p><SourceNote source={row.source}/><button className="text-button" onClick={() => select(row.id)}>Xem hướng dẫn →</button></>}</Listing></>}
  </section>;
}
function GuideDetail({ id }: { id: string }) {
  return <><Resource<Row> path={`/guides/${id}`}>{row => <><h2>{value(row.title)}</h2><Fields row={row} fields={[["campus_code", "Cơ sở"], ["admission_year", "Năm"], ["audience_code", "Đối tượng"]]}/><p className="prose">{value(row.body)}</p><SourceNote source={row.source}/></>}</Resource>
    <p className="feature-hint">Đánh dấu checklist để tự theo dõi trong màn hình hiện tại.</p>
    <Listing title="Các bước, giấy tờ và liên hệ" path={`/guides/${id}/entries?limit=20`}>{row => <div className="checklist-item"><label><input type="checkbox"/><strong>{value(row.label)}{row.is_required === true && ' · Bắt buộc'}</strong></label><p className="prose">{value(row.instructions)}</p>{safeUrl(row.link_url) && <a href={safeUrl(row.link_url)} target="_blank" rel="noopener noreferrer">Mở hướng dẫn / kênh hỗ trợ ↗</a>}</div>}</Listing>
    <Events filters={{ guide_id: id }}/>
  </>;
}
export function Finances({ scope }: { scope: Scope }) {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [offering, setOffering] = useState('');
  const [selected, select] = useState('');
  return <section className="panel feature-panel"><div className="panel-heading"><h2>Học phí và học bổng</h2>{selected && <button className="secondary" onClick={() => select('')}>← Các chính sách</button>}</div>
    {selected ? <Resource<Row> key={selected} path={`/financial-policies/${selected}`}>{row => <><h2>{value(row.title)}</h2><Fields row={row} fields={[["campus_code", "Cơ sở"], ["entry_year", "Khóa"], ["policy_type", "Loại chính sách"], ["amount_vnd", "Số tiền (VND)"], ["charge_unit", "Đơn vị thu"], ["eligibility_text", "Điều kiện áp dụng"]]}/>{row.due_at != null && <p>Hạn nộp: {new Date(value(row.due_at)).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' })}</p>}<p className="prose">{value(row.body)}</p><SourceNote source={row.source}/></>}</Resource> : <><OfferingFilter scope={scope} selected={offering} onChange={setOffering}/><FilterForm fields={[["type", "Mã loại chính sách"]]} onApply={setFilters}/><Listing path={`/financial-policies?${query({ ...scope, ...filters, program_offering_id: offering })}`}>{row => <><h3>{value(row.title)}</h3><p>{value(row.campus_code)} · Khóa {value(row.entry_year)} · {value(row.policy_type)}</p><SourceNote source={row.source}/><button className="text-button" onClick={() => select(row.id)}>Xem chính sách →</button></>}</Listing></>}
  </section>;
}
function OfferingFilter({ scope, selected, onChange }: { scope: Scope; selected: string; onChange: (id: string) => void }) {
  const offerings = usePage<Row>(`/program-offerings?${query(scope)}`);
  return <><label className="feature-label">Chương trình và khóa học<select value={selected} onChange={event => onChange(event.target.value)}><option value="">Tất cả chương trình và chính sách chung</option>{offerings.data.map(row => <option key={row.id} value={row.id}>{value(row.program_name)} · {value(row.campus_code)} · {value(row.entry_year)}</option>)}</select></label><ListStatus state={offerings}/>{selected && <p className="feature-hint">Đang xem chính sách gắn riêng với chương trình. Chọn “Tất cả” để xem thêm chính sách chung.</p>}</>;
}

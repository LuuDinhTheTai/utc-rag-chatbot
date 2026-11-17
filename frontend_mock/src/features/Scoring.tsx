import { useState } from 'react';
import { query, usePage, type Source } from '../api';
import { ApiError, request } from '../client';
import { ListStatus, Notice, SourceNote, useAction, useResource, value, type Row, type Scope } from './shared';
import { scoreInputs, type ScoreInput } from './score-model';

interface Rule extends Row { method_code: string; output_scale_code: string }
interface Form { id: string; version_id: string; output_scale_code: string; source: Source; inputs: ScoreInput[] }
interface Calculation { total: string; scale_code: string; parts: { label: string; score: string }[]; comparison: { status: string; cutoff?: string; difference?: string; reason?: string } | null; source: Source; disclaimer: string }
export default function Scoring({ scope }: { scope: Scope }) {
  const rules = usePage<Rule>(`/score-rules?${query(scope)}`, !!scope.campus_code && !!scope.year);
  const [selected, select] = useState<Rule | null>(null);
  return <section className="panel feature-panel"><div className="panel-heading"><h2>Quy đổi điểm xét tuyển</h2>{selected && <button className="secondary" onClick={() => select(null)}>← Chọn phương thức</button>}</div>
    {!scope.campus_code || !scope.year ? <div className="feature-empty">Chọn một cơ sở và năm tuyển sinh, rồi nhấn Áp dụng để tra cứu quy tắc.</div> : selected ? <RuleForm key={selected.id} rule={selected} scope={scope}/> : <><p>Chọn phương thức có quy tắc đã được công bố cho phạm vi đang xem.</p><div className="feature-cards">{rules.data.map(rule => <button className="feature-card" key={rule.id} onClick={() => select(rule)}><strong>{rule.method_code}</strong><span>Thang điểm: {rule.output_scale_code}</span><span>Mở biểu mẫu →</span></button>)}</div><ListStatus state={rules}/></>}
  </section>;
}
function RuleForm({ rule, scope }: { rule: Rule; scope: Scope }) {
  const form = useResource<Form>(`/score-rules/${rule.id}/form`);
  return <><Notice error={form.error} retry={form.reload}/>{form.loading && <p className="loading" role="status">Đang tải biểu mẫu đã duyệt…</p>}{form.data && <Calculator key={form.data.version_id} form={form.data} rule={rule} scope={scope} reload={form.reload}/>}</>;
}
function Calculator({ form, rule, scope, reload }: { form: Form; rule: Rule; scope: Scope; reload: () => void }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [comparison, setComparison] = useState(false);
  const [target, setTarget] = useState({ option: '', combination: '' });
  const [result, setResult] = useState<Calculation | null>(null);
  const [stale, setStale] = useState(false);
  const action = useAction();
  async function calculate(signal: AbortSignal) {
    setResult(null);
    if (!form.inputs.length) throw new Error('Biểu mẫu chưa có trường nhập điểm.');
    if (comparison && !target.option) throw new Error('Vui lòng chọn ngành và phương thức để đối chiếu.');
    const body = { rule_version_id: form.version_id, inputs: scoreInputs(form.inputs, values), compare_previous_year: comparison, ...(comparison ? { admission_option_id: target.option, ...(target.combination ? { combination_code: target.combination } : {}) } : {}) };
    try {
      const response = await request<Calculation>('/score-calculations', { method: 'POST', body, signal });
      if (!signal.aborted) setResult(response);
    } catch (error) { if (error instanceof ApiError && error.code === 'RULE_VERSION_CHANGED') { setStale(true); throw new Error('Quy tắc đã có phiên bản mới. Hãy tải lại biểu mẫu và nhập lại điểm.'); } throw error; }
  }
  return <><div className="scope-banner"><strong>Phương thức {rule.method_code}</strong><span>{scope.campus_code} · {scope.year} · Thang {form.output_scale_code}</span></div><SourceNote source={form.source}/>
    <form onSubmit={event => { event.preventDefault(); void action.run(calculate); }}><fieldset className="feature-fieldset" disabled={action.busy || stale}><div className="input-grid">{form.inputs.map(input => <label key={input.id}>{input.label}{input.is_required ? ' *' : ''}{input.choices.length ? <select required={input.is_required} value={values[input.input_code] ?? ''} onChange={event => { setValues({ ...values, [input.input_code]: event.target.value }); setResult(null); }}><option value="">Chọn giá trị</option>{input.choices.map(choice => <option key={choice.match_code} value={choice.match_code}>{choice.match_code}</option>)}</select> : <input type="number" step="any" min={input.min_value ?? undefined} max={input.max_value ?? undefined} required={input.is_required} value={values[input.input_code] ?? ''} onChange={event => { setValues({ ...values, [input.input_code]: event.target.value }); setResult(null); }}/>}<small>{input.min_value != null && `Tối thiểu ${input.min_value}`}{input.max_value != null && ` · Tối đa ${input.max_value}`}</small></label>)}</div>
    <label className="checkbox-label"><input type="checkbox" checked={comparison} onChange={event => { setComparison(event.target.checked); setTarget({ option: '', combination: '' }); setResult(null); }}/>So sánh với điểm chuẩn năm liền trước</label>
    {comparison && <CompareTarget scope={scope} method={rule.method_code} onChange={(option, combination) => { setTarget({ option, combination }); setResult(null); }}/>}<button className="primary" disabled={!form.inputs.length}>{action.busy ? 'Đang tính…' : 'Tính điểm tham khảo'}</button></fieldset></form>
    <Notice error={action.error}/>{stale && <button className="secondary" onClick={reload}>Tải biểu mẫu mới</button>}
    {result && <section className="score-result" aria-live="polite"><span>KẾT QUẢ QUY ĐỔI</span><strong className="score-total">{result.total}<small>Thang {result.scale_code}</small></strong><dl className="detail-fields">{result.parts.map((part, i) => <div key={i}><dt>{part.label}</dt><dd>{part.score}</dd></div>)}</dl>{result.comparison && (result.comparison.status === 'COMPARED' ? <p>Điểm chuẩn năm trước: <strong>{result.comparison.cutoff}</strong> · Chênh lệch: <strong>{result.comparison.difference}</strong></p> : <p>Không có một mốc điểm chuẩn tương thích duy nhất để so sánh.</p>)}<p>{result.disclaimer}</p><SourceNote source={result.source}/></section>}
  </>;
}
function CompareTarget({ scope, method, onChange }: { scope: Scope; method: string; onChange: (option: string, combination: string) => void }) {
  const cycles = usePage<Row>(`/admission-cycles?${query(scope)}`);
  const [cycle, setCycle] = useState('');
  return <section className="detail-box"><label className="feature-label">Đợt tuyển sinh<select value={cycle} onChange={event => { setCycle(event.target.value); onChange('', ''); }}><option value="">Chọn đợt</option>{cycles.data.map(row => <option key={row.id} value={row.id}>{value(row.round_code)} · {value(row.admission_year)}</option>)}</select></label><ListStatus state={cycles}/>{cycle && <CompareOptions key={cycle} cycle={cycle} method={method} onChange={onChange}/>}</section>;
}
function CompareOptions({ cycle, method, onChange }: { cycle: string; method: string; onChange: (option: string, combination: string) => void }) {
  const options = usePage<Row>(`/admission-cycles/${cycle}/options?${query({ method_code: method })}`);
  const [option, setOption] = useState('');
  return <><label className="feature-label">Ngành mở tuyển<select value={option} onChange={event => { setOption(event.target.value); onChange(event.target.value, ''); }}><option value="">Chọn ngành</option>{options.data.map(row => <option key={row.id} value={row.id}>{value(row.program_code)} · {value(row.program_name)}</option>)}</select></label><ListStatus state={options}/>{option && <CompareCombination key={option} option={option} onChange={onChange}/>}</>;
}
function CompareCombination({ option, onChange }: { option: string; onChange: (option: string, combination: string) => void }) {
  const combinations = usePage<Row>(`/admission-options/${option}/subject-combinations?limit=20`);
  const [code, setCode] = useState('');
  return <><label className="feature-label">Tổ hợp đối chiếu<select value={code} onChange={event => { setCode(event.target.value); onChange(option, event.target.value); }}><option value="">Không áp dụng tổ hợp</option>{combinations.data.map(row => <option key={row.id} value={value(row.code)}>{value(row.code)}</option>)}</select></label><ListStatus state={combinations}/></>;
}

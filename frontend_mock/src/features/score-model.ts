export interface ScoreInput { id: string; input_kind: string; input_code: string; label: string; is_required: boolean; min_value: string | null; max_value: string | null; choices: { match_code: string }[] }
type ScoreValue = { input_code: string; numeric_value?: string; choice_code?: string };
export function scoreInputs(definitions: ScoreInput[], values: Record<string, string>) {
  return definitions.flatMap<ScoreValue>(input => {
    const v = values[input.input_code]?.trim() ?? '';
    if (!v) {
      if (input.is_required) throw new Error(`Vui lòng nhập ${input.label}.`);
      return [];
    }
    if (input.choices.length) {
      if (!input.choices.some(choice => choice.match_code === v)) throw new Error(`Lựa chọn không hợp lệ: ${input.label}.`);
      return [{ input_code: input.input_code, choice_code: v }];
    }
    if (!/^-?\d+(\.\d+)?$/.test(v)) throw new Error(`Điểm ${input.label} phải là số thập phân.`);
    if ((input.min_value !== null && Number(v) < Number(input.min_value)) || (input.max_value !== null && Number(v) > Number(input.max_value))) throw new Error(`Điểm ${input.label} nằm ngoài giới hạn cho phép.`);
    return [{ input_code: input.input_code, numeric_value: v }];
  });
}

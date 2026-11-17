import { request } from '../client.ts';
export interface Attempt { id: string; status: string; questionnaire_version_id: string; saved_at?: string | null }

// Read state first: a previous completion may have succeeded despite a lost response.
export async function saveAttempt(id: string, answers: Record<string, string>, finish: boolean, full: boolean, signal: AbortSignal) {
  const current = await request<Attempt>(`/personality-attempts/${id}`, { signal });
  signal.throwIfAborted();
  if (current.status === 'COMPLETED') return 'COMPLETED';
  if (finish && !full) throw new Error('Vui lòng tải và trả lời đầy đủ tất cả câu hỏi.');
  await request(`/personality-attempts/${id}/answers`, { method: 'PUT', body: { answers: Object.entries(answers).map(([question_id, option_id]) => ({ question_id, option_id })) }, signal });
  if (finish) {
    await request(`/personality-attempts/${id}/result`, { method: 'POST', signal });
    return 'COMPLETED';
  }
  return 'IN_PROGRESS';
}

export class ApiError extends Error {
  status: number;
  code: string;
  details: Record<string, unknown>;
  constructor(status: number, code: string, message: string, details: Record<string, unknown> = {}) {
    super(message); this.status = status; this.code = code; this.details = details;
  }
}
export const errorText = (error: unknown) => error instanceof Error ? error.message : 'Không thể hoàn tất thao tác.';
let csrf: Promise<{ token: string; header_name: string }> | null = null;
export function resetCsrf() { csrf = null; }
export function ensureCsrf() {
  if (!csrf) csrf = request<{ token: string; header_name: string }>('/auth/csrf').catch(error => { csrf = null; throw error; });
  return csrf;
}
export interface RequestOptions { method?: string; body?: unknown; signal?: AbortSignal; responseType?: 'pdf'; ifMatch?: string; onResponse?: (headers: Headers) => void }
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = { Accept: options.responseType === 'pdf' ? 'application/pdf' : 'application/json' };
  const multipart = options.body instanceof FormData;
  if (options.body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
  if (options.ifMatch) headers['If-Match'] = options.ifMatch;
  if (method !== 'GET') { const token = await ensureCsrf(); headers[token.header_name] = token.token; }
  options.signal?.throwIfAborted();
  let response: Response;
  try { response = await fetch(`/api/v1${path}`, { method, headers, credentials: 'same-origin', signal: options.signal, body: multipart ? options.body as FormData : options.body === undefined ? undefined : JSON.stringify(options.body) }); }
  catch (error) { if (options.signal?.aborted) throw error; throw new Error('Không thể kết nối máy chủ. Vui lòng kiểm tra kết nối và thử lại.'); }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    options.signal?.throwIfAborted();
    if (problem.code === 'CSRF_INVALID') resetCsrf();
    if (response.status === 401 && !path.startsWith('/auth/') && path !== '/me') window.dispatchEvent(new Event('utc-session-expired'));
    const messages: Record<number, string> = { 400: 'Yêu cầu không hợp lệ. Vui lòng tải lại dữ liệu.', 401: 'Phiên đã hết hạn hoặc thông tin đăng nhập không đúng.', 403: 'Bạn không có quyền thực hiện thao tác này.', 404: 'Nội dung không còn khả dụng, chưa được công bố hoặc bạn không có quyền xem.', 409: 'Dữ liệu bị xung đột. Vui lòng kiểm tra và thử lại.', 422: 'Thông tin nhập chưa hợp lệ.', 429: 'Quá nhiều yêu cầu. Vui lòng chờ một phút rồi thử lại.', 503: 'Dịch vụ tạm thời không khả dụng. Vui lòng thử lại sau.', 504: 'Dịch vụ trả lời quá lâu. Bạn có thể thử gửi lại.' };
    Object.assign(messages, { 412: 'Nội dung đã thay đổi. Đọc lại phiên bản mới trước khi tiếp tục.', 428: 'Thiếu thông tin phiên bản. Hãy tải lại tài nguyên.', 413: 'Tệp vượt giới hạn 20 MiB.', 415: 'Tệp không phải PDF hợp lệ hoặc đang được mã hóa.' });
    throw new ApiError(response.status, problem.code ?? '', problem.code === 'CSRF_INVALID' ? 'Phiên bảo mật đã thay đổi. Vui lòng thử lại thao tác.' : messages[response.status] ?? `Không hoàn tất yêu cầu (HTTP ${response.status}).`, { ...problem, location: response.headers.get('Location') });
  }
  options.onResponse?.(response.headers);
  if (response.status === 204) return undefined as T;
  if (options.responseType === 'pdf') {
    if (!response.headers.get('content-type')?.includes('application/pdf')) throw new Error('Máy chủ không trả về tệp PDF hợp lệ.');
    return await response.blob() as T;
  }
  if (!response.headers.get('content-type')?.includes('json')) throw new Error('Máy chủ trả về dữ liệu không hợp lệ.');
  return response.json();
}

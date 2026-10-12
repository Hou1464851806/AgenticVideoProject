export class ApiFailure extends Error {
  constructor(message: string, readonly retryable: boolean) { super(message); }
}

/** Browser calls only the same-origin business API. */
export async function request<T>(path: string, method = 'GET', data?: unknown, extraHeaders: Record<string,string> = {}): Promise<T> {
  const headers: Record<string, string> = { ...extraHeaders };
  if (method !== 'GET') {
    const csrf = await request<{ csrfToken: string }>('/auth/csrf');
    headers['X-XSRF-TOKEN'] = csrf.csrfToken;
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(`/api/v1${path}`, {
    method, headers, credentials: 'same-origin', body: data === undefined ? undefined : JSON.stringify(data),
  });
  const result = await response.json();
  if(response.status===401)window.dispatchEvent(new Event('auth-required'));
  if (!response.ok) throw new ApiFailure(result.error?.message ?? '请求失败，请稍后重试', result.error?.retryable === true);
  return result.data;
}

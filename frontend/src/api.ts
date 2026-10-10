/** Browser calls only the same-origin business API. */
export async function request<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== 'GET') {
    const csrf = await request<{ csrfToken: string }>('/auth/csrf');
    headers['X-XSRF-TOKEN'] = csrf.csrfToken;
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(`/api/v1${path}`, {
    method, headers, credentials: 'same-origin', body: data === undefined ? undefined : JSON.stringify(data),
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error?.message ?? '请求失败，请稍后重试');
  return result.data;
}

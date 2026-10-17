import { test, expect } from '@playwright/test';

for (const [status, code, message] of [
  [429, 'RATE_LIMITED', '上传过于频繁，请约 42 秒后重试'],
  [503, 'UPLOAD_ADMISSION_UNAVAILABLE', '上传准入服务暂时不可用，请稍后重试'],
] as const) {
  test(`${code} stops rapid retry and permits manual retry with the same key`, async ({ page }) => {
    let creates = 0;
    const keys: string[] = [];
    await page.route('**/api/v1/**', async route => {
      const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
      let data: unknown = { items: [], nextCursor: null };
      if (path === '/auth/me') data = { user: { id: 'admission-user', username: '上传测试' } };
      else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
      else if (path === '/projects/admission') data = { id: 'admission', name: '上传准入' };
      else if (path === '/projects') data = { items: [{ id: 'admission', name: '上传准入' }], nextCursor: null };
      else if (path.endsWith('/snapshot')) data = { cursor: 'admission:0' };
      else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
      else if (path === '/projects/admission/uploads' && route.request().method() === 'POST') {
        creates++;
        keys.push(route.request().postDataJSON().idempotencyKey);
        if (creates === 1) {
          await route.fulfill({ status, headers: status === 429 ? { 'Retry-After': '42' } : {}, json: {
            requestId: 'admission-error', error: { code, message, retryable: true, details: status === 429 ? { retryAfterMs: 41234 } : {} },
          } });
          return;
        }
        data = { uploadId: 'accepted', filename: 'admission.mp4', status: 'UPLOADING', declaredBytes: 1,
          reservedBytes: 1, reservedSlots: 1, partSizeBytes: 8388608, expectedParts: 1, writerToken: 'writer' };
      } else if (path === '/uploads/accepted/part-urls') {
        data = { parts: [{ url: new URL('/test-upload-part', route.request().url()).href, headers: {}, sizeBytes: 1 }] };
      } else if (path === '/uploads/accepted/complete') {
        data = { uploadId: 'accepted', status: 'COMPLETED', declaredBytes: 1 };
      }
      await route.fulfill({ json: { data } });
    });
    let parts = 0;
    await page.route('**/test-upload-part', async route => {
      parts++;
      await route.fulfill({ status: parts === 1 ? 503 : 200, headers: { ETag: 'verified' } });
    });
    await page.route('**/api/v1/uploads/accepted/resume', route => route.fulfill({ json: { data: {
      uploadId: 'accepted', status: 'UPLOADING', declaredBytes: 1, partSizeBytes: 8388608,
      expectedParts: 1, confirmedParts: [], writerToken: 'writer',
    } } }));
    await page.goto('/?project=admission');
    await page.getByLabel('选择视频', { exact: true }).setInputFiles({ name: 'admission.mp4', mimeType: 'video/mp4', buffer: Buffer.alloc(1) });
    const row = page.getByRole('article', { name: '上传：admission.mp4', exact: true });
    await expect(row).toContainText(message);
    await expect(row).toContainText('上传失败');
    await page.waitForTimeout(1100); // Both former 250/500ms automatic retry slots have elapsed.
    expect(creates).toBe(1);
    expect(parts).toBe(0);
    await row.getByRole('button', { name: 'admission.mp4 继续上传', exact: true }).click();
    await expect(row).toContainText('传输完成');
    expect(creates).toBe(2);
    expect(keys[1]).toBe(keys[0]);
    expect(parts).toBe(2); // Existing part retry and resume still work.
  });
}

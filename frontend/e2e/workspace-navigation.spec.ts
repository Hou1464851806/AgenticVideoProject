import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';

// Current HTTP boundary only: no uploads, model calls or service restarts.
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [] };
    if (path === '/auth/me') data = { user: { id: 'navigator', username: '导航测试' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects') data = { items: [{ id: 'a', name: '项目甲' }, { id: 'b', name: '项目乙' }], nextCursor: null };
    else if (path === '/projects/a' || path === '/projects/b') data = { id: path.split('/').at(-1), name: path.endsWith('a') ? '项目甲' : '项目乙' };
    else if (path === '/projects/denied') { await route.fulfill({ status: 403, json: { error: { message: '项目不存在或无权访问' } } }); return; }
    else if (path === '/projects/a/assets') data = { items: [{ id: 'asset-a', filename: '素材甲.mp4', validationStatus: 'VALID' }] };
    else if (path === '/projects/a/conversations') data = { items: [{ id: 'chat-a', title: '聊天甲', assetIds: ['asset-a'] }] };
    else if (path === '/projects/b/conversations') data = { items: [{ id: 'chat-b', title: '聊天乙', assetIds: [] }] };
    else if (path.match(/^\/conversations\/chat-[ab]\/snapshot$/)) data = { cursor: 'project:0', assetIds: path.includes('chat-a') ? ['asset-a'] : [], items: [], latestTask: null, agent: { currentProduction: null, currentDraft: null, canAcceptDraft: false, canPreviewDraft: false, pendingQuestions: [] }, previews: { items: [] } };
    else if (path.endsWith('/usage')) data = {
      storage: Object.fromEntries(['ORIGINAL', 'DERIVED', 'PREVIEW', 'EXPORT'].map(key => [key, { storedBytes: 1024, reservedBytes: 512, pendingCleanupBytes: 128 }])),
      models: { callCount: 1, reusedBatchCount: 0, uncertainCallCount: 0, unpricedCallCount: 1, estimatedCostCny: 0, tokens: 12, audioSeconds: 0, items: [], nextOffset: -1 },
      thresholds: { costCny: null, calls: null, tokens: null, audioSeconds: null }, warnings: [],
    };
    else if (path.endsWith('/messages')) data = { view: { planId: null, revisionId: null } };
    else if (path.endsWith('/snapshot')) data = { cursor: 'project:0' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    else if (path === '/auth/logout') data = {};
    await route.fulfill({ json: { data } });
  });
});

test('view switches preserve chat input and references while hidden panels cannot receive focus', async ({ page }) => {
  await page.goto('/?project=a');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByLabel('对话输入', { exact: true }).fill('尚未发送的剪辑思路');
  await page.getByLabel('素材甲.mp4', { exact: true }).check();
  await page.getByRole('tab', { name: '素材', exact: true }).click();
  await expect(page.getByLabel('对话输入', { exact: true })).toBeHidden();
  const hiddenInput = page.getByLabel('对话输入', { exact: true });
  await hiddenInput.evaluate((input: HTMLTextAreaElement) => input.focus());
  await expect(hiddenInput).not.toBeFocused();
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await expect(hiddenInput).toHaveValue('尚未发送的剪辑思路');
  await expect(page.getByLabel('素材甲.mp4', { exact: true })).toBeChecked();
  await page.reload();
  await expect(page.getByRole('tab', { name: '制作', exact: true })).toHaveAttribute('aria-selected', 'true');
});

test('empty projects and invalid preferences fall back to assets and forbidden projects never restore', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('workspace-view:navigator:a', 'retired-panel');
    localStorage.setItem('workspace-view:navigator:b', 'results');
    localStorage.setItem('workspace-view:navigator:denied', 'conversation');
  });
  await page.goto('/?project=a');
  await expect(page.getByRole('button', { name: '播放 素材甲.mp4', exact: true })).toBeVisible();
  await expect(page.getByRole('tab', { name: '素材', exact: true })).toHaveAttribute('aria-selected', 'true');
  await page.goto('/?project=b');
  await expect(page.getByText('暂无素材。', { exact: true })).toBeVisible();
  await expect(page.getByRole('tab', { name: '素材', exact: true })).toHaveAttribute('aria-selected', 'true');
  await page.goto('/?project=denied');
  await expect(page.getByRole('alert')).toContainText('项目不存在或无权访问');
  await expect(page.getByRole('tab')).toHaveCount(0);
});

test('project navigation and browser history isolate old input, references and playback', async ({ page }) => {
  await page.route('**/api/v1/assets/asset-a/playback-access', route => route.fulfill({ json: { data: { assetId: 'asset-a', filename: '素材甲.mp4', url: '/source-a.mp4', durationMs: 2000, expiresAt: '2099-01-01' } } }));
  await page.route('**/api/v1/assets/asset-a', route => route.fulfill({ json: { data: { mediaInfo: { streams: [] }, timeMapping: { hasAudio: false }, analysis: { stages: {}, errors: {} } } } }));
  await page.route('**/api/v1/assets/asset-a/evidence', route => route.fulfill({ json: { data: { observations: [], units: [], summary: {} } } }));
  await page.route('**/source-a.mp4', route => route.fulfill({ contentType: 'video/mp4', path: 'e2e/fixtures/short-video.mp4' }));
  await page.goto('/');
  await page.getByRole('button', { name: '项目甲', exact: true }).click();
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByLabel('对话输入', { exact: true }).fill('甲的未发送输入');
  await page.getByLabel('素材甲.mp4', { exact: true }).check();
  await page.getByRole('tab', { name: '素材', exact: true }).click();
  await page.getByRole('button', { name: '播放 素材甲.mp4', exact: true }).click();
  await expect(page.locator('video')).toHaveAttribute('src', '/source-a.mp4');
  await page.getByRole('button', { name: '← 项目列表', exact: true }).click();
  await page.getByRole('button', { name: '项目乙', exact: true }).click();
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await expect(page.getByLabel('对话输入', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('素材甲.mp4', { exact: true })).toHaveCount(0);
  await expect(page.locator('video')).toHaveCount(0);
  await page.goBack();
  await expect(page.getByRole('heading', { name: '我的项目', exact: true })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: '项目甲', exact: true })).toBeVisible();
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await expect(page.getByLabel('对话输入', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('素材甲.mp4', { exact: true })).not.toBeChecked();
  await expect(page.locator('video')).toHaveCount(0);
  await page.goForward();
  await expect(page.getByRole('heading', { name: '我的项目', exact: true })).toBeVisible();
  await page.goForward();
  await expect(page.getByRole('heading', { name: '项目乙', exact: true })).toBeVisible();
});

test('late project responses cannot reopen a project after browser back', async ({ page }) => {
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.goto('/?project=a');
  await expect(page.getByRole('heading', { name: '项目甲', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '← 项目列表', exact: true }).click();
  await page.route('**/api/v1/projects/b', async route => {
    await held;
    await route.fulfill({ json: { data: { id: 'b', name: '项目乙' } } });
  });
  const pending = page.waitForRequest('**/api/v1/projects/b');
  await page.getByRole('button', { name: '项目乙', exact: true }).click();
  await pending;
  await page.goBack();
  await expect(page.getByRole('heading', { name: '项目甲', exact: true })).toBeVisible();
  const response = page.waitForResponse('**/api/v1/projects/b');
  release();
  await response;
  await expect(page.getByRole('heading', { name: '项目甲', exact: true })).toBeVisible();
  await expect(page).toHaveURL(/project=a$/);
});

for (const viewport of [{ width: 1366, height: 768 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
  test(`main views remain reachable by keyboard without overflow at ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.route('**/api/v1/projects/a', route => route.fulfill({ json: { data: { id: 'a', name: '长项目名称用于验证换行与导航布局'.repeat(7) } } }));
    await page.goto('/?project=a');
    const assets = page.getByRole('tab', { name: '素材', exact: true });
    await assets.focus();
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('tab', { name: '制作', exact: true })).toBeFocused();
    await expect(page.getByRole('heading', { name: '制作', exact: true })).toBeVisible();
    await page.keyboard.press('End');
    await expect(page.getByRole('tab', { name: '作品', exact: true })).toBeFocused();
    await expect(page.getByRole('heading', { name: '作品', exact: true })).toBeVisible();
    await page.keyboard.press('Home');
    await expect(assets).toBeFocused();
    for (const view of ['素材', '制作', '作品']) {
      await page.getByRole('tab', { name: view, exact: true }).click();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await expect(page.getByRole('tabpanel')).toHaveCount(1);
    }
    await page.getByRole('button', { name: '用量', exact: true }).click();
    const usage = page.getByRole('complementary', { name: '项目用量', exact: true });
    await expect(usage).toContainText('缺价格的调用 1 次');
    await expect(usage.getByRole('button', { name: '保存预警阈值', exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    mkdirSync('../docs/verification/frontend-redesign-step2', { recursive: true });
    await page.screenshot({ path: `../docs/verification/frontend-redesign-step2/controlled-usage-${viewport.width}x${viewport.height}.png`, fullPage: true });
    await page.keyboard.press('Escape');
    await expect(usage).toHaveCount(0);
    await expect(page.getByRole('button', { name: '用量', exact: true })).toBeFocused();
  });
}

test('unavailable browser storage leaves navigation usable with an explicit notice', async ({ page }) => {
  await page.addInitScript(() => {
    Storage.prototype.getItem = function(key) { if (key.startsWith('workspace-view:')) throw new DOMException('blocked', 'SecurityError'); return null; };
    Storage.prototype.setItem = function(key) { if (key.startsWith('workspace-view:')) throw new DOMException('blocked', 'SecurityError'); };
  });
  await page.goto('/?project=a');
  await expect(page.getByRole('status').filter({ hasText: '浏览器无法读取视图偏好' })).toBeVisible();
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await expect(page.getByLabel('对话输入', { exact: true })).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: '浏览器无法保存视图偏好' })).toBeVisible();
});

test('account logout and session expiry return to sign in without old project controls', async ({ page }) => {
  await page.goto('/?project=a');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByLabel('对话输入', { exact: true }).fill('退出前的输入');
  await page.getByText('账户', { exact: true }).click();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录工作区', exact: true })).toBeVisible();
  await expect(page.getByRole('tab')).toHaveCount(0);
  await page.reload();
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await expect(page.getByLabel('对话输入', { exact: true })).toHaveValue('');
  await page.route('**/api/v1/projects/a/usage', route => route.fulfill({ status: 401, json: { error: { message: '登录已到期' } } }));
  await page.getByRole('button', { name: '用量', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录工作区', exact: true })).toBeVisible();
  await expect(page.getByRole('status')).toHaveText('登录已到期，请重新登录');
  await expect(page.getByRole('tab')).toHaveCount(0);
});

test('view navigation preserves an in-flight upload and its selected file for resume', async ({ page }) => {
  let created = 0;
  let release!: () => void;
  const held = new Promise<void>(resolve => { release = resolve; });
  const upload = { uploadId: 'upload-a', filename: '待上传.mp4', status: 'UPLOADING', writerToken: 'writer', declaredBytes: 4, confirmedBytes: 0, partSizeBytes: 4, expectedParts: 1, expiresAt: '2099-01-01' };
  await page.route('**/api/v1/projects/a/uploads', async route => {
    if (route.request().method() === 'POST') { created++; await route.fulfill({ json: { data: upload } }); }
    else await route.fulfill({ json: { data: { items: created ? [upload] : [], nextCursor: null } } });
  });
  await page.route('**/api/v1/uploads/upload-a/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let data: unknown = {};
    if (path.endsWith('/part-urls')) data = { parts: [{ url: '/upload-part', headers: {}, sizeBytes: 4 }] };
    else if (path.endsWith('/pause')) data = { ...upload, status: 'PAUSED' };
    else if (path.endsWith('/resume')) data = { ...upload, confirmedBytes: 4, confirmedParts: [{ partNumber: 1, sizeBytes: 4 }] };
    else if (path.endsWith('/complete')) { upload.status = 'COMPLETED'; data = upload; }
    await route.fulfill({ json: { data } });
  });
  await page.route('**/upload-part', async route => { await held; await route.fulfill({ headers: { ETag: 'etag' }, body: '' }); });
  await page.goto('/?project=a');
  const transferring = page.waitForRequest('**/upload-part');
  await page.getByLabel('选择视频', { exact: true }).setInputFiles({ name: '待上传.mp4', mimeType: 'video/mp4', buffer: Buffer.from('test') });
  await transferring;
  await page.getByRole('button', { name: '待上传.mp4 暂停上传', exact: true }).click();
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByRole('tab', { name: '素材', exact: true }).click();
  await expect(page.getByText('正在暂停', { exact: true })).toBeVisible();
  release();
  await expect(page.getByText('已暂停', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '待上传.mp4 继续上传', exact: true }).click();
  await expect(page.getByText(/传输完成，待媒体验证/)).toBeVisible();
  expect(created).toBe(1);
});


test('view preference is scoped to the signed-in account', async ({ page }) => {
  await page.goto('/?project=a');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.reload();
  await expect(page.getByRole('tab', { name: '作品', exact: true })).toHaveAttribute('aria-selected', 'true');
  await page.route('**/api/v1/auth/me', route => route.fulfill({ json: { data: { user: { id: 'other-user', username: '另一个账号' } } } }));
  await page.reload();
  await expect(page.getByRole('button', { name: '播放 素材甲.mp4', exact: true })).toBeVisible();
  await expect(page.getByRole('tab', { name: '素材', exact: true })).toHaveAttribute('aria-selected', 'true');
});

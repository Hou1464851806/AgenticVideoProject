import { test, expect, type Page } from '@playwright/test';

type Observation = { id: string; startMs: number; endMs: number; text: string };
async function setup(page: Page, count: number, withUpload = false) {
  let failed = false;
  let cancelled = false;
  let inspectionCalls = 0;
  const upload = { uploadId: 'upload-1', filename: '续传.mp4', status: 'PAUSED', declaredBytes: 100, confirmedBytes: 40, partSizeBytes: 100, expectedParts: 1, reservedBytes: 100, reservedSlots: 1, expiresAt: '2099-01-01T00:00:00Z' };
  const observations: Observation[] = Array.from({ length: count }, (_, i) => ({ id: `e${i}`, startMs: i === 0 ? 400 : 500 + i, endMs: 800, text: i === 0 ? '很长的语音内容'.repeat(80) : `证据 ${i} ${i === 199 ? '特殊关键词' : ''}` }));
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [] };
    if (path === '/auth/me') data = { user: { id: 'u', username: '用户' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'x' };
    else if (path === '/projects') data = { items: [{ id: 'p', name: '项目', rowVersion: 0 }], nextCursor: null };
    else if (path === '/projects/p') data = { id: 'p', name: '项目', rowVersion: 0 };
    else if (path === '/projects/p/uploads') data = { items: withUpload ? [{ ...upload, status: cancelled ? 'CANCELLED' : upload.status }] : [], nextCursor: null };
    else if (path === '/uploads/upload-1/cancel') { cancelled = true; data = { ...upload, status: 'CANCELLED' }; }
    else if (path === '/projects/p/assets') data = { items: [{ id: 'a', filename: '非常长的素材名称'.repeat(8) + '.mp4', validationStatus: 'VALID' }, { id: 'b', filename: '第二个素材.mp4', validationStatus: 'VALID' }] };
    else if (path === '/assets/b/playback-access') data = { assetId: 'b', filename: '第二个素材.mp4', url: '/e2e/fixtures/short-video.mp4', durationMs: 1000, expiresAt: '2099-01-01' };
    else if (path === '/assets/b') data = { mediaInfo: { streams: [] }, timeMapping: { hasAudio: true } };
    else if (path === '/assets/b/evidence') data = { observations: [{ id: 'other', startMs: 200, endMs: 600, text: '第二份素材内容' }], units: [], summary: {} };
    else if (path === '/assets/a/playback-access') data = { assetId: 'a', filename: '非常长的素材名称'.repeat(8) + '.mp4', url: '/e2e/fixtures/short-video.mp4', durationMs: 1000, expiresAt: '2099-01-01' };
    else if (path === '/projects/p/search') { const kind = route.request().postDataJSON().sourceKinds[0]; data = { hits: [{ assetId: 'a', filename: '素材.mp4', text: '命中内容', startMs: 400, endMs: 800, observedAtMs: kind === 'SPEECH' ? undefined : 400, sourceKind: kind, evidenceIds: ['e0'] }], semanticAvailable: true, coverage: { assets: [] } }; }
    else if (path === '/evidence/e0/context') data = { observations: [{ id: 'near', text: '邻近内容', startMs: 300, endMs: 500 }] };
    else if (path === '/evidence/e0/frame-access') data = { url: '/e2e/fixtures/short-video.mp4' };
    else if (path === '/projects/p/visual-inspections') { inspectionCalls++; data = { id: 'i', status: 'NO_NEW_EVIDENCE', budget: { maxFrames: 1, maxCalls: 1, maxSteps: 1, maxSeconds: 1, maxInputBytes: 1 }, usage: { frames: 1, calls: 1, steps: 1, inputBytes: 1 }, coverage: { startMs: 0, endMs: 800 }, observations: [], coverageWarning: '覆盖有限', expansionProposal: null }; }
    else if (path === '/assets/a') data = { mediaInfo: { streams: [] }, timeMapping: { hasAudio: true }, analysis: { stages: { speech: 'RUNNING' }, errors: {} } };
    else if (path === '/assets/a/evidence') { if (failed) { await route.fulfill({ status: 500, json: { error: { message: '证据读取失败' } } }); return; } data = { observations, units: [], summary: { text: '摘要用于导航' } }; }
    else if (path.endsWith('/snapshot')) data = { cursor: '1' };
    else if (path.endsWith('/events')) { await route.fulfill({ status: 200, contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    await route.fulfill({ json: { data } });
  });
  await page.goto('/?project=p');
  await page.getByRole('button', { name: '打开素材库', exact: true }).click();
  await page.getByRole('button', { name: /^播放 非常长/ }).click();
  await expect(page.getByRole('dialog', { name: '原片回看' })).toBeVisible();
  return { observations, inspections: () => inspectionCalls, fail: () => { failed = true; }, recover: () => { failed = false; } };
}

for (const count of [0, 1, 20, 21, 200]) {
  test(`evidence count ${count}`, async ({ page }) => {
    await setup(page, count);
    const region = page.getByRole('region', { name: '语音证据' });
    await expect(region).toContainText(`共 ${count} 条`);
    await expect(region.locator('article')).toHaveCount(Math.min(count, 20));
    if (!count) await expect(region).toContainText('暂无语音证据');
    if (count > 20) {
      await region.getByRole('button', { name: '下一页', exact: true }).click();
      await expect(region.locator('article')).toHaveCount(Math.min(count - 20, 20));
      if (count === 200) { for (let current = 3; current <= 10; current++) { await region.getByRole('button', { name: '下一页' }).click(); await expect(region).toContainText(`第 ${current} / 10 页`); } await expect(region.getByRole('button', { name: '下一页' })).toBeDisabled(); }
    } else await expect(region.getByRole('navigation', { name: '证据分页' })).toHaveCount(0);
  });
}

test('filter, expand and locate using real video', async ({ page }) => {
  await setup(page, 200);
  const region = page.getByRole('region', { name: '语音证据' });
  const player = page.getByLabel('视频播放器');
  await expect.poll(() => player.evaluate((v: HTMLVideoElement) => v.readyState)).toBeGreaterThan(0);
  await player.evaluate((v: HTMLVideoElement) => { v.pause(); v.currentTime = 0; });
  await region.getByLabel('筛选原文').fill('特殊关键词');
  await expect(region).toContainText('匹配 1 条');
  await expect(region.locator('article')).toHaveCount(1);
  await region.getByLabel('筛选原文').fill('不存在');
  await expect(region).toContainText('没有匹配的语音证据');
  await region.getByLabel('筛选原文').clear();
  await region.getByRole('button', { name: '展开全文' }).click();
  await expect(region.getByRole('button', { name: '收起全文' })).toBeVisible();
  expect(await player.evaluate((v: HTMLVideoElement) => v.paused && v.currentTime === 0)).toBe(true);
  const row = region.locator('article').filter({ hasText: '很长的语音内容' });
  await row.getByRole('button', { name: /^核查语音/ }).click();
  await expect(row).toHaveClass(/selected/);
  await expect.poll(() => player.evaluate((v: HTMLVideoElement) => v.currentTime)).toBeGreaterThanOrEqual(.35);
});

test('refresh preserves page and failure can be retried', async ({ page }) => {
  const fixture = await setup(page, 200);
  const region = page.getByRole('region', { name: '语音证据' });
  await region.getByRole('button', { name: '下一页' }).click();
  await expect(region).toContainText('第 2 / 10 页');
  fixture.fail();
  await expect(region.getByRole('alert')).toContainText('证据读取失败');
  await expect(region).toContainText('第 2 / 10 页');
  fixture.recover();
  await region.getByRole('button', { name: '重试' }).click();
  await expect(region.getByRole('alert')).toHaveCount(0);
  await expect(region).toContainText('第 2 / 10 页');
});

test('reopen resets filtering and initial load failure retries', async ({ page }) => {
  const fixture = await setup(page, 200);
  let region = page.getByRole('region', { name: '语音证据' });
  await region.getByLabel('筛选原文').fill('特殊关键词');
  await page.getByRole('button', { name: '关闭原片', exact: true }).click();
  fixture.fail();
  await page.getByRole('button', { name: /^播放 非常长/ }).click();
  region = page.getByRole('region', { name: '语音证据' });
  await expect(region.getByRole('alert')).toContainText('证据读取失败');
  fixture.recover();
  await region.getByRole('button', { name: '重试' }).click();
  await expect(region.getByLabel('筛选原文')).toHaveValue('');
  await expect(region).toContainText('共 200 条');
});

test('switching asset clears evidence state', async ({ page }) => {
  await setup(page, 200);
  const region = page.getByRole('region', { name: '语音证据' });
  await region.getByLabel('筛选原文').fill('特殊关键词');
  await page.getByRole('button', { name: '关闭原片', exact: true }).click();
  await page.getByRole('button', { name: '播放 第二个素材.mp4' }).click();
  await expect(region.getByLabel('筛选原文')).toHaveValue('');
  await expect(region).toContainText('第二份素材内容');
  await expect(region).toContainText('共 1 条');
});

test('search result opens video at its original timestamp', async ({ page }) => {
  await setup(page, 1);
  await page.getByRole('button', { name: '关闭原片', exact: true }).click();
  await page.getByRole('tab', { name: '查找' }).click();
  await page.getByLabel('查找原句').fill('命中内容');
  await page.getByRole('button', { name: '查找语音' }).click();
  await page.getByRole('button', { name: '核查原视频', exact: true }).click();
  const player = page.getByLabel('视频播放器');
  await expect.poll(() => player.evaluate((v: HTMLVideoElement) => v.currentTime)).toBeGreaterThanOrEqual(.35);
});

for (const [name, width, height] of [['desktop',1366,768],['tablet',768,1024],['mobile',390,844]] as const) {
  test(`evidence layout ${name}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height });
    await setup(page, 200);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.getByLabel('视频播放器')).toBeVisible();
    await expect(page.getByRole('region', { name: '语音证据' })).toBeVisible();
    await page.screenshot({ path: info.outputPath(`${name}.png`) });
  });
}

test('drawer tabs and nested player restore focus', async ({ page }) => {
  await setup(page, 1);
  const drawer = page.getByRole('dialog', { name: '素材库' });
  const player = page.getByRole('dialog', { name: '原片回看' });
  await page.keyboard.press('Escape');
  await expect(player).toBeHidden();
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('button', { name: /^播放 非常长/ })).toBeFocused();
  const tab = drawer.getByRole('tab', { name: '素材' });
  await tab.focus();
  await page.keyboard.press('ArrowRight');
  await expect(drawer.getByRole('tab', { name: '查找' })).toBeFocused();
  await expect(drawer.getByRole('tab', { name: '查找' })).toHaveAttribute('aria-selected', 'true');
  await expect(drawer.getByRole('tabpanel', { name: '查找' })).toBeVisible();
  await page.keyboard.press('ArrowLeft');
  await expect(tab).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(drawer).toBeHidden();
});

test('asset actions do not issue analysis until explicit request', async ({ page }) => {
  let analyses = 0;
  page.on('request', request => { if (new URL(request.url()).pathname.endsWith('/assets/a/vision-analysis')) analyses++; });
  await setup(page, 1);
  await page.getByRole('button', { name: '关闭原片', exact: true }).click();
  const row = page.getByRole('article', { name: /^素材：非常长/ });
  const height = await row.evaluate(el => el.getBoundingClientRect().height);
  await row.getByRole('button', { name: /^更多操作/ }).click();
  await expect(page.getByText('补充画面分析会产生模型用量；语音与画面能力独立。')).toBeVisible();
  expect(await row.evaluate(el => el.getBoundingClientRect().height)).toBe(height);
  expect(analyses).toBe(0);
});


test('restored upload shows progress, reselect and cancellation', async ({ page }) => {
  await setup(page, 1, true);
  await page.getByRole('button', { name: '关闭原片', exact: true }).click();
  const row = page.getByRole('article', { name: '上传：续传.mp4' });
  await expect(row).toContainText('40% 已确认');
  await expect(row.getByRole('progressbar', { name: '续传.mp4 上传进度' })).toHaveAttribute('aria-valuenow', '40');
  await expect(row.getByLabel('续传.mp4 重选原文件继续')).toBeVisible();
  await row.getByRole('button', { name: '续传.mp4 取消上传' }).click();
  await expect(row).toContainText('已取消');
});


test('search evidence expands and inspection requires explicit action', async ({ page }) => {
  const fixture = await setup(page, 1);
  await page.getByRole('button', { name: '关闭原片', exact: true }).click();
  await page.getByRole('tab', { name: '查找' }).click();
  await page.getByText('筛选条件').click();
  await page.getByLabel('证据类型').selectOption('VISUAL');
  await page.getByLabel('查找原句').fill('命中');
  await page.getByRole('button', { name: '查找证据' }).click();
  const hit = page.getByRole('article', { name: '画面检索证据' });
  await expect(page.getByText(/1 条候选/)).toBeVisible();
  await hit.getByRole('button', { name: '查看邻近上下文' }).click();
  await expect(hit.getByRole('region', { name: '邻近原文' })).toContainText('邻近内容');
  await hit.getByRole('button', { name: '收起邻近上下文' }).click();
  await expect(hit.getByRole('region', { name: '邻近原文' })).toHaveCount(0);
  await hit.getByRole('button', { name: '查看观察帧' }).click();
  await expect(hit.getByRole('img', { name: '证据观察帧' })).toBeVisible();
  await hit.getByRole('button', { name: '收起观察帧' }).click();
  expect(fixture.inspections()).toBe(0);
  await hit.getByRole('button', { name: '预算内复查候选' }).click();
  await expect(page.getByRole('region', { name: '预算内补查结果' })).toContainText('覆盖有限');
  expect(fixture.inspections()).toBe(1);
});

test('upload pause and resume retain the same upload session', async ({ page }) => {
  await setup(page, 1);
  await page.getByRole('button', { name: '关闭原片', exact: true }).click();
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let started!: () => void;
  const putStarted = new Promise<void>(resolve => { started = resolve; });
  let size = 0, resumed = 0;
  await page.route('**/fake-part', async route => { started(); await gate; await route.fulfill({ status: 200, headers: { ETag: 'etag' } }); });
  await page.route('**/api/v1/projects/p/uploads', async route => {
    if (route.request().method() === 'GET') { await route.fulfill({ json: { data: { items: [], nextCursor: null } } }); return; }
    size = route.request().postDataJSON().sizeBytes;
    await route.fulfill({ json: { data: { uploadId: 'live', filename: 'short-video.mp4', status: 'UPLOADING', declaredBytes: size, partSizeBytes: size, expectedParts: 1, confirmedBytes: 0, confirmedParts: [], writerToken: 'writer', expiresAt: '2099-01-01' } } });
  });
  await page.route('**/api/v1/uploads/live/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let data: unknown = {};
    if (path.endsWith('/part-urls')) data = { parts: [{ url: '/fake-part', headers: {}, sizeBytes: size }] };
    else if (path.endsWith('/pause')) data = { status: 'PAUSED' };
    else if (path.endsWith('/resume')) { resumed++; data = { uploadId: 'live', status: 'UPLOADING', declaredBytes: size, partSizeBytes: size, expectedParts: 1, confirmedBytes: 0, confirmedParts: [], writerToken: 'writer' }; }
    else if (path.endsWith('/complete')) data = { status: 'COMPLETED', validationStatus: 'VALID' };
    await route.fulfill({ json: { data } });
  });
  await page.getByLabel('选择视频').setInputFiles('e2e/fixtures/short-video.mp4');
  await putStarted;
  const row = page.getByRole('article', { name: '上传：short-video.mp4' });
  await row.getByRole('button', { name: 'short-video.mp4 暂停上传' }).click();
  release();
  await expect(row).toContainText('已暂停');
  await row.getByRole('button', { name: 'short-video.mp4 继续上传' }).click();
  await expect(row).toContainText('传输完成');
  expect(resumed).toBe(1);
});

test('reselecting a local file resumes the restored upload', async ({ page }) => {
  await setup(page, 1, true);
  await page.getByRole('button', { name: '关闭原片', exact: true }).click();
  let resumed = false;
  await page.route('**/fake-resume-part', route => route.fulfill({ status: 200, headers: { ETag: 'etag' } }));
  await page.route('**/api/v1/uploads/upload-1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    let data: unknown = {};
    if (path.endsWith('/resume')) { resumed = true; const size = route.request().postDataJSON().sizeBytes; data = { uploadId: 'upload-1', status: 'UPLOADING', declaredBytes: size, partSizeBytes: size, expectedParts: 1, confirmedBytes: 0, confirmedParts: [], writerToken: 'writer' }; }
    else if (path.endsWith('/part-urls')) data = { parts: [{ url: '/fake-resume-part', headers: {}, sizeBytes: 9378 }] };
    else if (path.endsWith('/complete')) data = { status: 'COMPLETED', validationStatus: 'VALID' };
    await route.fulfill({ json: { data } });
  });
  const row = page.getByRole('article', { name: '上传：续传.mp4' });
  await row.getByLabel('续传.mp4 重选原文件继续').setInputFiles('e2e/fixtures/short-video.mp4');
  await expect(row).toContainText('传输完成');
  expect(resumed).toBe(true);
});

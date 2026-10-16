import { test, expect, type Route } from '@playwright/test';
import { mkdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
const screenshots = resolve(process.env.E2E_REDESIGN_SCREENSHOTS ?? 'test-results/frontend-redesign');

// Match S3 range responses so seeking exercises a real media HTTP boundary.
const sourceBytes = readFileSync('e2e/fixtures/short-video.mp4');
async function sourceVideo(route: Route) {
  const range = route.request().headers().range?.match(/^bytes=(\d+)-(\d*)$/);
  const start = range ? Number(range[1]) : 0;
  const end = range?.[2] ? Math.min(Number(range[2]), sourceBytes.length - 1) : sourceBytes.length - 1;
  await route.fulfill({ status: range ? 206 : 200, contentType: 'video/mp4', headers: { 'Accept-Ranges': 'bytes', ...(range ? { 'Content-Range': `bytes ${start}-${end}/${sourceBytes.length}` } : {}) }, body: sourceBytes.subarray(start, end + 1) });
}

// Current public UI and HTTP contracts; no model calls or shared service changes.
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [], nextCursor: null };
    if (path === '/auth/me') data = { user: { id: 'assets-user', username: '素材测试' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects/assets') data = { id: 'assets', name: '素材与内容查找' };
    else if (path === '/projects') data = { items: [{ id: 'assets', name: '素材与内容查找' }], nextCursor: null };
    else if (path.endsWith('/snapshot')) data = { cursor: 'assets:0' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    await route.fulfill({ json: { data } });
  });
});

test('completed uploads merge only by asset identity while equal filenames remain separate', async ({ page }) => {
  await page.route('**/api/v1/projects/assets/assets?*', route => route.fulfill({ json: { data: { items: [
    { id: 'a', filename: '同名.mp4', validationStatus: 'VALID' },
    { id: 'b', filename: '同名.mp4', validationStatus: 'VALID' },
    { id: 'pending', filename: '验证中.mp4', validationStatus: 'VALIDATING' },
  ] } } }));
  await page.route('**/api/v1/projects/assets/uploads', route => route.fulfill({ json: { data: { items: [
    { uploadId: 'u-a', assetId: 'a', filename: '同名.mp4', status: 'COMPLETED', validationStatus: 'VALID', declaredBytes: 40 },
    { uploadId: 'u-pending', assetId: 'pending', filename: '验证中.mp4', status: 'COMPLETED', validationStatus: 'VALIDATING', declaredBytes: 40 },
  ], nextCursor: null } } }));
  await page.goto('/?project=assets');
  await expect(page.getByRole('article', { name: '素材：同名.mp4', exact: true })).toHaveCount(2);
  await expect(page.getByRole('article', { name: '上传：同名.mp4', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '播放 同名.mp4', exact: true })).toHaveCount(2);
  await expect(page.getByRole('article', { name: '上传：验证中.mp4', exact: true })).toContainText('传输完成，待媒体验证');
  await expect(page.getByRole('button', { name: '播放 验证中.mp4', exact: true })).toHaveCount(0);
});

test('partial analysis retains speech and retries only the failed stage; maintenance stays secondary', async ({ page }) => {
  const writes: string[] = [];
  await page.route('**/api/v1/projects/assets/assets?*', route => route.fulfill({ json: { data: { items: [{ id: 'a', filename: '讲话.mp4', validationStatus: 'VALID' }] } } }));
  await page.route('**/api/v1/assets/a', route => route.fulfill({ json: { data: { filename: '讲话.mp4', analysis: { stages: { MEDIA: 'SUCCEEDED', ASR: 'SUCCEEDED', VISION: 'FAILED', EMBEDDING: 'SUCCEEDED', INDEX: 'SUCCEEDED' }, errors: { VISION: { message: '画面分析失败，语音证据仍可使用' } } } } } }));
  await page.route('**/api/v1/assets/a/evidence', route => route.fulfill({ json: { data: { units: [{}], observations: [] } } }));
  await page.route('**/api/v1/assets/a/search-index', async route => {
    if (route.request().method() === 'POST') writes.push('index');
    await route.fulfill({ json: { data: { publishedGeneration: null, generations: [] } } });
  });
  await page.route('**/api/v1/assets/a/analysis-stages/*/retry', async route => { writes.push(new URL(route.request().url()).pathname); await route.fulfill({ json: { data: {} } }); });
  await page.goto('/?project=assets');
  await page.getByRole('button', { name: '查看分析状态 讲话.mp4', exact: true }).click();
  const status = page.getByRole('region', { name: '素材分析状态', exact: true });
  await expect(status).toContainText('语音已就绪');
  await expect(status.getByRole('button', { name: '仅重试失败的 语音', exact: true })).toHaveCount(0);
  await expect(page.getByLabel('索引 Embedding 模型', { exact: true })).toHaveCount(0);
  expect(writes).toEqual([]);
  await status.getByRole('button', { name: '仅重试失败的 画面', exact: true }).click();
  await expect.poll(() => writes).toEqual(['/api/v1/assets/a/analysis-stages/VISION/retry']);
  await page.getByText('诊断与高级维护', { exact: true }).click();
  await page.getByLabel('维护素材', { exact: true }).selectOption('a');
  await expect(page.getByLabel('索引 Embedding 模型', { exact: true })).toBeVisible();
  expect(writes).toHaveLength(1);
});

test('query filters preserve request semantics and results retain their original source and query', async ({ page }) => {
  const searches: unknown[] = [], inspections: unknown[] = [];
  await page.route('**/api/v1/projects/assets/search', async route => {
    searches.push(route.request().postDataJSON());
    await route.fulfill({ json: { data: { semanticAvailable: true, coverage: { assets: [{ assetId: 'a', filename: '街景.mp4', sourceKind: 'VISUAL', status: 'SUCCEEDED', indexStatus: 'SUCCEEDED', processedRanges: [{ startMs: 0, endMs: 2000 }] }] }, hits: [{ assetId: 'a', filename: '街景.mp4', text: '街角有一辆车', sourceKind: 'VISUAL', observedAtMs: 1000, evidenceIds: ['frame'] }] } } });
  });
  await page.route('**/api/v1/assets/a', route => route.fulfill({ json: { data: { durationMs: 2000 } } }));
  await page.route('**/api/v1/projects/assets/visual-inspections', async route => {
    inspections.push(route.request().postDataJSON());
    await route.fulfill({ json: { data: { id: 'inspection', status: 'NO_NEW_EVIDENCE', budget: { maxFrames: 4, maxCalls: 4, maxSteps: 8, maxSeconds: 180, maxInputBytes: 4194304 }, usage: { frames: 4, calls: 1, steps: 1, inputBytes: 100 }, coverage: { startMs: 0, endMs: 2000 }, observations: [], coverageWarning: '仍可能遗漏', expansionProposal: null } } });
  });
  await page.goto('/?project=assets');
  await page.getByText('筛选条件', { exact: true }).click();
  await page.getByLabel('证据类型', { exact: true }).selectOption('VISUAL');
  await page.getByLabel('检索方式', { exact: true }).selectOption('semantic');
  await page.getByLabel('查找原句', { exact: true }).fill('街角的车');
  await page.getByRole('button', { name: '查找证据', exact: true }).click();
  const hit = page.getByRole('article', { name: '画面检索证据', exact: true });
  await expect(hit).toContainText('街角有一辆车');
  expect(searches).toEqual([{ query: '街角的车', literalPhrase: false, limit: 20, sourceKinds: ['VISUAL'] }]);
  await expect(page.getByText(/代表帧为稀疏采样/)).toBeVisible();
  await page.getByLabel('证据类型', { exact: true }).selectOption('SPEECH');
  await page.getByLabel('查找原句', { exact: true }).fill('新的语音问题');
  await hit.getByRole('button', { name: '预算内复查候选', exact: true }).click();
  await expect.poll(() => inspections.length).toBe(1);
  expect(inspections[0]).toEqual({ assetId: 'a', query: '街角的车', sourceKind: 'VISUAL', startMs: 0, endMs: 2000 });
  await expect(page.getByRole('region', { name: '预算内补查结果', exact: true })).toContainText('仍可能遗漏');
});

test('restored uploads show confirmed bytes, reselect requirements, expiry and cleanup independently', async ({ page }) => {
  const resumes: unknown[] = [];
  await page.route('**/api/v1/projects/assets/uploads', route => route.fulfill({ json: { data: { items: [
    { uploadId: 'paused', filename: '暂停.mp4', status: 'PAUSED', declaredBytes: 100, confirmedBytes: 30, expiresAt: '2099-01-01' },
    { uploadId: 'expired', filename: '过期.mp4', status: 'EXPIRED', declaredBytes: 100, confirmedBytes: 20, cleanupStatus: 'PENDING' },
    { uploadId: 'cancelled', filename: '取消.mp4', status: 'CANCELLED', declaredBytes: 100, confirmedBytes: 20, cleanupStatus: 'CLEANED' },
    { uploadId: 'invalid', filename: '损坏.mp4', status: 'COMPLETED', declaredBytes: 100, validationStatus: 'INVALID', cleanupStatus: 'PENDING', error: { code: 'MEDIA_HASH_MISMATCH', message: '服务端校验值与声明不符' } },
  ], nextCursor: null } } }));
  await page.route('**/api/v1/uploads/paused/resume', async route => {
    resumes.push(route.request().postDataJSON());
    await route.fulfill({ status: 409, json: { error: { message: '文件内容与原上传不一致' } } });
  });
  await page.goto('/?project=assets');
  const paused = page.getByRole('article', { name: '上传：暂停.mp4', exact: true });
  await expect(paused).toContainText('刷新后浏览器无法保留原文件');
  await expect(paused.getByRole('progressbar')).toHaveAttribute('value', '30');
  await expect(paused.getByRole('progressbar')).toHaveAttribute('max', '100');
  await expect(paused).toContainText('30% 已确认');
  const expired = page.getByRole('article', { name: '上传：过期.mp4', exact: true });
  await expect(expired).toContainText('上传已过期，请重新上传');
  await expect(expired).toContainText('字节占用尚未释放');
  await expect(expired.getByRole('button')).toHaveCount(0);
  await expect(page.getByRole('article', { name: '上传：取消.mp4', exact: true })).toContainText('残留清理完成，占用已释放');
  await expect(page.getByRole('article', { name: '上传：损坏.mp4', exact: true })).toContainText('服务端校验值与声明不符');
  await paused.getByLabel('暂停.mp4 重选原文件继续', { exact: true }).setInputFiles({ name: '暂停.mp4', mimeType: 'video/mp4', buffer: Buffer.alloc(100) });
  await expect(paused.getByRole('alert')).toContainText('文件内容与原上传不一致');
  expect(resumes).toHaveLength(1);
  expect(resumes[0]).toMatchObject({ sizeBytes: 100 });
});

test('cancelled upload keeps occupied bytes until the service reports cleanup complete', async ({ page }) => {
  let cancelled = false, cleaned = false;
  const upload = { uploadId: 'cancel', filename: '待取消.mp4', status: 'PAUSED', declaredBytes: 100, confirmedBytes: 25, expiresAt: '2099-01-01' };
  await page.route('**/api/v1/projects/assets/uploads', route => route.fulfill({ json: { data: { items: [{ ...upload, status: cancelled ? 'CANCELLED' : 'PAUSED', cleanupStatus: cleaned ? 'CLEANED' : 'PENDING' }], nextCursor: null } } }));
  await page.route('**/api/v1/uploads/cancel/cancel', async route => { cancelled = true; await route.fulfill({ json: { data: { ...upload, status: 'CANCELLED', cleanupStatus: 'PENDING' } } }); });
  await page.goto('/?project=assets');
  await page.getByRole('button', { name: '待取消.mp4 取消上传', exact: true }).click();
  const row = page.getByRole('article', { name: '上传：待取消.mp4', exact: true });
  await expect(row).toContainText('字节占用尚未释放');
  await expect(row.getByRole('button')).toHaveCount(0);
  cleaned = true;
  await expect(row).toContainText('残留清理完成，占用已释放');
});

test('silent media is a capability limit and expanding details performs no analysis', async ({ page }) => {
  const writes: string[] = [];
  await page.route('**/api/v1/projects/assets/assets?*', route => route.fulfill({ json: { data: { items: [{ id: 'silent', filename: '静音.mp4', validationStatus: 'VALID' }] } } }));
  await page.route('**/api/v1/assets/silent', route => route.fulfill({ json: { data: { filename: '静音.mp4', analysis: { stages: { MEDIA: 'SUCCEEDED', ASR: 'NOT_APPLICABLE', VISION: 'NOT_STARTED', EMBEDDING: 'NOT_APPLICABLE', INDEX: 'NOT_APPLICABLE' }, errors: {} } } } }));
  page.on('request', request => { if (request.method() === 'POST') writes.push(request.url()); });
  await page.goto('/?project=assets');
  await page.getByRole('button', { name: '查看分析状态 静音.mp4', exact: true }).click();
  await expect(page.getByRole('region', { name: '素材分析状态', exact: true })).toContainText('无音轨，不能进行语音分析');
  await page.getByText('完整分析阶段', { exact: true }).click();
  await expect(page.getByRole('button', { name: '仅重试失败的 语音', exact: true })).toHaveCount(0);
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(writes).toEqual([]);
});

for (const mode of ['literal', 'semantic']) {
  test(`empty ${mode} results expose partial coverage without asserting content absence`, async ({ page }) => {
    let body: unknown;
    await page.route('**/api/v1/projects/assets/search', async route => {
      body = route.request().postDataJSON();
      await route.fulfill({ json: { data: { hits: [], semanticAvailable: false, coverage: { assets: [
        { assetId: 'a', filename: '就绪.mp4', sourceKind: 'SPEECH', status: 'SUCCEEDED', indexStatus: 'SUCCEEDED', processedRanges: [{ startMs: 0, endMs: 1000 }] },
        { assetId: 'b', filename: '未完成.mp4', sourceKind: 'SPEECH', status: 'FAILED', indexStatus: 'FAILED', processedRanges: [] },
      ] } } } });
    });
    await page.goto('/?project=assets');
    await page.getByText('筛选条件', { exact: true }).click();
    await page.getByLabel('检索方式', { exact: true }).selectOption(mode);
    await page.getByLabel('查找原句', { exact: true }).fill('未找到的句子');
    await page.getByRole('button', { name: '查找语音', exact: true }).click();
    await expect(page.getByText(/仍有素材尚未完成分析/)).toBeVisible();
    await expect(page.getByText(mode === 'literal' ? '在已分析的语音范围内未找到原文匹配' : '在已分析的语音范围内未找到主题匹配', { exact: true })).toBeVisible();
    expect(body).toEqual({ query: '未找到的句子', literalPhrase: mode === 'literal', limit: 20, sourceKinds: ['SPEECH'] });
    await page.getByText('查看查询覆盖', { exact: true }).click();
    await expect(page.getByRole('region', { name: '语音查询覆盖', exact: true })).toContainText('未命中不能证明原视频没有该内容');
  });
}

for (const choice of ['DECLINE', 'APPROVE']) {
  test(`inspection expansion requires explicit ${choice} and retains the original bounded result`, async ({ page }) => {
    let starts = 0, children = 0;
    const decisions: unknown[] = [];
    const run = { id: 'bounded', status: 'BUDGET_EXHAUSTED', budget: { maxFrames: 4, maxCalls: 4, maxSteps: 8, maxSeconds: 180, maxInputBytes: 4194304 }, usage: { frames: 4, calls: 4, steps: 4, inputBytes: 100 }, coverage: { startMs: 0, endMs: 2000 }, observations: [{ id: 'old', sourceKind: 'VISUAL', text: '保留的原观察', observedAtMs: 500 }], coverageWarning: '仍可能遗漏', expansionProposal: null };
    const proposal = { assetId: 'a', startMs: 2000, endMs: 8000, status: 'PROPOSED', budget: { maxFrames: 8, maxCalls: 8 }, cost: { explanation: '费用估算：会新增视觉调用' }, childInspectionId: null as string | null };
    await page.route('**/api/v1/projects/assets/search', route => route.fulfill({ json: { data: { hits: [], semanticAvailable: true, coverage: { assets: [{ assetId: 'a', filename: '街景.mp4', sourceKind: 'VISUAL', status: 'SUCCEEDED', indexStatus: 'SUCCEEDED', processedRanges: [] }] } } } }));
    await page.route('**/api/v1/projects/assets/visual-inspections', async route => { starts++; await route.fulfill({ json: { data: run } }); });
    await page.route('**/api/v1/visual-inspections/bounded/expansion-proposal', route => route.fulfill({ json: { data: proposal } }));
    await page.route('**/api/v1/visual-inspections/bounded/expansion-decision', async route => {
      decisions.push(route.request().postDataJSON());
      proposal.status = choice === 'APPROVE' ? 'APPROVED' : 'DECLINED';
      proposal.childInspectionId = choice === 'APPROVE' ? 'child' : null;
      await route.fulfill({ json: { data: proposal } });
    });
    await page.route('**/api/v1/visual-inspections/child', async route => { children++; await route.fulfill({ json: { data: { ...run, id: 'child', status: 'SUCCEEDED', budget: { ...run.budget, maxFrames: 8, maxCalls: 8 }, observations: [{ id: 'new', sourceKind: 'VISUAL', text: '扩大后的新观察', observedAtMs: 3000 }] } } }); });
    await page.goto('/?project=assets');
    await page.getByText('筛选条件', { exact: true }).click();
    await page.getByLabel('证据类型', { exact: true }).selectOption('VISUAL');
    await page.getByLabel('查找原句', { exact: true }).fill('街景');
    await page.getByRole('button', { name: '查找证据', exact: true }).click();
    await page.getByText('查看查询覆盖', { exact: true }).click();
    expect(starts).toBe(0);
    await page.getByRole('button', { name: '预算内覆盖补查', exact: true }).click();
    const card = page.getByRole('region', { name: '预算内补查结果', exact: true });
    await expect(card).toContainText('预算耗尽，已停止');
    await card.getByRole('button', { name: '查看扩大补查方案', exact: true }).click();
    await expect(card).toContainText('拟扩大素材：街景.mp4');
    await expect(card).toContainText('新增最多 8 帧');
    await expect(card).toContainText('费用估算');
    expect(decisions).toEqual([]);
    expect(children).toBe(0);
    await card.getByRole('button', { name: choice === 'APPROVE' ? '接受范围与费用，继续补查' : '保留结果，不扩大', exact: true }).click();
    await expect.poll(() => decisions).toEqual([{ choice }]);
    await expect(card).toContainText('保留的原观察');
    if (choice === 'APPROVE') {
      await expect(card.getByRole('region', { name: '扩大补查结果', exact: true })).toContainText('扩大后的新观察');
      expect(children).toBe(1);
    } else {
      await expect(card).toContainText('已拒绝扩大');
      expect(children).toBe(0);
    }
    expect(starts).toBe(1);
  });
}

test('source playback stays in assets, renews the same source, seeks and returns to retained results', async ({ page }) => {
  let accesses = 0;
  await page.route('**/api/v1/projects/assets/assets?*', route => route.fulfill({ json: { data: { items: [{ id: 'a', filename: '原片.mp4', validationStatus: 'VALID' }] } } }));
  await page.route('**/api/v1/assets/a/playback-access', async route => { accesses++; await route.fulfill({ json: { data: { assetId: 'a', filename: '原片.mp4', durationMs: 1000, url: accesses === 1 ? '/expired-source.mp4' : '/source.mp4', expiresAt: '2099-01-01' } } }); });
  await page.route('**/expired-source.mp4', route => route.fulfill({ status: 403, body: 'expired' }));
  await page.route('**/source.mp4', sourceVideo);
  await page.route('**/api/v1/assets/a', route => route.fulfill({ json: { data: { mediaInfo: { streams: [{ codec_type: 'video', codec_name: 'h264', width: 160, height: 90 }] }, timeMapping: { hasAudio: false }, analysis: { stages: { MEDIA: 'SUCCEEDED', ASR: 'NOT_APPLICABLE' }, errors: {} } } } }));
  await page.route('**/api/v1/assets/a/evidence', route => route.fulfill({ json: { data: { observations: [], units: [], summary: {} } } }));
  await page.goto('/?project=assets');
  await page.getByLabel('查找原句', { exact: true }).fill('保留的查询');
  await page.getByRole('button', { name: '播放 原片.mp4', exact: true }).click();
  const player = page.getByLabel('视频播放器', { exact: true });
  await expect.poll(() => player.evaluate((video: HTMLVideoElement) => video.readyState)).toBeGreaterThanOrEqual(1);
  expect(accesses).toBe(2);
  await expect(page.getByRole('tab', { name: '素材', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('索引 Embedding 模型', { exact: true })).toHaveCount(0);
  await page.getByText('媒体详情与精确定位', { exact: true }).click();
  await page.getByLabel('定位秒数', { exact: true }).fill('0.4');
  await page.getByRole('button', { name: '定位播放', exact: true }).click();
  await expect.poll(() => player.evaluate((video: HTMLVideoElement) => video.currentTime)).toBeGreaterThanOrEqual(.35);
  await expect(page.getByText('H.264', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '返回素材列表', exact: true }).click();
  await expect(player).toHaveCount(0);
  await expect(page.getByLabel('查找原句', { exact: true })).toHaveValue('保留的查询');
});

test('source clips from a saved plan stop at their original end boundary', async ({ page }) => {
  await page.route('**/api/v1/projects/assets/plans', route => route.fulfill({ json: { data: { items: [{ id: 'plan', title: '已确认短片', editingTaskId: null }] } } }));
  await page.route('**/api/v1/plans/plan/revisions', route => route.fulfill({ json: { data: { items: [{ id: 'revision', revisionNumber: 1 }] } } }));
  await page.route('**/api/v1/revisions/revision', route => route.fulfill({ json: { data: { id: 'revision', planId: 'plan', revisionNumber: 1, content: { outputs: [{ outputId: 'output', title: '短片', durationMs: 300, render: { width: 160, height: 90 }, subtitles: { cues: [] }, clips: [{ clipId: 'clip', assetId: 'a', sourceInMs: 200, sourceOutMs: 500, reason: '原片核查', evidenceIds: [] }] }] } } } }));
  await page.route('**/api/v1/assets/a', route => route.fulfill({ json: { data: { id: 'a', filename: '原片.mp4', mediaInfo: { streams: [] }, timeMapping: { hasAudio: false } } } }));
  await page.route('**/api/v1/assets/a/evidence', route => route.fulfill({ json: { data: { observations: [], units: [], summary: {} } } }));
  await page.route('**/api/v1/assets/a/playback-access', route => route.fulfill({ json: { data: { assetId: 'a', filename: '原片.mp4', url: '/source.mp4', durationMs: 1000, expiresAt: '2099-01-01' } } }));
  await page.route('**/source.mp4', sourceVideo);
  await page.goto('/?project=assets');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
  await page.getByLabel('查看版本', { exact: true }).selectOption('revision');
  await page.getByRole('button', { name: '播放源片段', exact: true }).click();
  const player = page.getByLabel('视频播放器', { exact: true });
  await expect.poll(() => player.evaluate((video: HTMLVideoElement) => video.currentTime)).toBeGreaterThanOrEqual(.2);
  await expect.poll(() => player.evaluate((video: HTMLVideoElement) => ({ paused: video.paused, positionMs: Math.round(video.currentTime * 1000) }))).toEqual({ paused: true, positionMs: 500 });
  expect(await player.evaluate((video: HTMLVideoElement) => video.currentTime)).toBeCloseTo(.5, 2);
});

for (const viewport of [{ width: 1366, height: 768 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
  test(`asset query, context and source playback are usable without overflow at ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    let accesses = 0;
    const filename = '用于核对长文件名与换行的讲话素材.mp4';
    await page.route('**/api/v1/projects/assets/assets?*', route => route.fulfill({ json: { data: { items: [{ id: 'a', filename, validationStatus: 'VALID' }] } } }));
    await page.route('**/api/v1/projects/assets/uploads', route => route.fulfill({ json: { data: { items: [{ uploadId: 'u', assetId: 'a', filename, status: 'COMPLETED', validationStatus: 'VALID', declaredBytes: 400 }], nextCursor: null } } }));
    await page.route('**/api/v1/assets/a', route => route.fulfill({ json: { data: { id: 'a', filename, durationMs: 1000, mediaInfo: { streams: [{ codec_type: 'video', codec_name: 'h264', width: 160, height: 90 }] }, timeMapping: { hasAudio: true }, analysis: { stages: { MEDIA: 'SUCCEEDED', ASR: 'SUCCEEDED', VISION: 'FAILED', EMBEDDING: 'SUCCEEDED', INDEX: 'SUCCEEDED' }, errors: { VISION: { message: '画面分析失败，已有语音仍可使用' } } } } } }));
    await page.route('**/api/v1/assets/a/evidence', route => route.fulfill({ json: { data: { observations: [], units: [], summary: {} } } }));
    await page.route('**/api/v1/projects/assets/search', route => route.fulfill({ json: { data: { hits: [{ assetId: 'a', filename, sourceKind: 'SPEECH', text: '这是一个篇章的结束，也是新篇章的开始。', startMs: 400, endMs: 800, evidenceIds: ['speech'] }], semanticAvailable: true, coverage: { assets: [{ assetId: 'a', filename, sourceKind: 'SPEECH', status: 'SUCCEEDED', indexStatus: 'SUCCEEDED', processedRanges: [{ startMs: 0, endMs: 1000 }] }] } } } }));
    await page.route('**/api/v1/evidence/speech/context?*', route => route.fulfill({ json: { data: { assetId: 'a', observations: [{ id: 'context', text: '这并不是告别。', startMs: 100, endMs: 400 }] } } }));
    await page.route('**/api/v1/assets/a/playback-access', async route => { accesses++; await route.fulfill({ json: { data: { assetId: 'a', filename, durationMs: 1000, url: accesses === 1 ? '/expired-source.mp4' : '/source.mp4', expiresAt: '2099-01-01' } } }); });
    await page.route('**/expired-source.mp4', route => route.fulfill({ status: 403, body: 'expired' }));
    await page.route('**/source.mp4', sourceVideo);
    await page.goto('/?project=assets');
    await page.getByRole('button', { name: `查看分析状态 ${filename}`, exact: true }).click();
    await expect(page.getByRole('region', { name: '素材分析状态', exact: true })).toContainText('语音已就绪');
    mkdirSync(screenshots, { recursive: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `${screenshots}/controlled-assets-${viewport.width}x${viewport.height}.png`, fullPage: true });
    await page.getByLabel('查找原句', { exact: true }).fill('新篇章');
    await page.getByRole('button', { name: '查找语音', exact: true }).click();
    const hit = page.getByRole('article', { name: '语音检索证据', exact: true });
    await hit.getByRole('button', { name: '查看邻近上下文', exact: true }).click();
    await expect(page.getByRole('region', { name: '邻近原文', exact: true })).toContainText('这并不是告别。');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `${screenshots}/controlled-search-${viewport.width}x${viewport.height}.png`, fullPage: true });
    await hit.getByRole('button', { name: '核查原视频', exact: true }).click();
    const player = page.getByLabel('视频播放器', { exact: true });
    await expect.poll(() => player.evaluate((video: HTMLVideoElement) => video.currentTime)).toBeGreaterThanOrEqual(.4);
    expect(accesses).toBe(2);
    await player.evaluate((video: HTMLVideoElement) => video.pause());
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `${screenshots}/controlled-source-${viewport.width}x${viewport.height}.png`, fullPage: true });
    await page.getByRole('button', { name: '返回素材列表', exact: true }).click();
    await expect(hit).toBeVisible();
    await expect(page.getByLabel('查找原句', { exact: true })).toHaveValue('新篇章');
  });
}

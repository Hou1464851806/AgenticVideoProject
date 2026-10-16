import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const screenshots = resolve(process.env.E2E_REDESIGN_SCREENSHOTS ?? 'test-results/frontend-redesign');
const output = { outputId: 'output', title: '正式开场', durationMs: 2000, render: { width: 1280, height: 720 }, clips: [], subtitles: { cues: [] } };
const revision = { id: 'revision', planId: 'plan', revisionNumber: 1, parentRevisionId: null, content: { outputs: [output] } };
const render = { requestId: 'export', taskId: 'export-task', revisionId: 'revision', status: 'RUNNING', configuration: { profile: 'HD_720' }, outputId: 'output' };
const storage = { ORIGINAL: { storedBytes: 2048, reservedBytes: 1024, pendingCleanupBytes: 512 }, DERIVED: { storedBytes: 0, reservedBytes: 0, pendingCleanupBytes: 0 }, PREVIEW: { storedBytes: 0, reservedBytes: 0, pendingCleanupBytes: 0 }, EXPORT: { storedBytes: 0, reservedBytes: 0, pendingCleanupBytes: 0 } };
const usage = { storage, models: { callCount: 2, reusedBatchCount: 1, uncertainCallCount: 1, unpricedCallCount: 1, estimatedCostCny: 0.12, tokens: 200, audioSeconds: 3, items: [{ id: 'receipt', provider: 'DEEPSEEK', model: 'test-model', purpose: 'AGENT', status: 'UNCERTAIN', retryNumber: 0, requestId: 'provider-receipt', providerTaskId: null, usageBasis: 'ESTIMATED', costBasis: 'ESTIMATED_LIST_PRICE', estimatedCostCny: null, pricingRef: '测试价格依据', reportedUsage: { input_tokens: 10, secret: 'MUST_NOT_RENDER', reasoning_content: 'PRIVATE_THOUGHT' }, usage: { inputTokens: 100, outputTokens: 100, audioSeconds: 0, cachedInputTokens: 0 } }], nextOffset: -1 }, thresholds: { costCny: null, calls: null, tokens: null, audioSeconds: null }, warnings: [{ metric: 'calls', value: 2, threshold: 1 }] };

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [] };
    if (path === '/auth/me') data = { user: { id: 'results-user', username: '作品测试' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects') data = { items: [{ id: 'project', name: '作品与用量' }], nextCursor: null };
    else if (path === '/projects/project') data = { id: 'project', name: '作品与用量' };
    else if (path.endsWith('/assets')) data = { items: [{ id: 'asset', filename: '原视频.mp4', validationStatus: 'VALID' }] };
    else if (path === '/projects/project/plans') data = { items: [{ id: 'plan', title: '已确认作品', editingTaskId: null }] };
    else if (path === '/plans/plan/revisions') data = { items: [revision] };
    else if (path === '/revisions/revision') data = revision;
    else if (path === '/revisions/revision/confirmations') data = { items: [{ confirmationId: 'confirmed' }] };
    else if (path === '/projects/project/usage') data = usage;
    else if (path === '/projects/project/snapshot') data = { cursor: 'project:1' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    else if (route.request().method() !== 'GET') { await route.fulfill({ status: 400, json: { error: { message: '未定义的作品操作' } } }); return; }
    await route.fulfill({ json: { data } });
  });
});

test('a confirmed single version opens directly as awaiting export without confirmation again', async ({ page }) => {
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
  await expect(page.getByRole('heading', { name: 'V1 · 正式开场', exact: true })).toBeVisible();
  await expect(page.getByLabel('查看版本', { exact: true })).toHaveCount(0);
  await expect(page.getByText('待导出', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '确认当前版本', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '导出正式成片', exact: true })).toBeEnabled();
});

test('deleting an export requires explicit object confirmation and keeps cleanup distinct', async ({ page }) => {
  let state = 'AVAILABLE';
  const deletes: unknown[] = [];
  const completed = { ...render, status: 'SUCCEEDED', artifactId: 'artifact' };
  await page.route('**/api/v1/revisions/revision/export-requests', route => route.fulfill({ json: { data: { items: [completed] } } }));
  await page.route('**/api/v1/render-requests/export', route => route.fulfill({ json: { data: completed } }));
  await page.route('**/api/v1/artifacts/artifact', async route => {
    if (route.request().method() === 'DELETE') { deletes.push(route.request().postDataJSON()); state = 'DELETE_PENDING'; }
    await route.fulfill({ json: { data: { state, removedBytes: 0 } } });
  });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
  await expect(page.getByRole('button', { name: '确认当前版本', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '删除成片文件', exact: true }).click();
  await expect(page.getByRole('alertdialog', { name: '确认删除成片文件', exact: true })).toContainText('正式开场');
  await expect(page.getByRole('button', { name: '保留文件', exact: true })).toBeFocused();
  expect(deletes).toEqual([]);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('alertdialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '删除成片文件', exact: true })).toBeFocused();
  expect(deletes).toEqual([]);
  await page.getByRole('button', { name: '删除成片文件', exact: true }).click();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: '确认删除此成片', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect.poll(() => deletes).toEqual([{ explicitIntent: 'DELETE' }]);
  await expect(page.getByText('正在删除成片文件，存储恢复后自动重试。', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '下载正式成片', exact: true })).toHaveCount(0);
  state = 'REMOVED';
  await expect(page.getByText('成片文件已删除，原始素材保留。', { exact: true })).toBeVisible();
});

test('usage separates unknown cost and cleanup, saves thresholds and exposes only safe receipts', async ({ page }) => {
  let thresholds = { ...usage.thresholds } as { costCny: number | null; calls: number | null; tokens: number | null; audioSeconds: number | null };
  const writes: unknown[] = [];
  await page.route('**/api/v1/projects/project/usage', route => route.fulfill({ json: { data: { ...usage, thresholds } } }));
  await page.route('**/api/v1/projects/project/usage/thresholds', async route => { thresholds = route.request().postDataJSON(); writes.push(thresholds); await route.fulfill({ json: { data: {} } }); });
  await page.goto('/?project=project');
  await page.getByRole('button', { name: '用量', exact: true }).click();
  await expect(page.getByLabel('存储与模型用量', { exact: true })).toContainText('费用未知 1 次');
  await expect(page.getByLabel('存储与模型用量', { exact: true })).toContainText('待清理是已占用的一部分');
  await page.getByText('存储分类', { exact: true }).click();
  await expect(page.getByRole('row', { name: '原始素材 2.00 KiB 1.00 KiB 512 B', exact: true })).toBeVisible();
  await page.getByText('用量明细与估算依据', { exact: true }).click();
  await page.getByText('模型调用记录', { exact: true }).click();
  await page.getByText(/Agent 对话与编排 · DEEPSEEK/).click();
  await expect(page.getByLabel('存储与模型用量', { exact: true })).not.toContainText('provider-receipt');
  await page.getByLabel('模型调用次数预警阈值', { exact: true }).fill('9');
  await page.getByRole('button', { name: '保存预警阈值', exact: true }).click();
  await expect.poll(() => writes).toEqual([{ costCny: null, calls: 9, tokens: null, audioSeconds: null }]);
  await page.getByRole('button', { name: '关闭用量', exact: true }).click();
  await page.getByRole('button', { name: '诊断与高级维护', exact: true }).click();
  await page.getByRole('button', { name: '用量', exact: true }).click();
  await expect(page.getByLabel('模型调用次数预警阈值', { exact: true })).toHaveValue('9');
  await page.getByText('用量明细与估算依据', { exact: true }).click();
  await page.getByText('模型调用记录', { exact: true }).click();
  await page.getByText(/Agent 对话与编排 · DEEPSEEK/).click();
  await expect(page.getByLabel('存储与模型用量', { exact: true })).toContainText('provider-receipt');
  await expect(page.locator('body')).not.toContainText('MUST_NOT_RENDER');
  await expect(page.locator('body')).not.toContainText('PRIVATE_THOUGHT');
  await expect(page.getByLabel('存储与模型用量', { exact: true })).toContainText('预警提示不会阻止执行');
});

test('multi-output export pins revision, output and profile through a lost response and view switches', async ({ page }) => {
  const second = { ...revision, id: 'revision-2', revisionNumber: 2, content: { outputs: [output, { ...output, outputId: 'output-2', title: '第二条正式输出' }] } };
  const writes: { key: string; body: unknown; path: string }[] = [];
  await page.route('**/api/v1/plans/plan/revisions', route => route.fulfill({ json: { data: { items: [revision, second] } } }));
  await page.route('**/api/v1/revisions/revision-2', route => route.fulfill({ json: { data: second } }));
  await page.route('**/api/v1/revisions/revision-2/confirmations', route => route.fulfill({ json: { data: { items: [{ confirmationId: 'confirmed-2' }] } } }));
  await page.route('**/api/v1/revisions/revision-2/export-requests', async route => {
    if (route.request().method() === 'GET') { await route.fulfill({ json: { data: { items: [] } } }); return; }
    writes.push({ key: route.request().headers()['idempotency-key']!, body: route.request().postDataJSON(), path: new URL(route.request().url()).pathname });
    if (writes.length === 1) await route.abort('failed');
    else await route.fulfill({ json: { data: { ...render, revisionId: 'revision-2', outputId: 'output-2', configuration: { profile: 'FULL_HD_1080' } } } });
  });
  await page.route('**/api/v1/render-requests/export', route => route.fulfill({ json: { data: { ...render, revisionId: 'revision-2', outputId: 'output-2', configuration: { profile: 'FULL_HD_1080' } } } }));
  const cancelled: string[] = [];
  await page.route('**/api/v1/tasks/*/cancel', async route => { cancelled.push(new URL(route.request().url()).pathname); await route.fulfill({ json: { data: {} } }); });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
  await page.getByLabel('查看版本', { exact: true }).selectOption('revision-2');
  await page.getByLabel('成片导出清晰度', { exact: true }).selectOption('FULL_HD_1080');
  await expect(page.getByText('本次导出：V2 · 1080p · 每次一条输出', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '导出正式成片', exact: true }).click();
  expect(writes).toEqual([]);
  await page.getByLabel('选择导出输出', { exact: true }).selectOption('output-2');
  await page.getByRole('button', { name: '导出所选输出', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Failed to fetch');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByRole('button', { name: '导出所选输出', exact: true }).click();
  await expect.poll(() => writes.length).toBe(2);
  expect(writes[1]).toEqual(writes[0]);
  expect(writes[0]!.body).toEqual({ outputIds: ['output-2'], confirmationId: 'confirmed-2', exportProfile: 'FULL_HD_1080', explicitIntent: 'EXPORT' });
  await page.getByLabel('查看版本', { exact: true }).selectOption('revision');
  await expect(page.getByRole('region', { name: '正式成片导出', exact: true })).toContainText('固定 V2');
  await expect(page.getByRole('region', { name: '正式成片导出', exact: true })).toContainText('第二条正式输出 · 1080p');
  await page.getByRole('button', { name: '停止正式导出', exact: true }).click();
  await expect.poll(() => cancelled).toEqual(['/api/v1/tasks/export-task/cancel']);
});

test('historical unconfirmed versions require explicit confirmation before export', async ({ page }) => {
  let confirmed = false;
  await page.route('**/api/v1/revisions/revision/confirmations', async route => {
    if (route.request().method() === 'POST') { expect(route.request().postDataJSON()).toEqual({ explicitIntent: 'CONFIRM' }); confirmed = true; await route.fulfill({ json: { data: { confirmationId: 'confirmed' } } }); }
    else await route.fulfill({ json: { data: { items: [] } } });
  });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
  await expect(page.getByRole('button', { name: '导出正式成片', exact: true })).toBeDisabled();
  expect(confirmed).toBe(false);
  await page.getByRole('button', { name: '确认当前版本', exact: true }).click();
  await expect(page.getByRole('button', { name: '导出正式成片', exact: true })).toBeEnabled();
  expect(confirmed).toBe(true);
});

for (const state of ['UNAVAILABLE', 'REMOVED']) {
  test(`refresh restores ${state} files without treating a successful task as playable`, async ({ page }) => {
    const completed = { ...render, status: 'SUCCEEDED', artifactId: 'artifact' };
    await page.route('**/api/v1/revisions/revision/export-requests', route => route.fulfill({ json: { data: { items: [completed] } } }));
    await page.route('**/api/v1/render-requests/export', route => route.fulfill({ json: { data: completed } }));
    await page.route('**/api/v1/artifacts/artifact', route => route.fulfill({ json: { data: { state, storedBytes: 0 } } }));
    await page.goto('/?project=project');
    await page.getByRole('tab', { name: '作品', exact: true }).click();
    await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
    await expect(page.getByRole('region', { name: '正式成片导出', exact: true })).toContainText(state === 'UNAVAILABLE' ? '成片文件不可用' : '成片文件已删除');
    await expect(page.getByRole('button', { name: '播放正式成片', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '下载正式成片', exact: true })).toHaveCount(0);
  });
}

for (const viewport of [{ width: 1366, height: 768 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
  test(`works and usage stay usable without page overflow at ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.route('**/api/v1/projects/project/usage', route => route.fulfill({ json: { data: { ...usage, models: { ...usage.models, estimatedCostCny: 2.67844952 } } } }));
    await page.goto('/?project=project');
    await page.getByRole('tab', { name: '作品', exact: true }).click();
    await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
    await expect(page.getByText('待导出', { exact: true })).toBeVisible();
    mkdirSync(screenshots, { recursive: true });
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `${screenshots}/controlled-works-${viewport.width}x${viewport.height}.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole('button', { name: '用量', exact: true }).click();
    await expect(page.getByLabel('存储与模型用量', { exact: true })).toContainText('费用未知 1 次');
    await page.screenshot({ path: `${screenshots}/controlled-usage-summary-${viewport.width}x${viewport.height}.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole('button', { name: '关闭用量', exact: true }).click();
    await expect(page.getByRole('button', { name: '用量', exact: true })).toBeFocused();
    const operations: string[] = [];
    page.on('request', req => { if (req.method() !== 'GET' && !req.url().endsWith('/auth/csrf')) operations.push(req.url()); });
    await page.getByRole('button', { name: '诊断与高级维护', exact: true }).click();
    await page.getByRole('button', { name: '前往素材索引维护', exact: true }).click();
    await expect(page.getByLabel('维护素材', { exact: true })).toBeVisible();
    expect(operations).toEqual([]);
  });
}

test('a running export prevents duplicate submission while another resolution remains explicit', async ({ page }) => {
  const writes: unknown[] = [];
  await page.route('**/api/v1/revisions/revision/export-requests', async route => {
    if (route.request().method() === 'POST') { writes.push(route.request().postDataJSON()); await route.fulfill({ json: { data: render } }); }
    else await route.fulfill({ json: { data: { items: [render] } } });
  });
  await page.route('**/api/v1/render-requests/export', route => route.fulfill({ json: { data: render } }));
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
  await expect(page.getByRole('button', { name: '导出正式成片', exact: true })).toBeDisabled();
  expect(writes).toEqual([]);
  await page.getByLabel('成片导出清晰度', { exact: true }).selectOption('FULL_HD_1080');
  await expect(page.getByRole('button', { name: '导出正式成片', exact: true })).toBeEnabled();
  expect(writes).toEqual([]);
});

test('unrecognized export failure keeps its reason and offers no automatic resubmission', async ({ page }) => {
  const failed = { ...render, status: 'FAILED', error: { message: '存储暂时不可写，请稍后显式导出。' } };
  await page.route('**/api/v1/revisions/revision/export-requests', route => route.fulfill({ json: { data: { items: [failed] } } }));
  await page.route('**/api/v1/render-requests/export', route => route.fulfill({ json: { data: failed } }));
  const writes: string[] = [];
  page.on('request', req => { if (req.method() === 'POST' && req.url().endsWith('/export-requests')) writes.push(req.url()); });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
  await expect(page.getByRole('region', { name: '正式成片导出', exact: true }).getByRole('alert')).toHaveText('存储暂时不可写，请稍后显式导出。');
  expect(writes).toEqual([]);
});


test('single-version selection completes even while a background plan refresh is in flight', async ({ page }) => {
  let reads=0,release!:()=>void;
  const pending=new Promise<void>(resolve=>{release=resolve;});
  await page.route('**/api/v1/projects/project/plans',async route=>{
    if(++reads===2)await pending;
    await route.fulfill({json:{data:{items:[{id:'plan',title:'已确认作品',editingTaskId:null}]}}});
  });
  await page.goto('/?project=project');
  await page.getByRole('tab',{name:'作品',exact:true}).click();
  await expect.poll(()=>reads).toBeGreaterThanOrEqual(2);
  await page.getByLabel('查看方案',{exact:true}).selectOption('plan');
  await expect(page.getByRole('heading',{name:'V1 · 正式开场',exact:true})).toBeVisible();
  release();
  await expect(page.getByRole('button',{name:'导出正式成片',exact:true})).toBeEnabled();
});

test('expired formal playback renews the same artifact without submitting another export', async ({ page }) => {
  const completed={...render,status:'SUCCEEDED',artifactId:'artifact'};
  let accesses=0;
  const renders:unknown[]=[];
  await page.route('**/api/v1/revisions/revision/export-requests',async route=>{
    if(route.request().method()==='POST')renders.push(route.request().postDataJSON());
    await route.fulfill({json:{data:{items:[completed]}}});
  });
  await page.route('**/api/v1/render-requests/export',route=>route.fulfill({json:{data:completed}}));
  await page.route('**/api/v1/artifacts/artifact',route=>route.fulfill({json:{data:{state:'AVAILABLE',storedBytes:1024}}}));
  await page.route('**/api/v1/artifacts/artifact/playback-access',route=>route.fulfill({json:{data:{url:++accesses===1?'/expired-export.mp4':'/fresh-export.mp4'}}}));
  await page.route('**/expired-export.mp4',route=>route.fulfill({status:403,body:'expired'}));
  await page.route('**/fresh-export.mp4',route=>route.fulfill({contentType:'video/mp4',path:'e2e/fixtures/short-video.mp4'}));
  await page.goto('/?project=project');
  await page.getByRole('tab',{name:'作品',exact:true}).click();
  await page.getByLabel('查看方案',{exact:true}).selectOption('plan');
  await page.getByRole('button',{name:'播放正式成片',exact:true}).click();
  await expect.poll(()=>accesses).toBe(2);
  const player=page.getByLabel('正式成片播放器',{exact:true});
  await expect.poll(()=>player.evaluate((video:HTMLVideoElement)=>video.readyState)).toBeGreaterThanOrEqual(1);
  expect(renders).toEqual([]);
});

test('a saved-plan preview keeps its original output name when another plan is selected', async ({ page }) => {
  const other = { ...revision, id: 'other-revision', planId: 'other-plan', content: { outputs: [{ ...output, outputId: 'other-output', title: '另一个作品' }] } };
  await page.route('**/api/v1/projects/project/plans', route => route.fulfill({ json: { data: { items: [{ id: 'plan', title: '已确认作品' }, { id: 'other-plan', title: '另一方案' }] } } }));
  await page.route('**/api/v1/plans/other-plan/revisions', route => route.fulfill({ json: { data: { items: [other] } } }));
  await page.route('**/api/v1/revisions/other-revision', route => route.fulfill({ json: { data: other } }));
  const preview = { ...render, requestId: 'preview', status: 'RUNNING' };
  await page.route('**/api/v1/revisions/revision/previews', route => route.fulfill({ json: { data: preview } }));
  await page.route('**/api/v1/render-requests/preview', route => route.fulfill({ json: { data: preview } }));
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
  await page.getByRole('button', { name: '生成低清效果预览', exact: true }).click();
  const result = page.getByRole('region', { name: '低清效果预览', exact: true });
  await expect(result).toContainText('正式开场');
  await page.getByLabel('查看方案', { exact: true }).selectOption('other-plan');
  await expect(page.getByRole('heading', { name: 'V1 · 另一个作品', exact: true })).toBeVisible();
  await expect(result).toContainText('正式开场');
  await expect(result).not.toContainText('另一个作品');
});

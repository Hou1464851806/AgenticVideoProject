import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const screenshots = resolve(process.env.E2E_REDESIGN_SCREENSHOTS ?? 'test-results/frontend-redesign');
const binding = { messageId: 'draft-current', validationId: 'validation', editGeneration: 2, draftHash: 'a'.repeat(64), requirementsHash: 'b'.repeat(64) };
const draft = { id: 'draft-current', content: '当前草稿', normalizedContent: { outputs: [{ output_key: 'out-1', title: '开场短片', durationMs: 2000, render: { width: 1280, height: 720 }, clips: [{ assetId: 'asset', sourceInMs: 0, sourceOutMs: 1000, reason: '开场证据' }], subtitles: { cues: [] } }] } };
const baseSnapshot = { cursor: 'project:1', assetIds: ['asset'], latestTask: null, items: [{ id: 'draft-old', role: 'ASSISTANT', content: '较早的剪辑草稿', references: [{ type: 'EDIT_DRAFT', id: 'draft-old' }] }], agent: { currentProduction: { productionId: 'production', currentDraft: binding, frozen: false, requirements: { topic: '开场短片', profile: 'PORTRAIT_720', asset_ids: ['asset'], subtitles_enabled: false }, editGeneration: 2 }, currentDraft: draft, canAcceptDraft: true, canPreviewDraft: true, pendingQuestions: [] }, previews: { items: [] } };
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [] };
    if (path === '/auth/me') data = { user: { id: 'production-user', username: '制作测试' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects') data = { items: [{ id: 'project', name: '对话制作工作区' }], nextCursor: null };
    else if (path === '/projects/project') data = { id: 'project', name: '对话制作工作区' };
    else if (path.endsWith('/assets')) data = { items: [{ id: 'asset', filename: '讲话素材.mp4', validationStatus: 'VALID' }] };
    else if (path === '/projects/project/conversations') data = { items: [{ id: 'chat', title: '当前制作', assetIds: ['asset'] }] };
    else if (path === '/conversations/chat/snapshot') data = baseSnapshot;
    else if (path === '/conversations/chat/messages') data = { view: { planId: null, revisionId: null } };
    else if (path === '/projects/project/snapshot') data = { cursor: 'project:1' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    else if (route.request().method() !== 'GET') { await route.fulfill({ status: 400, json: { error: { message: '未定义的制作操作' } } }); return; }
    await route.fulfill({ json: { data } });
  });
});

test('desktop shows conversation beside one current draft with named source clips', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  const chat = page.getByRole('tabpanel', { name: '对话', exact: true });
  const preview = page.getByRole('tabpanel', { name: '预览', exact: true });
  await expect(chat).toBeVisible();
  await expect(preview).toBeVisible();
  await expect(preview.getByRole('region', { name: '剪辑草稿', exact: true })).toContainText('开场短片');
  await expect(preview).toContainText('讲话素材.mp4');
  await expect(page.getByRole('button', { name: '确认全部输出', exact: true })).toHaveCount(1);
  const left = await chat.boundingBox(), right = await preview.boundingBox();
  expect(left!.x + left!.width).toBeLessThanOrEqual(right!.x);
  mkdirSync(screenshots, { recursive: true });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: `${screenshots}/controlled-production-1366x768.png`, fullPage: true });
});

for (const viewport of [{ width: 768, height: 1024 }, { width: 390, height: 844 }]) {
  test(`narrow conversation and preview preserve input and named references at ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto('/?project=project');
    await page.getByRole('tab', { name: '制作', exact: true }).click();
    await page.getByLabel('对话输入', { exact: true }).fill('保留尚未发送的中文');
    await page.getByText('引用素材', { exact: true }).click();
    await page.getByLabel('讲话素材.mp4', { exact: true }).check();
    await expect(page.getByLabel('本轮引用', { exact: true })).toContainText('讲话素材.mp4');
    mkdirSync(screenshots, { recursive: true });
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `${screenshots}/controlled-production-chat-${viewport.width}x${viewport.height}.png`, fullPage: true });
    await page.getByRole('tab', { name: '预览', exact: true }).click();
    await expect(page.getByRole('tabpanel', { name: '预览', exact: true })).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `${screenshots}/controlled-production-preview-${viewport.width}x${viewport.height}.png`, fullPage: true });
    await expect(page.getByLabel('对话输入', { exact: true })).toBeHidden();
    await page.getByLabel('对话输入', { exact: true }).evaluate((node: HTMLTextAreaElement) => node.focus());
    await expect(page.getByLabel('对话输入', { exact: true })).not.toBeFocused();
    await page.getByRole('tab', { name: '素材', exact: true }).click();
    await page.getByRole('tab', { name: '制作', exact: true }).click();
    await expect(page.getByRole('region', { name: '剪辑草稿', exact: true })).toContainText('开场短片');
    await page.getByRole('tab', { name: '对话', exact: true }).click();
    await expect(page.getByLabel('对话输入', { exact: true })).toHaveValue('保留尚未发送的中文');
    await expect(page.getByLabel('讲话素材.mp4', { exact: true })).toBeChecked();
    await page.getByRole('button', { name: '移除引用 讲话素材.mp4', exact: true }).click();
    await expect(page.getByLabel('讲话素材.mp4', { exact: true })).not.toBeChecked();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

test('Chinese composition blocks submit shortcuts and committed text sends unchanged', async ({ page }) => {
  const turns: unknown[] = [];
  await page.route('**/api/v1/conversations/chat/turns', async route => { turns.push(route.request().postDataJSON()); await route.fulfill({ json: { data: { taskId: 'agent' } } }); });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  const input = page.getByLabel('对话输入', { exact: true });
  await input.fill('正在组词的中文');
  await input.dispatchEvent('compositionstart');
  await input.press('Control+Enter');
  expect(turns).toEqual([]);
  await input.dispatchEvent('compositionend');
  await input.press('Control+Enter');
  await expect.poll(() => turns).toEqual([{ content: '正在组词的中文', references: [] }]);
});

test('a lost preview response keeps the same immutable request through main-view switches', async ({ page }) => {
  const requests: { key: string; body: unknown }[] = [];
  await page.route('**/api/v1/conversations/chat/drafts/previews', async route => {
    requests.push({ key: route.request().headers()['idempotency-key']!, body: route.request().postDataJSON() });
    if (requests.length === 1) await route.abort('failed');
    else await route.fulfill({ json: { data: { requestId: 'preview' } } });
  });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByRole('button', { name: '预览草稿', exact: true }).click();
  await expect(page.getByRole('region', { name: '剪辑草稿', exact: true }).getByRole('alert')).toBeVisible();
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByRole('button', { name: '预览草稿', exact: true }).click();
  await expect.poll(() => requests.length).toBe(2);
  expect(requests[0]).toEqual(requests[1]);
  expect(requests[0]!.body).toEqual({ draft: binding, outputKey: 'out-1', previewProfile: 'LOW_360' });
});

test('historical drafts open readonly original text without fabricating structured content', async ({ page }) => {
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  const old = page.getByRole('article', { name: '助手答复', exact: true }).filter({ hasText: '较早的剪辑草稿' });
  await old.getByRole('button', { name: '查看草稿', exact: true }).click();
  const details = page.getByRole('region', { name: '历史草稿详情', exact: true });
  await expect(details).toContainText('较早的剪辑草稿');
  await expect(details).toContainText('不能恢复编辑或重新确认');
  await expect(page.getByRole('button', { name: '确认全部输出', exact: true })).toHaveCount(0);
});

test('one preview request merges its published artifact while standalone history keeps its source', async ({ page }) => {
  const accesses: string[] = [];
  await page.route('**/api/v1/conversations/chat/snapshot', route => route.fulfill({ json: { data: { ...baseSnapshot, items: [
    { id: 'published', role: 'ASSISTANT', content: '当前预览已完成', references: [{ type: 'PREVIEW_RESULT', id: 'artifact-current', sourceDraftMessageId: draft.id, outputKey: 'out-1' }] },
    { id: 'history', role: 'ASSISTANT', content: '历史预览', references: [{ type: 'PREVIEW_RESULT', id: 'artifact-old', sourceDraftMessageId: 'draft-old', outputKey: 'out-2' }] },
  ], previews: { items: [{ requestId: 'preview-current', taskId: 'media', sourceDraftMessageId: draft.id, outputKey: 'out-1', status: 'SUCCEEDED', artifactId: 'artifact-current', artifactState: 'AVAILABLE' }] } } } }));
  await page.route('**/api/v1/artifacts/*/playback-access', async route => { accesses.push(new URL(route.request().url()).pathname); await route.fulfill({ json: { data: { url: '/playable.mp4' } } }); });
  await page.route('**/playable.mp4', route => route.fulfill({ contentType: 'video/mp4', path: 'e2e/fixtures/short-video.mp4' }));
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByRole('button', { name: '预览记录', exact: true }).click();
  const cards = page.getByRole('article', { name: '预览请求', exact: true });
  await expect(cards).toHaveCount(2);
  await expect(cards.first()).toContainText('开场短片');
  await expect(cards.last()).toContainText('历史草稿预览');
  await cards.last().getByRole('button', { name: '播放草稿预览', exact: true }).click();
  await expect.poll(() => accesses).toEqual(['/api/v1/artifacts/artifact-old/playback-access']);
  await expect(cards.getByRole('button', { name: '播放草稿预览', exact: true })).toHaveCount(1);
});

test('reading older messages keeps scroll position and offers an explicit new-message jump', async ({ page }) => {
  let updated = false;
  const messages = Array.from({ length: 40 }, (_, index) => ({ id: `message-${index}`, role: 'ASSISTANT', content: `已有讨论 ${index}：保留完整的聊天历史供回看。`, references: [] }));
  await page.route('**/api/v1/conversations/chat/snapshot', route => route.fulfill({ json: { data: { ...baseSnapshot, cursor: `project:${updated ? 2 : 1}`, items: updated ? [...messages, { id: 'new', role: 'ASSISTANT', content: '新答复', references: [] }] : messages } } }));
  await page.route('**/api/v1/conversations/chat/drafts/previews', async route => { updated = true; await route.fulfill({ json: { data: { requestId: 'preview' } } }); });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  const log = page.getByRole('log', { name: '对话记录', exact: true });
  await expect(page.getByRole('button', { name: '预览草稿', exact: true })).toBeEnabled();
  await log.evaluate(node => { node.scrollTop = 0; node.dispatchEvent(new Event('scroll')); });
  await page.getByRole('button', { name: '预览草稿', exact: true }).click();
  await expect(page.getByRole('button', { name: '有新消息，回到底部', exact: true })).toBeVisible();
  expect(await log.evaluate(node => node.scrollTop)).toBe(0);
  await page.getByRole('button', { name: '有新消息，回到底部', exact: true }).click();
  await expect.poll(() => log.evaluate(node => node.scrollTop + node.clientHeight >= node.scrollHeight - 1)).toBe(true);
});

test('confirmation works without a preview and never requests an export', async ({ page }) => {
  const writes: { path: string; body: unknown }[] = [];
  let frozen = false;
  await page.route('**/api/v1/conversations/chat/snapshot', route => route.fulfill({ json: { data: { ...baseSnapshot, cursor: `project:${frozen ? 2 : 1}`, agent: { ...baseSnapshot.agent, currentProduction: { ...baseSnapshot.agent.currentProduction, frozen }, canAcceptDraft: !frozen, canPreviewDraft: !frozen } } } }));
  await page.route('**/api/v1/conversations/chat/drafts/accept', async route => { writes.push({ path: 'accept', body: route.request().postDataJSON() }); frozen = true; await route.fulfill({ json: { data: {} } }); });
  page.on('request', request => { if (/\/export-requests$|\/drafts\/previews$/.test(new URL(request.url()).pathname) && request.method() === 'POST') writes.push({ path: 'unexpected', body: request.postDataJSON() }); });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByRole('button', { name: '确认全部输出', exact: true }).click();
  await expect(page.getByRole('button', { name: '确认全部输出', exact: true })).toBeDisabled();
  await expect.poll(() => writes).toEqual([{ path: 'accept', body: binding }]);
  await expect(page.getByRole('region', { name: '剪辑草稿', exact: true })).toContainText('此制作已确认封存');
  await expect(page.getByLabel('对话输入', { exact: true })).toBeEnabled();
});

for (const status of ['FAILED', 'CANCELLED']) {
  test(`a new edit generation keeps the old draft readonly after ${status}`, async ({ page }) => {
    await page.route('**/api/v1/conversations/chat/snapshot', route => route.fulfill({ json: { data: { ...baseSnapshot, latestTask: { taskId: 'new-task', status, phase: 'VALIDATING', failure: status === 'FAILED' ? { code: 'BUDGET_EXHAUSTED', message: '预算已耗尽', recoverable: false } : undefined }, agent: { ...baseSnapshot.agent, currentProduction: { ...baseSnapshot.agent.currentProduction, editGeneration: 3 }, canAcceptDraft: false, canPreviewDraft: false } } } }));
    await page.goto('/?project=project');
    await page.getByRole('tab', { name: '制作', exact: true }).click();
    await expect(page.getByRole('button', { name: '确认全部输出', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: '预览草稿', exact: true })).toBeDisabled();
    await expect(page.getByRole('region', { name: '剪辑草稿', exact: true })).toContainText('失败或停止不会恢复旧稿资格');
    await expect(page.getByRole('button', { name: '重试原需求', exact: true })).toHaveCount(0);
    await expect(page.getByRole('status').filter({ hasText: status === 'FAILED' ? /^失败$/ : /^已停止$/ })).toBeVisible();
  });
}

test('draft source clips open beside the preserved conversation and return to the same draft', async ({ page }) => {
  await page.route('**/api/v1/assets/asset/playback-access', route => route.fulfill({ json: { data: { assetId: 'asset', filename: '讲话素材.mp4', url: '/source.mp4', durationMs: 1000, expiresAt: '2099-01-01' } } }));
  await page.route('**/api/v1/assets/asset', route => route.fulfill({ json: { data: { mediaInfo: { streams: [] }, timeMapping: { hasAudio: false } } } }));
  await page.route('**/api/v1/assets/asset/evidence', route => route.fulfill({ json: { data: { observations: [], units: [], summary: {} } } }));
  await page.route('**/source.mp4', route => route.fulfill({ contentType: 'video/mp4', path: 'e2e/fixtures/short-video.mp4' }));
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByLabel('对话输入', { exact: true }).fill('核查片段后继续修改');
  await page.getByText('片段与选取依据', { exact: true }).click();
  await page.getByRole('button', { name: '回看片段 1', exact: true }).click();
  await expect(page.getByRole('tab', { name: '制作', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('tabpanel', { name: '预览', exact: true }).getByLabel('视频播放器', { exact: true })).toBeVisible();
  await expect(page.getByLabel('对话输入', { exact: true })).toHaveValue('核查片段后继续修改');
  await page.getByRole('button', { name: '当前草稿', exact: true }).click();
  await page.getByRole('button', { name: '回看片段 1', exact: true }).click();
  await expect(page.getByRole('region', { name: '原片回看', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '返回草稿', exact: true }).click();
  await expect(page.getByRole('button', { name: '确认全部输出', exact: true })).toHaveCount(1);
  await expect(page.getByRole('region', { name: '剪辑草稿', exact: true })).toContainText('开场短片');
});

test('named plan references send only the existing revision and output identity', async ({ page }) => {
  let sent: unknown;
  await page.route('**/api/v1/projects/project/plans', route => route.fulfill({ json: { data: { items: [{ id: 'plan', title: '正式短片', editingTaskId: null }] } } }));
  await page.route('**/api/v1/plans/plan/revisions', route => route.fulfill({ json: { data: { items: [{ id: 'revision', revisionNumber: 1 }] } } }));
  await page.route('**/api/v1/revisions/revision', route => route.fulfill({ json: { data: { id: 'revision', planId: 'plan', revisionNumber: 1, content: { outputs: [{ outputId: 'output', title: '正式短片', durationMs: 1000, render: { width: 1280, height: 720 }, subtitles: { cues: [] }, clips: [] }] } } } }));
  await page.route('**/api/v1/conversations/chat/messages', route => route.fulfill({ json: { data: { view: { planId: null, revisionId: null, rowVersion: 0 } } } }));
  await page.route('**/api/v1/conversations/chat/view', route => route.fulfill({ json: { data: {} } }));
  await page.route('**/api/v1/conversations/chat/turns', async route => { sent = route.request().postDataJSON(); await route.fulfill({ json: { data: { taskId: 'agent' } } }); });
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '作品', exact: true }).click();
  await page.getByLabel('查看方案', { exact: true }).selectOption('plan');
  await expect(page.getByRole('heading', { name: 'V1 · 正式短片', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '引用成片 1', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#panel-conversation')).toBeFocused();
  await expect(page.getByLabel('本轮引用', { exact: true })).toContainText('V1 · 正式短片');
  await page.getByLabel('对话输入', { exact: true }).fill('讨论这条输出');
  await page.getByRole('button', { name: '发送需求', exact: true }).click();
  await expect.poll(() => sent).toEqual({ content: '讨论这条输出', references: [{ type: 'PLAN_REVISION', id: 'revision', outputId: 'output' }] });
});

// Deliberately resolve the older access after the latest source choice.
test('latest source choice wins when playback access responses arrive out of order', async ({ page }) => {
  let releaseFirst!: () => void;
  const first = new Promise<void>(resolve => { releaseFirst = resolve; });
  let calls = 0;
  await page.route('**/api/v1/assets/asset/playback-access', async route => {
    const call = ++calls;
    if (call === 1) await first;
    await route.fulfill({ json: { data: { assetId: 'asset', filename: `选择 ${call}`, url: `/source-${call}.mp4`, durationMs: 1000, expiresAt: '2099-01-01' } } });
  });
  await page.route('**/api/v1/assets/asset', route => route.fulfill({ json: { data: { mediaInfo: { streams: [] }, timeMapping: { hasAudio: false } } } }));
  await page.route('**/source-*.mp4', route => route.fulfill({ contentType: 'video/mp4', path: 'e2e/fixtures/short-video.mp4' }));
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByText('片段与选取依据', { exact: true }).click();
  await page.getByRole('button', { name: '回看片段 1', exact: true }).click();
  await expect.poll(() => calls).toBe(1);
  await page.getByRole('button', { name: '回看片段 1', exact: true }).click();
  await expect(page.getByRole('region', { name: '原片回看', exact: true })).toContainText('选择 2');
  releaseFirst();
  await expect(page.getByLabel('视频播放器', { exact: true })).toHaveAttribute('src', '/source-2.mp4');
});

test('a chat created after importing assets uses current filenames without a page reload', async ({ page }) => {
  let imported = false;
  await page.route('**/api/v1/projects/project/assets', route => route.fulfill({ json: { data: { items: imported ? [{ id: 'asset', filename: '刚导入的素材.mp4', validationStatus: 'VALID' }] : [] } } }));
  await page.route('**/api/v1/projects/project/conversations', route => route.fulfill({ json: { data: route.request().method() === 'POST' ? { id: 'chat', title: '新制作', assetIds: ['asset'] } : { items: [] } } }));
  await page.goto('/?project=project');
  // The import has completed at the HTTP boundary while the chat panel stays mounted.
  await expect(page.getByRole('button', { name: '新聊天', exact: true, includeHidden: true })).toBeEnabled();
  imported = true;
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await page.getByRole('button', { name: '新聊天', exact: true }).click();
  await page.getByText('引用素材', { exact: true }).click();
  await expect(page.getByLabel('刚导入的素材.mp4', { exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: '剪辑草稿', exact: true })).toContainText('刚导入的素材.mp4');
});

test('a completed reply shows its terminal status instead of the retained model phase', async ({ page }) => {
  await page.route('**/api/v1/conversations/chat/snapshot', route => route.fulfill({ json: { data: { ...baseSnapshot, latestTask: { taskId: 'finished', status: 'SUCCEEDED', phase: 'VALIDATING' } } } }));
  await page.goto('/?project=project');
  await page.getByRole('tab', { name: '制作', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: /^已完成$/ })).toBeVisible();
  await expect(page.getByText('正在验证草稿', { exact: true })).toHaveCount(0);
});

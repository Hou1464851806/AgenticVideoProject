import { test, expect } from '@playwright/test';

test('plain chat sends text and references and restores the published answer', async ({ page }) => {
  const messages: unknown[] = [];
  let sent: unknown;
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [] };
    if (path === '/auth/me') data = { user: { id: 'user', username: '浏览器测试' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects') data = { items: [{ id: 'project', name: '生命周期' }], nextCursor: null };
    else if (path === '/projects/project') data = { id: 'project', name: '生命周期' };
    else if (path.endsWith('/assets')) data = { items: [{ id: 'asset', filename: '素材.mp4' }] };
    else if (path.endsWith('/plans')) data = { items: [] };
    else if (path === '/projects/project/conversations') data = { items: [{ id: 'chat', title: '新聊天', assetIds: ['asset'], naturalLanguage: true }] };
    else if (path === '/conversations/chat/turns') {
      sent = route.request().postDataJSON();
      if (JSON.stringify(sent) !== JSON.stringify({ content: '先讨论剪辑思路', references: [] })) {
        await route.fulfill({ status: 400, json: { error: { message: '新版对话通过原文和引用表达需求' } } }); return;
      }
      messages.push({ id: 'answer', role: 'ASSISTANT', content: '先确定故事主线，再选择片段。', references: [] });
      data = { taskId: 'agent' };
    } else if (path === '/conversations/chat/snapshot') data = { cursor: `project:${messages.length}`, assetIds: ['asset'], items: messages, latestTask: null, agent: { currentProduction: null, currentDraft: null, canAcceptDraft: false, canPreviewDraft: false, pendingQuestions: [] }, previews: { items: [] } };
    else if (path === '/conversations/chat/messages') data = { view: { planId: null, revisionId: null } };
    else if (path === '/projects/project/snapshot') data = { cursor: 'project:0' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    await route.fulfill({ json: { data } });
  });
  await page.goto('/?project=project');
  await expect(page.getByLabel('需求类型', { exact: true })).toHaveCount(0);
  await page.getByLabel('对话输入', { exact: true }).fill('先讨论剪辑思路');
  await page.getByRole('button', { name: '发送需求', exact: true }).click();
  await expect(page.getByRole('article', { name: '助手答复', exact: true })).toContainText('先确定故事主线');
  expect(sent).toEqual({ content: '先讨论剪辑思路', references: [] });
  await page.reload();
  await expect(page.getByRole('article', { name: '助手答复', exact: true })).toContainText('先确定故事主线');
  await page.getByText('引用素材', { exact: true }).click();
  await page.getByLabel('素材.mp4', { exact: true }).check();
  await page.getByLabel('对话输入', { exact: true }).fill('先讨论剪辑思路');
  await page.getByRole('button', { name: '发送需求', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: '新版对话通过原文和引用表达需求' })).toBeVisible();
  await page.getByLabel('素材.mp4', { exact: true }).uncheck();
  await page.getByRole('button', { name: '发送需求', exact: true }).click();
  await expect(page.getByRole('article', { name: '助手答复', exact: true })).toHaveCount(2);
  expect(sent).toEqual({ content: '先讨论剪辑思路', references: [] });
});

test('multi-output draft previews one selected output and accepts the whole seen draft', async ({ page }) => {
  const binding = { messageId: 'draft-a', validationId: 'validation-a', editGeneration: 1, draftHash: 'a'.repeat(64), requirementsHash: 'b'.repeat(64) };
  const previewBodies: unknown[] = [], acceptBodies: unknown[] = [];
  let frozen = false;
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [] };
    if (path === '/auth/me') data = { user: { id: 'user', username: '浏览器测试' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects') data = { items: [{ id: 'project', name: '生命周期' }] };
    else if (path === '/projects/project') data = { id: 'project', name: '生命周期' };
    else if (path.endsWith('/assets') || path.endsWith('/plans')) data = { items: [] };
    else if (path === '/projects/project/conversations') data = { items: [{ id: 'chat', title: '草稿聊天', assetIds: [] }] };
    else if (path === '/conversations/chat/drafts/previews') { previewBodies.push(route.request().postDataJSON()); data = { requestId: 'preview-a' }; }
    else if (path === '/conversations/chat/drafts/accept') { acceptBodies.push(route.request().postDataJSON()); frozen = true; }
    else if (path === '/conversations/chat/snapshot') data = {
      cursor: `project:${frozen ? 2 : 1}`, assetIds: [], items: [], latestTask: null, previews: { items: [] },
      agent: { currentProduction: { productionId: 'production', currentDraft: binding, frozen, requirements: { topic: '两条短片' }, editGeneration: 1 },
        currentDraft: { id: 'draft-a', content: '双输出草稿', normalizedContent: { schemaVersion: '1.0', outputs: [
          { output_key: 'out-1', title: '横屏开场', durationMs: 2000, render: { width: 1280, height: 720 }, clips: [], subtitles: { cues: [] } },
          { output_key: 'out-2', title: '竖屏结尾', durationMs: 3000, render: { width: 720, height: 1280 }, clips: [], subtitles: { cues: [] } },
        ] } }, canAcceptDraft: !frozen, canPreviewDraft: !frozen, pendingQuestions: [] },
    };
    else if (path === '/conversations/chat/messages') data = { view: { planId: null, revisionId: null } };
    else if (path === '/projects/project/snapshot') data = { cursor: 'project:0' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    await route.fulfill({ json: { data } });
  });
  await page.goto('/?project=project');
  // Current draft is not in the message window; its structured snapshot must still be operable.
  await expect(page.getByRole('region', { name: '剪辑草稿', exact: true })).toContainText('竖屏结尾');
  await page.getByRole('button', { name: '预览草稿', exact: true }).click();
  expect(previewBodies).toHaveLength(0);
  await page.getByLabel('选择预览输出', { exact: true }).selectOption({ label: '竖屏结尾' });
  await page.getByRole('button', { name: '预览所选输出', exact: true }).click();
  await expect.poll(() => previewBodies.length).toBe(1);
  expect(previewBodies[0]).toEqual({ draft: binding, outputKey: 'out-2', previewProfile: 'LOW_360' });
  await page.getByRole('button', { name: '确认全部输出', exact: true }).click();
  await expect.poll(() => acceptBodies.length).toBe(1);
  expect(acceptBodies[0]).toEqual(binding);
  await expect(page.getByRole('button', { name: '确认全部输出', exact: true })).toBeDisabled();
  await page.reload();
  await expect(page.getByRole('button', { name: '预览草稿', exact: true })).toBeDisabled();
});

test('Agent stop and media stop stay independent and historical retry stays read-only', async ({ page }) => {
  let agentStatus = 'RUNNING', previewStatus = 'RUNNING', current = true;
  const cancelled: string[] = [];
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [] };
    if (path === '/auth/me') data = { user: { id: 'user', username: '浏览器测试' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects') data = { items: [{ id: 'project', name: '生命周期' }] };
    else if (path === '/projects/project') data = { id: 'project', name: '生命周期' };
    else if (path.endsWith('/assets') || path.endsWith('/plans')) data = { items: [] };
    else if (path === '/projects/project/conversations') data = { items: [{ id: 'chat', title: '草稿聊天', assetIds: [] }] };
    else if (path.endsWith('/cancel')) {
      cancelled.push(path);
      if (path === '/tasks/agent/cancel') agentStatus = 'CANCELLED';
      if (path === '/tasks/media/cancel') previewStatus = 'CANCELLED';
    } else if (path === '/conversations/chat/snapshot') data = {
      cursor: `project:${cancelled.length}`, assetIds: [], items: [], latestTask: { taskId: 'agent', status: agentStatus },
      agent: { currentProduction: null, currentDraft: null, canAcceptDraft: false, canPreviewDraft: current, pendingQuestions: [] },
      previews: { items: [{ requestId: 'preview', taskId: 'media', sourceDraftMessageId: 'old', outputKey: 'out-1', status: previewStatus }] },
    };
    else if (path === '/conversations/chat/messages') data = { view: { planId: null, revisionId: null } };
    else if (path === '/projects/project/snapshot') data = { cursor: 'project:0' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    await route.fulfill({ json: { data } });
  });
  await page.goto('/?project=project');
  await page.getByRole('button', { name: '停止回复', exact: true }).click();
  await expect(page.getByRole('button', { name: '停止此预览', exact: true })).toBeVisible();
  await expect.poll(() => cancelled).toEqual(['/tasks/agent/cancel']);
  await page.getByRole('button', { name: '停止此预览', exact: true }).click();
  await expect(page.getByRole('button', { name: '重试此预览', exact: true })).toBeDisabled();
  expect(cancelled).toEqual(['/tasks/agent/cancel', '/tasks/media/cancel']);
  current = false;
  await page.reload();
  await expect(page.getByRole('button', { name: '重试此预览', exact: true })).toBeDisabled();
  expect(cancelled).toHaveLength(2);
});

test('expired chat playback renews access to the same artifact without rendering again', async ({ page }) => {
  let accesses = 0, renders = 0;
  await page.route('**/expired.mp4', route => route.fulfill({ status: 403, body: 'expired' }));
  await page.route('**/playable.mp4', route => route.fulfill({ contentType: 'video/mp4', path: 'e2e/fixtures/short-video.mp4' }));
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [] };
    if (path === '/auth/me') data = { user: { id: 'user', username: '浏览器测试' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects') data = { items: [{ id: 'project', name: '生命周期' }] };
    else if (path === '/projects/project') data = { id: 'project', name: '生命周期' };
    else if (path.endsWith('/assets') || path.endsWith('/plans')) data = { items: [] };
    else if (path === '/projects/project/conversations') data = { items: [{ id: 'chat', title: '历史播放', assetIds: [] }] };
    else if (path === '/conversations/chat/drafts/previews') { renders++; }
    else if (path === '/artifacts/artifact-a/playback-access') { accesses++; data = { url: accesses === 1 ? '/expired.mp4' : '/playable.mp4' }; }
    else if (path === '/conversations/chat/snapshot') data = {
      cursor: 'project:1', assetIds: [], latestTask: null, previews: { items: [] },
      items: [{ id: 'result', role: 'ASSISTANT', content: '预览已完成', references: [{ type: 'PREVIEW_RESULT', id: 'artifact-a', sourceDraftMessageId: 'draft-a', outputKey: 'out-2' }] }],
      agent: { currentProduction: null, currentDraft: null, canAcceptDraft: false, canPreviewDraft: false, pendingQuestions: [] },
    };
    else if (path === '/conversations/chat/messages') data = { view: { planId: null, revisionId: null } };
    else if (path === '/projects/project/snapshot') data = { cursor: 'project:0' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    await route.fulfill({ json: { data } });
  });
  await page.goto('/?project=project');
  await page.getByRole('button', { name: '播放草稿预览', exact: true }).click();
  await expect.poll(() => page.getByLabel('聊天草稿预览播放器', { exact: true }).evaluate((node: HTMLVideoElement) => node.readyState)).toBeGreaterThanOrEqual(1);
  expect(accesses).toBe(2);
  expect(renders).toBe(0);
});

test('confirmed artifact loss offers an explicit retry only for the current draft', async ({ page }) => {
  let unavailable = false, retries = 0;
  const binding = { messageId: 'draft-a', validationId: 'validation-a', editGeneration: 1, draftHash: 'a'.repeat(64), requirementsHash: 'b'.repeat(64) };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = { items: [] };
    if (path === '/auth/me') data = { user: { id: 'user', username: '浏览器测试' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects') data = { items: [{ id: 'project', name: '生命周期' }] };
    else if (path === '/projects/project') data = { id: 'project', name: '生命周期' };
    else if (path === '/projects/project/conversations') data = { items: [{ id: 'chat', title: '产物失效', assetIds: [] }] };
    else if (path === '/artifacts/artifact-a/playback-access') {
      unavailable = true;
      await route.fulfill({ status: 410, json: { error: { message: '预览产物不可用，请手动重新生成' } } }); return;
    } else if (path === '/conversations/chat/drafts/previews/preview-a/retry') { retries++; }
    else if (path === '/conversations/chat/snapshot') data = {
      cursor: 'project:1', assetIds: [], latestTask: null,
      items: [{ id: 'result', role: 'ASSISTANT', content: '预览已完成', references: [{ type: 'PREVIEW_RESULT', id: 'artifact-a', sourceDraftMessageId: 'draft-a', outputKey: 'out-1' }] }],
      previews: { items: [{ requestId: 'preview-a', taskId: 'media', sourceDraftMessageId: 'draft-a', outputKey: 'out-1', status: 'SUCCEEDED', artifactId: 'artifact-a', artifactState: unavailable ? 'UNAVAILABLE' : 'AVAILABLE' }] },
      agent: { currentProduction: { productionId: 'production', currentDraft: binding, frozen: false, requirements: {}, editGeneration: 1 }, currentDraft: { id: 'draft-a', content: '当前草稿', normalizedContent: { outputs: [{ output_key: 'out-1', title: '短片', durationMs: 2000, render: { width: 1280, height: 720 }, clips: [], subtitles: { cues: [] } }] } }, canAcceptDraft: true, canPreviewDraft: true, pendingQuestions: [] },
    };
    else if (path === '/conversations/chat/messages') data = { view: { planId: null, revisionId: null } };
    else if (path === '/projects/project/snapshot') data = { cursor: 'project:0' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    await route.fulfill({ json: { data } });
  });
  await page.goto('/?project=project');
  await expect(page.getByRole('button', { name: '重试此预览', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '播放草稿预览', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('预览产物不可用');
  await expect(page.getByRole('button', { name: '重试此预览', exact: true })).toBeEnabled();
  expect(retries).toBe(0);
  await page.getByRole('button', { name: '重试此预览', exact: true }).click();
  await expect.poll(() => retries).toBe(1);
});

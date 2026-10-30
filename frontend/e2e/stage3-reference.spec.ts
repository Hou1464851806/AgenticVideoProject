import { test, expect } from '@playwright/test';

// Current shell only; every API response is controlled. No uploads, model calls,
// preview/confirm/export writes or question answers reach the real services.
test('searchable references preserve their request scope', async ({ page }) => {
  let previewRequested = false;
  let turnBody: any;
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url());
    const path = url.pathname.replace('/api/v1', '');
    const method = route.request().method();
    let data: unknown;
    let status = 200;
    if (path === '/auth/me') data = { user: { id: 'redesign-user', username: '新版工作台' } };
    else if (path === '/auth/csrf') data = { csrfToken: 'csrf' };
    else if (path === '/projects') data = { items: [{ id: 'baseline', name: '草稿布局测试', rowVersion: 0 }], nextCursor: null };
    else if (path === '/projects/baseline') data = { id: 'baseline', name: '草稿布局测试', rowVersion: 0 };
    else if (path === '/projects/baseline/snapshot') data = { cursor: 'baseline:1' };
    else if (path === '/projects/baseline/assets') data = { items: [{ id: 'asset', filename: '讲话素材.mp4', validationStatus: 'VALID' }, { id: 'asset2', filename: '演示素材.mp4', validationStatus: 'VALID' }] };
    else if (path === '/projects/baseline/uploads') data = { items: [], nextCursor: null };
    else if (path === '/projects/baseline/plans') data = { items: [] };
    else if (path === '/projects/baseline/conversations') data = { items: [{ id: 'chat', title: '两条输出草稿', assetIds: ['asset'] }] };
    else if (path === '/conversations/chat/turns' && method === 'POST') { turnBody = route.request().postDataJSON(); data = {}; }
    else if (path === '/conversations/chat/messages') data = { view: { planId: null, revisionId: null } };
    else if (path === '/conversations/chat/drafts/previews' && method === 'POST') {
      previewRequested = true; status = 202; data = { requestId: 'preview-1' };
    } else if (path === '/conversations/chat/snapshot') data = {
      cursor: 'baseline:1',
      assetIds: ['asset'],
      items: [{ id: 'welcome', role: 'ASSISTANT', content: '我已根据素材整理出一版双输出草稿，请确认镜头和字幕。', references: [{ type: 'EDIT_DRAFT', id: 'draft-current' }] }],
      latestTask: { taskId: 'task-q', status: 'WAITING_INPUT' },
      agent: {
        currentProduction: {
          productionId: 'production',
          currentDraft: { messageId: 'draft-current', validationId: 'validation-current', editGeneration: 2, draftHash: 'a'.repeat(64), requirementsHash: 'b'.repeat(64) },
          frozen: false,
          requirements: { topic: '双输出讲解', profile: 'PORTRAIT_720', subtitles_enabled: true },
        },
        currentDraft: {
          id: 'draft-current',
          content: '当前双输出草稿',
          normalizedContent: {
            outputs: ['横屏开场', '竖屏结尾'].map((title, index) => ({
              output_key: `out-${index + 1}`,
              title,
              durationMs: 2000,
              render: index ? { width: 720, height: 1280 } : { width: 1280, height: 720 },
              clips: [{ assetId: 'asset', sourceInMs: 0, sourceOutMs: 2000, reason: '已有语音证据' }],
              subtitles: { cues: [{ startMs: 0, endMs: 1900, text: '已有字幕' }] },
            })),
          },
        },
        canAcceptDraft: true,
        canPreviewDraft: true,
        pendingQuestions: [],
      },
      previews: {
        items: previewRequested
          ? [{ requestId: 'preview-1', taskId: 'media-1', sourceDraftMessageId: 'draft-current', outputKey: 'out-1', status: 'RUNNING' }]
          : [],
      },
      questions: [{ id: 'question-1', taskId: 'task-q', turnId: 'turn-1', prompt: '新制作需要多少秒？', reason: '时长尚未确定', fields: ['duration'], status: 'OPEN' }],
    };
    else if (path.endsWith('/events')) {
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: ': heartbeat\n\n' });
      return;
    } else data = { items: [] };
    await route.fulfill({ status, json: { data } });
  });

  await page.goto('/?project=baseline');
  const selector = page.getByLabel('引用素材', { exact: true });
  await page.locator('.asset-choices .n-select').click();
  await expect(selector).toBeVisible();
  await selector.fill('讲话');
  await page.getByText('讲话素材.mp4', { exact: true }).last().click();
  await selector.fill('演示');
  await page.getByText('演示素材.mp4', { exact: true }).last().click();
  await expect(page.locator('.reference-chips')).toContainText('讲话素材.mp4');
  await expect(page.locator('.reference-chips')).toContainText('演示素材.mp4');
  await page.getByRole('button', { name: '移除引用 讲话素材.mp4' }).click();
  await page.getByLabel('对话输入').fill('继续处理');
  await page.getByRole('button', { name: '发送需求' }).click();
  await expect.poll(() => turnBody).toBeTruthy();
  expect(turnBody.references).toEqual([{ type: 'ASSET', id: 'asset2' }]);
});

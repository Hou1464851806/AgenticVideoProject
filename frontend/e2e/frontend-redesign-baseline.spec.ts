import { test, expect } from '@playwright/test';

// Current shell only; every API response is controlled. No uploads, model calls,
// preview/confirm/export writes or question answers reach the real services.
test('current three-pane workspace renders the inline draft, question, preview and asset drawer', async ({ page }) => {
  let previewRequested = false;
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
    else if (path === '/projects/baseline/assets') data = { items: [{ id: 'asset', filename: '讲话素材.mp4', validationStatus: 'VALID' }] };
    else if (path === '/projects/baseline/uploads') data = { items: [], nextCursor: null };
    else if (path === '/projects/baseline/plans') data = { items: [] };
    else if (path === '/projects/baseline/conversations') data = { items: [{ id: 'chat', title: '两条输出草稿', assetIds: ['asset'] }] };
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
  await expect(page.locator('.chat-head h1')).toHaveText('两条输出草稿');
  await expect(page.locator('.project-current .name')).toHaveText('草稿布局测试');
  await expect(page.getByRole('region', { name: '剪辑草稿' })).toContainText('横屏开场');
  await expect(page.getByRole('region', { name: '剪辑草稿' })).toContainText('竖屏结尾');
  await expect(page.getByRole('region', { name: '待回答问题' })).toContainText('新制作需要多少秒');
  await expect(page.locator('.plan-panel')).toContainText('暂无方案');

  await page.getByRole('button', { name: '暂不采纳', exact: true }).click();
  await expect(page.locator('.draft-collapsed-inline')).toContainText('剪辑草稿已收起');
  await page.locator('.draft-collapsed-inline').getByRole('button', { name: '查看草稿', exact: true }).click();
  await expect(page.getByRole('button', { name: '预览草稿', exact: true })).toBeVisible();

  await page.getByRole('button', { name: '预览草稿', exact: true }).click();
  await page.getByLabel('选择预览输出', { exact: true }).selectOption('out-1');
  await page.getByRole('button', { name: '预览所选输出', exact: true }).click();
  await expect(page.getByRole('region', { name: '草稿预览记录' })).toContainText('正在执行');

  await page.getByRole('button', { name: '打开素材库', exact: true }).click();
  const drawer = page.getByRole('dialog', { name: '素材库' });
  await expect(drawer).toBeVisible();
  await expect(drawer.locator('.dropzone')).toContainText('拖入视频，或选择文件');
  await expect(drawer.locator('input[type=file][aria-label="选择视频"]')).toHaveCount(1);
  await expect(drawer.getByRole('article', { name: '素材：讲话素材.mp4' })).toBeVisible();
  await page.getByRole('button', { name: '关闭素材库', exact: true }).click();
  await expect(drawer).toBeHidden();

  await page.locator('.project-current').click();
  await expect(page.getByRole('menu', { name: '切换项目' })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: /草稿布局测试/ })).toBeVisible();
});

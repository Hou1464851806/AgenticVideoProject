import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

// Baseline evidence only: no model calls, uploads, render requests or Compose changes.
const screenshots = resolve('../docs/verification/frontend-redesign-step1');
const viewports = [{ width: 1366, height: 768 }, { width: 768, height: 1024 }, { width: 390, height: 844 }];

for (const viewport of viewports) {
  const size = `${viewport.width}x${viewport.height}`;
  test(`real existing workspace baseline ${size}`, async ({ page }) => {
    test.skip(process.env.E2E_REDESIGN_REAL !== '1', 'Requires existing preview-seed.json and running Java deployment');
    const seed = JSON.parse(readFileSync(resolve('../.scratch/asr-fixture/preview-seed.json'), 'utf8'));
    mkdirSync(screenshots, { recursive: true });
    await page.setViewportSize(viewport);
    await page.goto('/');
    await expect(page.getByRole('heading', { name: '登录工作区', exact: true })).toBeVisible();
    await page.screenshot({ path: `${screenshots}/real-login-${size}.png`, fullPage: true });
    await page.getByLabel('用户名', { exact: true }).fill(seed.username);
    await page.getByLabel('密码', { exact: true }).fill('analysis-browser-password');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page.getByRole('heading', { name: '我的项目', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '退出登录', exact: true })).toBeVisible();
    await page.screenshot({ path: `${screenshots}/real-projects-${size}.png`, fullPage: true });
    const project = await page.request.get(`/api/v1/projects/${seed.projectId}`);
    expect(project.status()).toBe(200);
    const projectName = (await project.json()).data.name;
    await page.goto(`/?project=${seed.projectId}`);
    await expect(page.getByRole('heading', { name: projectName, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '查看分析状态 labelled-speech.mp4', exact: true })).toBeVisible();
    // All three panels mount on desktop; wait for session restoration before capturing.
    await expect(page.getByRole('button', { name: '新聊天', exact: true, includeHidden: true })).toBeEnabled();
    const sessionId = await page.getByLabel('制作会话', { exact: true }).inputValue();
    const saved = await page.request.get(`/api/v1/conversations/${sessionId}/messages`);
    expect(saved.status()).toBe(200);
    const view = (await saved.json()).data.view;
    if (view.planId && view.revisionId) {
      await expect(page.getByLabel('查看方案', { exact: true })).toHaveValue(view.planId);
      await expect(page.getByLabel('查看版本', { exact: true })).toHaveValue(view.revisionId);
      await expect(page.getByRole('heading', { level: 4, name: /^V[0-9]+ ·/, includeHidden: true })).toHaveCount(1);
    }
    await page.screenshot({ path: `${screenshots}/real-assets-${size}.png`, fullPage: true });
    for (const [tab, label] of [['对话', 'conversation'], ['播放器与结果', 'results']]) {
      if (viewport.width <= 720) await page.getByRole('tab', { name: tab, exact: true }).click();
      await expect(page.getByRole('heading', { name: tab, exact: true })).toBeVisible();
      await expect(page.getByLabel('制作会话', { exact: true })).toBeAttached();
      await page.screenshot({ path: `${screenshots}/real-${label}-${size}.png`, fullPage: true });
    }
  });

  test(`controlled lifecycle and partial analysis baseline ${size}`, async ({ page }) => {
    mkdirSync(screenshots, { recursive: true });
    await page.setViewportSize(viewport);
    let readonly = false, failure = 'PAID_SUBMISSION_UNKNOWN';
    const binding = { messageId: 'draft-current', validationId: 'validation-current', editGeneration: 2, draftHash: 'a'.repeat(64), requirementsHash: 'b'.repeat(64) };
    const unexpected: string[] = [];
    await page.route('**/api/v1/**', async route => {
      const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
      let data: unknown;
      if (route.request().method() !== 'GET') {
        unexpected.push(`${route.request().method()} ${path}`);
        await route.fulfill({ status: 400, json: { error: { message: '基线采集禁止业务写入' } } }); return;
      }
      if (path === '/auth/me') data = { user: { id: 'baseline-user', username: '受控基线' } };
      else if (path === '/projects') data = { items: [{ id: 'baseline', name: '受控状态（非真实服务）', rowVersion: 0 }], nextCursor: null };
      else if (path === '/projects/baseline') data = { id: 'baseline', name: '受控状态（非真实服务）', rowVersion: 0 };
      else if (path === '/projects/baseline/assets') data = { items: [{ id: 'asset', filename: '讲话素材.mp4', validationStatus: 'VALID' }] };
      else if (path === '/projects/baseline/uploads') data = { items: [], nextCursor: null };
      else if (path === '/projects/baseline/plans') data = { items: [] };
      else if (path === '/projects/baseline/conversations') data = { items: [{ id: 'chat', title: '当前与历史草稿', assetIds: ['asset'] }] };
      else if (path === '/projects/baseline/snapshot') data = { cursor: 'baseline:1' };
      else if (path === '/conversations/chat/messages') data = { view: { planId: null, revisionId: null } };
      else if (path === '/conversations/chat/snapshot') data = {
        cursor: 'baseline:1', assetIds: ['asset'],
        items: [
          { id: 'draft-old', role: 'ASSISTANT', content: '第一版草稿', references: [{ type: 'EDIT_DRAFT', id: 'draft-old' }] },
          { id: 'preview-message', role: 'ASSISTANT', content: '第一版历史预览', references: [{ type: 'PREVIEW_RESULT', id: 'artifact-old', sourceDraftMessageId: 'draft-old', outputKey: 'out-2' }] },
        ],
        latestTask: { taskId: 'agent', status: 'FAILED', failure: { code: failure, message: failure === 'PAID_SUBMISSION_UNKNOWN' ? '模型提交状态未知，停止自动重付。' : '预算已耗尽，保留制作条件。', recoverable: false } },
        agent: { currentProduction: { productionId: 'production', currentDraft: binding, frozen: readonly, requirements: { topic: '两条短片', profile: 'PORTRAIT_720', subtitles_enabled: false }, editGeneration: 2 },
          currentDraft: { id: binding.messageId, content: '当前双输出草稿', normalizedContent: { outputs: ['横屏开场', '竖屏结尾'].map((title, index) => ({ output_key: `out-${index + 1}`, title, durationMs: 2000, render: index ? { width: 720, height: 1280 } : { width: 1280, height: 720 }, clips: [{ assetId: 'asset', sourceInMs: 0, sourceOutMs: 2000, reason: '已有语音证据' }], subtitles: { cues: [] } })) } },
          canAcceptDraft: !readonly, canPreviewDraft: !readonly, pendingQuestions: [] },
        previews: { items: [{ requestId: 'preview-old', taskId: 'media', sourceDraftMessageId: 'draft-old', outputKey: 'out-2', status: 'SUCCEEDED', artifactId: 'artifact-old', artifactState: 'AVAILABLE' }] },
      };
      else if (path === '/assets/asset') data = { id: 'asset', filename: '讲话素材.mp4', durationMs: 2000, validationStatus: 'VALID', analysis: { stages: { MEDIA: 'SUCCEEDED', ASR: 'SUCCEEDED', VISION: 'FAILED', EMBEDDING: 'SUCCEEDED', INDEX: 'SUCCEEDED' }, errors: { VISION: { message: '画面分析失败，语音证据仍可使用' } } } };
      else if (path === '/assets/asset/search-index') data = { publishedGeneration: null, generations: [] };
      else if (path === '/assets/asset/evidence') data = { observations: [], units: [] };
      else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
      else { unexpected.push(`GET ${path}`); await route.fulfill({ status: 404, json: { error: { message: `未定义基线接口 ${path}` } } }); return; }
      await route.fulfill({ json: { data } });
    });
    await page.goto('/?project=baseline');
    if (viewport.width <= 720) await page.getByRole('tab', { name: '对话', exact: true }).click();
    await expect(page.getByRole('region', { name: '剪辑草稿', exact: true })).toContainText('竖屏结尾');
    await expect(page.getByRole('button', { name: '确认全部输出', exact: true })).toBeEnabled();
    await expect(page.getByText('历史草稿 · draft-old · 只读', { exact: true })).toBeVisible();
    await expect(page.getByText(/来源草稿：draft-old.*历史预览/)).toBeVisible();
    await expect(page.getByRole('alert')).toContainText('模型提交状态未知');
    await expect(page.getByRole('button', { name: '重试原需求', exact: true })).toHaveCount(0);
    await page.screenshot({ path: `${screenshots}/controlled-draft-history-unknown-${size}.png`, fullPage: true });
    readonly = true; failure = 'BUDGET_EXHAUSTED';
    await page.reload();
    if (viewport.width <= 720) await page.getByRole('tab', { name: '对话', exact: true }).click();
    await expect(page.getByRole('button', { name: '确认全部输出', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: '预览草稿', exact: true })).toBeDisabled();
    await expect(page.getByRole('alert')).toContainText('预算已耗尽');
    await expect(page.getByRole('button', { name: '重试原需求', exact: true })).toHaveCount(0);
    await page.screenshot({ path: `${screenshots}/controlled-readonly-budget-${size}.png`, fullPage: true });
    if (viewport.width <= 720) await page.getByRole('tab', { name: '素材', exact: true }).click();
    await page.getByRole('button', { name: '查看分析状态 讲话素材.mp4', exact: true }).click();
    const analysis = page.getByRole('region', { name: '素材分析状态', exact: true });
    await expect(analysis).toContainText('语音：已就绪');
    await expect(analysis).toContainText('画面：失败');
    await expect(analysis.getByRole('button', { name: '仅重试失败的 画面', exact: true })).toBeVisible();
    await page.screenshot({ path: `${screenshots}/controlled-partial-analysis-${size}.png`, fullPage: true });
    expect(unexpected).toEqual([]);
  });
}

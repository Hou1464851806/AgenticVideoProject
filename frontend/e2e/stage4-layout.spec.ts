import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { newChat } from './fixtures/workspace';

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

test('desktop panel supports keyboard and pointer resizing with chat width protected', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 });
  await page.goto('/?project=project');
  const splitter = page.getByRole('separator', { name: '调整产物面板宽度' });
  await expect(splitter).toBeVisible();
  await splitter.focus();
  await page.keyboard.press('ArrowLeft');
  await expect(splitter).toHaveAttribute('aria-valuenow', '400');
  const box = await splitter.boundingBox();
  await page.mouse.move(box!.x + 4, box!.y + 30); await page.mouse.down();
  await page.mouse.move(box!.x - 56, box!.y + 30); await page.mouse.up();
  await expect(splitter).toHaveAttribute('aria-valuenow', '460');
  await splitter.press('End');
  await page.setViewportSize({ width: 1000, height: 768 });
  await page.getByRole('button', { name: '展开产物面板', exact: true }).click();
  await expect(splitter).toHaveAttribute('aria-valuemax', '356');
  await expect.poll(() => page.locator('.chat').evaluate(el => el.getBoundingClientRect().width)).toBeGreaterThanOrEqual(400);
  await expect(page.locator('.plan-panel')).toBeVisible();
});

for (const width of [768, 390]) {
  test(`narrow panels overlay and restore focus ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto('/?project=project');
    const railOpen = page.getByRole('button', { name: '展开工作区' });
    await railOpen.click();
    await expect(page.getByRole('button', { name: '收起工作区' })).toBeFocused();
    await expect(page.locator('.chat')).toHaveAttribute('inert', '');
    await page.keyboard.press('Escape');
    await expect(railOpen).toBeFocused();
    const open = page.getByRole('button', { name: '展开产物面板', exact: true });
    await open.click();
    await expect(page.getByRole('button', { name: '收起产物面板', exact: true })).toBeFocused();
    await expect(page.locator('.artifact-panel')).not.toHaveClass(/collapsed/);
    await expect(page.locator('.chat')).toHaveAttribute('inert', '');
    const panel = page.locator('.artifact-panel');
    const bounds = await panel.boundingBox();
    expect(bounds!.width).toBeLessThanOrEqual(width - 32);
    await page.keyboard.press('Escape');
    await expect(open).toBeFocused();
    await expect(page.locator('.artifact-panel')).toHaveClass(/collapsed/);
    await expect(page.locator('.chat')).not.toHaveAttribute('inert', '');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

test('narrow project selection closes workspace overlay', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/?project=project');
  await page.getByRole('button', { name: '展开工作区' }).click();
  await page.locator('.project-current').click();
  await page.getByRole('menuitem', { name: /对话制作工作区/ }).click();
  await expect(page.locator('.rail')).toHaveClass(/collapsed/);
  await expect(page.getByRole('button', { name: '展开工作区' })).toBeFocused();
  await expect(page.locator('.chat')).not.toHaveAttribute('inert', '');
});

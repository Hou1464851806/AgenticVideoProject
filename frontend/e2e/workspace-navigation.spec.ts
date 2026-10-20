import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { newChat, selectChat, selectProject } from './fixtures/workspace';

const screenshots = resolve(process.env.E2E_REDESIGN_SCREENSHOTS ?? 'test-results/frontend-redesign');

// Current shell boundary only: no uploads, model calls, rendering or service restarts.
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
    else if (path === '/projects/a/conversations') data = route.request().method() === 'POST'
      ? { id: 'chat-a3', title: '新聊天 3', assetIds: [] }
      : { items: [{ id: 'chat-a1', title: '聊天甲', assetIds: ['asset-a'] }, { id: 'chat-a2', title: '第二聊天', assetIds: [] }] };
    else if (path === '/projects/b/conversations') data = { items: [{ id: 'chat-b', title: '聊天乙', assetIds: [] }] };
    else if (path.match(/^\/conversations\/chat-[ab]\d?\/snapshot$/)) data = { cursor: 'project:0', assetIds: path.includes('chat-a1') ? ['asset-a'] : [], items: [], latestTask: null, agent: { currentProduction: null, currentDraft: null, canAcceptDraft: false, canPreviewDraft: false, pendingQuestions: [] }, previews: { items: [] } };
    else if (path.endsWith('/messages')) data = { view: { planId: null, revisionId: null } };
    else if (path.endsWith('/snapshot')) data = { cursor: 'project:0' };
    else if (path.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' }); return; }
    else if (path === '/auth/logout') data = {};
    await route.fulfill({ json: { data } });
  });
});

test('project switcher and browser history isolate projects without a project-list page', async ({ page }) => {
  await page.goto('/?project=a');
  await expect(page.locator('.project-current .name')).toHaveText('项目甲');
  await expect(page.locator('.chat-head h1')).toHaveText('第二聊天');
  await expect(page.locator('.chat-btn')).toHaveCount(2);
  await selectProject(page, 'b');
  await expect(page.locator('.project-current .name')).toHaveText('项目乙');
  await expect(page.locator('.chat-head h1')).toHaveText('聊天乙');
  await expect(page.locator('.chat-btn')).toHaveCount(1);
  await expect(page).toHaveURL(/project=b/);
  await page.goBack();
  await expect(page.locator('.project-current .name')).toHaveText('项目甲');
  await expect(page.locator('.chat-head h1')).toHaveText('第二聊天');
  await expect(page).toHaveURL(/project=a/);
});

test('session switching preserves unsent text and references instead of hiding a tab', async ({ page }) => {
  await page.goto('/?project=a');
  await selectChat(page, 'chat-a1');
  await page.getByLabel('对话输入', { exact: true }).fill('尚未发送的剪辑思路');
  await page.getByText('引用素材', { exact: true }).click();
  await page.getByLabel('素材甲.mp4', { exact: true }).check();
  await selectChat(page, 'chat-a2');
  await expect(page.getByLabel('对话输入', { exact: true })).toHaveValue('');
  await selectChat(page, 'chat-a1');
  await expect(page.getByLabel('对话输入', { exact: true })).toHaveValue('尚未发送的剪辑思路');
  await expect(page.getByLabel('素材甲.mp4', { exact: true })).toBeChecked();
});

test('new chat creates and selects a session without a page reload', async ({ page }) => {
  await page.goto('/?project=a');
  const id = await newChat(page);
  expect(id).toBe('chat-a3');
  await expect(page.locator('.chat-head h1')).toHaveText('新聊天 3');
  await expect(page.locator('.chat-btn')).toHaveCount(3);
  await expect(page.locator(`.conversation-panel[data-conversation-id="chat-a3"]`)).toBeVisible();
});

test('asset drawer traps focus and returns it to the trigger; forbidden projects never mount the shell', async ({ page }) => {
  await page.goto('/?project=a');
  const trigger = page.locator('.chat-head [aria-label="打开素材库"]');
  await trigger.click();
  const drawer = page.getByRole('dialog', { name: '素材库' });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('article', { name: '素材：素材甲.mp4' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(drawer).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await page.goto('/?project=denied');
  await expect(page.getByRole('alert')).toContainText('项目不存在或无权访问');
  await expect(page.locator('.conversation-panel')).toHaveCount(0);
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

for (const viewport of [{ width: 1366, height: 768 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
  test(`current shell remains reachable without overflow at ${viewport.width}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto('/?project=a');
    await expect(page.locator('.project-current .name')).toHaveText('项目甲');
    mkdirSync(screenshots, { recursive: true });
    await page.screenshot({ path: `${screenshots}/controlled-navigation-${viewport.width}x${viewport.height}.png`, fullPage: true });
    if (viewport.width <= 1180) await expect(page.locator('.artifact-panel')).toHaveClass(/collapsed/);
    if (viewport.width <= 900) await expect(page.locator('.rail')).toHaveClass(/collapsed/);
    if (viewport.width <= 900) await page.locator('.rail [aria-label="展开工作区"]').click();
    await expect(page.locator('.rail')).not.toHaveClass(/collapsed/);
    await expect(page.locator('.project-current .name')).toHaveText('项目甲');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByText('账户', { exact: true }).click();
    await expect(page.getByRole('button', { name: '退出登录', exact: true })).toBeVisible();
    await page.getByText('账户', { exact: true }).click();
  });
}

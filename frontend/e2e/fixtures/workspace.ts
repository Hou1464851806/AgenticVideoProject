import { expect, type Page } from '@playwright/test';

/** Open the left rail on narrow viewports where it starts collapsed. */
export async function showRail(page: Page) {
  await expect(page.locator('.rail')).toHaveCount(1, { timeout: 15000 });
  if (await page.locator('.rail.collapsed').count()) {
    await page.locator('.rail [aria-label="展开工作区"]').click();
    await expect(page.locator('.rail.collapsed')).toHaveCount(0);
  }
}

export async function currentChatId(page: Page) {
  return (await page.locator('.conversation-panel').getAttribute('data-conversation-id')) ?? '';
}

export async function newChat(page: Page) {
  await showRail(page);
  await expect(page.locator('.conversation-panel')).toHaveCount(1, { timeout: 15000 });
  // Wait until the child either restored a session or rendered its explicit empty state.
  await expect.poll(async () => {
    if ((await currentChatId(page)).length > 0) return 'session';
    if (await page.locator('.chat-empty').count()) return 'empty';
    return 'loading';
  }, { timeout: 15000 }).not.toBe('loading');
  const before = await currentChatId(page);
  const button = page.locator('.rail-inner .new-chat');
  await expect(button).toBeEnabled();
  await button.click();
  await expect.poll(async () => {
    const id = await currentChatId(page);
    return !!id && id !== before;
  }, { timeout: 15000 }).toBe(true);
  return currentChatId(page);
}

export async function selectChat(page: Page, id: string) {
  await showRail(page);
  const button = page.locator(`.chat-btn[data-conversation-id="${id}"]`);
  await expect(button).toHaveCount(1, { timeout: 15000 });
  await button.scrollIntoViewIfNeeded();
  await button.click();
  await expect(page.locator(`.conversation-panel[data-conversation-id="${id}"]`)).toBeVisible();
}

export async function openAssets(page: Page) {
  const drawer = page.getByRole('dialog', { name: '素材库' });
  if (await drawer.count()) return drawer;
  await page.locator('.chat-head [aria-label="打开素材库"]').click();
  await expect(drawer).toBeVisible();
  return drawer;
}

export async function closeAssets(page: Page) {
  const drawer = page.getByRole('dialog', { name: '素材库' });
  if (!(await drawer.count())) return;
  await drawer.getByRole('button', { name: '关闭素材库', exact: true }).click();
  await expect(drawer).toBeHidden();
}

export async function openSearch(page: Page) {
  const drawer = await openAssets(page);
  await drawer.getByRole('tab', { name: '查找', exact: true }).click();
  return drawer;
}

export async function showPlan(page: Page) {
  await expect(page.locator('.artifact-panel')).toHaveCount(1, { timeout: 15000 });
  await expect(page.locator('.plan-panel')).toHaveCount(1, { timeout: 15000 });
  if (await page.locator('.artifact-panel.collapsed').count()) {
    await page.locator('.artifact-rail').click();
    await expect(page.locator('.artifact-panel.collapsed')).toHaveCount(0);
  }
  await expect(page.locator('.plan-panel')).toBeVisible();
}

export async function hidePlan(page: Page) {
  if (!(await page.locator('.artifact-panel.collapsed').count())) {
    await page.locator('.artifact-panel [aria-label="收起产物面板"]').click();
    await expect(page.locator('.artifact-panel.collapsed')).toHaveCount(1);
  }
}

export async function createProject(page: Page, name: string) {
  await showRail(page);
  await page.locator('.label-plus').click();
  const modal = page.getByRole('dialog', { name: '新建项目' });
  await expect(modal).toBeVisible();
  await modal.getByLabel('项目名称', { exact: true }).fill(name);
  await modal.getByRole('button', { name: '创建项目', exact: true }).click();
  await expect(modal).toBeHidden({ timeout: 15000 });
  await expect(page.locator('.project-current .name')).toHaveText(name);
  await expect(page.locator('.chat-head')).toBeVisible();
}

export async function openProject(page: Page, id: string) {
  await page.goto(`/?project=${encodeURIComponent(id)}`);
  await expect(page.locator('.chat-head')).toBeVisible({ timeout: 15000 });
  await expect(page.locator('.conversation-panel')).toBeVisible();
}

export async function selectProject(page: Page, id: string) {
  await showRail(page);
  if (!(await page.locator('.project-menu').count())) await page.locator('.project-current').click();
  const item = page.locator(`.project-menu-item[data-project-id="${id}"]`);
  await expect(item).toHaveCount(1, { timeout: 15000 });
  const name = (await item.innerText()).replace('✓', '').trim();
  await item.click();
  await expect(page.locator('.project-current .name')).toHaveText(name);
  await expect(page.locator('.chat-head')).toBeVisible();
}

export async function openAccount(page: Page) {
  await showRail(page);
  const menu = page.locator('.account-menu');
  if (!(await menu.evaluate(el => (el as HTMLDetailsElement).open))) await page.getByText('账户', { exact: true }).click();
  await expect(page.getByRole('button', { name: '退出登录', exact: true })).toBeVisible();
}

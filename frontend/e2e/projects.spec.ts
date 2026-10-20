import type { Page } from '@playwright/test';
import { test, expect, serveCurrentFrontend } from './fixtures/current-frontend';
import { closeAssets, createProject, openAccount, openAssets, showPlan } from './fixtures/workspace';

async function signIn(page: Page, username: string) {
  await page.goto('/');
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('project-browser-password');
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  await page.getByLabel('密码', { exact: true }).fill('project-browser-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await openAccount(page);
  await expect(page.getByText(`已登录：${username}`, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '退出登录', exact: true })).toBeVisible();
  await page.getByText('账户', { exact: true }).click();
}

test('two browser accounts use private projects and the current shell remains reachable on a narrow screen', async ({ page: ownerPage, browser, baseURL }) => {
  const suffix = Date.now();
  await signIn(ownerPage, `project-owner-${suffix}`);
  await createProject(ownerPage, '演讲短视频');
  await expect(ownerPage.locator('.project-current .name')).toHaveText('演讲短视频');
  for (const view of ['assets', 'conversation', 'artifacts']) {
    if (view === 'assets') {
      await openAssets(ownerPage);
      await expect(ownerPage.getByRole('dialog', { name: '素材库' })).toBeVisible();
      await closeAssets(ownerPage);
    } else if (view === 'conversation') {
      await expect(ownerPage.locator('.conversation-panel')).toBeVisible();
    } else {
      await showPlan(ownerPage);
      await expect(ownerPage.getByRole('complementary', { name: '产物' })).toBeVisible();
    }
  }
  await ownerPage.screenshot({ path: 'test-results/project-wide.png', fullPage: true });
  const projectUrl = ownerPage.url();
  await ownerPage.reload();
  await expect(ownerPage.locator('.project-current .name')).toHaveText('演讲短视频');
  await ownerPage.setViewportSize({ width: 390, height: 844 });
  for (const view of ['assets', 'conversation', 'artifacts']) {
    if (view === 'assets') {
      await openAssets(ownerPage);
      await expect(ownerPage.getByRole('dialog', { name: '素材库' })).toBeVisible();
      await closeAssets(ownerPage);
    } else if (view === 'conversation') {
      await expect(ownerPage.locator('.conversation-panel')).toBeVisible();
    } else {
      await showPlan(ownerPage);
      await expect(ownerPage.getByRole('complementary', { name: '产物' })).toBeVisible();
    }
  }
  await ownerPage.screenshot({ path: 'test-results/project-narrow.png', fullPage: true });
  const outsider = await browser.newContext();
  const outsiderPage = await outsider.newPage();
  await serveCurrentFrontend(outsiderPage, baseURL!);
  await signIn(outsiderPage, `project-outsider-${suffix}`);
  await expect(outsiderPage.getByRole('button', { name: '演讲短视频', exact: true })).toHaveCount(0);
  await outsiderPage.goto(projectUrl);
  await expect(outsiderPage.getByRole('alert')).toContainText('项目不存在或无权访问');
  await expect(outsiderPage.getByText('演讲短视频', { exact: true })).toHaveCount(0);
  await openAccount(ownerPage);
  await ownerPage.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(ownerPage.getByRole('heading', { name: '登录工作台', exact: true })).toBeVisible();
  await outsider.close();
});

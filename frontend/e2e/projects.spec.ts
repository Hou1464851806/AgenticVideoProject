import type { Page } from '@playwright/test';
import { test, expect, serveCurrentFrontend } from './fixtures/current-frontend';

async function signIn(page: Page, username: string) {
  await page.goto('/');
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('project-browser-password');
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  await page.getByLabel('密码', { exact: true }).fill('project-browser-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByText('账户', { exact: true }).click();
  await expect(page.getByText(`已登录：${username}`, { exact: true })).toBeVisible();
  await page.getByText('账户', { exact: true }).click();
}

test('two browser accounts use private projects and all workspace regions remain reachable on a narrow screen', async ({ page: ownerPage, browser, baseURL }) => {
  const suffix = Date.now();
  await signIn(ownerPage, `project-owner-${suffix}`);
  await ownerPage.getByLabel('项目名称', { exact: true }).fill('演讲短视频');
  await ownerPage.getByRole('button', { name: '创建项目', exact: true }).click();
  await ownerPage.getByRole('button', { name: '演讲短视频', exact: true }).click();
  await expect(ownerPage.getByRole('heading', { name: '演讲短视频', exact: true })).toBeVisible();
  for (const [tab, heading] of [['素材', '素材库'], ['制作', '制作'], ['作品', '作品']]) {
    await ownerPage.getByRole('tab', { name: tab, exact: true }).click();
    await expect(ownerPage.getByRole('heading', { name: heading, exact: true })).toBeVisible();
  }
  await ownerPage.screenshot({ path: 'test-results/project-wide.png', fullPage: true });
  const projectUrl = ownerPage.url();
  await ownerPage.reload();
  await expect(ownerPage.getByRole('heading', { name: '演讲短视频', exact: true })).toBeVisible();
  await ownerPage.setViewportSize({ width: 390, height: 844 });
  for (const [tab, heading] of [['素材', '素材库'], ['制作', '制作'], ['作品', '作品']]) {
    await ownerPage.getByRole('tab', { name: tab, exact: true }).click();
    await expect(ownerPage.getByRole('heading', { name: heading, exact: true })).toBeVisible();
  }
  await ownerPage.screenshot({ path: 'test-results/project-narrow.png', fullPage: true });
  const outsider = await browser.newContext();
  const outsiderPage = await outsider.newPage();
  await serveCurrentFrontend(outsiderPage, baseURL!);
  await signIn(outsiderPage, `project-outsider-${suffix}`);
  await expect(outsiderPage.getByRole('button', { name: '演讲短视频', exact: true })).toHaveCount(0);
  await outsiderPage.goto(projectUrl);
  await expect(outsiderPage.getByRole('alert')).toContainText('项目不存在或无权访问');
  await expect(outsiderPage.getByRole('heading', { name: '演讲短视频', exact: true })).toHaveCount(0);
  await ownerPage.getByText('账户', { exact: true }).click();
  await ownerPage.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(ownerPage.getByRole('heading', { name: '登录工作台', exact: true })).toBeVisible();
  await outsider.close();
});

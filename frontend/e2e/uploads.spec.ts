import { test, expect, type Page } from '@playwright/test';

async function signIn(page: Page, username: string) {
  await page.goto('/');
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('project-browser-password');
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  await page.getByLabel('密码', { exact: true }).fill('project-browser-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByText(`已登录：${username}`, { exact: true })).toBeVisible();
}

test('browser directly uploads two videos and distinguishes transferred from media validated', async ({ page }) => {
  await signIn(page, `upload-browser-${Date.now()}`);
  await page.getByLabel('项目名称', { exact: true }).fill('真实上传');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '真实上传', exact: true }).click();
  const directRequests: string[] = [];
  page.on('request', request => { if (request.method() === 'PUT' && new URL(request.url()).port === '9000') directRequests.push(request.url()); });
  await page.getByLabel('选择视频', { exact: true }).setInputFiles([
    'e2e/fixtures/short-video.mp4', 'e2e/fixtures/short-video.mp4',
  ]);
  await expect(page.getByText('传输完成，待媒体验证', { exact: true })).toHaveCount(2, { timeout: 30000 });
  expect(directRequests).toHaveLength(2);
  await expect(page.getByText('可用素材', { exact: true })).toHaveCount(0);
  await page.reload();
  await expect(page.getByText('传输完成，待媒体验证', { exact: true })).toHaveCount(2);
  await page.screenshot({ path: 'test-results/upload-pending.png', fullPage: true });
});

test('lost create and complete responses reuse the same upload and pending asset', async ({ page }) => {
  await signIn(page, `upload-lost-response-${Date.now()}`);
  await page.getByLabel('项目名称', { exact: true }).fill('响应丢失恢复');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '响应丢失恢复', exact: true }).click();
  let lostCreate = false;
  let lostComplete = false;
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === 'POST' && ((!lostCreate && /\/projects\/[^/]+\/uploads$/.test(path)) || (!lostComplete && path.endsWith('/complete')))) {
      await route.fetch();
      if (path.endsWith('/complete')) lostComplete = true;
      else lostCreate = true;
      await route.abort('failed');
    } else await route.continue();
  });
  await page.getByLabel('选择视频', { exact: true }).setInputFiles('e2e/fixtures/short-video.mp4');
  await expect(page.getByText('传输完成，待媒体验证', { exact: true })).toHaveCount(1, { timeout: 30000 });
  expect(lostCreate).toBe(true);
  expect(lostComplete).toBe(true);
  await page.reload();
  await expect(page.getByText('传输完成，待媒体验证', { exact: true })).toHaveCount(1);
});

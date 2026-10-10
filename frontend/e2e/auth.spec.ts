import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';

test('browser registers, logs in and out, and retains the account after recreating database and API containers', async ({ page, request }) => {
  test.setTimeout(90000);
  const username = `browser-${Date.now()}`;
  const password = 'browser-strong-password';
  await page.goto('/');
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  const logs = execFileSync(process.env.DOCKER_BIN ?? 'docker', ['compose', 'logs', '--no-color', 'business-api'], { cwd: '..', encoding: 'utf8' });
  expect(logs.includes(password)).toBe(false);
  execFileSync(process.env.DOCKER_BIN ?? 'docker', ['compose', 'up', '-d', '--force-recreate', '--wait', 'postgres', 'business-api'], { cwd: '..', stdio: 'pipe' });
  await expect.poll(async () => {
    try { return (await request.get('/api/v1/auth/csrf')).status(); } catch { return 0; }
  }, { timeout: 60000 }).toBe(200);
  await page.reload();
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByText(`已登录：${username}`, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/sign-in.png', fullPage: true });
});

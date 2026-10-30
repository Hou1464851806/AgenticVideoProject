import { test, expect, type Page } from '@playwright/test';

async function mockApi(page: Page, signedIn = false) {
  let loggedIn = signedIn;
  let createAttempts = 0;
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    let data: unknown = {};
    let status = 200;
    if (path === '/auth/csrf') data = { csrfToken: 'test' };
    else if (path === '/auth/me') { if (loggedIn) data = { user: { id: 'u', username: '测试用户' } }; else status = 401; }
    else if (path === '/auth/register') data = { user: { id: 'u', username: '测试用户' } };
    else if (path === '/auth/login') { loggedIn = true; data = { user: { id: 'u', username: '测试用户' } }; }
    else if (path === '/auth/logout') { loggedIn = false; }
    else if (path === '/projects' && route.request().method() === 'POST') { if (!createAttempts++) { await route.fulfill({ status: 400, json: { error: { message: '项目创建失败' } } }); return; } data = { id: 'new', name: '示例项目', rowVersion: 0 }; }
    else if (path === '/projects/new') data = { id: 'new', name: '示例项目', rowVersion: 0 };
    else if (path === '/projects/new/assets') data = { items: [] };
    else if (path === '/projects/new/conversations') data = { items: [] };
    else if (path === '/projects/new/plans') data = { items: [] };
    else if (path === '/projects') data = { items: [], nextCursor: null };
    await route.fulfill({ status, json: { data } });
  });
}

test('registration, login, account and logout', async ({ page }) => {
  await mockApi(page);
  await page.goto('/');
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await page.getByLabel('用户名').fill('测试用户');
  await page.getByLabel('密码').fill('password123');
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('账号已创建');
  await page.getByLabel('密码').fill('password123');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('button', { name: '账户', exact: true }).click();
  await expect(page.getByText('已登录：测试用户')).toBeVisible();
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeVisible();
});

for (const [name, width, height] of [['desktop', 1366, 768], ['tablet', 768, 1024], ['mobile', 390, 844]] as const) {
  test(`stage 1 layout and modal keyboard: ${name}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height });
    await mockApi(page, true);
    await page.goto('/');
    const trigger = page.getByRole('button', { name: '＋ 新建项目', exact: true }).last();
    await trigger.click();
    const dialog = page.getByRole('dialog', { name: '新建项目' });
    await expect(dialog).toBeVisible();
    await expect.poll(() => dialog.evaluate(el => el.contains(document.activeElement))).toBe(true);
    for (let i = 0; i < 7; i++) { await page.keyboard.press('Tab'); await expect.poll(() => dialog.evaluate(el => el.contains(document.activeElement))).toBe(true); }
    await page.getByLabel('项目名称').fill('示例项目');
    await page.getByRole('button', { name: '创建项目', exact: true }).click();
    await expect(dialog.getByRole('alert')).toContainText('项目创建失败');
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`${name}.png`), fullPage: true });
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();
    await trigger.click();
    await page.getByLabel('项目名称').fill('示例项目');
    await page.getByRole('button', { name: '创建项目', exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect(page.locator('.crumb')).toHaveText('示例项目');
  });
}

import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';

async function openProject(page: Page, username: string) {
  await page.goto('/');
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('project-browser-password');
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  await page.getByLabel('密码', { exact: true }).fill('project-browser-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByText(`已登录：${username}`, { exact: true })).toBeVisible();
  await page.getByLabel('项目名称', { exact: true }).fill('可恢复上传');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '可恢复上传', exact: true }).click();
}

test('pause settles the in-flight part and continues the original upload without repeating successful parts', async ({ page }) => {
  test.setTimeout(60000);
  await openProject(page, `upload-pause-${Date.now()}`);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const puts: number[] = [];
  await page.route('http://localhost:9000/**', async route => {
    if (route.request().method() !== 'PUT') { await route.continue(); return; }
    const part = Number(new URL(route.request().url()).searchParams.get('partNumber'));
    puts.push(part);
    if (puts.length === 1) await gate;
    if (part === 2 && puts.filter(value => value === 2).length === 1) {
      await route.abort('internetdisconnected');
      await page.context().setOffline(true);
      await new Promise(resolve => setTimeout(resolve, 500));
      await page.context().setOffline(false);
      return;
    }
    await route.continue();
  });
  try {
    const created = page.waitForResponse(response => response.request().method() === 'POST' && /\/projects\/[^/]+\/uploads$/.test(new URL(response.url()).pathname));
    await page.getByLabel('选择视频', { exact: true }).setInputFiles({ name: 'resume.mp4', mimeType: 'video/mp4', buffer: Buffer.alloc(16 * 1024 * 1024 + 19, 37) });
    const uploadId = (await (await created).json()).data.uploadId;
    await expect.poll(() => puts.length).toBe(1);
    await page.getByRole('button', { name: 'resume.mp4 暂停上传', exact: true }).click();
    release();
    await expect(page.getByText('已暂停', { exact: true })).toBeVisible();
    expect(puts).toEqual([1]);
    expect((await (await page.request.get(`/api/v1/uploads/${uploadId}`)).json()).data.status).toBe('PAUSED');
    await page.getByRole('button', { name: 'resume.mp4 继续上传', exact: true }).click();
    await expect(page.getByText(/^传输完成，/)).toBeVisible({ timeout: 10000 });
    expect(puts).toEqual([1, 2, 2, 3]);
    const uploads = (await (await page.request.get(`/api/v1/uploads/${uploadId}`)).json()).data;
    expect(uploads.status).toBe('COMPLETED');
  } finally { release(); await page.context().setOffline(false); }
});

test('pausing while part authorization is in flight does not schedule the subsequent PUT', async ({ page }) => {
  test.setTimeout(30000);
  await openProject(page, `upload-authorize-pause-${Date.now()}`);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let authorizations = 0;
  let puts = 0;
  await page.route('**/part-urls', async route => {
    const response = await route.fetch();
    authorizations++;
    if (authorizations === 1) await gate;
    await route.fulfill({ response });
  });
  page.on('request', request => { if (request.method() === 'PUT' && new URL(request.url()).port === '9000') puts++; });
  try {
    await page.getByLabel('选择视频', { exact: true }).setInputFiles({ name: 'authorize-pause.mp4', mimeType: 'video/mp4', buffer: Buffer.alloc(1024, 101) });
    await expect.poll(() => authorizations).toBe(1);
    await page.getByRole('button', { name: 'authorize-pause.mp4 暂停上传', exact: true }).click();
    release();
    await expect(page.getByText('已暂停', { exact: true })).toBeVisible();
    expect(puts).toBe(0);
    await page.getByRole('button', { name: 'authorize-pause.mp4 继续上传', exact: true }).click();
    await expect(page.getByText(/^传输完成，/)).toBeVisible();
    expect(puts).toBe(1);
  } finally { release(); }
});

test('a paused queued file can be continued before either active upload releases its slot', async ({ page }) => {
  test.setTimeout(30000);
  await openProject(page, `upload-queued-pause-${Date.now()}`);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let puts = 0;
  await page.route('http://localhost:9000/**', async route => {
    if (route.request().method() === 'PUT') { puts++; await gate; }
    await route.continue();
  });
  try {
    await page.getByLabel('选择视频', { exact: true }).setInputFiles(['one.mp4', 'two.mp4', 'queued.mp4'].map(name => ({ name, mimeType: 'video/mp4', buffer: Buffer.alloc(1024, 97) })));
    await expect.poll(() => puts).toBe(2);
    await page.getByRole('button', { name: 'queued.mp4 暂停上传', exact: true }).click();
    await page.getByRole('button', { name: 'queued.mp4 继续上传', exact: true }).click();
    release();
    await expect(page.getByText(/^传输完成，/)).toHaveCount(3, { timeout: 10000 });
    expect(puts).toBe(3);
  } finally { release(); }
});

test('an actually expired part URL is renewed for the same multipart upload in the browser', async ({ page }) => {
  test.setTimeout(120000);
  const docker = process.env.DOCKER_BIN ?? 'docker';
  const restart = ['compose', 'up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '40', 'business-api'];
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    execFileSync(docker, restart, { env: { ...process.env, STORAGE_PART_URL_SECONDS: '3' }, timeout: 60000 });
    await openProject(page, `upload-expiry-${Date.now()}`);
    const statuses: number[] = [];
    const multipartIds = new Set<string>();
    let creations = 0;
    let puts = 0;
    page.on('request', request => {
      if (request.method() === 'POST' && /\/projects\/[^/]+\/uploads$/.test(new URL(request.url()).pathname)) creations++;
    });
    page.on('response', response => {
      if (response.request().method() === 'PUT' && new URL(response.url()).port === '9000') statuses.push(response.status());
    });
    await page.route('http://localhost:9000/**', async route => {
      if (route.request().method() === 'PUT') {
        multipartIds.add(new URL(route.request().url()).searchParams.get('uploadId')!);
        puts++;
        if (puts === 1) { timer = setTimeout(release, 4000); await gate; }
      }
      await route.continue();
    });
    const authorization = page.waitForResponse(response => response.url().endsWith('/part-urls'));
    await page.getByLabel('选择视频', { exact: true }).setInputFiles({ name: 'expiry.mp4', mimeType: 'video/mp4', buffer: Buffer.alloc(1024, 89) });
    const signed = (await (await authorization).json()).data.parts[0].url;
    expect(new URL(signed).searchParams.get('X-Amz-Expires')).toBe('3');
    await expect(page.getByText(/^传输完成，/)).toBeVisible({ timeout: 15000 });
    expect(statuses).toEqual([403, 200]);
    expect(puts).toBe(2);
    expect(creations).toBe(1);
    expect(multipartIds.size).toBe(1);
  } finally {
    release();
    clearTimeout(timer);
    await page.unrouteAll({ behavior: 'wait' });
    execFileSync(docker, restart, { timeout: 60000 });
  }
});

test('persistent failure stops after three attempts and a manual retry keeps the successful first part', async ({ page }) => {
  test.setTimeout(30000);
  await openProject(page, `upload-bounded-retry-${Date.now()}`);
  const puts: number[] = [];
  let unavailable = true;
  await page.route('http://localhost:9000/**', async route => {
    if (route.request().method() === 'PUT') {
      const part = Number(new URL(route.request().url()).searchParams.get('partNumber'));
      puts.push(part);
      if (part === 2 && unavailable) { await route.abort('internetdisconnected'); return; }
    }
    await route.continue();
  });
  await page.getByLabel('选择视频', { exact: true }).setInputFiles({ name: 'retry.mp4', mimeType: 'video/mp4', buffer: Buffer.alloc(8 * 1024 * 1024 + 19, 71) });
  await expect(page.getByText('上传失败', { exact: true })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('自动重试已用尽');
  expect(puts).toEqual([1, 2, 2, 2]);
  unavailable = false;
  await page.getByRole('button', { name: 'retry.mp4 继续上传', exact: true }).click();
  await expect(page.getByText(/^传输完成，/)).toBeVisible({ timeout: 10000 });
  expect(puts).toEqual([1, 2, 2, 2, 2]);
});

test('refresh reselects the original file, rejects same-name different bytes, and transfers only missing parts', async ({ page }) => {
  test.setTimeout(60000);
  await openProject(page, `upload-refresh-${Date.now()}`);
  const original = Buffer.alloc(8 * 1024 * 1024 + 19, 53);
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const puts: number[] = [];
  await page.route('http://localhost:9000/**', async route => {
    if (route.request().method() === 'PUT') {
      puts.push(Number(new URL(route.request().url()).searchParams.get('partNumber')));
      if (puts.length === 1) await gate;
    }
    await route.continue();
  });
  try {
    await page.getByLabel('选择视频', { exact: true }).setInputFiles({ name: 'refresh.mp4', mimeType: 'video/mp4', buffer: original });
    await expect.poll(() => puts.length).toBe(1);
    await page.getByRole('button', { name: 'refresh.mp4 暂停上传', exact: true }).click();
    release();
    await expect(page.getByText('已暂停', { exact: true })).toBeVisible();
    await page.reload();
    const reselection = page.getByLabel('refresh.mp4 重选原文件继续', { exact: true });
    await expect(reselection).toBeVisible();
    const different = Buffer.from(original);
    different[0] = 54;
    await reselection.setInputFiles({ name: 'refresh.mp4', mimeType: 'video/mp4', buffer: different });
    await expect(page.getByRole('alert')).toContainText('重选文件内容与原上传不一致');
    expect(puts).toEqual([1]);
    const popup = page.waitForEvent('popup');
    await page.evaluate(() => { window.open(location.href, '_blank'); });
    const competingPage = await popup;
    let competingPuts = 0;
    competingPage.on('request', request => { if (request.method() === 'PUT' && new URL(request.url()).port === '9000') competingPuts++; });
    await competingPage.getByLabel('refresh.mp4 重选原文件继续', { exact: true }).setInputFiles({ name: 'refresh.mp4', mimeType: 'video/mp4', buffer: original });
    await expect(competingPage.getByRole('alert')).toContainText('另一个页面');
    expect(competingPuts).toBe(0);
    await competingPage.close();
    await reselection.setInputFiles({ name: 'refresh.mp4', mimeType: 'video/mp4', buffer: original });
    await expect(page.getByText(/^传输完成，/)).toBeVisible({ timeout: 15000 });
    expect(puts).toEqual([1, 2]);
  } finally { release(); }
});

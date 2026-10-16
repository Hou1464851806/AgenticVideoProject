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

test('local MQ consumer validates an uploaded video before authenticated playback', async ({ page, browser, baseURL }) => {
  await signIn(page, `media-browser-${Date.now()}`);
  await page.getByLabel('项目名称', { exact: true }).fill('媒体验证与播放');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '媒体验证与播放', exact: true }).click();
  await page.getByLabel('选择视频', { exact: true }).setInputFiles('e2e/fixtures/short-video.mp4');
  await expect(page.getByRole('button', { name: '播放 short-video.mp4', exact: true })).toBeVisible({ timeout: 30000 });
  await expect(page.getByText('传输完成，媒体验证通过', { exact: true })).toBeVisible();
  const accessResponse = page.waitForResponse(response => response.url().endsWith('/playback-access'));
  await page.getByRole('button', { name: '播放 short-video.mp4', exact: true }).click();
  const access = (await (await accessResponse).json()).data;
  const ranged = await page.request.get(access.url, { headers: { Range: 'bytes=0-15' } });
  expect(ranged.status()).toBe(206);
  expect((await ranged.body()).length).toBe(16);
  expect(ranged.headers()['content-range']).toMatch(/^bytes 0-15\//);
  const player = page.getByLabel('视频播放器', { exact: true });
  await expect(player).toBeVisible();
  await page.getByText('媒体详情与精确定位', { exact: true }).click();
  await expect(page.getByText('160 × 90', { exact: true })).toBeVisible();
  await expect(page.getByText('H.264', { exact: true })).toBeVisible();
  await expect.poll(() => player.evaluate((video: HTMLVideoElement) => video.readyState)).toBeGreaterThanOrEqual(1);
  expect(await player.evaluate((video: HTMLVideoElement) => video.duration)).toBeCloseTo(1, 0);
  await page.getByLabel('定位秒数', { exact: true }).fill('0.4');
  await page.getByRole('button', { name: '定位播放', exact: true }).click();
  await expect.poll(() => player.evaluate((video: HTMLVideoElement) => video.currentTime)).toBeGreaterThanOrEqual(0.35);
  await expect.poll(() => player.evaluate((video: HTMLVideoElement) => video.readyState)).toBeGreaterThanOrEqual(2);
  expect(await player.evaluate((video: HTMLVideoElement) => video.videoWidth)).toBe(160);
  await player.evaluate((video: HTMLVideoElement) => video.pause());
  await page.screenshot({ path: 'test-results/media-playback.png', fullPage: true });
  let accessRequests = 0;
  await page.route('**/playback-access', async route => {
    const response = await route.fetch();
    const body = await response.json();
    accessRequests++;
    if (accessRequests === 1) {
      const unavailable = new URL(body.data.url);
      unavailable.searchParams.set('X-Amz-Signature', '0'.repeat(64));
      body.data.url = unavailable.toString();
    }
    await route.fulfill({ response, json: body });
  });
  await page.getByRole('button', { name: '返回素材列表', exact: true }).click();
  await page.getByRole('button', { name: '播放 short-video.mp4', exact: true }).click();
  await expect.poll(() => accessRequests).toBe(2);
  await expect.poll(() => player.evaluate((video: HTMLVideoElement) => video.readyState)).toBeGreaterThanOrEqual(2);
  await page.unroute('**/playback-access');
  const outsider = await browser.newContext();
  const outsiderPage = await outsider.newPage();
  await serveCurrentFrontend(outsiderPage, baseURL!);
  await signIn(outsiderPage, `media-outsider-${Date.now()}`);
  const csrf = (await (await outsiderPage.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const denied = await outsiderPage.request.post(`/api/v1/assets/${access.assetId}/playback-access`, { headers: { 'X-XSRF-TOKEN': csrf }, data: {} });
  expect(denied.status()).toBe(404);
  expect((await outsiderPage.request.get(`/api/v1/assets/${access.assetId}/evidence`)).status()).toBe(404);
  await outsider.close();
});

test('undecodable upload shows a reason and confirmed cleanup without becoming selectable', async ({ page }) => {
  await signIn(page, `media-corrupt-${Date.now()}`);
  await page.getByLabel('项目名称', { exact: true }).fill('无效媒体清理');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '无效媒体清理', exact: true }).click();
  await page.getByLabel('选择视频', { exact: true }).setInputFiles({ name: 'broken.mp4', mimeType: 'video/mp4', buffer: Buffer.from('not a video') });
  await expect(page.getByText('传输完成，媒体验证失败', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole('alert')).toContainText('无法完整解码');
  await expect(page.getByText('失败对象已清理', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole('button', { name: '播放 broken.mp4', exact: true })).toHaveCount(0);
});

test('server independently rejects a valid video whose declared hash differs', async ({ page }) => {
  await signIn(page, `media-hash-${Date.now()}`);
  await page.getByLabel('项目名称', { exact: true }).fill('服务端完整性');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '服务端完整性', exact: true }).click();
  await page.route('**/api/v1/projects/*/uploads', async route => {
    if (route.request().method() === 'POST') {
      const input = route.request().postDataJSON();
      input.sha256 = '0'.repeat(64);
      await route.continue({ postData: JSON.stringify(input) });
    } else await route.continue();
  });
  await page.getByLabel('选择视频', { exact: true }).setInputFiles('e2e/fixtures/short-video.mp4');
  await expect(page.getByRole('alert')).toContainText('服务端校验值与声明不符', { timeout: 30000 });
  await expect(page.getByText('失败对象已清理', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '播放 short-video.mp4', exact: true })).toHaveCount(0);
});

test('variable frame rate and audio offset retain source time bases without inventing an audio track', async ({ page }) => {
  await signIn(page, `media-timeline-${Date.now()}`);
  await page.getByLabel('项目名称', { exact: true }).fill('原始时间映射');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '原始时间映射', exact: true }).click();
  await page.getByLabel('选择视频', { exact: true }).setInputFiles(['e2e/fixtures/variable-frame-rate.mp4', 'e2e/fixtures/audio-offset.mp4']);
  for (const filename of ['variable-frame-rate.mp4', 'audio-offset.mp4']) {
    const button = page.getByRole('button', { name: `播放 ${filename}`, exact: true });
    await expect(button).toBeVisible({ timeout: 30000 });
    const response = page.waitForResponse(reply => reply.url().endsWith('/playback-access'));
    await button.click();
    const assetId = (await (await response).json()).data.assetId;
    const asset = (await (await page.request.get(`/api/v1/assets/${assetId}`)).json()).data;
    expect(asset.timeMapping.videoTimeBase).toBe('1/10240');
    if (filename.startsWith('variable')) {
      expect(asset.timeMapping.hasAudio).toBe(false);
      expect(asset.mediaInfo.streams[0].r_frame_rate).toBe('20/1');
      expect(asset.mediaInfo.streams[0].avg_frame_rate).toBe('10/1');
      expect(asset.durationMs).toBe(800);
    } else {
      expect(asset.timeMapping.hasAudio).toBe(true);
      expect(asset.timeMapping.audioOffsetFromVideoMs).toBe(136);
      expect(asset.timeMapping.audioTimeBase).toBe('1/16000');
    }
  }
});

test('over-duration video is rejected before decoding and its object is cleaned', async ({ page }) => {
  await signIn(page, `media-duration-${Date.now()}`);
  await page.getByLabel('项目名称', { exact: true }).fill('时长边界');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '时长边界', exact: true }).click();
  await page.getByLabel('选择视频', { exact: true }).setInputFiles('e2e/fixtures/over-duration.mp4');
  await expect(page.getByRole('alert')).toContainText('不超过 60 分钟', { timeout: 30000 });
  await expect(page.getByText('失败对象已清理', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '播放 over-duration.mp4', exact: true })).toHaveCount(0);
});

import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
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

test('worker interruption after claim and ACK recovers with a new attempt and one valid asset', async ({ page }) => {
  test.setTimeout(180000);
  const docker = process.env.DOCKER_BIN ?? 'docker';
  const directory = mkdtempSync(join(tmpdir(), 'agenticvideo-media-recovery-'));
  execFileSync(docker, ['run', '--rm', '--user', '0', '--entrypoint', 'ffmpeg', '-v', `${directory}:/fixtures`, 'agenticvideo-media-worker',
    '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=60', '-t', '120', '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '42', '-an', '-y', '/fixtures/recovery.mp4'], { timeout: 60000 });
  try {
    await signIn(page, `media-recovery-${Date.now()}`);
    await page.getByLabel('项目名称', { exact: true }).fill('领取后中断恢复');
    await page.getByRole('button', { name: '创建项目', exact: true }).click();
    await page.getByRole('button', { name: '领取后中断恢复', exact: true }).click();
    const completion = page.waitForResponse(response => response.url().endsWith('/complete'));
    await page.getByLabel('选择视频', { exact: true }).setInputFiles(join(directory, 'recovery.mp4'));
    const taskId = (await (await completion).json()).data.validationTaskId;
    await expect.poll(async () => (await (await page.request.get(`/api/v1/tasks/${taskId}`)).json()).data.status,
      { timeout: 20000, intervals: [20] }).toBe('RUNNING');
    execFileSync(docker, ['compose', 'kill', '-s', 'SIGKILL', 'media-worker'], { timeout: 10000 });
    expect((await (await page.request.get(`/api/v1/tasks/${taskId}`)).json()).data.status).toBe('RUNNING');
    execFileSync(docker, ['compose', 'up', '-d', 'media-worker'], { timeout: 30000 });
    await expect(page.getByRole('button', { name: '播放 recovery.mp4', exact: true })).toHaveCount(1, { timeout: 100000 });
    const recovered = (await (await page.request.get(`/api/v1/tasks/${taskId}`)).json()).data;
    expect(recovered.status).toBe('SUCCEEDED');
    expect(recovered.attemptCount).toBe(2);
    expect(execFileSync(docker, ['compose', 'exec', '-T', 'media-worker', 'sh', '-c',
      'find /work -mindepth 1 -maxdepth 1 -type d | wc -l'], { timeout: 5000 }).toString().trim()).toBe('0');
  } finally {
    execFileSync(docker, ['compose', 'up', '-d', 'media-worker'], { timeout: 30000 });
    rmSync(directory, { recursive: true, force: true });
  }
});

test('cancelling active media verification confirms FFmpeg exit before cancellation becomes terminal', async ({ page }) => {
  test.setTimeout(90000);
  const docker = process.env.DOCKER_BIN ?? 'docker';
  const directory = mkdtempSync(join(tmpdir(), 'agenticvideo-media-cancel-'));
  execFileSync(docker, ['run', '--rm', '--user', '0', '--entrypoint', 'ffmpeg', '-v', `${directory}:/fixtures`, 'agenticvideo-media-worker',
    '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=1280x720:rate=60', '-t', '120', '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '42', '-an', '-y', '/fixtures/cancel.mp4'], { timeout: 60000 });
  const processes = () => execFileSync(docker, ['compose', 'exec', '-T', 'media-worker', 'sh', '-c',
    'for task_proc in /proc/[0-9]*/comm; do read -r task_name < "$task_proc"; case "$task_name" in ffmpeg|ffprobe) printf "%s\n" "$task_name";; esac; done'], { timeout: 5000 }).toString();
  try {
    await signIn(page, `media-cancel-${Date.now()}`);
    await page.getByLabel('项目名称', { exact: true }).fill('验证进程取消');
    await page.getByRole('button', { name: '创建项目', exact: true }).click();
    await page.getByRole('button', { name: '验证进程取消', exact: true }).click();
    const completion = page.waitForResponse(response => response.url().endsWith('/complete'));
    await page.getByLabel('选择视频', { exact: true }).setInputFiles(join(directory, 'cancel.mp4'));
    const taskId = (await (await completion).json()).data.validationTaskId;
    await expect.poll(processes, { timeout: 20000, intervals: [50] }).toContain('ffmpeg');
    for (let duplicate = 0; duplicate < 3; duplicate++) {
      execFileSync(docker, ['compose', 'exec', '-T', 'rabbitmq', 'sh', '-c',
        'RABBITMQADMIN_USERNAME="$RABBITMQ_DEFAULT_USER" RABBITMQADMIN_PASSWORD="$RABBITMQ_DEFAULT_PASS" rabbitmqadmin --quiet publish message --routing-key media.v1 --payload-file -'],
        { input: JSON.stringify({ taskId, taskType: 'MEDIA_PREPARE' }), timeout: 5000 });
    }
    const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    const response = await page.request.post(`/api/v1/tasks/${taskId}/cancel`, { headers: { 'X-XSRF-TOKEN': csrf }, data: {} });
    expect(response.status()).toBe(202);
    await expect.poll(async () => (await (await page.request.get(`/api/v1/tasks/${taskId}`)).json()).data.status,
      { timeout: 15000, intervals: [100] }).toBe('CANCELLED');
    expect(processes()).toBe('');
    expect((await (await page.request.get(`/api/v1/tasks/${taskId}`)).json()).data.attemptCount).toBe(1);
    await expect(page.getByRole('button', { name: '播放 cancel.mp4', exact: true })).toHaveCount(0);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

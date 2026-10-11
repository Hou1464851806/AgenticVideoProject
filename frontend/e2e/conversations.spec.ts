import { test, expect, type BrowserContext } from '@playwright/test';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { createHash } from 'node:crypto';

let fixtureCookies: Awaited<ReturnType<BrowserContext['cookies']>> = [];

test.beforeEach(async ({ page }) => {
  test.setTimeout(900000);
  const location = resolve('../.scratch/asr-fixture/search-seed.json');
  const hash = createHash('sha256').update(readFileSync('e2e/fixtures/labelled-speech.mp4')).digest('hex');
  if (existsSync(location)) {
    const seed = JSON.parse(readFileSync(location,'utf8'));
    if (fixtureCookies.length && seed.hash === hash) {
      await page.context().addCookies(fixtureCookies);
      const asset = await page.request.get(`/api/v1/assets/${seed.assetId}`);
      if (asset.ok() && (await asset.json()).data.analysis?.stages.ASR === 'SUCCEEDED') return;
      await page.context().clearCookies();
    }
    const token = (await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    const login = await page.request.post('/api/v1/auth/login', {headers:{'X-XSRF-TOKEN':token},
      data:{username:seed.username,password:'analysis-browser-password'}});
    if (login.ok() && seed.hash === hash) {
      const asset = await page.request.get(`/api/v1/assets/${seed.assetId}`);
      if (asset.ok() && (await asset.json()).data.analysis?.stages.ASR === 'SUCCEEDED') {
        fixtureCookies = await page.context().cookies();
        return;
      }
    }
  }
  await page.context().clearCookies();
  await page.goto('/');
  const username = `conversation-fixture-${Date.now()}`;
  await page.getByRole('button',{name:'创建账号',exact:true}).click();
  await page.getByLabel('用户名',{exact:true}).fill(username);
  await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
  await page.getByRole('button',{name:'注册',exact:true}).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByLabel('项目名称',{exact:true}).fill('原句检索');
  await page.getByRole('button',{name:'创建项目',exact:true}).click();
  await page.getByRole('button',{name:'原句检索',exact:true}).click();
  const completed = page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
  const assetId = (await(await completed).json()).data.assetId;
  const projectId = new URL(page.url()).searchParams.get('project');
  mkdirSync(dirname(location),{recursive:true});
  writeFileSync(location,JSON.stringify({username,projectId,assetId,hash}),{mode:0o600});
  await expect.poll(async ()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,
    {timeout:660000}).toBe('SUCCEEDED');
  fixtureCookies = await page.context().cookies();
});

test('real DeepSeek answers a natural language speech query with verifiable evidence in an independent session', async ({ page }) => {
  test.setTimeout(900000);
  const seed = JSON.parse(readFileSync(resolve('../.scratch/asr-fixture/search-seed.json'), 'utf8'));
  const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const headers = { 'X-XSRF-TOKEN': csrf };
  const created = await page.request.post(`/api/v1/projects/${seed.projectId}/conversations`, {
    headers, data: { title: `真实模型查询-${Date.now()}`, assetIds: [seed.assetId] },
  });
  expect(created.status()).toBe(201);
  const session = (await created.json()).data.id;
  const sent = await page.request.post(`/api/v1/conversations/${session}/turns`, {
    headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
    data: { content: '素材中的讲述者叫什么名字？请查证语音原文并引用证据。', references: [{ type: 'ASSET', id: seed.assetId }] },
  });
  expect(sent.status()).toBe(202);
  const task = (await sent.json()).data.taskId;
  await expect.poll(async () => (await (await page.request.get(`/api/v1/tasks/${task}`)).json()).data.attemptCount,
    { timeout: 15000 }).toBeGreaterThan(0);
  await expect.poll(async () => (await (await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,
    { timeout: 180000 }).toBe('SUCCEEDED');
  const history = (await (await page.request.get(`/api/v1/conversations/${session}/messages`)).json()).data.items;
  expect(history).toHaveLength(2);
  const answer = history[1];
  expect(answer.role).toBe('ASSISTANT');
  expect(answer.content).toContain('张明');
  expect(answer.references.length).toBeGreaterThan(0);
  for (const citation of answer.references) {
    expect(citation).toMatchObject({ type: 'EVIDENCE', assetId: seed.assetId, sourceKind: 'SPEECH' });
    const context = await page.request.get(`/api/v1/evidence/${citation.id}/context`);
    expect(context.status()).toBe(200);
    const observations = (await context.json()).data.observations;
    expect(observations.some((item: {id:string;text:string}) => item.id === citation.id && item.text === citation.text)).toBe(true);
  }
  expect(JSON.stringify(history)).not.toMatch(/reasoning_content|leaseToken|objectKey|api_key|Authorization|\/work\//i);
});

test('browser sessions keep separate context and allow a draft while real model work is running', async ({ page }) => {
  test.setTimeout(900000);
  const seed = JSON.parse(readFileSync(resolve('../.scratch/asr-fixture/search-seed.json'), 'utf8'));
  await page.goto(`/?project=${seed.projectId}`);
  await page.getByLabel('会话名称', { exact: true }).fill(`制作步骤-${Date.now()}`, {timeout:5000});
  const creation = page.waitForResponse(response => response.url().endsWith('/conversations') && response.request().method() === 'POST');
  await page.getByRole('button', { name: '创建制作会话', exact: true }).click();
  const firstSession = (await (await creation).json()).data.id;
  await page.getByLabel('引用素材 labelled-speech.mp4', { exact: true }).check();
  await page.getByLabel('对话输入', { exact: true }).fill('请记住本会话的代号是海棠。素材介绍了多少个制作步骤？请引用语音证据。');
  const accepted = page.waitForResponse(response => response.url().endsWith('/turns') && response.request().method() === 'POST');
  await page.getByRole('button', { name: '发送需求', exact: true }).click();
  expect((await accepted).status()).toBe(202);
  await expect(page.getByRole('button', { name: '发送需求', exact: true })).toBeDisabled();
  await page.getByLabel('对话输入', { exact: true }).fill('任务运行时仍可编辑的草稿');
  await expect(page.getByLabel('对话输入', { exact: true })).toHaveValue('任务运行时仍可编辑的草稿');
  const answer = page.getByRole('article', { name: '助手答复' }).last();
  await expect(answer).toContainText(/三个|3\s*个/, { timeout: 180000 });
  await expect(answer.getByRole('button', { name: '核查引用原视频', exact: true }).first()).toBeVisible();
  await answer.getByRole('button', { name: '核查引用原视频', exact: true }).first().click();
  await expect(page.getByLabel('视频播放器')).toBeVisible();
  const history = (await (await page.request.get(`/api/v1/conversations/${firstSession}/messages`)).json()).data.items;
  expect(history).toHaveLength(2);
  expect(history[0].references).toEqual([{type:'ASSET',id:seed.assetId}]);
  await page.getByLabel('对话输入', {exact:true}).fill('我刚才为本会话指定的代号是什么？请依据本会话历史回答，不要猜测。');
  await page.getByRole('button', {name:'发送需求',exact:true}).click();
  await expect(page.getByRole('article', {name:'助手答复'})).toHaveCount(2, {timeout:180000});
  await expect(page.getByRole('article', {name:'助手答复'}).last()).toContainText('海棠');
  await page.getByLabel('会话名称', { exact: true }).fill(`独立会话-${Date.now()}`);
  await page.getByRole('button', { name: '创建制作会话', exact: true }).click();
  await expect(page.getByRole('article', { name: '助手答复' })).toHaveCount(0);
  await page.getByLabel('对话输入', { exact: true }).fill('我之前在本会话指定的代号是什么？没有记录就直接说明不知道，不要猜测。');
  await page.getByRole('button', { name: '发送需求', exact: true }).click();
  await expect(page.getByRole('article', { name: '助手答复' })).toBeVisible({ timeout: 180000 });
  await expect(page.getByRole('article', { name: '助手答复' })).not.toContainText('海棠');
});

test('two actual browser tabs compete for one turn and an empty fixed scope yields an honest answer', async ({ page, context }) => {
  test.setTimeout(900000);
  const seed = JSON.parse(readFileSync(resolve('../.scratch/asr-fixture/search-seed.json'), 'utf8'));
  const token = (await (await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const created = await page.request.post(`/api/v1/projects/${seed.projectId}/conversations`, {
    headers: {'X-XSRF-TOKEN':token}, data: {title:`双标签页-${Date.now()}`,assetIds:[seed.assetId]},
  });
  expect(created.status()).toBe(201);
  const session = (await created.json()).data.id;
  const tab = await context.newPage();
  await Promise.all([page.goto(`/?project=${seed.projectId}`), tab.goto(`/?project=${seed.projectId}`)]);
  const content = '在这个会话的固定素材范围内，讲述者叫什么名字？没有语音证据就明确说明。';
  const path = `/api/v1/conversations/${session}/turns`;
  const send = (target: typeof page, key: string) => target.evaluate(async input => {
    const response = await fetch(input.path, {method:'POST',credentials:'same-origin',
      headers:{'Content-Type':'application/json','X-XSRF-TOKEN':input.token,'Idempotency-Key':input.key},
      body:JSON.stringify({content:input.content,references:[],context:{assetIds:[]}})});
    return {status:response.status,body:await response.json()};
  }, {path,token,key,content});
  const keys = [crypto.randomUUID(),crypto.randomUUID()];
  const results = await Promise.all([send(page,keys[0]!),send(tab,keys[1]!)]);
  expect(results.map(item=>item.status).sort()).toEqual([202,409]);
  const winner = results.findIndex(item=>item.status===202);
  const identity = results[winner]!.body.data;
  const duplicate = await send(tab,keys[winner]!);
  expect(duplicate.status).toBe(202);
  expect(duplicate.body.data).toEqual(identity);
  await expect.poll(async ()=>(await(await page.request.get(`/api/v1/tasks/${identity.taskId}`)).json()).data.status,
    {timeout:120000}).toBe('SUCCEEDED');
  const history = (await(await page.request.get(`/api/v1/conversations/${session}/messages`)).json()).data.items;
  expect(history).toHaveLength(2);
  expect(history[1].content).toMatch(/没有|无法|缺少|无可用/);
  expect(history[1].content).not.toContain('张明');
  expect(history[1].references).toEqual([]);
  await tab.close();
});

test('a video uploaded after opening the workspace becomes selectable for a new fixed session scope', async ({ page }) => {
  const token = (await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const name = `会话上传联动-${Date.now()}`;
  const created = await page.request.post('/api/v1/projects', {headers:{'X-XSRF-TOKEN':token},data:{name}});
  expect(created.status()).toBe(201);
  const project = (await created.json()).data.id;
  await page.goto(`/?project=${project}`);
  await expect(page.getByLabel('会话名称',{exact:true})).toBeVisible();
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/variable-frame-rate.mp4');
  await expect(page.getByRole('button',{name:'播放 variable-frame-rate.mp4',exact:true})).toBeVisible({timeout:30000});
  await expect(page.getByRole('group',{name:'新会话的固定素材范围',exact:true})
    .getByRole('checkbox',{name:'variable-frame-rate.mp4',exact:true})).toBeChecked({timeout:5000});
  await page.getByLabel('会话名称',{exact:true}).fill('刚上传的视频');
  const accepted = page.waitForResponse(response=>response.url().endsWith('/conversations')&&response.request().method()==='POST');
  await page.getByRole('button',{name:'创建制作会话',exact:true}).click();
  const scope = (await(await accepted).json()).data.assetIds;
  expect(scope).toHaveLength(1);
  const available = (await(await page.request.get(`/api/v1/projects/${project}/assets`)).json()).data.items;
  expect(scope).toEqual([available[0].id]);
});

test('different evidence times in one video seek even when playback access returns the same signed URL', async ({ page }) => {
  const seed = JSON.parse(readFileSync(resolve('../.scratch/asr-fixture/search-seed.json'), 'utf8'));
  const post = async (path: string, data: unknown) => {
    const token = (await (await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post(`/api/v1${path}`, { headers: { 'X-XSRF-TOKEN': token }, data });
  };
  const access = await post(`/assets/${seed.assetId}/playback-access`, {});
  expect(access.status()).toBe(200);
  // Replay a genuinely authorized response so identical signed URLs are deterministic.
  // Evidence queries, video bytes, timestamps and user actions remain real.
  const authorized = await access.text();
  await page.route(`**/api/v1/assets/${seed.assetId}/playback-access`, route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: authorized }));
  await page.goto(`/?project=${seed.projectId}`);
  const searched = page.waitForResponse(response => response.url().endsWith('/search') && response.request().method() === 'POST');
  await page.getByLabel('查找原句', { exact: true }).fill('步');
  await page.getByRole('button', { name: '查找语音', exact: true }).click();
  const hits = (await (await searched).json()).data.hits;
  expect(hits.length).toBeGreaterThan(1);
  expect(hits[1].startMs).not.toBe(hits[0].startMs);
  const cards = page.getByRole('article', { name: '语音检索证据' });
  await cards.nth(0).getByRole('button', { name: '核查原视频', exact: true }).click();
  const video = page.getByLabel('视频播放器', { exact: true });
  await expect.poll(() => video.evaluate((node: HTMLVideoElement) => node.readyState)).toBeGreaterThanOrEqual(1);
  await expect.poll(() => video.evaluate((node: HTMLVideoElement) => node.currentTime)).toBeGreaterThanOrEqual(hits[0].startMs / 1000);
  await video.evaluate((node: HTMLVideoElement) => { node.pause(); node.currentTime = 0; });
  await cards.nth(1).getByRole('button', { name: '核查原视频', exact: true }).click();
  await expect.poll(() => video.evaluate((node: HTMLVideoElement) => node.currentTime)).toBeGreaterThanOrEqual(hits[1].startMs / 1000);
  expect(await video.evaluate((node: HTMLVideoElement) => node.currentTime)).toBeLessThan(hits[1].startMs / 1000 + 2);
  await expect(page.getByLabel('定位秒数', { exact: true })).toHaveValue(String(hits[1].startMs / 1000));
});


test('real Agent keeps the complete human-transcribed viewpoint inside a hard duration range', async ({ page }) => {
  page.setDefaultTimeout(30000);
  const post = async (path: string, data: unknown, key = crypto.randomUUID()) => {
    const token = (await (await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post(`/api/v1${path}`, { headers: { 'X-XSRF-TOKEN': token, 'Idempotency-Key': key }, data });
  };
  const project = (await (await post('/projects', { name: '人工讲话的完整观点' })).json()).data;
  await page.goto(`/?project=${project.id}`);
  const uploaded = page.waitForResponse(response => response.url().endsWith('/complete'));
  await page.getByLabel('选择视频', { exact: true }).setInputFiles('e2e/fixtures/human-speech.mp4');
  const assetId = (await (await uploaded).json()).data.assetId;
  await expect.poll(async () => (await (await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,
    { timeout: 180000 }).toBe('SUCCEEDED');
  const originalAnalysis = (await (await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
  const created = await post(`/projects/${project.id}/conversations`, { title: '篇章结束与新的开始', assetIds: [assetId] });
  expect(created.status()).toBe(201);
  const session = (await created.json()).data;
  const request = { content: '生成一条7至11秒的横屏短视频方案，主题是篇章结束与新的开始。必须完整保留“这并不是告别”以及“这是一个篇章的结束，也是新篇章的开始”，不要截断观点。可以保留原视频中的句前句后停顿，但不要添加新素材、变速或重复片段。',
    context: { editing: { topic: '篇章结束与新的开始', minDurationMs: 7000, maxDurationMs: 11000, profile: 'LANDSCAPE_720' } } };
  const accepted = await post(`/conversations/${session.id}/turns`, request, 'human-initial-plan');
  expect(accepted.status()).toBe(202);
  const identity = (await accepted.json()).data;
  await expect.poll(async () => (await (await page.request.get(`/api/v1/tasks/${identity.taskId}`)).json()).data.status,
    { timeout: 180000 }).toBe('SUCCEEDED');
  const versions = (await (await page.request.get(`/api/v1/plans/${identity.planId}/revisions`)).json()).data.items;
  expect(versions).toHaveLength(1);
  const revision = (await (await page.request.get(`/api/v1/revisions/${versions[0].id}`)).json()).data;
  expect(revision.content.outputs).toHaveLength(1);
  const output = revision.content.outputs[0];
  expect(output.durationMs).toBeGreaterThanOrEqual(7000);
  expect(output.durationMs).toBeLessThanOrEqual(11000);
  // Independent human reference: FLEURS utterance 1906, attribution in fixtures/LICENSES.md.
  const words = output.subtitles.cues.map((cue: {text:string}) => cue.text).join('').replace(/[^\p{Script=Han}]/gu, '');
  expect(words).toBe('这并不是告别这是一个篇章的结束也是新篇章的开始');
  for (const clip of output.clips) {
    expect(clip.assetId).toBe(assetId); expect(clip.sourceInMs).toBeGreaterThanOrEqual(0);
    expect(clip.sourceOutMs).toBeLessThanOrEqual(11000); expect(clip.sourceOutMs).toBeGreaterThan(clip.sourceInMs);
    expect(clip.reason.length).toBeGreaterThan(0); expect(clip.evidenceIds.length).toBeGreaterThan(0);
  }
  const history = (await (await page.request.get(`/api/v1/conversations/${session.id}/messages`)).json()).data.items;
  expect(history[1].references).toContainEqual(expect.objectContaining({ type: 'PLAN_REVISION', id: revision.id }));
  await page.goto(`/?project=${project.id}`);
  await expect(page.getByLabel('需求类型', {exact:true})).toBeVisible();
  await page.getByRole('button', {name:'查看方案 V1',exact:true}).click();
  await expect(page.getByLabel('查看版本', {exact:true})).toHaveValue(revision.id);
  const selectedClip = page.getByRole('article', {name:'方案剪辑片段'}).first();
  await expect(selectedClip).toContainText('human-speech.mp4');
  await selectedClip.getByRole('button', {name:'播放源片段',exact:true}).click();
  const video = page.getByLabel('视频播放器', {exact:true});
  await expect.poll(() => video.evaluate((node: HTMLVideoElement) => node.readyState)).toBeGreaterThanOrEqual(1);
  const endSeconds = output.clips[0].sourceOutMs/1000;
  await video.evaluate((node: HTMLVideoElement, end: number) => {node.currentTime=end-0.05;void node.play();}, endSeconds);
  await expect.poll(() => video.evaluate((node: HTMLVideoElement) => node.paused)).toBe(true);
  expect(await video.evaluate((node: HTMLVideoElement) => node.currentTime)).toBeLessThanOrEqual(endSeconds+0.15);
  await page.getByLabel('需求类型', {exact:true}).selectOption('GENERATE_PLAN');
  await page.getByLabel('方案主题', {exact:true}).fill('篇章结束与新的开始');
  await page.getByLabel('最短时长（秒）', {exact:true}).fill('7');
  await page.getByLabel('最长时长（秒）', {exact:true}).fill('11');
  await page.getByLabel('对话输入', {exact:true}).fill(request.content);
  const uiAccepted = page.waitForResponse(response => response.url().endsWith('/turns') && response.request().method()==='POST');
  await page.getByRole('button', {name:'发送需求',exact:true}).click();
  const uiResponse = await uiAccepted;
  expect(uiResponse.status()).toBe(202);
  const uiIdentity = (await uiResponse.json()).data;
  expect(uiIdentity.planId).not.toBe(identity.planId);
  await expect(page.getByRole('article', {name:'助手答复'})).toHaveCount(2, {timeout:180000});
  await expect(page.getByRole('article', {name:'助手答复'}).last().getByRole('button', {name:'查看方案 V1',exact:true})).toBeVisible();
  await expect(page.getByLabel('查看版本', {exact:true})).toHaveValue(revision.id);
  const reusedAnalysis = (await (await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
  expect(reusedAnalysis.providerCalls).toEqual(originalAnalysis.providerCalls);
  expect(reusedAnalysis.observations).toEqual(originalAnalysis.observations);
  const impossible = await post(`/conversations/${session.id}/turns`, {
    content: '只用这份11秒原视频生成12至13秒方案，不可添加素材、变速或重复。若不能满足，请说明原因。',
    context: { editing: { topic:'篇章结束与新的开始', minDurationMs:12000, maxDurationMs:13000, profile:'LANDSCAPE_720' } },
  });
  expect(impossible.status()).toBe(202);
  const impossibleIdentity = (await impossible.json()).data;
  await expect.poll(async () => (await (await page.request.get(`/api/v1/tasks/${impossibleIdentity.taskId}`)).json()).data.status,
    {timeout:180000}).toBe('SUCCEEDED');
  expect((await (await page.request.get(`/api/v1/plans/${impossibleIdentity.planId}/revisions`)).json()).data.items).toHaveLength(0);
  const explanation = (await (await page.request.get(`/api/v1/conversations/${session.id}/messages`)).json()).data.items.at(-1);
  expect(explanation.role).toBe('ASSISTANT');
  expect(explanation.content).toMatch(/无法|不能|不足|不满足|不够/);
  const replay = (await (await post(`/conversations/${session.id}/turns`, request, 'human-initial-plan')).json()).data;
  expect(replay).toEqual(identity);
  expect((await (await page.request.get(`/api/v1/plans/${identity.planId}/revisions`)).json()).data.items).toHaveLength(1);
  await page.getByRole('button', {name:'返回项目列表',exact:true}).click();
  const isolatedProjectName = `无方案隔离项目-${Date.now()}`;
  await page.getByLabel('项目名称', {exact:true}).fill(isolatedProjectName);
  await page.getByRole('button', {name:'创建项目',exact:true}).click();
  const projectSwitchRequests: string[] = [];
  page.on('request', request => projectSwitchRequests.push(request.url()));
  await page.getByRole('button', {name:isolatedProjectName,exact:true}).click();
  await page.waitForLoadState('networkidle');
  expect(projectSwitchRequests.some(url => url.includes(`/conversations/${session.id}/messages`))).toBe(false);
  await expect(page.getByRole('region', {name:'编排剪辑方案'})).toContainText('暂无方案');
  await expect(page.getByLabel('查看版本', {exact:true})).toHaveCount(0);
  await expect(page.getByRole('article', {name:'方案剪辑片段'})).toHaveCount(0);
});

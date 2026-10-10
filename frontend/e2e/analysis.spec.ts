import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';

test('human-transcribed Chinese speech is transferred and retained as provider observations', async ({ page }) => {
  test.setTimeout(180000);
  await page.goto('/');
  await page.getByRole('button',{name:'创建账号',exact:true}).click();
  await page.getByLabel('用户名',{exact:true}).fill(`analysis-human-${Date.now()}`);
  await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
  await page.getByRole('button',{name:'注册',exact:true}).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByLabel('项目名称',{exact:true}).fill('人工转写样本');
  await page.getByRole('button',{name:'创建项目',exact:true}).click();
  await page.getByRole('button',{name:'人工转写样本',exact:true}).click();
  const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/human-speech.mp4');
  const assetId=(await(await completed).json()).data.assetId;
  await expect.poll(async ()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,{timeout:120000}).toBe('SUCCEEDED');
  const evidence=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
  const text=evidence.observations.map((item:{text:string})=>item.text).join('').replace(/[^\p{Script=Han}]/gu,'');
  expect(text).toBe('这并不是告别这是一个篇章的结束也是新篇章的开始');
  for(const sentence of evidence.observations) {
    expect(sentence.startMs).toBeGreaterThanOrEqual(0);
    expect(sentence.endMs).toBeLessThanOrEqual(11000);
    expect(sentence.endMs).toBeGreaterThan(sentence.startMs);
    expect(sentence.words.length).toBeGreaterThan(0);
  }
});

test('a verified silent video exposes independent stages and never invents speech evidence', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  const username = `analysis-silent-${Date.now()}`;
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('analysis-browser-password');
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  await page.getByLabel('密码', { exact: true }).fill('analysis-browser-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByLabel('项目名称', { exact: true }).fill('无音轨阶段');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '无音轨阶段', exact: true }).click();
  const completed = page.waitForResponse(response => response.url().endsWith('/complete'));
  await page.getByLabel('选择视频', { exact: true }).setInputFiles('e2e/fixtures/variable-frame-rate.mp4');
  const assetId = (await (await completed).json()).data.assetId;
  await expect(page.getByRole('button', { name: '播放 variable-frame-rate.mp4', exact: true })).toBeVisible({ timeout: 30000 });
  const asset = (await (await page.request.get(`/api/v1/assets/${assetId}`)).json()).data;
  expect(asset.analysis.stages).toMatchObject({ MEDIA: 'SUCCEEDED', ASR: 'NOT_APPLICABLE', INDEX: 'NOT_APPLICABLE' });
  const evidence = await page.request.get(`/api/v1/assets/${assetId}/evidence`);
  expect(evidence.status()).toBe(200);
  expect((await evidence.json()).data.observations).toEqual([]);
  await page.getByRole('button', { name: '播放 variable-frame-rate.mp4', exact: true }).click();
  await expect(page.getByText('无音轨，不能进行语音分析', { exact: true })).toBeVisible();
  await expect(page.getByText('媒体：已就绪', { exact: true })).toBeVisible();
  await expect(page.getByText('语音：不适用', { exact: true })).toBeVisible();
  await expect(page.getByText('索引：不适用', { exact: true })).toBeVisible();
});

test('a lost real provider query response recovers the original ASR task without a second submission', async ({ page }) => {
  test.setTimeout(180000);
  let submissions=0;
  let dropped=false;
  let queryHeld=false;
  let releaseQuery!:()=>void;
  const queryGate=new Promise<void>(resolve=>{releaseQuery=resolve;});
  const queried:string[]=[];
  const proxy=createServer(async (request,response) => {
    try {
      const chunks:Buffer[]=[];
      for await (const chunk of request) chunks.push(Buffer.from(chunk));
      const path=request.url!;
      if(path.includes('/asr/transcription')) submissions++;
      const query=path.match(/\/tasks\/([^/?]+)/);
      if(query) queried.push(query[1]!);
      const upstream=await fetch(`https://dashscope.aliyuncs.com${path}`, {
        method:request.method,
        headers:Object.fromEntries(Object.entries(request.headers).filter(([name])=>!['host','connection','content-length','transfer-encoding'].includes(name))) as Record<string,string>,
        body:request.method==='POST'?Buffer.concat(chunks):undefined,
      });
      const result=Buffer.from(await upstream.arrayBuffer());
      if(query && !dropped) { queryHeld=true;await queryGate;dropped=true;response.destroy();return; }
      response.writeHead(upstream.status,{'Content-Type':'application/json'});response.end(result);
    } catch { response.writeHead(502);response.end('{}'); }
  });
  await new Promise<void>(resolve=>proxy.listen(0,'0.0.0.0',resolve));
  const address=proxy.address();
  if(!address || typeof address==='string') throw new Error('Provider fault proxy unavailable');
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','40','agent-service'],{env,timeout:60000,stdio:'pipe'});
  try {
    recreate({...process.env,DASHSCOPE_BASE_URL:`http://host.docker.internal:${address.port}/api/v1`});
    await page.goto('/');
    await page.getByRole('button',{name:'创建账号',exact:true}).click();
    await page.getByLabel('用户名',{exact:true}).fill(`analysis-poll-loss-${Date.now()}`);
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
    await page.getByRole('button',{name:'注册',exact:true}).click();
    await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
    await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.getByLabel('项目名称',{exact:true}).fill('模型查询丢失恢复');
    await page.getByRole('button',{name:'创建项目',exact:true}).click();
    await page.getByRole('button',{name:'模型查询丢失恢复',exact:true}).click();
    const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
    await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
    const assetId=(await(await completed).json()).data.assetId;
    await expect.poll(()=>queryHeld).toBe(true);
    await page.getByRole('button',{name:'播放 labelled-speech.mp4',exact:true}).click();
    await expect(page.getByText('语音：正在分析',{exact:true})).toBeVisible();
    releaseQuery();
    await expect.poll(async ()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,{timeout:120000}).toBe('SUCCEEDED');
    await expect(page.getByText('语音：已就绪',{exact:true})).toBeVisible();
    await expect(page.getByRole('button',{name:/^核查语音：/}).first()).toBeVisible();
    expect(dropped).toBe(true);
    expect(submissions).toBe(1);
    expect(queried.length).toBeGreaterThan(1);
    expect(new Set(queried).size).toBe(1);
    const evidence=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
    expect(evidence.providerCalls).toHaveLength(1);
    expect(evidence.providerCalls[0].providerTaskId).toBe(queried[0]);
    const taskId=evidence.providerCalls[0].taskId;
    const attempts=(await(await page.request.get(`/api/v1/tasks/${taskId}`)).json()).data.attemptCount;
    for(let duplicate=0;duplicate<3;duplicate++) {
      execFileSync(docker,['compose','exec','-T','rabbitmq','sh','-c',
        'RABBITMQADMIN_USERNAME="$RABBITMQ_DEFAULT_USER" RABBITMQADMIN_PASSWORD="$RABBITMQ_DEFAULT_PASS" rabbitmqadmin --quiet publish message --routing-key analysis.v1 --payload-file -'],
        {input:JSON.stringify({taskId,taskType:'CONTENT_ANALYZE'}),timeout:5000,stdio:['pipe','pipe','pipe']});
    }
    await expect.poll(()=> {
      const queues=execFileSync(docker,['compose','exec','-T','rabbitmq','rabbitmqctl','-q','list_queues','name','messages'],{timeout:5000}).toString();
      return Number(queues.split('\n').find(line=>line.startsWith('analysis.v1\t'))?.split('\t')[1]);
    },{timeout:20000}).toBe(0);
    expect(submissions).toBe(1);
    expect((await(await page.request.get(`/api/v1/tasks/${taskId}`)).json()).data.attemptCount).toBe(attempts);
  } finally {
    releaseQuery();
    recreate({...process.env});
    await new Promise<void>(resolve=>proxy.close(()=>resolve()));
  }
});

test('uploaded Chinese speech becomes durable timed evidence and seeks the original offset video', async ({ page }) => {
  test.setTimeout(180000);
  await page.goto('/');
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await page.getByLabel('用户名', { exact: true }).fill(`analysis-speech-${Date.now()}`);
  await page.getByLabel('密码', { exact: true }).fill('analysis-browser-password');
  await page.getByRole('button', { name: '注册', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  await page.getByLabel('密码', { exact: true }).fill('analysis-browser-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByLabel('项目名称', { exact: true }).fill('中文语音证据');
  await page.getByRole('button', { name: '创建项目', exact: true }).click();
  await page.getByRole('button', { name: '中文语音证据', exact: true }).click();
  const completed = page.waitForResponse(response => response.url().endsWith('/complete'));
  await page.getByLabel('选择视频', { exact: true }).setInputFiles('e2e/fixtures/labelled-speech.mp4');
  const assetId = (await (await completed).json()).data.assetId;
  await expect.poll(async () => (await (await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,
    { timeout: 120000 }).toBe('SUCCEEDED');
  const evidence = (await (await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
  expect(evidence.observations.length).toBeGreaterThan(1);
  const sentence = evidence.observations.find((item: { text: string }) => item.text.includes('上传素材'));
  expect(sentence).toBeTruthy();
  expect(sentence.startMs - sentence.audioStartMs).toBe(1436);
  expect(sentence.words.length).toBeGreaterThan(0);
  for(const word of sentence.words) {
    expect(word.startMs-word.audioStartMs).toBe(1436);
    expect(word.endMs-word.audioEndMs).toBe(1436);
  }
  expect(evidence.units.length).toBeGreaterThan(0);
  expect(evidence.summary.text).toContain('视频制作');
  expect(evidence.providerCalls).toHaveLength(1);
  expect(evidence.providerCalls[0]).toMatchObject({ status: 'SUCCEEDED', model: 'qwen3-asr-flash-filetrans' });
  expect(evidence.providerCalls[0].usage.seconds).toBeGreaterThan(0);
  await page.getByRole('button', { name: '播放 labelled-speech.mp4', exact: true }).click();
  await page.getByRole('button', { name: `核查语音：${sentence.text}`, exact: true }).click();
  const player = page.getByLabel('视频播放器', { exact: true });
  await expect.poll(() => player.evaluate((video: HTMLVideoElement) => video.currentTime)).toBeGreaterThanOrEqual(sentence.startMs / 1000 - 0.05);
  await player.evaluate((video: HTMLVideoElement) => video.pause());
  await page.reload();
  const restored = (await (await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
  expect(restored.observations.map((item: { id: string }) => item.id)).toEqual(evidence.observations.map((item: { id: string }) => item.id));
  expect(restored.providerCalls).toHaveLength(1);
});

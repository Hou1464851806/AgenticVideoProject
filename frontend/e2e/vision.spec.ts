import {test,expect} from '@playwright/test';
import {mkdir,writeFile,readFile} from 'node:fs/promises';

test('real visual analysis retains repeated frame times and searchable visible text in a VFR video',async({page})=>{
  test.setTimeout(240000);
  const post=async(path:string,data:unknown)=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf},data});
  };
  const username=`vision-vfr-${Date.now()}`;
  expect((await post('/auth/register',{username,password:'vision-browser-password'})).status()).toBe(201);
  expect((await post('/auth/login',{username,password:'vision-browser-password'})).status()).toBe(200);
  const project=(await(await post('/projects',{name:'人工标注画面与文字'})).json()).data;
  await page.goto(`/?project=${project.id}`);
  const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-vision-vfr.mp4');
  const assetId=(await(await completed).json()).data.assetId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.validationStatus,{timeout:30000}).toBe('VALID');
  const accepted=await post(`/assets/${assetId}/vision-analysis`,{});
  expect(accepted.status()).toBe(202);
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis.stages.VISION,{timeout:180000}).toBe('SUCCEEDED');
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis.stages.INDEX,{timeout:60000}).toBe('SUCCEEDED');
  const evidence=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
  expect(evidence.observations).toEqual([]);
  expect(evidence.vision.frames.map((frame:{observedAtMs:number})=>frame.observedAtMs)).toEqual([0,2320,4760,7080]);
  expect(evidence.vision.coverage).toMatchObject({kind:'SPARSE_FRAMES',complete:false});
  expect(evidence.vision.providerCalls).toHaveLength(2);
  for(const call of evidence.vision.providerCalls){
    expect(call.model).toBe('qwen3-vl-plus');
    expect(call.status).toBe('SUCCEEDED');
    expect(call.requestId).toBeTruthy();
    expect(call.usage).toBeTruthy();
  }
  const result=(await(await post(`/projects/${project.id}/search`,{query:'证据42',literalPhrase:true,sourceKinds:['VISIBLE_TEXT']})).json()).data;
  expect(result.hits.map((hit:{observedAtMs:number})=>hit.observedAtMs).sort((a:number,b:number)=>a-b)).toEqual([0,4760,7080]);
  for(const hit of result.hits){
    expect(hit.sourceKind).toBe('VISIBLE_TEXT');
    expect(hit.temporalPrecision).toBe('FRAME_OBSERVATION');
    expect(hit.startMs).toBeUndefined();expect(hit.endMs).toBeUndefined();
    const frame=await post(`/evidence/${hit.evidenceIds[0]}/frame-access`,{});
    expect(frame.status()).toBe(200);
    const access=(await frame.json()).data;
    expect(access.observedAtMs).toBe(hit.observedAtMs);
    expect((await page.request.get(access.url)).status()).toBe(200);
  }
  await mkdir('../.scratch/vision-fixture',{recursive:true});
  await writeFile('../.scratch/vision-fixture/seed.json',JSON.stringify({username,projectId:project.id,assetId}));
});

test('viewer can distinguish visible text, inspect its frame and seek its observation point',async({page})=>{
  const seed=JSON.parse(await readFile('../.scratch/vision-fixture/seed.json','utf8'));
  const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  expect((await page.request.post('/api/v1/auth/login',{headers:{'X-XSRF-TOKEN':csrf},data:{username:seed.username,password:'vision-browser-password'}})).status()).toBe(200);
  await page.goto(`/?project=${seed.projectId}`);
  await page.getByLabel('证据类型',{exact:true}).selectOption('VISIBLE_TEXT');
  await page.getByLabel('查找原句',{exact:true}).fill('证据42');
  await page.getByRole('button',{name:'查找证据',exact:true}).click();
  const cards=page.getByRole('article',{name:'可见文字检索证据',exact:true});
  await expect(cards).toHaveCount(3);
  await expect(page.getByText('代表帧为稀疏采样；未命中不能证明画面或文字未出现。',{exact:true})).toBeVisible();
  await cards.first().getByRole('button',{name:'查看观察帧',exact:true}).click();
  await expect(cards.first().getByRole('img',{name:'证据观察帧',exact:true})).toBeVisible();
  await cards.first().getByRole('button',{name:'查看邻近上下文',exact:true}).click();
  await expect(cards.first().getByRole('region',{name:'邻近原文',exact:true})).toContainText('帧观察时刻');
  await cards.first().getByRole('button',{name:'核查原视频',exact:true}).click();
  await expect(page.getByText('画面：已就绪',{exact:true})).toBeVisible();
});

test('speech mentioning a cat never becomes visual appearance and both sources remain indexed',async({page})=>{
  test.setTimeout(240000);
  const post=async(path:string,data:unknown)=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf},data});
  };
  const username=`speech-visual-${Date.now()}`;
  await post('/auth/register',{username,password:'vision-browser-password'});
  await post('/auth/login',{username,password:'vision-browser-password'});
  const project=(await(await post('/projects',{name:'语音提及和实际画面'})).json()).data;
  await page.goto(`/?project=${project.id}`);
  const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/speech-mention-only.mp4');
  const asset=(await(await completed).json()).data.assetId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${asset}`)).json()).data.validationStatus,{timeout:30000}).toBe('VALID');
  expect((await post(`/assets/${asset}/vision-analysis`,{})).status()).toBe(202);
  await expect.poll(async()=>{
    const stages=(await(await page.request.get(`/api/v1/assets/${asset}`)).json()).data.analysis.stages;
    return [stages.ASR,stages.VISION,stages.INDEX];
  },{timeout:180000}).toEqual(['SUCCEEDED','SUCCEEDED','SUCCEEDED']);
  const evidence=(await(await page.request.get(`/api/v1/assets/${asset}/evidence`)).json()).data;
  expect(evidence.observations.map((item:{text:string})=>item.text).join('')).toContain('猫');
  expect(evidence.vision.observations.filter((item:{sourceKind:string})=>item.sourceKind==='VISUAL').map((item:{text:string})=>item.text).join('')).not.toContain('猫');
  expect(evidence.vision.providerCalls).toHaveLength(1);
  const speech=(await(await post(`/projects/${project.id}/search`,{query:'猫',literalPhrase:true,sourceKinds:['SPEECH']})).json()).data;
  expect(speech.hits.length).toBeGreaterThan(0);
  const visual=(await(await post(`/projects/${project.id}/search`,{query:'猫',literalPhrase:true,sourceKinds:['VISUAL']})).json()).data;
  expect(visual.hits).toEqual([]);
  const text=(await(await post(`/projects/${project.id}/search`,{query:'证据42',literalPhrase:true,sourceKinds:['VISIBLE_TEXT']})).json()).data;
  expect(text.hits.length).toBeGreaterThan(0);
  expect(text.coverage.assets[0]).toMatchObject({sourceKind:'VISIBLE_TEXT',status:'SUCCEEDED',sampling:{kind:'SPARSE_FRAMES',complete:false}});
  const state=(await(await page.request.get(`/api/v1/assets/${asset}/search-index`)).json()).data;
  expect(state.publishedGeneration.unitCount).toBe(evidence.units.length);
});

test('real DeepSeek uses visible text evidence and publishes observation point citations',async({page})=>{
  test.setTimeout(150000);
  const seed=JSON.parse(await readFile('../.scratch/vision-fixture/seed.json','utf8'));
  const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':`vision-${Date.now()}`},data});};
  await post('/auth/login',{username:seed.username,password:'vision-browser-password'});
  const conversation=(await(await post(`/projects/${seed.projectId}/conversations`,{title:'真实视觉对话',assetIds:[seed.assetId]})).json()).data;
  const turn=(await(await post(`/conversations/${conversation.id}/turns`,{content:'请查询画面可见文字“证据42”，说明实际观察时刻，引用可见文字证据。只查可见文字，不要推断出现区间。'})).json()).data;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${turn.taskId}`)).json()).data.status,{timeout:120000}).toBe('SUCCEEDED');
  const messages=(await(await page.request.get(`/api/v1/conversations/${conversation.id}/messages`)).json()).data.items;
  const refs=messages.at(-1).references.filter((ref:{type:string})=>ref.type==='EVIDENCE');
  expect(refs.length).toBeGreaterThan(0);
  for(const ref of refs){expect(ref.sourceKind).toBe('VISIBLE_TEXT');expect(ref.observedAtMs).toBeDefined();expect(ref.startMs).toBeUndefined();expect(ref.endMs).toBeUndefined();}
});

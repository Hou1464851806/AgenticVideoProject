import { test, expect, type BrowserContext,type Page,type APIResponse } from '@playwright/test';
import { existsSync,mkdirSync,readFileSync,writeFileSync } from 'node:fs';
import { resolve,dirname } from 'node:path';
import { createHash } from 'node:crypto';

test('literal speech query returns the current source evidence and a candidate window', async ({ page }) => {
  test.setTimeout(720000);
  const seedFile=resolve('../.scratch/asr-fixture/search-seed.json');
  const hash=createHash('sha256').update(readFileSync('e2e/fixtures/labelled-speech.mp4')).digest('hex');
  let assetId='';
  await page.goto('/');
  if(existsSync(seedFile)){
    const seed=JSON.parse(readFileSync(seedFile,'utf8'));
    if(seed.hash===hash){
      const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
      const login=await page.request.post('/api/v1/auth/login',{headers:{'X-XSRF-TOKEN':csrf},data:{username:seed.username,password:'analysis-browser-password'}});
      if(login.ok()){
        const asset=await page.request.get(`/api/v1/assets/${encodeURIComponent(seed.assetId)}`);
        if(asset.ok()){
          assetId=seed.assetId;
          await page.goto(`/?project=${encodeURIComponent(seed.projectId)}`);
        }
      }
    }
  }
  if(!assetId){
    await page.context().clearCookies();
  await page.goto('/');
  await page.getByRole('button',{name:'创建账号',exact:true}).click();
  await page.getByLabel('用户名',{exact:true}).fill(`literal-search-${Date.now()}`);
  await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
  await page.getByRole('button',{name:'注册',exact:true}).click();
  await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
  await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await page.getByLabel('项目名称',{exact:true}).fill('原句检索');
  await page.getByRole('button',{name:'创建项目',exact:true}).click();
  await page.getByRole('button',{name:'原句检索',exact:true}).click();
  const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
    assetId=(await(await completed).json()).data.assetId;
    mkdirSync(dirname(seedFile),{recursive:true});
    writeFileSync(seedFile,JSON.stringify({assetId,projectId:new URL(page.url()).searchParams.get('project'),username:await page.locator('.session-bar p').innerText().then(text=>text.replace('已登录：','')),hash}),{mode:0o600});
  }
  await expect(page.getByRole('heading',{name:'原句检索',exact:true})).toBeVisible();
  await expect.poll(async ()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,{timeout:660000}).toBe('SUCCEEDED');
  const indexStatus=(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.INDEX;
  if(indexStatus!=='SUCCEEDED'){
    const token=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    const rebuild=await page.request.post(`/api/v1/assets/${assetId}/search-index`,{headers:{'X-XSRF-TOKEN':token},data:{}});
    expect(rebuild.status()).toBe(202);
  }
  await expect.poll(async ()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.INDEX,{timeout:180000}).toBe('SUCCEEDED');
  const evidence=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
  const projectId=new URL(page.url()).searchParams.get('project');
  const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const response=await page.request.post(`/api/v1/projects/${projectId}/search`,{headers:{'X-XSRF-TOKEN':csrf},data:{query:'素材',literalPhrase:true,limit:10}});
  expect(response.status()).toBe(200);
  const result=(await response.json()).data;
  expect(result.channels).toEqual(['LITERAL']);expect(result.semanticAvailable).toBe(true);
  expect(result.hits.length).toBeGreaterThan(0);
  for(const hit of result.hits){
    expect(hit).toMatchObject({assetId,sourceKind:'SPEECH',temporalPrecision:'MODEL_TIMESTAMPS',contextAvailable:true});
    expect(hit.sourceSha256).toBe(hash);
    expect(hit.analysisId).toBe(evidence.observations.find((item:{id:string})=>item.id===hit.evidenceIds[0]).analysisId);
    expect(hit.text).toContain('素材');expect(hit.evidenceIds.length).toBeGreaterThan(0);
    expect(hit.candidateWindow).toMatchObject({startMs:hit.startMs,endMs:hit.endMs,purpose:'RETRIEVAL'});
    for(const id of hit.evidenceIds)expect(evidence.observations.some((item:{id:string})=>item.id===id)).toBe(true);
  }
  expect(result.coverage.assets).toContainEqual(expect.objectContaining({assetId,sourceKind:'SPEECH',status:'SUCCEEDED',startMs:0,endMs:14044,processedRanges:[{startMs:1436,endMs:14044}]}));
  const contextResponse=await page.request.get(`/api/v1/evidence/${result.hits[0].evidenceIds[0]}/context?boundedWindowMs=5000`);
  expect(contextResponse.status()).toBe(200);
  const context=(await contextResponse.json()).data;
  expect(context).toMatchObject({assetId,targetEvidenceId:result.hits[0].evidenceIds[0]});
  expect(context.observations.length).toBeGreaterThan(1);
  expect(context.observations.map((item:{text:string})=>item.text).join('')).toContain('张明');
  expect(context.observations.map((item:{id:string})=>item.id)).toContain(result.hits[0].evidenceIds[0]);
  await page.getByLabel('查找原句',{exact:true}).fill('素材',{timeout:5000});
  await page.getByRole('button',{name:'查找语音',exact:true}).click();
  const card=page.getByRole('article',{name:'语音检索证据'}).first();
  await expect(card).toContainText('labelled-speech.mp4');
  await expect(card).toContainText('素材');
  await expect(page.getByRole('region',{name:'语音查询覆盖'})).toContainText('语义索引：已就绪');
  await card.getByRole('button',{name:'查看邻近上下文',exact:true}).click({timeout:5000});
  await expect(card.getByRole('region',{name:'邻近原文'})).toContainText('张明');
  await card.getByRole('button',{name:'核查原视频',exact:true}).click();
  await expect.poll(()=>page.getByLabel('视频播放器').evaluate((node:HTMLVideoElement)=>node.readyState)).toBeGreaterThanOrEqual(1);
  const hit=result.hits[0];
  await expect.poll(()=>page.getByLabel('视频播放器').evaluate((node:HTMLVideoElement)=>node.currentTime)).toBeGreaterThanOrEqual(hit.startMs/1000);
  expect(await page.getByLabel('视频播放器').evaluate((node:HTMLVideoElement)=>node.currentTime)).toBeLessThan(hit.startMs/1000+2);

  const search=async(query:string,limit=10)=>page.request.post(`/api/v1/projects/${projectId}/search`,{headers:{'X-XSRF-TOKEN':csrf},data:{query,literalPhrase:true,limit}});
  for(const query of ['张明','三个','核查原文','，']){
    const response=await search(query);expect(response.status()).toBe(200);
    const found=(await response.json()).data.hits;
    expect(found.length).toBeGreaterThan(0);
    for(const hit of found){expect(hit.assetId).toBe(assetId);expect(hit.text).toContain(query);}
  }
  for(const query of ['%','_','\\',"' OR 1=1 --",'不存在的火星项目']){
    const response=await search(query);expect(response.status()).toBe(200);
    const result=(await response.json()).data;expect(result.hits).toEqual([]);
    expect(result.coverage.assets).toHaveLength(1);expect(result.coverage.assets[0].assetId).toBe(assetId);
  }
  expect((await(await search('步',1)).json()).data.hits).toHaveLength(1);
  for(const limit of [0,51])expect((await search('步',limit)).status()).toBe(400);
  expect((await search('x'.repeat(257))).status()).toBe(400);
  expect((await page.request.get(`/api/v1/evidence/${result.hits[0].evidenceIds[0]}/context?boundedWindowMs=30001`)).status()).toBe(400);
  await page.getByLabel('查找原句',{exact:true}).fill('不存在的火星项目');
  await page.getByRole('button',{name:'查找语音',exact:true}).click();
  await expect(page.getByText('在已分析的语音范围内未找到原文匹配',{exact:true})).toBeVisible();
  const coverage=page.getByRole('region',{name:'语音查询覆盖'});
  await expect(coverage).toContainText('labelled-speech.mp4');
  await expect(coverage).toContainText('语音：已分析');
  await expect(coverage).toContainText('1.44–14.04 秒');

});

test('identical phrases in two accounts stay inside the authorized project and evidence context',async ({browser})=>{
  test.setTimeout(1440000);
  const contexts:BrowserContext[]=[];
  const owners:Array<{context:BrowserContext;page:Page;assetId:string;projectId:string;post:(path:string,data:unknown)=>Promise<APIResponse>}> = [];
  const hash=createHash('sha256').update(readFileSync('e2e/fixtures/labelled-speech.mp4')).digest('hex');
  try{
    for(const label of ['owner','other']){
      const context=await browser.newContext({baseURL:process.env.E2E_BASE_URL??'http://localhost:8080'});
      contexts.push(context);
      const page=await context.newPage();
      const post=async(path:string,data:unknown)=>{
        const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
        return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf},data});
      };
      const seedFile=resolve(`../.scratch/asr-fixture/search-${label}-seed.json`);
      let seed=existsSync(seedFile)?JSON.parse(readFileSync(seedFile,'utf8')):null;
      if(seed?.hash!==hash)seed=null;
      if(seed){
        const login=await post('/auth/login',{username:seed.username,password:'analysis-browser-password'});
        if(!login.ok()||!(await page.request.get(`/api/v1/assets/${encodeURIComponent(seed.assetId)}`)).ok())seed=null;
      }
      if(!seed){
        const username=`search-scope-${label}-${Date.now()}`;
        expect((await post('/auth/register',{username,password:'analysis-browser-password'})).status()).toBe(201);
        expect((await post('/auth/login',{username,password:'analysis-browser-password'})).status()).toBe(200);
        const project=(await(await post('/projects',{name:`同词隔离-${label}`})).json()).data;
        await page.goto(`/?project=${project.id}`);
        const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
        await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
        seed={username,hash,projectId:project.id,assetId:(await(await completed).json()).data.assetId};
        mkdirSync(dirname(seedFile),{recursive:true});writeFileSync(seedFile,JSON.stringify(seed),{mode:0o600});
      }
      await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${seed.assetId}`)).json()).data.analysis?.stages.ASR,{timeout:660000}).toBe('SUCCEEDED');
      owners.push({context,page,assetId:seed.assetId,projectId:seed.projectId,post});
    }
    const [owner,other]=owners;
    if(!owner||!other)throw new Error('Two independent accounts are required');
    const search=async(value:typeof owner,projectId:string)=>value.post(`/projects/${projectId}/search`,{query:'上传素材',literalPhrase:true,limit:50});
    const first=(await(await search(owner,owner.projectId)).json()).data;
    const second=(await(await search(other,other.projectId)).json()).data;
    expect(first.hits.length).toBeGreaterThan(0);expect(second.hits.length).toBeGreaterThan(0);
    for(const hit of first.hits)expect(hit.assetId).toBe(owner.assetId);
    for(const hit of second.hits)expect(hit.assetId).toBe(other.assetId);
    expect((await search(owner,other.projectId)).status()).toBe(404);
    expect((await search(other,owner.projectId)).status()).toBe(404);
    expect((await owner.page.request.get(`/api/v1/evidence/${second.hits[0].evidenceIds[0]}/context`)).status()).toBe(404);
    expect((await other.page.request.get(`/api/v1/evidence/${first.hits[0].evidenceIds[0]}/context`)).status()).toBe(404);
    const empty=(await(await owner.post('/projects',{name:'同账号空项目'})).json()).data;
    const nothing=(await(await search(owner,empty.id)).json()).data;
    expect(nothing.hits).toEqual([]);expect(nothing.coverage.assets).toEqual([]);
    await owner.page.goto(`/?project=${empty.id}`);
    await owner.page.getByLabel('查找原句',{exact:true}).fill('上传素材');
    await owner.page.getByRole('button',{name:'查找语音',exact:true}).click();
    await expect(owner.page.getByText('尚无已完成分析的语音证据',{exact:true})).toBeVisible();
  }finally{for(const context of contexts)await context.close();}
});

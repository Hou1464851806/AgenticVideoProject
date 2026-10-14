import {expect,type Page} from '@playwright/test';
import {readFileSync,mkdirSync,writeFileSync,existsSync} from 'node:fs';
import {resolve} from 'node:path';

export async function renderSeed(page:Page) {
  const file=resolve('../.scratch/asr-fixture/preview-seed.json');
  const post=async(path:string,data:unknown)=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf},data});
  };
  if(existsSync(file)) {
    const seed=JSON.parse(readFileSync(file,'utf8'));
    if((await post('/auth/login',{username:seed.username,password:'analysis-browser-password'})).ok() && (await page.request.get(`/api/v1/assets/${seed.assetId}`)).ok()){
      const library=await page.request.get(`/api/v1/projects/${seed.projectId}/assets?includePending=true`);
      // The Compose fixture uses the real default ten-video limit; leave room for another test upload.
      if(library.ok() && (await library.json()).data.items.length<10)return seed;
    }
  }
  const username=`preview-real-${Date.now()}`;
  expect((await post('/auth/register',{username,password:'analysis-browser-password'})).status()).toBe(201);
  expect((await post('/auth/login',{username,password:'analysis-browser-password'})).status()).toBe(200);
  const project=(await(await post('/projects',{name:'真实预览测试'})).json()).data;
  await page.goto(`/?project=${project.id}`);
  const uploaded=page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
  const assetId=(await(await uploaded).json()).data.assetId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,{timeout:120000}).toBe('SUCCEEDED');
  const seed={username,projectId:project.id,assetId};mkdirSync(resolve('../.scratch/asr-fixture'),{recursive:true});writeFileSync(file,JSON.stringify(seed),{mode:0o600});return seed;
}

// Wait for conversation view restoration before choosing a test's immutable revision.
export async function openRenderRevision(page:Page, projectId:string, planId:string, revisionId:string) {
  const sessions=(await(await page.request.get(`/api/v1/projects/${projectId}/conversations`)).json()).data.items;
  const selected=page.url().startsWith('http')?await page.evaluate(id=>localStorage.getItem(`conversation:${id}`),projectId):null;
  const session=sessions.find((item:{id:string})=>item.id===selected)??sessions[sessions.length-1];
  const saved=session?(await(await page.request.get(`/api/v1/conversations/${session.id}/messages`)).json()).data.view:null;
  await page.goto(`/?project=${projectId}`);
  if(saved?.planId&&saved?.revisionId){
    await expect(page.getByLabel('查看方案',{exact:true})).toHaveValue(saved.planId);
    await expect(page.getByLabel('查看版本',{exact:true})).toHaveValue(saved.revisionId);
    await expect(page.getByRole('heading',{level:4,name:/^V[0-9]+ ·/})).toBeVisible();
  }
  await page.getByLabel('查看方案',{exact:true}).selectOption(planId);
  await page.getByLabel('查看版本',{exact:true}).selectOption(revisionId);
  await expect(page.getByLabel('查看方案',{exact:true})).toHaveValue(planId);
  await expect(page.getByLabel('查看版本',{exact:true})).toHaveValue(revisionId);
}

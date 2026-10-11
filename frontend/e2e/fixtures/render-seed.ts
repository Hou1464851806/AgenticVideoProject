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
    if((await post('/auth/login',{username:seed.username,password:'analysis-browser-password'})).ok() && (await page.request.get(`/api/v1/assets/${seed.assetId}`)).ok())return seed;
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

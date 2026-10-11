import {test,expect} from '@playwright/test';
import {renderSeed} from './fixtures/render-seed';
import {readFileSync,mkdirSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';

test('published semantic index finds a Chinese paraphrase with original evidence',async({page})=>{
  test.setTimeout(240000);
  const seed=await renderSeed(page);
  const post=async(path:string,data:unknown)=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf},data});
  };
  const rebuild=await post(`/assets/${seed.assetId}/search-index`,{});
  expect(rebuild.status()).toBe(202);
  const task=(await rebuild.json()).data.taskId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:180000}).toBe('SUCCEEDED');
  const response=await post(`/projects/${seed.projectId}/search`,{query:'把视频传到项目里',literalPhrase:false,limit:5});
  expect(response.status()).toBe(200);
  const result=(await response.json()).data;
  expect(result.semanticAvailable).toBe(true);
  expect(result.channels).toEqual(['DENSE','BM25','LITERAL']);
  expect(result.hits.some((hit:{text:string})=>hit.text.includes('上传素材'))).toBe(true);
  const evidence=(await(await page.request.get(`/api/v1/assets/${seed.assetId}/evidence`)).json()).data;
  for(const hit of result.hits){
    expect(hit.assetId).toBe(seed.assetId);
    expect(hit.evidenceIds.length).toBeGreaterThan(0);
    for(const id of hit.evidenceIds)expect(evidence.observations.some((item:{id:string})=>item.id===id)).toBe(true);
  }
  await page.goto(`/?project=${seed.projectId}`);
  await page.getByLabel('检索方式',{exact:true}).selectOption('semantic',{timeout:5000});
  await page.getByLabel('查找原句',{exact:true}).fill('把视频传到项目里');
  await page.getByRole('button',{name:'查找语音',exact:true}).click();
  await expect(page.getByRole('article',{name:'语音检索证据'}).filter({hasText:'上传素材'})).toBeVisible();
  await expect(page.getByRole('region',{name:'语音查询覆盖'})).toContainText('语义索引：已就绪');
});

test('held-out semantic queries measure dense BM25 and fusion with project isolation',async({page,browser})=>{
  test.setTimeout(240000);
  const seed=await renderSeed(page);
  const post=async(path:string,data:unknown)=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf},data});
  };
  const evidence=(await(await page.request.get(`/api/v1/assets/${seed.assetId}/evidence`)).json()).data;
  const labels=JSON.parse(readFileSync('e2e/fixtures/semantic-queries.json','utf8'));
  const measurements=[];
  for(const item of labels.queries){
    const relevant=evidence.units.filter((unit:{text:string})=>unit.text.includes(item.relevantText)).map((unit:{id:string})=>unit.id);
    expect(relevant.length).toBeGreaterThan(0);
    const response=await post(`/projects/${seed.projectId}/search`,{query:item.query,literalPhrase:false,limit:50});
    expect(response.status()).toBe(200);
    const result=(await response.json()).data;
    const metrics:Record<string,unknown>={};
    for(const channel of ['DENSE','BM25','RRF']){
      const ranking:string[]=result.rankings[channel];expect(Array.isArray(ranking)).toBe(true);
      const first=ranking.findIndex(id=>relevant.includes(id));
      metrics[channel]={ranking,firstRelevantRank:first<0?null:first+1,reciprocalRank:first<0?0:1/(first+1),
        recallAt1:relevant.filter((id:string)=>ranking.slice(0,1).includes(id)).length/relevant.length,
        recallAt3:relevant.filter((id:string)=>ranking.slice(0,3).includes(id)).length/relevant.length,
        recallAt5:relevant.filter((id:string)=>ranking.slice(0,5).includes(id)).length/relevant.length};
    }
    for(const hit of result.hits)expect(hit.assetId).toBe(seed.assetId);
    measurements.push({...item,relevantUnitIds:relevant,metrics});
  }
  const empty=(await(await post('/projects',{name:'语义隔离空项目'})).json()).data;
  const response=await post(`/projects/${empty.id}/search`,{query:'上传素材',literalPhrase:false});
  expect((await response.json()).data.hits).toEqual([]);
  const outsider=await browser.newContext({baseURL:process.env.E2E_BASE_URL??'http://localhost:8080'});
  try{
    const csrf=(await(await outsider.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    const credentials={username:`semantic-outsider-${Date.now()}`,password:'analysis-browser-password'};
    expect((await outsider.request.post('/api/v1/auth/register',{headers:{'X-XSRF-TOKEN':csrf},data:credentials})).status()).toBe(201);
    const csrf2=(await(await outsider.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    expect((await outsider.request.post('/api/v1/auth/login',{headers:{'X-XSRF-TOKEN':csrf2},data:credentials})).status()).toBe(200);
    const csrf3=(await(await outsider.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    expect((await outsider.request.post(`/api/v1/projects/${seed.projectId}/search`,{headers:{'X-XSRF-TOKEN':csrf3},data:{query:'张明',literalPhrase:false}})).status()).toBe(404);
    expect((await outsider.request.post(`/api/v1/assets/${seed.assetId}/search-index`,{headers:{'X-XSRF-TOKEN':csrf3},data:{}})).status()).toBe(404);
  }finally{await outsider.close();}
  mkdirSync(resolve('../.scratch/search-evaluation'),{recursive:true});
  writeFileSync(resolve('../.scratch/search-evaluation/issue-11.json'),JSON.stringify({measuredAt:new Date().toISOString(),assetId:seed.assetId,...labels,measurements,crossProjectLeakCount:0},null,2));
});

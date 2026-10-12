import {test,expect} from '@playwright/test';
import {renderSeed} from './fixtures/render-seed';
import {execFileSync} from 'node:child_process';

test('failed Embedding recovers independently while old semantic results and evidence stay available',async({page})=>{
  test.setTimeout(300000);
  await renderSeed(page);
  let seed:{projectId:string;assetId:string};
  const post=async(path:string,data:unknown)=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf},data});
  };
  const project=(await(await post('/projects',{name:`Embedding阶段恢复-${Date.now()}`})).json()).data;
  await page.goto(`/?project=${project.id}`);
  const upload=page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
  seed={projectId:project.id,assetId:(await(await upload).json()).data.assetId};
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${seed.assetId}`)).json()).data.analysis?.stages.ASR,{timeout:120000}).toBe('SUCCEEDED');
  const status=async()=>(await(await page.request.get(`/api/v1/assets/${seed.assetId}`)).json()).data.analysis.stages;
  const index=async()=>(await(await page.request.get(`/api/v1/assets/${seed.assetId}/search-index`)).json()).data;
  const build=await post(`/assets/${seed.assetId}/search-index`,{model:'text-embedding-v4',dimension:1024});
  const task=(await build.json()).data.taskId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:120000}).toBe('SUCCEEDED');
  const old=(await index()).publishedGeneration.id;
  const original=(await(await page.request.get(`/api/v1/assets/${seed.assetId}/evidence`)).json()).data;
  await page.goto(`/?project=${seed.projectId}`);
  await page.getByRole('button',{name:'播放 labelled-speech.mp4',exact:true}).click();
  await expect(page.getByText('Embedding：已就绪',{exact:true})).toBeVisible();
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','agent-service'],{env,timeout:60000,stdio:'pipe'});
  try{
    recreate({...process.env,DASHSCOPE_API_KEY:''});
    const accepted=await post(`/assets/${seed.assetId}/search-index`,{model:'text-embedding-v4',dimension:512});
    expect(accepted.status()).toBe(202);
    const failed=(await accepted.json()).data.taskId;
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${failed}`)).json()).data.status,{timeout:60000}).toBe('FAILED');
    expect(await status()).toMatchObject({ASR:'SUCCEEDED',EMBEDDING:'FAILED',INDEX:'NOT_STARTED'});
    expect((await index()).publishedGeneration.id).toBe(old);
    const search=await post(`/projects/${seed.projectId}/search`,{query:'把视频传到项目里',literalPhrase:false});
    expect(search.status()).toBe(200);expect((await search.json()).data.hits.some((hit:{text:string})=>hit.text.includes('上传素材'))).toBe(true);
    await page.reload();await page.getByRole('button',{name:'播放 labelled-speech.mp4',exact:true}).click();
    await expect(page.getByText('Embedding：失败',{exact:true})).toBeVisible();
    recreate(process.env);
    const recovery=page.waitForResponse(response=>response.url().endsWith('/analysis-stages/EMBEDDING/retry'));
    await page.getByRole('button',{name:'仅重试失败的 Embedding',exact:true}).click();
    expect((await recovery).status()).toBe(202);
    await expect.poll(async()=>await status(),{timeout:120000}).toMatchObject({ASR:'SUCCEEDED',EMBEDDING:'SUCCEEDED',INDEX:'SUCCEEDED'});
    await expect(page.getByText('Embedding：已就绪',{exact:true})).toBeVisible();
    const published=(await index()).publishedGeneration;
    expect(published.dimension).toBe(512);expect(published.id).not.toBe(old);
    const nextSearch=await post(`/projects/${seed.projectId}/search`,{query:'把视频传到项目里',literalPhrase:false});
    expect(nextSearch.status()).toBe(200);expect((await nextSearch.json()).data.hits.some((hit:{text:string})=>hit.text.includes('上传素材'))).toBe(true);
    const after=(await(await page.request.get(`/api/v1/assets/${seed.assetId}/evidence`)).json()).data;
    expect(after.observations).toEqual(original.observations);expect(after.providerCalls).toEqual(original.providerCalls);
  }finally{recreate(process.env);}
});

test('visual recovery preserves speech and interrupted Qdrant writes reuse successful vectors without model credentials',async({page})=>{
  test.setTimeout(300000);
  await renderSeed(page);
  let seed:{projectId:string;assetId:string};
  const post=async(path:string,data:unknown)=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf},data});
  };
  const project=(await(await post('/projects',{name:`阶段恢复-${Date.now()}`})).json()).data;
  await page.goto(`/?project=${project.id}`);
  const upload=page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
  seed={projectId:project.id,assetId:(await(await upload).json()).data.assetId};
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${seed.assetId}`)).json()).data.analysis?.stages.ASR,{timeout:120000}).toBe('SUCCEEDED');
  const status=async()=>(await(await page.request.get(`/api/v1/assets/${seed.assetId}`)).json()).data.analysis.stages;
  const evidence=async()=>(await(await page.request.get(`/api/v1/assets/${seed.assetId}/evidence`)).json()).data;
  const index=async()=>(await(await page.request.get(`/api/v1/assets/${seed.assetId}/search-index`)).json()).data;
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','agent-service'],{env,timeout:60000,stdio:'pipe'});
  await expect.poll(async()=>(await status()).INDEX,{timeout:120000}).toBe('SUCCEEDED');
  const original=await evidence();
  try{
    if((await status()).VISION!=='SUCCEEDED'){
      recreate({...process.env,DASHSCOPE_API_KEY:''});
      expect((await post(`/assets/${seed.assetId}/vision-analysis`,{})).status()).toBe(202);
      await expect.poll(async()=>(await status()).VISION,{timeout:60000}).toBe('UNVERIFIED');
      const speech=await post(`/projects/${seed.projectId}/search`,{query:'上传素材',literalPhrase:true});
      expect((await speech.json()).data.hits.length).toBeGreaterThan(0);
      recreate(process.env);
      await page.goto(`/?project=${seed.projectId}`);await page.getByRole('button',{name:'播放 labelled-speech.mp4',exact:true}).click();
      await page.getByRole('button',{name:'仅重试失败的 画面',exact:true}).click();
      await expect.poll(async()=>(await status()).VISION,{timeout:120000}).toBe('SUCCEEDED');
    }
    await expect.poll(async()=>(await status()).INDEX,{timeout:120000}).toBe('SUCCEEDED');
    const before=await evidence();expect(before.observations).toEqual(original.observations);expect(before.providerCalls).toEqual(original.providerCalls);
    const old=(await index()).publishedGeneration.id;
    let interrupted=0;
    const {createServer}=await import('node:http');
    const {promisify}=await import('node:util');
    const {execFile}=await import('node:child_process');
    const execute=promisify(execFile);
    const proxy=createServer(async(request,response)=>{
      try{
        const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
        if(request.method==='PUT'&&request.url?.includes('/points?')){interrupted++;response.writeHead(503);response.end('{}');return;}
        const code='import requests,sys,json; r=requests.request(sys.argv[1],"http://qdrant:6333"+sys.argv[2],data=sys.argv[3],headers={"Content-Type":"application/json"},timeout=15); print(json.dumps({"status":r.status_code,"body":r.text}))';
        const result=await execute(docker,['compose','exec','-T','agent-service','python','-c',code,request.method!,request.url!,Buffer.concat(chunks).toString()],{timeout:30000,maxBuffer:8*1024*1024});
        const upstream=JSON.parse(result.stdout);response.writeHead(upstream.status,{'Content-Type':'application/json'});response.end(upstream.body);
      }catch{response.writeHead(502);response.end('{}');}
    });
    await new Promise<void>(resolve=>proxy.listen(0,'0.0.0.0',resolve));const address=proxy.address();if(!address||typeof address==='string')throw new Error('Qdrant fault proxy unavailable');
    try{
      recreate({...process.env,DASHSCOPE_API_KEY:'',QDRANT_URL:`http://host.docker.internal:${address.port}`});
      const current=(await index()).publishedGeneration;
      const accepted=await post(`/assets/${seed.assetId}/search-index`,{model:current.model,dimension:current.dimension,bm25AvgLength:128});const task=(await accepted.json()).data.taskId;
      await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:60000}).toBe('FAILED');
      expect(interrupted).toBe(1);expect(await status()).toMatchObject({ASR:'SUCCEEDED',VISION:'SUCCEEDED',EMBEDDING:'SUCCEEDED',INDEX:'FAILED'});
      expect((await index()).publishedGeneration.id).toBe(old);
      const search=await post(`/projects/${seed.projectId}/search`,{query:'把视频传到项目里',literalPhrase:false});expect(search.status()).toBe(200);expect((await search.json()).data.hits.length).toBeGreaterThan(0);
      recreate({...process.env,DASHSCOPE_API_KEY:''});
      await page.reload();await page.getByRole('button',{name:'播放 labelled-speech.mp4',exact:true}).click();
      await page.getByRole('button',{name:'仅重试失败的 索引',exact:true}).click();
      await expect.poll(async()=>(await status()).INDEX,{timeout:120000}).toBe('SUCCEEDED');
      await expect(page.getByText('索引：已就绪',{exact:true})).toBeVisible();
      expect((await index()).publishedGeneration.id).not.toBe(old);
      const after=await evidence();expect(after.observations).toEqual(before.observations);expect(after.providerCalls).toEqual(before.providerCalls);expect(after.vision).toEqual(before.vision);
    }finally{recreate(process.env);await new Promise<void>(resolve=>proxy.close(()=>resolve()));}
  }finally{recreate(process.env);}
});

test('temporary media storage failure can be retried from an independent status panel before playback is available',async({page})=>{
  test.setTimeout(240000);await renderSeed(page);
  const post=async(path:string,data:unknown)=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf},data});
  };
  const project=(await(await post('/projects',{name:`媒体阶段恢复-${Date.now()}`})).json()).data;
  const {createServer}=await import('node:http');
  const {mkdtempSync,writeFileSync,rmSync}=await import('node:fs');
  const {tmpdir}=await import('node:os');const {join,resolve}=await import('node:path');
  const proxy=createServer((_request,response)=>{response.writeHead(503,{'Content-Type':'application/xml'});response.end('<Error><Code>ServiceUnavailable</Code><Message>Injected storage outage</Message></Error>');});
  await new Promise<void>(resolve=>proxy.listen(0,'0.0.0.0',resolve));const address=proxy.address();if(!address||typeof address==='string')throw new Error('Storage failure proxy unavailable');
  const directory=mkdtempSync(join(tmpdir(),'agenticvideo-media-stage-'));const override=join(directory,'compose.yaml');
  writeFileSync(override,`services:\n  media-worker:\n    environment:\n      STORAGE_ENDPOINT: http://host.docker.internal:${address.port}\n`);
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(fault:boolean)=>execFileSync(docker,['compose',...(fault?['-f',resolve('../compose.yaml'),'-f',override]:[]),'up','-d','--no-deps','--force-recreate','--wait','media-worker'],{timeout:60000,stdio:'pipe'});
  try{
    recreate(true);await page.goto(`/?project=${project.id}`);
    const uploaded=page.waitForResponse(response=>response.url().endsWith('/complete'));
    await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/brief-small-text.mp4');
    const asset=(await(await uploaded).json()).data.assetId;
    const details=async()=>(await(await page.request.get(`/api/v1/assets/${asset}`)).json()).data;
    await expect.poll(async()=>(await details()).validationStatus,{timeout:150000}).toBe('INVALID');
    expect((await details()).analysis).toMatchObject({stages:{MEDIA:'FAILED',ASR:'NOT_STARTED'},mediaRetryable:true});
    expect(await page.getByRole('button',{name:'播放 brief-small-text.mp4',exact:true}).count()).toBe(0);
    await page.getByRole('button',{name:'查看分析状态 brief-small-text.mp4',exact:true}).click();
    await expect(page.getByText('媒体：失败',{exact:true})).toBeVisible();
    recreate(false);
    const accepted=page.waitForResponse(response=>response.url().endsWith('/analysis-stages/MEDIA/retry'));
    await page.getByRole('button',{name:'仅重试失败的 媒体',exact:true}).click();expect((await accepted).status()).toBe(202);
    await expect.poll(async()=>(await details()).validationStatus,{timeout:60000}).toBe('VALID');
    await expect(page.getByText('媒体：已就绪',{exact:true})).toBeVisible();
    await expect(page.getByRole('button',{name:'播放 brief-small-text.mp4',exact:true})).toBeVisible();
    expect((await details()).analysis.stages.ASR).toBe('NOT_APPLICABLE');
  }finally{recreate(false);await new Promise<void>(resolve=>proxy.close(()=>resolve()));rmSync(directory,{recursive:true,force:true});}
});

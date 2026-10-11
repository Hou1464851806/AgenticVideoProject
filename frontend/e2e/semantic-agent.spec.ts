import {test,expect} from '@playwright/test';
import {createServer} from 'node:http';
import {execFileSync} from 'node:child_process';
import {renderSeed} from './fixtures/render-seed';

test('Agent controlled search uses real hybrid retrieval inside its fixed asset scope',async({page})=>{
  test.setTimeout(120000);
  const seed=await renderSeed(page);
  let observed:{channels:string[];semanticAvailable:boolean;hits:Array<{assetId:string;evidenceId:string;text:string}>}|null=null;
  let calls=0;
  const provider=createServer(async(request,response)=>{
    const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
    const body=JSON.parse(Buffer.concat(chunks).toString());calls++;
    if(calls===2){
      const message=body.messages.findLast((item:{role:string})=>item.role==='tool');
      observed=JSON.parse(message.content);
    }
    const name=calls===1?'search_evidence':'answer_query';
    const args=calls===1?{query:'检查转写内容是不是准确'}:{content:'请核查原文。',evidenceIds:observed?.hits.map(hit=>hit.evidenceId)??[]};
    response.writeHead(200,{'Content-Type':'application/json'});
    response.end(JSON.stringify({id:`semantic-agent-${calls}`,model:'deepseek-flash',choices:[{message:{role:'assistant',content:null,tool_calls:[{id:`tool-${calls}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]}}],usage:{}}));
  });
  await new Promise<void>(resolve=>provider.listen(0,'0.0.0.0',resolve));
  const address=provider.address();if(!address||typeof address==='string')throw new Error('Provider fixture unavailable');
  const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(process.env.DOCKER_BIN??'docker',['compose','up','-d','--no-deps','--force-recreate','--wait','agent-service'],{env,timeout:60000,stdio:'pipe'});
  try{
    recreate({...process.env,DEEPSEEK_BASE_URL:`http://host.docker.internal:${address.port}`});
    const post=async(path:string,data:unknown)=>{
      const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
      return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data});
    };
    const session=(await(await post(`/projects/${seed.projectId}/conversations`,{title:'受控主题检索',assetIds:[seed.assetId]})).json()).data;
    const accepted=await post(`/conversations/${session.id}/turns`,{content:'检索检查转写准确性的步骤'});
    expect(accepted.status()).toBe(202);const task=(await accepted.json()).data.taskId;
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:30000}).toBe('SUCCEEDED');
    expect(observed).not.toBeNull();
    expect(observed!.channels).toEqual(['DENSE','BM25','LITERAL']);
    expect(observed!.semanticAvailable).toBe(true);
    expect(observed!.hits.some(hit=>hit.text.includes('核查原文'))).toBe(true);
    for(const hit of observed!.hits)expect(hit.assetId).toBe(seed.assetId);
    const history=(await(await page.request.get(`/api/v1/conversations/${session.id}/messages`)).json()).data.items;
    expect(history[1].references.length).toBeGreaterThan(0);
  }finally{recreate({...process.env});await new Promise<void>(resolve=>provider.close(()=>resolve()));}
});

test('cancelling an Agent stays responsive while an external embedding query is blocked',async({page})=>{
  test.setTimeout(120000);
  const seed=await renderSeed(page);
  let release:()=>void=()=>{};let entered:()=>void=()=>{};
  const gate=new Promise<void>(resolve=>{release=resolve;});
  const started=new Promise<void>(resolve=>{entered=resolve;});
  let calls=0;
  const provider=createServer(async(request,response)=>{
    const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
    if(request.url?.includes('/text-embedding/')){
      entered();await gate;
      response.writeHead(200,{'Content-Type':'application/json'});
      response.end(JSON.stringify({output:{embeddings:[{text_index:0,embedding:Array(1024).fill(0.5)}]}}));return;
    }
    calls++;
    const name=calls===1?'search_evidence':'answer_query';
    const args=calls===1?{query:'检查转写内容是不是准确'}:{content:'工具尚未核查完成。',evidenceIds:[]};
    response.writeHead(200,{'Content-Type':'application/json'});
    response.end(JSON.stringify({id:`slow-query-${calls}`,model:'deepseek-flash',choices:[{message:{role:'assistant',content:null,tool_calls:[{id:`tool-${calls}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]}}],usage:{}}));
  });
  await new Promise<void>(resolve=>provider.listen(0,'0.0.0.0',resolve));
  const address=provider.address();if(!address||typeof address==='string')throw new Error('Provider fixture unavailable');
  const recreate=(service:string,env:NodeJS.ProcessEnv)=>execFileSync(process.env.DOCKER_BIN??'docker',['compose','up','-d','--no-deps','--force-recreate','--wait',service],{env,timeout:60000,stdio:'pipe'});
  const post=async(path:string,data:unknown,timeout=30000)=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data,timeout});
  };
  let task='';
  try{
    recreate('business-api',{...process.env,DASHSCOPE_BASE_URL:`http://host.docker.internal:${address.port}/native`});
    recreate('agent-service',{...process.env,DEEPSEEK_BASE_URL:`http://host.docker.internal:${address.port}`});
    expect((await post('/auth/login',{username:seed.username,password:'analysis-browser-password'})).status()).toBe(200);
    const created=await post(`/projects/${seed.projectId}/conversations`,{title:'远程查询中取消',assetIds:[seed.assetId]});
    expect(created.status()).toBe(201);const session=(await created.json()).data;
    const accepted=await post(`/conversations/${session.id}/turns`,{content:'核查转写内容'});
    expect(accepted.status()).toBe(202);task=(await accepted.json()).data.taskId;
    await Promise.race([started,new Promise((_,reject)=>setTimeout(()=>reject(new Error('Embedding query never started')),10000))]);
    const cancelled=await post(`/tasks/${task}/cancel`,{},1500);
    expect(cancelled.status()).toBe(202);
    expect((await cancelled.json()).data.status).toBe('CANCEL_REQUESTED');
    release();
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:90000}).toBe('CANCELLED');
    const history=(await(await page.request.get(`/api/v1/conversations/${session.id}/messages`)).json()).data.items;
    expect(history.filter((message:{role:string})=>message.role==='assistant')).toEqual([]);
  }finally{
    release();
    recreate('business-api',{...process.env});recreate('agent-service',{...process.env});
    await new Promise<void>(resolve=>provider.close(()=>resolve()));
  }
});

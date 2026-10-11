import {test,expect} from '@playwright/test';
import {createServer} from 'node:http';
import {execFileSync} from 'node:child_process';
import {writeFileSync,rmSync} from 'node:fs';
import {resolve} from 'node:path';

for (const kind of ['REQUEST','RESPONSE'] as const) test(`oversized ${kind} records stop registration retries and show the byte limit in the workspace`,async({page})=>{
  test.setTimeout(120000);
  let preparations=0;let paidCalls=0;
  const gateway=createServer(async(request,response)=>{
    try{
      const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
      if(request.url==='/chat/completions'){
        paidCalls++;response.writeHead(200,{'Content-Type':'application/json'});
        response.end(JSON.stringify({id:'response-limit-fixture',model:'deepseek-flash',choices:[{message:{role:'assistant',content:null,tool_calls:[{id:'answer-1',type:'function',function:{name:'answer_query',arguments:JSON.stringify({content:'没有可用证据。'.repeat(100),evidenceIds:[]})}}]}}],usage:{}}));return;
      }
      if(request.url?.endsWith('/provider-call'))preparations++;
      const upstream=await fetch(`http://localhost:8080${request.url}`,{method:request.method,
        headers:Object.fromEntries(Object.entries(request.headers).filter(([name])=>!['host','connection','content-length','transfer-encoding'].includes(name))) as Record<string,string>,
        body:Buffer.concat(chunks)});
      response.writeHead(upstream.status,{'Content-Type':'application/json'});response.end(Buffer.from(await upstream.arrayBuffer()));
    }catch{response.writeHead(502);response.end('{}');}
  });
  await new Promise<void>(resolve=>gateway.listen(0,'0.0.0.0',resolve));
  const address=gateway.address();if(!address||typeof address==='string')throw new Error('Gateway unavailable');
  const override=resolve('../.scratch/model-record-gateway.yaml');
  writeFileSync(override,`services:\n  agent-service:\n    environment:\n      BUSINESS_URL: http://host.docker.internal:${address.port}\n      DEEPSEEK_BASE_URL: http://host.docker.internal:${address.port}\n`);
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(fault:boolean)=>execFileSync(docker,['compose',...(fault?['-f',resolve('../compose.yaml'),'-f',override]:[]),'up','-d','--no-deps','--force-recreate','--wait','business-api','agent-service'],
    {env:fault?{...process.env,MODEL_RECORD_REQUEST_MAX_BYTES:kind==='REQUEST'?'512':'16777216',MODEL_RECORD_RESPONSE_MAX_BYTES:kind==='RESPONSE'?'128':'4194304'}:{...process.env},timeout:90000,stdio:'pipe'});
  try{
    recreate(true);
    const post=async(path:string,data:unknown)=>{
      const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
      return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data});
    };
    const username=`record-limit-${Date.now()}`;const password='analysis-browser-password';
    expect((await post('/auth/register',{username,password})).status()).toBe(201);
    expect((await post('/auth/login',{username,password})).status()).toBe(200);
    const project=(await(await post('/projects',{name:'模型记录上限'})).json()).data;
    const session=(await(await post(`/projects/${project.id}/conversations`,{title:'明确的超限原因',assetIds:[]})).json()).data;
    await page.goto(`/?project=${project.id}`);await page.getByLabel('制作会话',{exact:true}).selectOption(session.id);
    await page.getByLabel('需求类型',{exact:true}).selectOption('GENERATE_PLAN');
    await page.getByLabel('方案主题',{exact:true}).fill('产品解决的问题');
    await page.getByLabel('对话输入',{exact:true}).fill('生成一条讲话方案，保留完整观点。');
    await page.getByRole('button',{name:'发送需求',exact:true}).click();
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/conversations/${session.id}/messages`)).json()).data.latestTask?.status,{timeout:15000}).toBe('FAILED');
    const latest=(await(await page.request.get(`/api/v1/conversations/${session.id}/messages`)).json()).data.latestTask;
    expect(latest.failure.code).toBe('MODEL_RECORD_TOO_LARGE');
    expect(preparations).toBe(kind==='REQUEST'?1:2);expect(paidCalls).toBe(kind==='REQUEST'?0:1);
    await expect(page.getByRole('alert')).toContainText('字节');
    await expect(page.getByRole('alert')).toContainText(kind==='REQUEST'?'512':'128');
    await page.reload();await expect(page.getByRole('alert')).toContainText(kind==='REQUEST'?'512':'128');
  }finally{recreate(false);rmSync(override,{force:true});await new Promise<void>(resolve=>gateway.close(()=>resolve()));}
});

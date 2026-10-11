import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

test('analysis executor interruption after provider submission recovers without another paid ASR task', async ({ page }) => {
  test.setTimeout(180000);
  let release!:()=>void;
  const gate=new Promise<void>(resolve=>{release=resolve;});
  let held=false;
  let submissions=0;
  const newlySubmitted=new Set<string>();
  const leases=new Map<string,Array<{attemptId:string;leaseToken:string}>>();
  let internalAuthorization='';
  const businessProxy=createServer(async(request,response)=>{
    try{
      const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
      const path=request.url!;
      const upstream=await fetch(`http://localhost:8080${path}`,{method:request.method,
        headers:Object.fromEntries(Object.entries(request.headers).filter(([name])=>!['host','connection','content-length','transfer-encoding'].includes(name))) as Record<string,string>,
        body:request.method==='POST'?Buffer.concat(chunks):undefined});
      const body=Buffer.from(await upstream.arrayBuffer());
      const claimed=path.match(/\/tasks\/([^/]+)\/claim$/);
      if(claimed&&upstream.status===200){
        const data=JSON.parse(body.toString()).data;
        if(data.claimed){
          internalAuthorization=String(request.headers.authorization??'');
          const history=leases.get(claimed[1]!)??[];
          if(!history.some(lease=>lease.attemptId===data.attemptId))history.push({attemptId:data.attemptId,leaseToken:data.leaseToken});
          leases.set(claimed[1]!,history);
        }
      }
      response.writeHead(upstream.status,{'Content-Type':'application/json'});response.end(body);
    }catch{response.writeHead(502);response.end('{}');}
  });
  const providerIds:string[]=[];
  const proxy=createServer(async (request,response) => {
    try {
      const chunks:Buffer[]=[];
      for await(const chunk of request)chunks.push(Buffer.from(chunk));
      const path=request.url!;
      if(path.includes('/asr/transcription'))submissions++;
      const query=path.match(/\/tasks\/([^/?]+)/);
      if(query)providerIds.push(query[1]!);
      const upstream=await fetch(`https://dashscope.aliyuncs.com${path}`,{method:request.method,
        headers:Object.fromEntries(Object.entries(request.headers).filter(([name])=>!['host','connection','content-length','transfer-encoding'].includes(name))) as Record<string,string>,
        body:request.method==='POST'?Buffer.concat(chunks):undefined});
      const output=Buffer.from(await upstream.arrayBuffer());
      if(path.includes('/asr/transcription')&&upstream.status===200)newlySubmitted.add(JSON.parse(output.toString()).output.task_id);
      if(query&&newlySubmitted.has(query[1]!)&&!held){held=true;await gate;}
      response.writeHead(upstream.status,{'Content-Type':'application/json'});response.end(output);
    }catch{response.writeHead(502);response.end('{}');}
  });
  await new Promise<void>(resolve=>proxy.listen(0,'0.0.0.0',resolve));
  await new Promise<void>(resolve=>businessProxy.listen(0,'0.0.0.0',resolve));
  const address=proxy.address();
  if(!address||typeof address==='string')throw new Error('Provider fault proxy unavailable');
  const businessAddress=businessProxy.address();
  if(!businessAddress||typeof businessAddress==='string')throw new Error('Business fault proxy unavailable');
  const directory=mkdtempSync(join(tmpdir(),'agenticvideo-analysis-recovery-'));
  const override=join(directory,'compose.yaml');
  writeFileSync(override,`services:\n  agent-service:\n    environment:\n      BUSINESS_URL: http://host.docker.internal:${businessAddress.port}\n`);
  const docker=process.env.DOCKER_BIN??'docker';
  const environment={...process.env,DASHSCOPE_BASE_URL:`http://host.docker.internal:${address.port}/api/v1`};
  const recreate=(env:NodeJS.ProcessEnv,trace=false)=>execFileSync(docker,['compose',...(trace?['-f',resolve('../compose.yaml'),'-f',override]:[]),'up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','40','agent-service'],{env,timeout:60000,stdio:'pipe'});
  try {
    recreate(environment,true);
    await page.goto('/');
    await page.getByRole('button',{name:'创建账号',exact:true}).click();
    await page.getByLabel('用户名',{exact:true}).fill(`analysis-kill-${Date.now()}`);
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
    await page.getByRole('button',{name:'注册',exact:true}).click();
    await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
    await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.getByLabel('项目名称',{exact:true}).fill('分析领取后中断');
    await page.getByRole('button',{name:'创建项目',exact:true}).click();
    await page.getByRole('button',{name:'分析领取后中断',exact:true}).click();
    const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
    await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
    const assetId=(await(await completed).json()).data.assetId;
    await expect.poll(()=>held,{timeout:30000}).toBe(true);
    const before=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
    expect(before.providerCalls).toHaveLength(1);
    const taskId=before.providerCalls[0].taskId;
    expect(typeof taskId).toBe('string');
    expect(leases.get(taskId)?.length).toBe(1);
    expect(before.providerCalls[0].status).toBe('SUBMITTED');
    execFileSync(docker,['compose','kill','-s','SIGKILL','agent-service'],{timeout:10000,stdio:'pipe'});
    release();
    recreate(environment,true);
    await expect.poll(async ()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,{timeout:110000}).toBe('SUCCEEDED');
    const after=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
    expect(after.providerCalls).toHaveLength(1);
    expect(after.providerCalls[0].providerTaskId).toBe(before.providerCalls[0].providerTaskId);
    expect(submissions).toBe(1);
    expect(providerIds.filter(id=>id===before.providerCalls[0].providerTaskId).length).toBeGreaterThan(1);
    expect((await(await page.request.get(`/api/v1/tasks/${taskId}`)).json()).data).toMatchObject({status:'SUCCEEDED',attemptCount:2});
    expect(leases.get(taskId)?.length).toBe(2);
    const old=leases.get(taskId)![0]!;
    const late=await fetch(`http://localhost:8080/internal/v1/tasks/${taskId}/complete`,{
      method:'POST',headers:{'Content-Type':'application/json',Authorization:internalAuthorization},
      body:JSON.stringify({...old,producerKey:'late-speech',resultType:'SPEECH_ANALYZED',result:{modelCallId:after.providerCalls[0].id}}),
    });
    expect(late.status).toBe(409);
    const lateProvider=await fetch(`http://localhost:8080/internal/v1/tasks/${taskId}/provider-call`,{
      method:'POST',headers:{'Content-Type':'application/json',Authorization:internalAuthorization},body:JSON.stringify({...old,action:'LOOKUP',data:{}}),
    });
    expect(lateProvider.status).toBe(409);
    const unchanged=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
    expect(unchanged.observations.map((item:{id:string})=>item.id)).toEqual(after.observations.map((item:{id:string})=>item.id));
    const attemptedDirectories=leases.get(taskId)!.map(lease=>lease.attemptId);
    const cleanupCheck=`from pathlib import Path; attempts=${JSON.stringify(attemptedDirectories)}; print(sum((Path("/work") / attempt).exists() for attempt in attempts))`;
    expect(execFileSync(docker,['compose','exec','-T','agent-service','python','-c',cleanupCheck],{timeout:5000}).toString().trim()).toBe('0');
  }finally{
    release();recreate({...process.env});
    await new Promise<void>(resolve=>proxy.close(()=>resolve()));
    await new Promise<void>(resolve=>businessProxy.close(()=>resolve()));
    rmSync(directory,{recursive:true,force:true});
  }
});

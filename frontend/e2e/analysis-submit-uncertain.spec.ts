import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';

test('losing a real paid submission receipt marks uncertainty instead of submitting again',async ({page})=>{
  test.setTimeout(90000);
  let submissions=0;
  let acceptedTaskId='';
  const proxy=createServer(async(request,response)=>{
    try{
      const path=request.url!;
      const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
      const upstream=await fetch(`https://dashscope.aliyuncs.com${path}`,{method:request.method,
        headers:Object.fromEntries(Object.entries(request.headers).filter(([name])=>!['host','connection','content-length','transfer-encoding'].includes(name))) as Record<string,string>,
        body:request.method==='POST'?Buffer.concat(chunks):undefined});
      const body=Buffer.from(await upstream.arrayBuffer());
      if(path.includes('/asr/transcription')){
        submissions++;
        if(upstream.status===200)acceptedTaskId=JSON.parse(body.toString()).output.task_id;
        response.destroy();return;
      }
      response.writeHead(upstream.status,{'Content-Type':'application/json'});response.end(body);
    }catch{response.writeHead(502);response.end('{}');}
  });
  await new Promise<void>(resolve=>proxy.listen(0,'0.0.0.0',resolve));
  const address=proxy.address();if(!address||typeof address==='string')throw new Error('Provider fault proxy unavailable');
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','40','agent-service'],{env,timeout:60000,stdio:'pipe'});
  try{
    recreate({...process.env,DASHSCOPE_BASE_URL:`http://host.docker.internal:${address.port}/api/v1`});
    await page.goto('/');await page.getByRole('button',{name:'创建账号',exact:true}).click();
    await page.getByLabel('用户名',{exact:true}).fill(`analysis-submit-loss-${Date.now()}`);
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
    await page.getByRole('button',{name:'注册',exact:true}).click();await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.getByLabel('项目名称',{exact:true}).fill('模型提交回执丢失');await page.getByRole('button',{name:'创建项目',exact:true}).click();
    await page.getByRole('button',{name:'模型提交回执丢失',exact:true}).click();
    const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
    await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
    const assetId=(await(await completed).json()).data.assetId;
    await expect.poll(async ()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,{timeout:20000}).toBe('UNVERIFIED');
    expect(acceptedTaskId.length).toBeGreaterThan(0);
    expect(submissions).toBe(1);
    const evidence=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
    expect(evidence.observations).toEqual([]);expect(evidence.providerCalls).toHaveLength(1);
    expect(evidence.providerCalls[0].status).toBe('UNCERTAIN');
    expect(evidence.providerCalls[0].providerTaskId).toBeUndefined();
    expect((await(await page.request.get(`/api/v1/tasks/${evidence.providerCalls[0].taskId}`)).json()).data).toMatchObject({status:'FAILED',attemptCount:1});
  }finally{recreate({...process.env});await new Promise<void>(resolve=>proxy.close(()=>resolve()));}
});

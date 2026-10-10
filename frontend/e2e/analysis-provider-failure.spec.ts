import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';

test('real provider decoding failure is recorded without publishing evidence or paying twice',async ({page})=>{
  test.setTimeout(100000);
  let submissions=0; let invalidAddress='';
  const proxy=createServer(async(request,response)=>{
    try{
      const path=request.url!;
      if(path.includes('/asr/transcription'))submissions++;
      const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
      let outbound=Buffer.concat(chunks);
      if(path.includes('/asr/transcription')){const payload=JSON.parse(outbound.toString());payload.input.file_url=invalidAddress;outbound=Buffer.from(JSON.stringify(payload));}
      const upstream=await fetch(`https://dashscope.aliyuncs.com${path}`,{method:request.method,
        headers:Object.fromEntries(Object.entries(request.headers).filter(([name])=>!['host','connection','content-length','transfer-encoding'].includes(name))) as Record<string,string>,
        body:request.method==='POST'?outbound:undefined});
      const body=Buffer.from(await upstream.arrayBuffer());
      if(path.includes('/uploads')&&upstream.status===200){
        const p=JSON.parse(body.toString()).data;const key=p.upload_dir+'/invalid-'+Date.now()+'.wav';const form=new FormData();
        for(const [k,v] of Object.entries({OSSAccessKeyId:p.oss_access_key_id,Signature:p.signature,policy:p.policy,'x-oss-object-acl':p.x_oss_object_acl,'x-oss-forbid-overwrite':p.x_oss_forbid_overwrite,key,success_action_status:'200'}))form.append(k,String(v));
        form.append('file',new Blob(['not a wave file']),'invalid.wav');
        const uploaded=await fetch(p.upload_host,{method:'POST',body:form});if(uploaded.status!==200)throw new Error('Fault audio upload failed');invalidAddress='oss://'+key;
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
    await page.getByLabel('用户名',{exact:true}).fill(`analysis-provider-failure-${Date.now()}`);
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
    await page.getByRole('button',{name:'注册',exact:true}).click();await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.getByLabel('项目名称',{exact:true}).fill('模型解码失败');await page.getByRole('button',{name:'创建项目',exact:true}).click();
    await page.getByRole('button',{name:'模型解码失败',exact:true}).click();
    const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
    await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
    const assetId=(await(await completed).json()).data.assetId;
    await expect.poll(async ()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,{timeout:30000}).toBe('FAILED');
    expect(submissions).toBe(1);
    const evidence=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
    expect(evidence.observations).toEqual([]);expect(evidence.providerCalls).toHaveLength(1);
    expect(evidence.providerCalls[0]).toMatchObject({status:'FAILED',failure:{status:'FAILED',code:'DECODE_ERROR'}});
    expect((await(await page.request.get(`/api/v1/tasks/${evidence.providerCalls[0].taskId}`)).json()).data.attemptCount).toBe(1);
  }finally{recreate({...process.env});await new Promise<void>(resolve=>proxy.close(()=>resolve()));}
});

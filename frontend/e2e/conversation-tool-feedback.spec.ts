import {test,expect} from '@playwright/test';
import {createServer} from 'node:http';
import {execFileSync} from 'node:child_process';

test('a malformed external model tool call receives feedback and can produce an honest answer',async({page})=>{
  test.setTimeout(120000);
  let calls=0;let feedback=false;
  const provider=createServer(async(request,response)=>{
    const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
    const body=JSON.parse(Buffer.concat(chunks).toString());
    calls++;
    if(calls===2)feedback=body.messages.some((message:{role:string;content:string})=>message.role==='tool'&&JSON.parse(message.content).error);
    const name=calls===1?'search_evidence':'answer_query';
    const args=calls===1?{}:{content:'没有可用语音证据，无法确认姓名。',evidenceIds:[]};
    response.writeHead(200,{'Content-Type':'application/json'});
    response.end(JSON.stringify({id:`external-fixture-${calls}`,model:'deepseek-flash',choices:[{message:{role:'assistant',content:null,tool_calls:[{id:`tool-${calls}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]}}],usage:{}}));
  });
  await new Promise<void>(resolve=>provider.listen(0,'0.0.0.0',resolve));
  const address=provider.address();if(!address||typeof address==='string')throw new Error('Provider fixture unavailable');
  const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(process.env.DOCKER_BIN??'docker',['compose','up','-d','--no-deps','--force-recreate','--wait','agent-service'],{env,timeout:60000,stdio:'pipe'});
  try{
    recreate({...process.env,DEEPSEEK_BASE_URL:`http://host.docker.internal:${address.port}`});
    const post=async(path:string,data:unknown)=>{
      const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
      return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data});
    };
    const username=`tool-feedback-${Date.now()}`;const password='analysis-browser-password';
    expect((await post('/auth/register',{username,password})).status()).toBe(201);
    expect((await post('/auth/login',{username,password})).status()).toBe(200);
    const project=(await(await post('/projects',{name:'工具参数反馈'})).json()).data;
    const session=(await(await post(`/projects/${project.id}/conversations`,{title:'空范围查询',assetIds:[]})).json()).data;
    const accepted=await post(`/conversations/${session.id}/turns`,{content:'没有证据就说明，讲述者叫什么名字？'});
    expect(accepted.status()).toBe(202);const identity=(await accepted.json()).data;
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${identity.taskId}`)).json()).data.status,{timeout:15000}).toBe('SUCCEEDED');
    expect(calls).toBe(2);expect(feedback).toBe(true);
    const history=(await(await page.request.get(`/api/v1/conversations/${session.id}/messages`)).json()).data.items;
    expect(history[1].content).toBe('没有可用语音证据，无法确认姓名。');expect(history[1].references).toEqual([]);
  }finally{recreate({...process.env});await new Promise<void>(resolve=>provider.close(()=>resolve()));}
});

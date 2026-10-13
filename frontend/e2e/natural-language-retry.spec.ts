import {test,expect} from '@playwright/test';
import {createServer} from 'node:http';
import {execFileSync} from 'node:child_process';

test('retry after cancellation was admitted only restores its confirmation reply',async({page})=>{
  test.setTimeout(180000);
  let calls=0;let retryTools:string[]=[];
  const provider=createServer(async(request,response)=>{
    const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
    const payload=JSON.parse(Buffer.concat(chunks).toString());calls++;
    if(calls===4){response.writeHead(503);response.end('deliberately interrupted confirmation');return;}
    if(calls===5)retryTools=payload.tools.map((tool:{function:{name:string}})=>tool.function.name);
    const name=calls===1||calls===3?'submit_production_request':calls===2?'ask_clarification':'answer_query';
    const args=calls===1?{intent:'CLARIFY',newRequirement:true,fields:{}}:calls===2?{prompt:'想做什么主题？',reason:'主题影响选段',fields:['topic']}:calls===3?{intent:'CANCEL',newRequirement:false,questionAction:'CANCEL',fields:{}}:{content:'已取消原需求。',evidenceIds:[]};
    response.writeHead(200,{'Content-Type':'application/json'});
    response.end(JSON.stringify({id:`cancel-retry-${calls}`,model:'deepseek-flash',choices:[{message:{role:'assistant',content:null,tool_calls:[{id:`cancel-retry-tool-${calls}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]}}],usage:{}}));
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
    const credentials={username:`cancel-retry-${Date.now()}`,password:'conversation-retry-password'};
    expect((await post('/auth/register',credentials)).status()).toBe(201);expect((await post('/auth/login',credentials)).status()).toBe(200);
    const project=(await(await post('/projects',{name:'取消后恢复确认'})).json()).data;await page.goto(`/?project=${project.id}`);
    const created=page.waitForResponse(response=>response.request().method()==='POST'&&response.url().endsWith('/conversations'));
    await page.getByRole('button',{name:'新聊天',exact:true}).click();const session=(await(await created).json()).data;
    const send=async(content:string)=>{
      await page.getByLabel('对话输入',{exact:true}).fill(content);
      const accepted=page.waitForResponse(response=>response.request().method()==='POST'&&response.url().endsWith('/turns'));
      await page.getByRole('button',{name:'发送需求',exact:true}).click();const response=await accepted;expect(response.status()).toBe(202);return(await response.json()).data;
    };
    const first=await send('做一条短视频，主题未定');
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${first.taskId}`)).json()).data.status,{timeout:30000}).toBe('WAITING_INPUT');
    const cancel=await send('先不做了');
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${cancel.taskId}`)).json()).data.status,{timeout:30000}).toBe('FAILED');
    expect((await(await page.request.get(`/api/v1/tasks/${first.taskId}`)).json()).data.status).toBe('CANCELLED');
    await page.getByLabel('我理解原调用费用未知，接受重试可能产生新费用',{exact:true}).check();
    const retried=page.waitForResponse(response=>response.request().method()==='POST'&&response.url().endsWith('/retry'));
    await page.getByRole('button',{name:'重试原需求',exact:true}).click();const response=await retried;expect(response.status()).toBe(202);const task=(await response.json()).data.taskId;
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:30000}).toBe('SUCCEEDED');
    expect(retryTools).toEqual(['answer_query']);expect(calls).toBe(5);
    const snapshot=(await(await page.request.get(`/api/v1/conversations/${session.id}/snapshot`)).json()).data;
    expect(snapshot.questions).toHaveLength(1);expect(snapshot.questions[0].status).toBe('CANCELLED');
    await expect(page.getByRole('article',{name:'助手答复',exact:true}).last()).toContainText('已取消原需求');
  }finally{
    try{recreate({...process.env});}finally{await new Promise<void>(resolve=>provider.close(()=>resolve()));}
  }
});

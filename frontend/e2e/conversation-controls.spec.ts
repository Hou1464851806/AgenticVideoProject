import {test,expect} from '@playwright/test';
import {createServer} from 'node:http';
import {execFileSync} from 'node:child_process';
import {writeFileSync,rmSync} from 'node:fs';
import {resolve} from 'node:path';

test('retrying a failed turn asks about unknown cost and uses real DeepSeek without another user message',async({page})=>{
  test.setTimeout(150000);
  const gateway=createServer(async(request,response)=>{for await(const chunk of request)void chunk;response.writeHead(503);response.end('model boundary unavailable');});
  await new Promise<void>(resolve=>gateway.listen(0,'0.0.0.0',resolve));
  const address=gateway.address();if(!address||typeof address==='string')throw new Error('Gateway unavailable');
  const override=resolve('../.scratch/conversation-retry-gateway.yaml');
  writeFileSync(override,`services:\n  agent-service:\n    environment:\n      DEEPSEEK_BASE_URL: http://host.docker.internal:${address.port}\n`);
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(fixture:boolean)=>execFileSync(docker,['compose',...(fixture?['-f',resolve('../compose.yaml'),'-f',override]:[]),'up','-d','--no-deps','--force-recreate','--wait','agent-service'],{timeout:90000,stdio:'pipe'});
  try {
    recreate(true);
    const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data});};
    const credentials={username:`retry-model-${Date.now()}`,password:'control-browser-password'};
    await post('/auth/register',credentials);await post('/auth/login',credentials);
    const project=(await(await post('/projects',{name:'失败原需求恢复'})).json()).data.id;
    const session=(await(await post(`/projects/${project}/conversations`,{title:'原需求重试'})).json()).data.id;
    await page.goto(`/?project=${project}`);
    await page.getByLabel('对话输入',{exact:true}).fill('请说明固定素材范围为空时能否核查视频事实。');
    const accepted=page.waitForResponse(response=>response.url().endsWith('/turns'));
    await page.getByRole('button',{name:'发送需求',exact:true}).click();
    const original=(await(await accepted).json()).data;
    expect((await(await page.request.get(`/api/v1/tasks/${original.taskId}`)).json()).data.type).toBe('AGENT_TURN');
    await expect(page.getByRole('button',{name:'重试原需求',exact:true})).toBeVisible({timeout:20000});
    await expect(page.getByRole('button',{name:'重试原需求',exact:true})).toBeDisabled();
    await page.getByRole('checkbox',{name:'我理解原调用费用未知，接受重试可能产生新费用'}).check();
    recreate(false);
    const retried=page.waitForResponse(response=>response.url().endsWith('/retry'));
    await page.getByRole('button',{name:'重试原需求',exact:true}).click();
    const task=(await(await retried).json()).data;
    expect(task.messageId).toBe(original.messageId);expect(task.retryOfTaskId).toBe(original.taskId);
    await expect(page.getByRole('article',{name:'助手答复'})).toHaveCount(1,{timeout:60000});
    await expect(page.getByRole('article',{name:'用户需求'})).toHaveCount(1);
    expect((await(await page.request.get(`/api/v1/tasks/${original.taskId}`)).json()).data.status).toBe('FAILED');
    const history=(await(await page.request.get(`/api/v1/conversations/${session}/messages`)).json()).data.items;
    expect(history).toHaveLength(2);expect(history[0].id).toBe(original.messageId);
    await page.reload();await expect(page.getByRole('article',{name:'助手答复'})).toHaveCount(1);
  }finally{await page.close();recreate(false);rmSync(override,{force:true});gateway.closeAllConnections();await new Promise<void>(resolve=>gateway.close(()=>resolve()));}
});

test('stopping a model call awaiting response headers releases the conversation without publishing a late answer',async({page})=>{
  test.setTimeout(150000);
  let submitted=0;
  const gateway=createServer(async(request,response)=>{
    for await(const chunk of request)void chunk;
    submitted++;
    await new Promise(resolve=>setTimeout(resolve,60000));
    if(response.destroyed)return;
    response.writeHead(200,{'Content-Type':'text/event-stream'});
    response.end(`data: ${JSON.stringify({choices:[{delta:{role:'assistant',tool_calls:[{index:0,id:'late',type:'function',function:{name:'answer_query',arguments:JSON.stringify({content:'迟到结果不应发布',evidenceIds:[]})}}]}}]})}\n\ndata: [DONE]\n\n`);
  });
  await new Promise<void>(resolve=>gateway.listen(0,'0.0.0.0',resolve));
  const address=gateway.address();if(!address||typeof address==='string')throw new Error('Gateway unavailable');
  const override=resolve('../.scratch/conversation-stop-gateway.yaml');
  writeFileSync(override,`services:\n  agent-service:\n    environment:\n      DEEPSEEK_BASE_URL: http://host.docker.internal:${address.port}\n`);
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(fixture:boolean)=>execFileSync(docker,['compose',...(fixture?['-f',resolve('../compose.yaml'),'-f',override]:[]),'up','-d','--no-deps','--force-recreate','--wait','agent-service'],{timeout:90000,stdio:'pipe'});
  try {
    recreate(true);
    const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data});};
    const credentials={username:`stop-model-${Date.now()}`,password:'control-browser-password'};
    await post('/auth/register',credentials);await post('/auth/login',credentials);
    const project=(await(await post('/projects',{name:'停止模型等待'})).json()).data.id;
    const session=(await(await post(`/projects/${project}/conversations`,{title:'停止当前需求'})).json()).data.id;
    await page.goto(`/?project=${project}`);
    await page.getByLabel('对话输入',{exact:true}).fill('核查素材');
    const accepted=page.waitForResponse(response=>response.url().endsWith('/turns'));
    await page.getByRole('button',{name:'发送需求',exact:true}).click();
    const task=(await(await accepted).json()).data.taskId;
    await expect.poll(()=>submitted,{timeout:15000}).toBe(1);
    await expect(page.getByRole('button',{name:'停止当前对话',exact:true})).toBeVisible({timeout:5000});
    await page.getByRole('button',{name:'停止当前对话',exact:true}).click();
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:15000}).toBe('CANCELLED');
    await expect(page.getByText('对话已停止；未发布本轮结果。',{exact:true})).toBeVisible();
    await expect(page.getByRole('article',{name:'助手答复'})).toHaveCount(0);
    await expect(page.getByText(/费用不能保证撤销/)).toBeVisible();
    recreate(false);
    await page.getByLabel('对话输入',{exact:true}).fill('请说明固定素材范围为空时能否核查视频事实。');
    await page.getByRole('button',{name:'发送需求',exact:true}).click();
    await expect(page.getByRole('article',{name:'助手答复'})).toHaveCount(1,{timeout:60000});
    expect((await(await page.request.get(`/api/v1/conversations/${session}/messages`)).json()).data.items).toHaveLength(3);
    expect(await page.locator('body').innerText()).not.toContain('迟到结果不应发布');
  }finally {
    await page.close();recreate(false);rmSync(override,{force:true});gateway.closeAllConnections();await new Promise<void>(resolve=>gateway.close(()=>resolve()));
  }
});

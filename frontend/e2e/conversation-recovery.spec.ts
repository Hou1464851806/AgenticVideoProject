import { test, expect } from '@playwright/test';

test('an expired replay cursor reloads the snapshot before reconnecting', async ({ page }) => {
  const credentials={username:`expired-replay-${Date.now()}`,password:'recovery-browser-password'};
  let csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  await page.request.post('/api/v1/auth/register',{headers:{'X-XSRF-TOKEN':csrf},data:credentials});
  await page.request.post('/api/v1/auth/login',{headers:{'X-XSRF-TOKEN':csrf},data:credentials});
  csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const project=(await(await page.request.post('/api/v1/projects',{headers:{'X-XSRF-TOKEN':csrf},data:{name:'游标过期恢复'}})).json()).data.id;
  let snapshots=0;
  const cursors:string[]=[];
  await page.route(`**/projects/${project}/snapshot`,async route=>{
    const response=await route.fetch();const body=await response.json();
    if(snapshots++===0)body.data.cursor=`${project}:1`;
    await route.fulfill({response,json:body});
  });
  await page.route(`**/projects/${project}/events?*`,async route=>{
    cursors.push(new URL(route.request().url()).searchParams.get('after')!);
    if(cursors.length===1)await route.fulfill({status:410,json:{message:'事件游标已过期'}});
    else await route.continue();
  });
  await page.goto(`/?project=${project}`);
  await expect.poll(()=>cursors[1],{timeout:10000}).toBe(`${project}:0`);
  expect(snapshots).toBeGreaterThanOrEqual(2);
  await expect(page.getByText(`已登录：${credentials.username}`,{exact:true})).toBeVisible();
});

test('one project stream restores a real Agent turn after refresh and closing the page', async ({ page, context }) => {
  test.setTimeout(180000);
  const username=`recovery-${Date.now()}`;
  let csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const credentials={username,password:'recovery-browser-password'};
  expect((await page.request.post('/api/v1/auth/register',{headers:{'X-XSRF-TOKEN':csrf},data:credentials})).status()).toBe(201);
  expect((await page.request.post('/api/v1/auth/login',{headers:{'X-XSRF-TOKEN':csrf},data:credentials})).status()).toBe(200);
  csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const headers={'X-XSRF-TOKEN':csrf};
  const project=(await(await page.request.post('/api/v1/projects',{headers,data:{name:'恢复验证'}})).json()).data.id;
  const conversation=(await(await page.request.post(`/api/v1/projects/${project}/conversations`,{headers,data:{title:'恢复查询'}})).json()).data.id;
  await page.setViewportSize({width:390,height:844});
  const subscriptions:string[]=[];
  page.on('request',request=>{if(request.url().includes(`/projects/${project}/events`))subscriptions.push(request.url());});
  await page.goto(`/?project=${project}`);
  await expect.poll(()=>subscriptions.length).toBe(1);
  await page.getByRole('tab',{name:'对话',exact:true}).click();
  await page.getByLabel('对话输入',{exact:true}).fill('本会话代号是海棠。请说明当前没有素材时能否确定视频制作步骤，明确说明限制。');
  const accepted=page.waitForResponse(response=>response.url().endsWith('/turns')&&response.request().method()==='POST');
  await page.getByRole('button',{name:'发送需求',exact:true}).click();
  const task=(await(await accepted).json()).data.taskId;
  await page.reload({waitUntil:'domcontentloaded'});
  await page.getByRole('tab',{name:'对话',exact:true}).click();
  await expect(page.getByRole('article',{name:'用户需求'})).toHaveCount(1);
  await page.close();
  await expect.poll(async()=>(await(await context.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:120000}).toBe('SUCCEEDED');
  const reopened=await context.newPage();
  await reopened.setViewportSize({width:390,height:844});
  await reopened.goto(`/?project=${project}`,{waitUntil:'domcontentloaded'});
  await reopened.getByRole('tab',{name:'对话',exact:true}).click();
  await expect(reopened.getByRole('article',{name:'助手答复'})).toHaveCount(1);
  await expect(reopened.getByRole('article',{name:'助手答复'})).toContainText(/没有|无法|未上传|无素材|缺少/);
  const snapshot=(await(await context.request.get(`/api/v1/projects/${project}/snapshot`)).json()).data;
  expect(snapshot.conversations.find((item:{id:string})=>item.id===conversation).items).toHaveLength(2);
  expect(JSON.stringify(snapshot)).not.toMatch(/reasoning_content|leaseToken|object_key|Authorization/);
  expect(await reopened.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

test('batched public drafts survive interrupted SSE and duplicate delivery without exposing private model fields',async({page,context})=>{
  test.setTimeout(180000);
  const {createServer}=await import('node:http');
  const {execFileSync}=await import('node:child_process');
  const {writeFileSync,rmSync}=await import('node:fs');
  const {resolve}=await import('node:path');
  let streaming=false;
  const answer='已核查😀\n当前没有素材，因此无法判断视频的制作步骤。公开结论只说明可确认的信息，上传素材后可以依据证据核查。';
  const gateway=createServer(async(request,response)=>{
    const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
    const payload=JSON.parse(Buffer.concat(chunks).toString());streaming=payload.stream===true;
    response.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
    const send=(delta:unknown,usage?:unknown)=>response.write(`data: ${JSON.stringify({id:'stream-boundary-fixture',model:'deepseek-flash',choices:[{delta,index:0}],usage})}\n\n`);
    send({role:'assistant',content:'private-assistant-planning',reasoning_content:'private-provider-reasoning',tool_calls:[{index:0,id:'public-answer',type:'function',function:{name:'answer_query',arguments:''}}]});
    const argumentsText=JSON.stringify({debug:'private-tool-arguments',evidenceIds:[],content:answer}).replace('😀','\\ud83d\\ude00');
    for(let index=0;index<argumentsText.length;index+=3){
      if(response.destroyed)return;
      send({tool_calls:[{index:0,function:{arguments:argumentsText.slice(index,index+3)}}]});
      await new Promise(resolve=>setTimeout(resolve,40));
    }
    send({}, {prompt_tokens:100,completion_tokens:80,total_tokens:180});response.end('data: [DONE]\n\n');
  });
  await new Promise<void>(resolve=>gateway.listen(0,'0.0.0.0',resolve));
  const address=gateway.address();if(!address||typeof address==='string')throw new Error('Gateway unavailable');
  const override=resolve('../.scratch/conversation-stream-gateway.yaml');
  writeFileSync(override,`services:\n  agent-service:\n    environment:\n      DEEPSEEK_BASE_URL: http://host.docker.internal:${address.port}\n`);
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(fixture:boolean)=>execFileSync(docker,['compose',...(fixture?['-f',resolve('../compose.yaml'),'-f',override]:[]),'up','-d','--no-deps','--force-recreate','--wait','agent-service'],{timeout:90000,stdio:'pipe'});
  try{
    recreate(true);
    await page.addInitScript(()=>{
      const Native=window.EventSource;
      window.EventSource=class extends Native{
        override addEventListener(type:string,listener:EventListenerOrEventListenerObject|null,options?:boolean|AddEventListenerOptions){
          if(!listener){super.addEventListener(type,listener,options);return;}
          super.addEventListener(type,event=>{
            const deliver=()=>typeof listener==='function'?listener.call(this,event):listener.handleEvent(event);
            deliver();if(type==='assistant.delta'||type==='assistant.completed')deliver();
          },options);
        }
      };
    });
    const post=async(path:string,data:unknown)=>{
      const token=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
      return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':token,'Idempotency-Key':crypto.randomUUID()},data});
    };
    const credentials={username:`stream-${Date.now()}`,password:'stream-browser-password'};
    expect((await post('/auth/register',credentials)).status()).toBe(201);expect((await post('/auth/login',credentials)).status()).toBe(200);
    const project=(await(await post('/projects',{name:'流式恢复'})).json()).data.id;
    const session=(await(await post(`/projects/${project}/conversations`,{title:'公开草稿'})).json()).data.id;
    await page.goto(`/?project=${project}`);
    await page.getByLabel('对话输入',{exact:true}).fill('查询制作步骤');
    await page.getByRole('button',{name:'发送需求',exact:true}).click();
    const draft=page.getByRole('article',{name:'助手未完成草稿'});
    await expect(draft).toContainText('已核查😀',{timeout:15000});
    await expect(draft).toContainText('尚未发布正式答复');
    await context.setOffline(true);
    await new Promise(resolve=>setTimeout(resolve,500));
    await context.setOffline(false);
    await page.reload({waitUntil:'domcontentloaded'});
    await expect(page.getByRole('article',{name:'助手答复'})).toHaveCount(1,{timeout:15000});
    await expect(page.getByRole('article',{name:'助手答复'})).toContainText(answer);
    await expect(page.getByRole('article',{name:'助手未完成草稿'})).toHaveCount(0);
    expect(streaming).toBe(true);
    expect(await page.locator('body').innerText()).not.toMatch(/private-assistant-planning|private-provider-reasoning|private-tool-arguments/);
    const restored=(await(await page.request.get(`/api/v1/conversations/${session}/snapshot`)).json()).data;
    expect(restored.items).toHaveLength(2);expect(restored.items[1].content).toBe(answer);
    await page.request.post('/api/v1/auth/logout',{headers:{'X-XSRF-TOKEN':(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken},data:{}});
    await expect(page.getByRole('heading',{name:'登录工作台'})).toBeVisible({timeout:10000});
    await expect(page.getByRole('status')).toContainText('登录已到期');
  }finally{
    await page.close();recreate(false);rmSync(override,{force:true});
    gateway.closeAllConnections();await new Promise<void>(resolve=>gateway.close(()=>resolve()));
  }
});

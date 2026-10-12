import {test,expect} from '@playwright/test';
import {createServer} from 'node:http';
import {execFileSync} from 'node:child_process';

test('natural language brief corrections and explicit question answers survive service restart without rerunning the waiting turn',async({page})=>{
  test.setTimeout(240000);
  let calls=0;const assetIds:string[]=[];
  const explicit=(value:unknown)=>({value,basis:'EXPLICIT'});
  const provider=createServer(async(request,response)=>{
    for await(const chunk of request)void chunk;calls++;
    const name=[1,3,5,8].includes(calls)?'update_editing_brief':[2,7].includes(calls)?'ask_clarification':'answer_query';
    const fields=calls===1?{topic:explicit('上传教程'),count:{value:1,basis:'DEFAULT'},assetIds:explicit(assetIds),forbiddenAssetIds:explicit([assetIds[1]]),forbiddenContent:explicit(['姓名'])}:calls===3?{duration:explicit({minMs:8000,maxMs:10000})}:calls===5?{duration:explicit({minMs:9000,maxMs:11000}),forbiddenAssetIds:explicit([assetIds[0]])}:{topic:explicit('旅行记录'),duration:explicit({minMs:10000,maxMs:15000})};
    const args=name==='update_editing_brief'?{fields}:name==='ask_clarification'?{prompt:calls===2?'希望制作多少秒的视频？':'新需求希望制作什么主题？',fields:[calls===2?'duration':'topic'],reason:'关键条件未说明'}:{content:'制作需求简报已更新，请检查明确约束与已说明默认值。',evidenceIds:[]};
    response.writeHead(200,{'Content-Type':'application/json'});
    response.end(JSON.stringify({id:`brief-fixture-${calls}`,model:'deepseek-flash',choices:[{message:{role:'assistant',content:null,tool_calls:[{id:`brief-tool-${calls}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]}}],usage:{}}));
  });
  await new Promise<void>(resolve=>provider.listen(0,'0.0.0.0',resolve));
  const address=provider.address();if(!address||typeof address==='string')throw new Error('Provider fixture unavailable');
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(services:string[],env:NodeJS.ProcessEnv)=>execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait',...services],{env,timeout:90000,stdio:'pipe'});
  const env={...process.env,DEEPSEEK_BASE_URL:`http://host.docker.internal:${address.port}`};
  try{
    recreate(['agent-service'],env);
    const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data});};
    const credentials={username:`brief-browser-${Date.now()}`,password:'brief-browser-password'};
    expect((await post('/auth/register',credentials)).status()).toBe(201);expect((await post('/auth/login',credentials)).status()).toBe(200);
    const project=(await(await post('/projects',{name:'可纠正需求简报'})).json()).data;
    await page.goto(`/?project=${project.id}`);
    for(const file of ['labelled-speech.mp4','labelled-vision-vfr.mp4']){
      const transferred=page.waitForResponse(reply=>reply.url().endsWith('/complete'));
      await page.getByLabel('选择视频',{exact:true}).setInputFiles(`e2e/fixtures/${file}`);const asset=(await(await transferred).json()).data.assetId;assetIds.push(asset);
      await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${asset}`)).json()).data.validationStatus,{timeout:30000}).toBe('VALID');
    }
    const session=(await(await post(`/projects/${project.id}/conversations`,{title:'教程制作条件',assetIds})).json()).data;
    const idle=async()=>expect.poll(async()=>(await(await page.request.get(`/api/v1/conversations/${session.id}/snapshot`)).json()).data.latestTask.status,{timeout:30000}).toBe('SUCCEEDED');
    await page.reload();await page.getByLabel('制作会话',{exact:true}).selectOption(session.id);
    await expect(page.getByLabel('需求类型',{exact:true}).locator('option[value="REQUIREMENTS"]')).toHaveCount(1,{timeout:5000});
    await page.getByLabel('需求类型',{exact:true}).selectOption('REQUIREMENTS');
    await page.getByLabel('对话输入',{exact:true}).fill('做上传教程，禁止展示姓名，不使用labelled-vision-vfr.mp4；时长先问我。');
    await page.getByRole('button',{name:'发送需求',exact:true}).click();
    const brief=page.getByRole('region',{name:'制作需求简报',exact:true});const question=page.getByRole('article',{name:'待回答的澄清问题',exact:true});
    await expect(brief).toContainText('上传教程',{timeout:30000});await expect(brief).toContainText('已说明默认值');await expect(brief).toContainText('labelled-vision-vfr.mp4');
    await expect(question).toContainText('希望制作多少秒的视频？');expect(calls).toBe(2);
    const before=(await(await page.request.get(`/api/v1/conversations/${session.id}/snapshot`)).json()).data;
    recreate(['business-api','agent-service'],env);expect((await post('/auth/login',credentials)).status()).toBe(200);
    await page.reload();await expect(question).toContainText('希望制作多少秒的视频？');expect(calls).toBe(2);
    await question.getByLabel('澄清回答',{exact:true}).fill('8至10秒');await question.getByRole('button',{name:'提交澄清回答',exact:true}).click();
    await expect(brief).toContainText('8–10 秒',{timeout:30000});await expect(question).toHaveCount(0);await idle();expect(calls).toBe(4);await page.getByLabel('需求类型',{exact:true}).selectOption('REQUIREMENTS');
    await page.getByLabel('对话输入',{exact:true}).fill('纠正时长为9至11秒，禁用素材改为labelled-speech.mp4。');await page.getByRole('button',{name:'发送需求',exact:true}).click();
    await expect(brief).toContainText('9–11 秒',{timeout:30000});await idle();expect(calls).toBe(6);
    const corrected=(await(await page.request.get(`/api/v1/conversations/${session.id}/snapshot`)).json()).data;
    expect(corrected.brief.fields.topic.sourceMessageId).toBe(before.brief.fields.topic.sourceMessageId);expect(corrected.brief.fields.duration.sourceMessageId).not.toBe(before.brief.fields.topic.sourceMessageId);
    expect(corrected.brief.fields.forbiddenAssetIds.value).toEqual([assetIds[0]]);
    await page.getByLabel('对话输入',{exact:true}).fill('另一个需求缺少主题，先问清楚。');await page.getByRole('button',{name:'发送需求',exact:true}).click();await expect(question).toContainText('新需求希望制作什么主题？',{timeout:30000});
    await page.getByLabel('对话输入',{exact:true}).fill('放弃原等待，开始旅行记录需求，10至15秒。');await question.getByRole('button',{name:'开始新需求',exact:true}).click();
    await expect(brief).toContainText('旅行记录',{timeout:30000});await expect(question).toHaveCount(0);await idle();expect(calls).toBe(9);
    const after=(await(await page.request.get(`/api/v1/conversations/${session.id}/snapshot`)).json()).data;
    expect(after.brief.fields.forbiddenAssetIds).toBeUndefined();expect(after.questions.filter((item:{status:string})=>item.status==='OPEN')).toHaveLength(0);
    await brief.getByRole('button',{name:'应用简报生成讲话方案',exact:true}).click();
    await expect(page.getByLabel('需求类型',{exact:true})).toHaveValue('GENERATE_PLAN');
    await page.getByLabel('对话输入',{exact:true}).fill('按已检查的旅行简报生成方案。');
    const applied=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/turns'));
    await page.getByRole('button',{name:'发送需求',exact:true}).click();const appliedResponse=await applied;expect(appliedResponse.status()).toBe(202);
    expect(appliedResponse.request().postDataJSON().context.briefRevision).toBe(after.brief.revision);await idle();

  }finally{recreate(['agent-service'],{...process.env});await new Promise<void>(resolve=>provider.close(()=>resolve()));}
});

test('real DeepSeek asks for a missing duration then retains hard conditions while the user corrects the brief',async({page})=>{
  test.setTimeout(180000);
  const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data});};
  const credentials={username:`brief-native-${Date.now()}`,password:'brief-browser-password'};
  expect((await post('/auth/register',credentials)).status()).toBe(201);expect((await post('/auth/login',credentials)).status()).toBe(200);
  const project=(await(await post('/projects',{name:'真实模型制作需求'})).json()).data;
  const session=(await(await post(`/projects/${project.id}/conversations`,{title:'上传流程需求',assetIds:[]})).json()).data;
  await page.goto(`/?project=${project.id}`);await page.getByLabel('制作会话',{exact:true}).selectOption(session.id);await page.getByLabel('需求类型',{exact:true}).selectOption('REQUIREMENTS');
  await page.getByLabel('对话输入',{exact:true}).fill('帮我整理制作需求：主题是素材上传流程，做一条，禁止展示电话。固定素材范围为空，暂不生成方案。时长还没确定，请明确询问，其他低影响默认值请说明。');
  await page.getByRole('button',{name:'发送需求',exact:true}).click();
  const question=page.getByRole('article',{name:'待回答的澄清问题',exact:true});await expect(question).toContainText(/时长|多少秒|多久/,{timeout:60000});
  await question.getByLabel('澄清回答',{exact:true}).fill('8至10秒。');await question.getByRole('button',{name:'提交澄清回答',exact:true}).click();
  const snapshot=async()=>(await(await page.request.get(`/api/v1/conversations/${session.id}/snapshot`)).json()).data;
  await expect.poll(async()=>(await snapshot()).latestTask.status,{timeout:60000}).toBe('SUCCEEDED');
  const before=await snapshot();expect(before.brief.fields.duration.value).toEqual({minMs:8000,maxMs:10000});expect(before.brief.fields.count.value).toBe(1);
  expect(before.brief.fields.forbiddenContent.value.join('')).toMatch(/电话/);expect(before.questions[0].status).toBe('ANSWERED');
  const brief=page.getByRole('region',{name:'制作需求简报',exact:true});await expect(brief).toContainText('8–10 秒');
  await page.getByLabel('需求类型',{exact:true}).selectOption('REQUIREMENTS');await page.getByLabel('对话输入',{exact:true}).fill('将时长纠正为9至11秒，主题、数量、禁用内容及其他条件都保留。');
  const sent=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/turns'));await page.getByRole('button',{name:'发送需求',exact:true}).click();
  const accepted=await sent;expect(accepted.status()).toBe(202);const task=(await accepted.json()).data.taskId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:60000}).toBe('SUCCEEDED');
  const after=await snapshot();expect(after.brief.fields.duration.value).toEqual({minMs:9000,maxMs:11000});expect(after.brief.fields.topic).toEqual(before.brief.fields.topic);expect(after.brief.fields.count).toEqual(before.brief.fields.count);expect(after.brief.fields.forbiddenContent).toEqual(before.brief.fields.forbiddenContent);
  await page.reload();await expect(brief).toContainText('9–11 秒');await expect(question).toHaveCount(0);
});


test('a waiting editing question can explicitly resume from the version the user is viewing',async({page})=>{
  test.setTimeout(120000);
  const {randomUUID}=await import('node:crypto');
  const provider=createServer(async(request,response)=>{for await(const chunk of request)void chunk;response.writeHead(200,{'Content-Type':'application/json'});response.end(JSON.stringify({id:'base-resume-fixture',model:'deepseek-flash',choices:[{message:{role:'assistant',content:null,tool_calls:[{id:'base-confirmed',type:'function',function:{name:'answer_query',arguments:JSON.stringify({content:'已关联明确选定的基准继续原需求；固定范围为空，未发布方案。',evidenceIds:[]})}}]}}],usage:{}}));});
  await new Promise<void>(resolve=>provider.listen(0,'0.0.0.0',resolve));const address=provider.address();if(!address||typeof address==='string')throw new Error('Provider fixture unavailable');
  const docker=process.env.DOCKER_BIN??'docker';const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','agent-service'],{env,timeout:60000,stdio:'pipe'});
  try{
    recreate({...process.env,DEEPSEEK_BASE_URL:`http://host.docker.internal:${address.port}`});
    const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':randomUUID()},data});};
    const credentials={username:`brief-base-${Date.now()}`,password:'brief-browser-password'};expect((await post('/auth/register',credentials)).status()).toBe(201);expect((await post('/auth/login',credentials)).status()).toBe(200);
    const project=(await(await post('/projects',{name:'等待时明确新基准'})).json()).data;const session=(await(await post(`/projects/${project.id}/conversations`,{title:'编辑等待',assetIds:[]})).json()).data;
    const task=randomUUID(),turn=randomUUID(),message=randomUUID(),plan=randomUUID(),revision=randomUUID(),question=randomUUID();
    const input={conversationId:session.id,turnId:turn,messageId:message,content:'先确认主题再修改方案',assetIds:[],references:[],history:[{role:'USER',content:'先确认主题再修改方案'}],context:{requirements:true},editing:{topic:'教程',minDurationMs:1000,maxDurationMs:2000,profile:'LANDSCAPE_720',planId:plan}};
    const encoded=Buffer.from(JSON.stringify(input)).toString('base64');
    execFileSync(docker,['compose','exec','-T','postgres','psql','-U','agentic_video','-d','agentic_video','-v','ON_ERROR_STOP=1'],{input:`BEGIN;
INSERT INTO tasks(id,project_id,type,status,input_json) VALUES ('${task}','${project.id}','AGENT_TURN','WAITING_INPUT',convert_from(decode('${encoded}','base64'),'UTF8')::jsonb);
INSERT INTO conversation_messages(id,conversation_id,message_seq,role,content) VALUES ('${message}','${session.id}',1,'USER','先确认主题再修改方案');
INSERT INTO conversation_turns(id,conversation_id,message_id,task_id,request_key,request_json) VALUES ('${turn}','${session.id}','${message}','${task}','waiting-base','{}');
INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES ('${plan}','${project.id}','教程',2);
INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES ('${revision}','${project.id}','${plan}',1,'${task}','{"schemaVersion":"1.0","outputs":[]}');
INSERT INTO conversation_questions(id,conversation_id,task_id,turn_id,prompt,reason,fields_json) VALUES ('${question}','${session.id}','${task}','${turn}','主题如何修改？','主题需要澄清','["topic"]');COMMIT;`,stdio:'pipe'});
    await page.goto(`/?project=${project.id}`);await page.getByLabel('查看方案',{exact:true}).selectOption(plan);await page.getByLabel('查看版本',{exact:true}).selectOption(revision);
    const card=page.getByRole('article',{name:'待回答的澄清问题',exact:true});await expect(card.getByLabel('以当前查看版本恢复方案修改',{exact:true})).toBeVisible({timeout:5000});
    await card.getByLabel('以当前查看版本恢复方案修改',{exact:true}).check();await card.getByLabel('澄清回答',{exact:true}).fill('使用当前查看版本，主题保留教程。');
    const answered=page.waitForResponse(reply=>reply.url().endsWith(`/questions/${question}/answers`));await card.getByRole('button',{name:'提交澄清回答',exact:true}).click();const response=await answered;expect(response.status()).toBe(202);
    expect(response.request().postDataJSON().editing.baseRevisionId).toBe(revision);const identity=(await response.json()).data;expect(identity.resumesTaskId).toBe(task);expect(identity.taskId).not.toBe(task);
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${identity.taskId}`)).json()).data.status,{timeout:30000}).toBe('SUCCEEDED');await expect(card).toHaveCount(0);
  }finally{recreate({...process.env});await new Promise<void>(resolve=>provider.close(()=>resolve()));}
});

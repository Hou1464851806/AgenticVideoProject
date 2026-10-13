import {test,expect} from '@playwright/test';
import {renderSeed} from './fixtures/render-seed';

test('new chat welcomes the user before the first message and survives reopening',async({page})=>{
  const post=async(path:string,data:unknown,key=crypto.randomUUID())=>{
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':key},data});
  };
  const credentials={username:`natural-chat-${Date.now()}`,password:'natural-chat-password'};
  expect((await post('/auth/register',credentials)).status()).toBe(201);
  expect((await post('/auth/login',credentials)).status()).toBe(200);
  const project=(await(await post('/projects',{name:'自然语言工作区'})).json()).data;
  await page.goto(`/?project=${project.id}`);
  await page.getByRole('button',{name:'新聊天',exact:true}).click({timeout:3000});
  const welcome=page.getByRole('article',{name:'助手答复',exact:true});
  await expect(welcome).toHaveCount(1);
  await expect(welcome).toContainText('你好');
  await expect(welcome).toContainText('0 份素材');
  await expect(page.getByLabel('对话输入',{exact:true})).toBeVisible();
  await expect(page.getByLabel('需求类型',{exact:true})).toHaveCount(0);
  await page.reload();
  await expect(welcome).toHaveCount(1);
  await expect(welcome).toContainText('你好');
});

test('real Agent chooses speech evidence and narrows a new chat through natural language',async({page})=>{
  test.setTimeout(240000);
  const seed=await renderSeed(page);
  await page.goto(`/?project=${seed.projectId}`);
  const uploaded=page.waitForResponse(reply=>reply.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-vision-vfr.mp4');
  const otherAsset=(await(await uploaded).json()).data.assetId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${otherAsset}`)).json()).data.validationStatus,{timeout:30000}).toBe('VALID');
  const created=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/conversations'));
  await page.getByRole('button',{name:'新聊天',exact:true}).click();
  const session=(await(await created).json()).data;
  await page.getByLabel('对话输入',{exact:true}).fill('只用 labelled-speech.mp4，讲述者叫什么名字？请查证语音原文并引用证据。');
  const accepted=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/turns'));
  await page.getByRole('button',{name:'发送需求',exact:true}).click();
  const response=await accepted;expect(response.status()).toBe(202);
  expect(response.request().postDataJSON().context).toEqual({naturalLanguage:true});
  const task=(await response.json()).data.taskId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${task}`)).json()).data.status,{timeout:180000}).toBe('SUCCEEDED');
  const answer=page.getByRole('article',{name:'助手答复',exact:true}).last();
  await expect(answer).toContainText('张明');
  await expect(answer.getByRole('button',{name:'核查引用原视频',exact:true}).first()).toBeVisible();
  const snapshot=(await(await page.request.get(`/api/v1/conversations/${session.id}/snapshot`)).json()).data;
  const reply=snapshot.items.at(-1);expect(reply.references.length).toBeGreaterThan(0);
  for(const citation of reply.references)expect(citation).toMatchObject({type:'EVIDENCE',assetId:seed.assetId,sourceKind:'SPEECH'});
  const sessions=(await(await page.request.get(`/api/v1/projects/${seed.projectId}/conversations`)).json()).data.items;
  expect(sessions.find((item:{id:string})=>item.id===session.id).assetIds).toEqual([seed.assetId]);
  await expect(page.locator('.conversation-panel .scope')).toContainText('固定包含 1 个素材');
  await page.reload();await expect(page.getByRole('article',{name:'助手答复',exact:true}).last()).toContainText('张明');
});

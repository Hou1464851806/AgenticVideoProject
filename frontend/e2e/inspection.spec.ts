import {test,expect} from '@playwright/test';
import fs from 'node:fs';

test('bounded dense inspection finds manually labelled brief small text without rewriting prior observations',async({page})=>{
  test.setTimeout(180000);
  const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':`inspection-${Date.now()}`},data});};
  const username=`brief-text-${Date.now()}`;await post('/auth/register',{username,password:'inspection-browser-password'});await post('/auth/login',{username,password:'inspection-browser-password'});
  const project=(await(await post('/projects',{name:'短暂小字补查'})).json()).data;
  fs.writeFileSync('../.scratch/vision-fixture/inspection-seed.json',JSON.stringify({username,projectId:project.id}));
  await page.goto(`/?project=${project.id}`);
  const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/brief-small-text.mp4');
  const assetId=(await(await completed).json()).data.assetId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.validationStatus,{timeout:30000}).toBe('VALID');
  const prior=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
  const accepted=await post(`/projects/${project.id}/visual-inspections`,{assetId,query:'瞬时83',sourceKind:'VISIBLE_TEXT',startMs:500,endMs:1500});
  expect(accepted.status()).toBe(202);
  const runId=(await accepted.json()).data.id;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/visual-inspections/${runId}`)).json()).data.status,{timeout:120000}).toBe('SUCCEEDED');
  const run=(await(await page.request.get(`/api/v1/visual-inspections/${runId}`)).json()).data;
  expect(run.budget).toMatchObject({maxFrames:4,maxCalls:4,maxSteps:8,maxSeconds:180,maxInputBytes:4194304});
  expect(run.usage.frames).toBe(4);expect(run.usage.calls).toBeLessThanOrEqual(4);expect(run.usage.steps).toBeLessThanOrEqual(8);expect(run.usage.inputBytes).toBeLessThanOrEqual(4194304);
  expect(run.observations.filter((o:{sourceKind:string;text:string})=>o.sourceKind==='VISIBLE_TEXT'&&o.text.includes('瞬时83')).map((o:{observedAtMs:number})=>o.observedAtMs)).toEqual([1000,1250]);
  expect(run.coverage).toMatchObject({kind:'BOUNDED_DENSE_FRAMES',complete:false,startMs:500,endMs:1500});
  const after=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
  for(const frame of prior.vision.frames)expect(after.vision.frames).toContainEqual(frame);
  expect(after.vision.providerCalls.every((call:{status:string;requestId:string})=>call.status==='SUCCEEDED'&&!!call.requestId)).toBe(true);
});


test('no-candidate browser inspection exits with a bounded result card and retained observations',async({page})=>{
  test.setTimeout(180000);
  const seed=JSON.parse(fs.readFileSync('../.scratch/vision-fixture/inspection-seed.json','utf8'));
  const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  expect((await page.request.post('/api/v1/auth/login',{headers:{'X-XSRF-TOKEN':csrf},data:{username:seed.username,password:'inspection-browser-password'}})).status()).toBe(200);
  await page.goto(`/?project=${seed.projectId}`);
  await page.getByLabel('证据类型').selectOption('VISIBLE_TEXT');await page.getByLabel('查找原句').fill('未出现的飞机');await page.getByRole('button',{name:'查找证据',exact:true}).click();
  await expect(page.getByRole('button',{name:'预算内覆盖补查'})).toBeVisible({timeout:5000});
  await page.getByRole('button',{name:'预算内覆盖补查'}).click();
  const card=page.getByRole('region',{name:'预算内补查结果'});
  await expect(card).toContainText('最多 4 帧');await expect(card).toContainText('无新匹配，已停止',{timeout:120000});
  await expect(card).toContainText('瞬时83');await expect(card).toContainText('仍可能遗漏');
  await expect(card.getByRole('button',{name:'查看扩大补查方案'})).toBeVisible({timeout:5000});
  await card.getByRole('button',{name:'查看扩大补查方案'}).click();
  await expect(card).toContainText('新增最多 8 帧');await expect(card).toContainText('费用估算');
  await card.getByRole('button',{name:'保留结果，不扩大'}).click();
  await expect(card).toContainText('已拒绝扩大');await expect(card).toContainText('瞬时83');

});

test('explicit browser approval starts a separately budgeted expansion and preserves the first result',async({page})=>{
  test.setTimeout(180000);
  const seed=JSON.parse(fs.readFileSync('../.scratch/vision-fixture/inspection-seed.json','utf8'));
  const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  expect((await page.request.post('/api/v1/auth/login',{headers:{'X-XSRF-TOKEN':csrf},data:{username:seed.username,password:'inspection-browser-password'}})).status()).toBe(200);
  await page.goto(`/?project=${seed.projectId}`);await page.getByLabel('证据类型').selectOption('VISIBLE_TEXT');await page.getByLabel('查找原句').fill('瞬时83');await page.getByRole('button',{name:'查找证据',exact:true}).click();
  const hit=page.getByRole('article',{name:'可见文字检索证据'}).first();
  await expect(hit).toBeVisible();await page.getByLabel('证据类型').selectOption('VISUAL');
  const created=page.waitForResponse(response=>response.url().endsWith('/visual-inspections')&&response.request().method()==='POST');
  await hit.getByRole('button',{name:'预算内复查候选'}).click();
  const accepted=await created;expect(accepted.request().postDataJSON().sourceKind).toBe('VISIBLE_TEXT');
  const card=page.getByRole('region',{name:'预算内补查结果',exact:true});
  await expect(card).toContainText('已找到新匹配，已停止',{timeout:120000});
  const proposed=page.waitForResponse(response=>response.url().endsWith('/expansion-proposal'));
  await card.getByRole('button',{name:'查看扩大补查方案'}).click();const proposal=(await(await proposed).json()).data;
  expect(proposal.status).toBe('PROPOSED');expect(proposal.childInspectionId).toBeNull();
  await expect(card.getByRole('button',{name:'接受范围与费用，继续补查'})).toBeVisible({timeout:5000});
  await card.getByRole('button',{name:'接受范围与费用，继续补查'}).click();
  const expanded=card.getByRole('region',{name:'扩大补查结果',exact:true});
  await expect(expanded).toContainText('最多 8 帧');await expect(expanded).toContainText('已找到新匹配，已停止',{timeout:120000});
  await expect(card).toContainText('本轮最多 4 帧');await expect(expanded).toContainText('瞬时83');
});

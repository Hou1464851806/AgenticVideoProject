import {test,expect,type Page} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {renderSeed} from './fixtures/render-seed';

const docker=process.env.DOCKER_BIN??'docker';
const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','business-api'],{env,timeout:60000,stdio:'pipe'});
async function post(page:Page,path:string,data:unknown) {
  const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf},data});
}
async function project(page:Page,name:string) {
  const username=`capacity-${Date.now()}`,password='project-browser-password';
  expect((await post(page,'/auth/register',{username,password})).status()).toBe(201);
  expect((await post(page,'/auth/login',{username,password})).status()).toBe(200);
  return (await(await post(page,'/projects',{name})).json()).data.id as string;
}

test('the browser explains configured file limits and measured Docker volume space with a usable next action',async({page})=>{
  test.setTimeout(150000);
  try {
    recreate({...process.env,UPLOAD_MAX_FILE_BYTES:'1024'});
    const id=await project(page,'限额与空间提示');await page.goto(`/?project=${id}`);
    await page.getByLabel('选择视频',{exact:true}).setInputFiles({name:'too-large.mp4',mimeType:'video/mp4',buffer:Buffer.alloc(1025)});
    await expect(page.getByRole('alert').filter({hasText:'单文件大小超过上限 1024 字节，请压缩或拆分视频后重试'})).toBeVisible();
    recreate({...process.env,CAPACITY_MIN_FREE_BYTES:'9223372036854775807'});
    // Recreating the API clears in-memory sessions; sign in again through the public interface.
    const next=await project(page,'实际卷低空间');await page.goto(`/?project=${next}`);
    await page.getByLabel('选择视频',{exact:true}).setInputFiles({name:'low-space.mp4',mimeType:'video/mp4',buffer:Buffer.alloc(1)});
    await expect(page.getByRole('alert').filter({hasText:'可用空间不足，请清理不需要的文件或扩容后重试'})).toBeVisible();
    expect((await(await page.request.get(`/api/v1/projects/${next}/uploads`)).json()).data.items).toHaveLength(0);
  } finally { recreate({...process.env}); }
});

test('the real media worker rechecks shared space before downloading and probing a queued upload',async({page})=>{
  test.setTimeout(150000);
  try {
    execFileSync(docker,['compose','stop','media-worker'],{timeout:60000,stdio:'pipe'});
    const id=await project(page,'执行前低空间');await page.goto(`/?project=${id}`);
    const completion=page.waitForResponse(response=>response.url().endsWith('/complete')&&response.request().method()==='POST');
    await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/short-video.mp4');
    const reply=await completion;expect(reply.status()).toBe(202);
    const upload=(await reply.json()).data;
    recreate({...process.env,CAPACITY_MIN_FREE_BYTES:'9223372036854775807'});
    execFileSync(docker,['compose','start','media-worker'],{timeout:60000,stdio:'pipe'});
    // Observe via a new login after the API restart.
    const username=(await page.locator('text=/已登录：/').textContent())!.replace('已登录：','').trim();
    expect((await post(page,'/auth/login',{username,password:'project-browser-password'})).status()).toBe(200);
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/uploads/${upload.uploadId}`)).json()).data.validationStatus,{timeout:30000}).toBe('INVALID');
    const state=(await(await page.request.get(`/api/v1/uploads/${upload.uploadId}`)).json()).data;
    expect(state.error).toMatchObject({code:'MEDIA_SPACE_LOW'});
    expect(state.error.message).toContain('空间');
    await page.reload();await expect(page.getByRole('alert').filter({hasText:'空间'})).toBeVisible();
    await expect(page.getByRole('button',{name:'播放 short-video.mp4',exact:true})).toHaveCount(0);
  } finally {
    recreate({...process.env});
    execFileSync(docker,['compose','start','media-worker'],{timeout:60000,stdio:'pipe'});
  }
});


test('queued preview and export use the same measured space gate before real rendering',async({page})=>{
  test.setTimeout(180000);
  try {
    const seed=await renderSeed(page);
    const plan=randomUUID(),revision=randomUUID(),task=randomUUID(),output=randomUUID();
    const content={schemaVersion:'1.0',outputs:[{outputId:output,title:'空间复查样本',durationMs:4000,
      clips:[{clipId:randomUUID(),assetId:seed.assetId,sourceInMs:2000,sourceOutMs:6000,audio:'original',evidenceIds:[],reason:'已完成版本的执行空间测试'}],
      render:{profile:'LANDSCAPE_720',width:1280,height:720,fit:'contain'},subtitles:{enabled:false,cues:[]}}]};
    const encoded=Buffer.from(JSON.stringify(content)).toString('base64');
    // Fixture setup only; assertions use public HTTP and real worker execution.
    execFileSync(docker,['compose','exec','-T','postgres','psql','-U','agentic_video','-d','agentic_video','-v','ON_ERROR_STOP=1'],{input:`BEGIN;
INSERT INTO tasks(id,project_id,type,status,input_json) VALUES ('${task}','${seed.projectId}','AGENT_TURN','SUCCEEDED','{}');
INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES ('${plan}','${seed.projectId}','空间测试版本',2);
INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES ('${revision}','${seed.projectId}','${plan}',1,'${task}',convert_from(decode('${encoded}','base64'),'UTF8')::jsonb);
INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES ('${revision}','${seed.assetId}'); COMMIT;`,stdio:'pipe'});
    execFileSync(docker,['compose','stop','media-worker'],{timeout:60000,stdio:'pipe'});
    const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
    const action=async(path:string,data:unknown)=>page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':randomUUID()},data});
    const preview=await action(`/revisions/${revision}/previews`,{outputId:output,previewProfile:'LOW_360'});expect(preview.status()).toBe(202);
    const confirmed=await action(`/revisions/${revision}/confirmations`,{explicitIntent:'CONFIRM'});expect(confirmed.status()).toBe(201);
    const exported=await action(`/revisions/${revision}/export-requests`,{explicitIntent:'EXPORT',exportProfile:'HD_720',outputIds:[output],confirmationId:(await confirmed.json()).data.confirmationId});expect(exported.status()).toBe(202);
    const requests=[(await preview.json()).data,(await exported.json()).data];
    recreate({...process.env,CAPACITY_MIN_FREE_BYTES:'9223372036854775807'});
    expect((await post(page,'/auth/login',{username:seed.username,password:'analysis-browser-password'})).status()).toBe(200);
    execFileSync(docker,['compose','start','media-worker'],{timeout:60000,stdio:'pipe'});
    for(const request of requests) {
      await expect.poll(async()=>(await(await page.request.get(`/api/v1/render-requests/${request.requestId}`)).json()).data.status,{timeout:30000}).toBe('FAILED');
      const state=(await(await page.request.get(`/api/v1/render-requests/${request.requestId}`)).json()).data;
      expect(state.error).toMatchObject({code:'MEDIA_SPACE_LOW'});expect(state.artifactId).toBeUndefined();expect(state.revisionId).toBe(revision);
    }
  } finally {
    recreate({...process.env});
    execFileSync(docker,['compose','start','media-worker'],{timeout:60000,stdio:'pipe'});
  }
});

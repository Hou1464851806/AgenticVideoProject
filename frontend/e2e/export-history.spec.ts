import {test,expect} from '@playwright/test';
import {renderSeed} from './fixtures/render-seed';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';

test('refresh restores button-created exports and an older successful file remains accessible after a later failure',async({page})=>{
  test.setTimeout(120000);const seed=await renderSeed(page),docker=process.env.DOCKER_BIN??'docker';
  const post=async(path:string,data:unknown)=>{const token=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':token,'Idempotency-Key':randomUUID()},data});};
  const plan=randomUUID(),revision=randomUUID(),task=randomUUID(),output=randomUUID();
  const content={schemaVersion:'1.0',outputs:[{outputId:output,title:'历史导出恢复',durationMs:2000,clips:[{clipId:randomUUID(),assetId:seed.assetId,sourceInMs:2000,sourceOutMs:4000,audio:'original',evidenceIds:[],reason:'已保存方案'}],render:{profile:'LANDSCAPE_720',width:1280,height:720,fit:'contain'},subtitles:{enabled:true,cues:[{startMs:0,endMs:1900,text:'成功成片仍可再次获取。'}]}}]};
  const encoded=Buffer.from(JSON.stringify(content)).toString('base64');
  execFileSync(docker,['compose','exec','-T','postgres','psql','-U','agentic_video','-d','agentic_video','-v','ON_ERROR_STOP=1'],{input:`BEGIN; INSERT INTO tasks(id,project_id,type,status,input_json) VALUES ('${task}','${seed.projectId}','AGENT_TURN','SUCCEEDED','{}'); INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES ('${plan}','${seed.projectId}','历史成片恢复',2); INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES ('${revision}','${seed.projectId}','${plan}',1,'${task}',convert_from(decode('${encoded}','base64'),'UTF8')::jsonb); INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES ('${revision}','${seed.assetId}'); COMMIT;`,stdio:'pipe'});
  await page.goto(`/?project=${seed.projectId}`);await page.getByLabel('查看方案',{exact:true}).selectOption(plan);await page.getByLabel('查看版本',{exact:true}).selectOption(revision);
  await page.getByRole('button',{name:'确认当前版本',exact:true}).click();
  const acceptance=page.waitForResponse(item=>item.url().endsWith('/export-requests')&&item.request().method()==='POST');await page.getByRole('button',{name:'导出正式成片',exact:true}).click();const good=(await(await acceptance).json()).data;
  await expect(page.getByRole('button',{name:'播放正式成片',exact:true})).toBeVisible({timeout:60000});
  try{
    execFileSync(docker,['compose','exec','-T','--user','0','media-worker','sh','-c','mv /usr/bin/ffmpeg /usr/bin/ffmpeg-real; cat > /usr/bin/ffmpeg; chmod 755 /usr/bin/ffmpeg'],{input:'#!/bin/sh\ncase " $* " in *" -filter_complex "*) exit 73;; *) exec /usr/bin/ffmpeg-real "$@";; esac\n',stdio:'pipe'});
    const confirmation=(await(await page.request.get(`/api/v1/revisions/${revision}/confirmations`)).json()).data.items[0].confirmationId;
    const failed=(await(await post(`/revisions/${revision}/export-requests`,{outputIds:[output],confirmationId:confirmation,exportProfile:'HD_720',explicitIntent:'EXPORT'})).json()).data;
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/render-requests/${failed.requestId}`)).json()).data.status,{timeout:30000}).toBe('FAILED');
  }finally{execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','media-worker'],{stdio:'pipe',timeout:60000});}
  await page.reload();await page.getByLabel('查看方案',{exact:true}).selectOption(plan);await page.getByLabel('查看版本',{exact:true}).selectOption(revision);
  await page.getByLabel('查看正式导出记录',{exact:true}).selectOption(good.requestId,{timeout:10000});
  await page.getByRole('button',{name:'播放正式成片',exact:true}).click();
  await expect.poll(()=>page.getByLabel('正式成片播放器',{exact:true}).evaluate((video:HTMLVideoElement)=>video.videoWidth)).toBe(1280);
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'下载正式成片',exact:true}).click();expect((await download).suggestedFilename()).toMatch(/^export-/);
  await page.getByRole('button',{name:'删除成片文件',exact:true}).click();await expect(page.getByText('成片文件已删除，原始素材保留。',{exact:true})).toBeVisible();
});

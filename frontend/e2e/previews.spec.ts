import {renderSeed as previewSeed} from './fixtures/render-seed';
import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';

// Prepare a previously committed version. Assertions exercise public HTTP and the actual worker/player.
test('manual preview renders the fixed version with Chinese captions and original audio and reuses its artifact', async ({page}) => {
  test.setTimeout(180000);
  const seed=await previewSeed(page);
  const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const before=(await(await page.request.get(`/api/v1/assets/${seed.assetId}/evidence`)).json()).data;
  const plan=randomUUID(),revision=randomUUID(),task=randomUUID(),output=randomUUID();
  const content={schemaVersion:'1.0',outputs:[{outputId:output,title:'真实低清原声预览',durationMs:4000,
    clips:[{clipId:randomUUID(),assetId:seed.assetId,sourceInMs:2000,sourceOutMs:6000,audio:'original',evidenceIds:[],reason:'已完成版本的媒体合成样本'}],
    render:{profile:'LANDSCAPE_720',width:1280,height:720,fit:'contain'},subtitles:{enabled:true,cues:[{startMs:0,endMs:2000,text:'中文字幕，核查原声。'}]}}]};
  const encoded=Buffer.from(JSON.stringify(content)).toString('base64');
  execFileSync(process.env.DOCKER_BIN??'docker',['compose','exec','-T','postgres','psql','-U','agentic_video','-d','agentic_video','-v','ON_ERROR_STOP=1'],{input:`BEGIN;
INSERT INTO tasks(id,project_id,type,status,input_json) VALUES ('${task}','${seed.projectId}','AGENT_TURN','SUCCEEDED','{}');
INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES ('${plan}','${seed.projectId}','真实预览样本',2);
INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES ('${revision}','${seed.projectId}','${plan}',1,'${task}',convert_from(decode('${encoded}','base64'),'UTF8')::jsonb);
INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES ('${revision}','${seed.assetId}'); COMMIT;`,stdio:'pipe'});
  const post=async(path:string,data:unknown)=>{const token=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':token,'Idempotency-Key':randomUUID()},data});};
  const requested=await post(`/revisions/${revision}/previews`,{outputId:output,previewProfile:'LOW_360'});
  expect(requested.status()).toBe(202);
  const render=(await requested.json()).data;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/render-requests/${render.requestId}`)).json()).data.status,{timeout:90000}).toBe('SUCCEEDED');
  const completed=(await(await page.request.get(`/api/v1/render-requests/${render.requestId}`)).json()).data;
  const repeated=(await(await post(`/revisions/${revision}/previews`,{outputId:output,previewProfile:'LOW_360'})).json()).data;
  expect(repeated).toMatchObject({taskId:render.taskId,artifactId:completed.artifactId,revisionId:revision});
  const access=(await(await post(`/artifacts/${completed.artifactId}/playback-access`,{})).json()).data;
  const directory=resolve('../.scratch/preview-verification');mkdirSync(directory,{recursive:true});
  const downloaded=await page.request.get(access.url);expect(downloaded.status()).toBe(200);
  writeFileSync(resolve(directory,'preview.mp4'),await downloaded.body());
  const docker=process.env.DOCKER_BIN??'docker';
  const probe=JSON.parse(execFileSync(docker,['run','--rm','--entrypoint','ffprobe','-v',`${directory}:/verification:ro`,'agenticvideo-media-worker','-v','error','-show_streams','-show_format','-of','json','/verification/preview.mp4'],{stdio:'pipe'}).toString());
  expect(probe.streams.find((item:{codec_type:string})=>item.codec_type==='audio')).toMatchObject({codec_name:'aac',channels:2,sample_rate:'48000'});
  execFileSync(docker,['run','--rm','--entrypoint','ffmpeg','-v',`${directory}:/verification`,'agenticvideo-media-worker','-nostdin','-v','error','-i','/verification/preview.mp4','-ss','1','-frames:v','1','-y','/verification/captions.png'],{stdio:'pipe'});
  // The reusable project may restore a conversation's old selection before accepting a new one.
  const sessions=(await(await page.request.get(`/api/v1/projects/${seed.projectId}/conversations`)).json()).data.items;
  const saved=sessions.length?(await(await page.request.get(`/api/v1/conversations/${sessions[sessions.length-1].id}/messages`)).json()).data.view:null;
  await page.goto(`/?project=${seed.projectId}`);
  if(saved?.planId&&saved?.revisionId){
    await expect(page.getByLabel('查看方案',{exact:true})).toHaveValue(saved.planId);
    await expect(page.getByLabel('查看版本',{exact:true})).toHaveValue(saved.revisionId);
    await expect(page.getByRole('button',{name:'确认当前版本',exact:true})).toBeVisible();
  }
  await page.getByLabel('查看方案',{exact:true}).selectOption(plan);
  await page.getByLabel('查看版本',{exact:true}).selectOption(revision);
  await page.getByRole('button',{name:'生成低清效果预览',exact:true}).click();
  await page.getByRole('button',{name:'播放低清预览',exact:true}).click();
  const player=page.getByLabel('低清合成预览播放器',{exact:true});
  await expect.poll(()=>player.evaluate((video:HTMLVideoElement)=>video.readyState)).toBeGreaterThanOrEqual(2);
  expect(await player.evaluate((video:HTMLVideoElement)=>({width:video.videoWidth,height:video.videoHeight,duration:video.duration}))).toEqual({width:640,height:360,duration:4});
  expect(access.durationMs).toBe(4000);
  const after=(await(await page.request.get(`/api/v1/assets/${seed.assetId}/evidence`)).json()).data;
  expect(after.providerCalls).toEqual(before.providerCalls);
});

test('portrait hard cuts retain clip order and silence and a lost uploaded completion reply publishes once',async({page})=>{
  test.setTimeout(180000);
  const seed=await previewSeed(page);
  const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const post=async(path:string,data:unknown)=>{const token=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':token,'Idempotency-Key':randomUUID()},data});};
  await page.goto(`/?project=${seed.projectId}`);
  const generated=resolve('../.scratch/preview-verification');mkdirSync(generated,{recursive:true});
  execFileSync(process.env.DOCKER_BIN??'docker',['run','--rm','--entrypoint','ffmpeg','-v',`${generated}:/verification`,'agenticvideo-media-worker','-nostdin','-v','error','-f','lavfi','-i','color=c=red:s=720x576:r=30','-vf','setsar=16/15','-t','2','-c:v','libx264','-preset','ultrafast','-an','-y','/verification/anamorphic.mp4'],{stdio:'pipe'});
  const uploaded=page.waitForResponse(response=>response.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles(resolve(generated,'anamorphic.mp4'));
  const silent=(await(await uploaded).json()).data.assetId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${silent}`)).json()).data.validationStatus,{timeout:30000}).toBe('VALID');
  const before=(await(await page.request.get(`/api/v1/assets/${seed.assetId}/evidence`)).json()).data;
  const plan=randomUUID(),revision=randomUUID(),task=randomUUID(),output=randomUUID();
  const content={schemaVersion:'1.0',outputs:[{outputId:output,title:'竖屏硬切与静音',durationMs:4000,
    clips:[{clipId:randomUUID(),assetId:seed.assetId,sourceInMs:2000,sourceOutMs:4000,audio:'original',evidenceIds:[],reason:'原声第一段'},
      {clipId:randomUUID(),assetId:silent,sourceInMs:0,sourceOutMs:2000,audio:'original',evidenceIds:[],reason:'无音轨第二段'}],
    render:{profile:'PORTRAIT_720',width:720,height:1280,fit:'contain'},subtitles:{enabled:true,cues:[{startMs:0,endMs:1900,text:'第一段：保留原声。'},{startMs:2000,endMs:3900,text:'第二段：无音轨补静音。'}]}}]};
  const encoded=Buffer.from(JSON.stringify(content)).toString('base64');const docker=process.env.DOCKER_BIN??'docker';
  execFileSync(docker,['compose','exec','-T','postgres','psql','-U','agentic_video','-d','agentic_video','-v','ON_ERROR_STOP=1'],{input:`BEGIN;
INSERT INTO tasks(id,project_id,type,status,input_json) VALUES ('${task}','${seed.projectId}','AGENT_TURN','SUCCEEDED','{}');
INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES ('${plan}','${seed.projectId}','竖屏预览回报样本',2);
INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES ('${revision}','${seed.projectId}','${plan}',1,'${task}',convert_from(decode('${encoded}','base64'),'UTF8')::jsonb);
INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES ('${revision}','${seed.assetId}'),('${revision}','${silent}'); COMMIT;`,stdio:'pipe'});
  const {createServer}=await import('node:http');let dropped=false;let receipts=0;let held=false;let release!:()=>void;
  const gate=new Promise<void>(resolve=>{release=resolve;});
  const proxy=createServer(async(request,response)=>{
    try{
      const chunks:Buffer[]=[];for await(const chunk of request)chunks.push(Buffer.from(chunk));
      const body=Buffer.concat(chunks);const isCompletion=request.url?.endsWith('/complete')&&body.toString().includes('PREVIEW_RENDERED');
      const upstream=await fetch(`http://localhost:8080${request.url}`,{method:request.method,headers:Object.fromEntries(Object.entries(request.headers).filter(([name])=>!['host','connection','content-length','transfer-encoding','upgrade','http2-settings','te','trailer'].includes(name))) as Record<string,string>,body:body.length?body:undefined});
      const result=Buffer.from(await upstream.arrayBuffer());
      if(isCompletion){receipts++;if(!dropped){dropped=true;held=true;await gate;response.destroy();return;}}
      response.writeHead(upstream.status,{'Content-Type':'application/json'});response.end(result);
    }catch{response.writeHead(502);response.end('{}');}
  });
  await new Promise<void>(resolve=>proxy.listen(0,'0.0.0.0',resolve));const address=proxy.address();if(!address||typeof address==='string')throw Error('Fault proxy unavailable');
  const recreate=(url?:string)=>execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','media-worker'],{env:{...process.env,MEDIA_BUSINESS_API_URL:url??'http://business-api:8080'},stdio:'pipe',timeout:60000});
  try{
    recreate(`http://host.docker.internal:${address.port}`);
    await page.getByLabel('查看方案',{exact:true}).selectOption(plan);
    await page.getByLabel('查看版本',{exact:true}).selectOption(revision);
    const accepted=page.waitForResponse(response=>response.request().method()==='POST'&&response.url().endsWith(`/revisions/${revision}/previews`));
    await page.getByRole('button',{name:'生成低清效果预览',exact:true}).click();
    const response=await accepted;expect(response.status()).toBe(202);const requested=(await response.json()).data;
    await expect.poll(()=>held,{timeout:60000}).toBe(true);
    // A second immutable version and a changed view cannot change the already accepted renderer input.
    const later=randomUUID(),laterTask=randomUUID();
    execFileSync(docker,['compose','exec','-T','postgres','psql','-U','agentic_video','-d','agentic_video','-v','ON_ERROR_STOP=1'],{input:`BEGIN; INSERT INTO tasks(id,project_id,type,status,input_json) VALUES ('${laterTask}','${seed.projectId}','AGENT_TURN','SUCCEEDED','{}'); INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,parent_revision_id,source_task_id,content_json) SELECT '${later}',project_id,plan_id,2,id,'${laterTask}',content_json FROM plan_revisions WHERE id='${revision}'; UPDATE edit_plans SET next_revision_number=3 WHERE id='${plan}'; COMMIT;`,stdio:'pipe'});
    await page.getByLabel('查看版本',{exact:true}).selectOption(later);
    await expect(page.getByRole('region',{name:'低清效果预览',exact:true})).toContainText('固定 V1');
    release();await expect.poll(()=>receipts,{timeout:15000}).toBe(2);
    const completed=(await(await page.request.get(`/api/v1/render-requests/${requested.requestId}`)).json()).data;
    expect(completed).toMatchObject({revisionId:revision,status:'SUCCEEDED'});
    await expect(page.getByLabel('查看版本',{exact:true})).toHaveValue(later);
    await expect(page.getByRole('region',{name:'低清效果预览',exact:true})).toContainText('固定 V1');
    expect((await(await page.request.get(`/api/v1/tasks/${requested.taskId}`)).json()).data.attemptCount).toBe(1);
    for(let i=0;i<3;i++)execFileSync(docker,['compose','exec','-T','rabbitmq','sh','-c','RABBITMQADMIN_USERNAME="$RABBITMQ_DEFAULT_USER" RABBITMQADMIN_PASSWORD="$RABBITMQ_DEFAULT_PASS" rabbitmqadmin --quiet publish message --routing-key media.v1 --payload-file -'],{input:JSON.stringify({taskId:requested.taskId,taskType:'PREVIEW_RENDER'}),stdio:'pipe'});
    const repeated=(await(await post(`/revisions/${revision}/previews`,{outputId:output,previewProfile:'LOW_360'})).json()).data;
    expect(repeated).toMatchObject({taskId:requested.taskId,artifactId:completed.artifactId});
    const access=(await(await post(`/artifacts/${completed.artifactId}/playback-access`,{})).json()).data;
    const directory=resolve('../.scratch/preview-verification');writeFileSync(resolve(directory,'portrait.mp4'),await(await page.request.get(access.url)).body());
    const run=(args:string[])=>execFileSync(docker,['run','--rm','--entrypoint','ffmpeg','-v',`${directory}:/verification`,'agenticvideo-media-worker','-nostdin','-v','error',...args],{stdio:'pipe'});
    for(const [name,time] of [['portrait-first','1'],['portrait-second','3']])run(['-i','/verification/portrait.mp4','-ss',time!,'-frames:v','1','-y',`/verification/${name}.png`]);
    // 720x576 SAR16:15 is displayed as 4:3; contain in 360x640 occupies y=185..455.
    const pixel=run(['-i','/verification/portrait.mp4','-ss','3','-vf','crop=2:2:180:180,format=rgb24','-frames:v','1','-f','rawvideo','-']);
    expect(Math.max(...pixel)).toBeLessThanOrEqual(4);
    const firstColor=run(['-i','/verification/portrait.mp4','-ss','1','-vf','crop=2:2:180:320,format=rgb24','-frames:v','1','-f','rawvideo','-']);
    const secondColor=run(['-i','/verification/portrait.mp4','-ss','3','-vf','crop=2:2:180:320,format=rgb24','-frames:v','1','-f','rawvideo','-']);
    expect(firstColor[1]).toBeGreaterThan(100);expect(firstColor[0]).toBeLessThan(10);
    expect(secondColor[0]).toBeGreaterThan(240);expect(secondColor[1]).toBeLessThan(10);
    const pcm=run(['-i','/verification/portrait.mp4','-map','0:a:0','-ac','1','-ar','48000','-f','s16le','-']);
    let firstEnergy=0,secondEnergy=0;for(let i=24000;i<72000;i++){firstEnergy+=pcm.readInt16LE(i*2)**2;secondEnergy+=pcm.readInt16LE((i+96000)*2)**2;}
    expect(firstEnergy/48000).toBeGreaterThan(10000);expect(secondEnergy/48000).toBeLessThan(4);
    const sourcePcm=execFileSync(docker,['run','--rm','--entrypoint','ffmpeg','-v',`${resolve('e2e/fixtures')}:/fixtures:ro`,'agenticvideo-media-worker','-nostdin','-v','error','-i','/fixtures/labelled-speech.mp4','-ss','2','-t','2','-map','0:a:0','-ac','1','-ar','48000','-f','s16le','-'],{stdio:'pipe'});
    let bestCorrelation=0;
    for(let lag=-96;lag<=96;lag++){
      let dot=0,left=0,right=0;for(let i=24000;i<72000;i++){
        const a=pcm.readInt16LE(i*2),b=sourcePcm.readInt16LE((i+lag)*2);dot+=a*b;left+=a*a;right+=b*b;
      }
      bestCorrelation=Math.max(bestCorrelation,dot/Math.sqrt(left*right));
    }
    // AAC is lossy; waveform agreement within 2ms verifies the original offset audio, not just a nonempty track.
    expect(bestCorrelation).toBeGreaterThan(0.8);
    expect((await(await page.request.get(`/api/v1/assets/${seed.assetId}/evidence`)).json()).data.providerCalls).toEqual(before.providerCalls);
  }finally{release();recreate();await new Promise<void>(resolve=>proxy.close(()=>resolve()));}
});

test('cancelling a running preview waits for the actual FFmpeg process to exit',async({page})=>{
  test.setTimeout(90000);
  const seed=await previewSeed(page);
  const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;
  const post=async(path:string,data:unknown)=>{const token=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':token,'Idempotency-Key':randomUUID()},data});};
  const plan=randomUUID(),revision=randomUUID(),task=randomUUID(),output=randomUUID();
  const content={schemaVersion:'1.0',outputs:[{outputId:output,title:'取消中的真实预览',durationMs:10000,clips:[{clipId:randomUUID(),assetId:seed.assetId,sourceInMs:2000,sourceOutMs:12000,audio:'original',evidenceIds:[],reason:'慢速输入仅用于取消故障注入'}],render:{profile:'LANDSCAPE_720',width:1280,height:720,fit:'contain'},subtitles:{enabled:true,cues:[{startMs:0,endMs:9000,text:'取消执行验证。'}]}}]};
  const encoded=Buffer.from(JSON.stringify(content)).toString('base64');const docker=process.env.DOCKER_BIN??'docker';
  execFileSync(docker,['compose','exec','-T','postgres','psql','-U','agentic_video','-d','agentic_video','-v','ON_ERROR_STOP=1'],{input:`BEGIN; INSERT INTO tasks(id,project_id,type,status,input_json) VALUES ('${task}','${seed.projectId}','AGENT_TURN','SUCCEEDED','{}'); INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES ('${plan}','${seed.projectId}','取消预览样本',2); INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES ('${revision}','${seed.projectId}','${plan}',1,'${task}',convert_from(decode('${encoded}','base64'),'UTF8')::jsonb); INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES ('${revision}','${seed.assetId}'); COMMIT;`,stdio:'pipe'});
  const processes=()=>execFileSync(docker,['compose','exec','-T','media-worker','sh','-c','for task_proc in /proc/[0-9]*/comm; do read -r task_name < "$task_proc"; case "$task_name" in ffmpeg-real) printf "%s\n" "$task_name";; esac; done'],{stdio:'pipe'}).toString();
  try{
    // Slow the real input reader at the external process seam; retain the actual FFmpeg binary.
    execFileSync(docker,['compose','exec','-T','--user','0','media-worker','sh','-c','mv /usr/bin/ffmpeg /usr/bin/ffmpeg-real; cat > /usr/bin/ffmpeg; chmod 755 /usr/bin/ffmpeg'],{input:'#!/bin/sh\ncase " $* " in *" -filter_complex "*) exec /usr/bin/ffmpeg-real -re "$@";; *) exec /usr/bin/ffmpeg-real "$@";; esac\n',stdio:'pipe'});
    const response=await post(`/revisions/${revision}/previews`,{outputId:output,previewProfile:'LOW_360'});expect(response.status(),await response.text()).toBe(202);const requested=(await response.json()).data;
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${requested.taskId}`)).json()).data.status,{timeout:30000,intervals:[50]}).toBe('RUNNING');
    await expect.poll(processes,{timeout:20000,intervals:[50]}).toContain('ffmpeg-real');
    expect((await post(`/tasks/${requested.taskId}/cancel`,{})).status()).toBe(202);
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${requested.taskId}`)).json()).data.status,{timeout:20000,intervals:[100]}).toBe('CANCELLED');
    expect(processes()).toBe('');
    const cancelled=(await(await page.request.get(`/api/v1/render-requests/${requested.requestId}`)).json()).data;
    expect(cancelled.status).toBe('CANCELLED');expect(cancelled.artifactId).toBeUndefined();
    expect((await(await page.request.get(`/api/v1/tasks/${requested.taskId}`)).json()).data.attemptCount).toBe(1);
  }finally{execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','media-worker'],{stdio:'pipe',timeout:60000});}
});

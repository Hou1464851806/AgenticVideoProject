import {test,expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';

test('real Agent makes a natural-language montage with required sources and verifiable media',async({page})=>{
  test.setTimeout(360000);
  const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data});};
  const credentials={username:`natural-montage-${Date.now()}`,password:'montage-browser-password'};
  expect((await post('/auth/register',credentials)).status()).toBe(201);expect((await post('/auth/login',credentials)).status()).toBe(200);
  const project=(await(await post('/projects',{name:'自然语言人工标注混剪'})).json()).data;await page.goto(`/?project=${project.id}`);
  const speechUploaded=page.waitForResponse(reply=>reply.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');const seed={projectId:project.id,assetId:(await(await speechUploaded).json()).data.assetId};
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${seed.assetId}`)).json()).data.analysis?.stages?.INDEX,{timeout:120000}).toBe('SUCCEEDED');
  const uploaded=page.waitForResponse(reply=>reply.url().endsWith('/complete'));
  await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-vision-vfr.mp4');const visual=(await(await uploaded).json()).data.assetId;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${visual}`)).json()).data.validationStatus,{timeout:30000}).toBe('VALID');
  expect((await post(`/assets/${visual}/vision-analysis`,{})).status()).toBe(202);
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${visual}`)).json()).data.analysis?.stages?.INDEX,{timeout:120000}).toBe('SUCCEEDED');
  const created=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/conversations'));
  await page.getByRole('button',{name:'新聊天',exact:true}).click();const session=(await(await created).json()).data;
  const send=async(content:string)=>{
    await page.getByLabel('对话输入',{exact:true}).fill(content);
    const accepted=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/turns'));
    await page.getByRole('button',{name:'发送需求',exact:true}).click();const response=await accepted;expect(response.status()).toBe(202);
    const receipt=(await response.json()).data;
    await expect.poll(async()=>['SUCCEEDED','FAILED','WAITING_INPUT'].includes((await(await page.request.get(`/api/v1/tasks/${receipt.taskId}`)).json()).data.status),{timeout:180000}).toBe(true);
    return receipt;
  };
  const first=await send('只用 labelled-speech.mp4 和 labelled-vision-vfr.mp4，两份都必须实际参与。先用讲话素材中完整的“第一步，上传素材。”，再用画面素材0至2000毫秒、核查0毫秒证据42画面帧。禁止原文包含“张明”。做一条竖屏混剪短视频，总时长严格3至30秒，只生成方案，不导出。');
  expect((await(await page.request.get(`/api/v1/tasks/${first.taskId}`)).json()).data.status).toBe('SUCCEEDED');
  await expect(page.getByLabel('需求类型',{exact:true})).toHaveCount(0);await expect(page.getByLabel('方案主题',{exact:true})).toHaveCount(0);
  const snapshot=async()=>(await(await page.request.get(`/api/v1/conversations/${session.id}/snapshot`)).json()).data;
  const result=await snapshot(),reference=result.items.at(-1).references.find((item:{type:string})=>item.type==='PLAN_REVISION');expect(reference).toBeTruthy();
  const version=(await(await page.request.get(`/api/v1/revisions/${reference.id}`)).json()).data,output=version.content.outputs[0];
  expect(output.clips.map((clip:{assetId:string})=>clip.assetId)).toEqual([seed.assetId,visual]);expect(output.render.profile).toBe('PORTRAIT_720');
  expect(output.durationMs).toBeGreaterThanOrEqual(3000);expect(output.durationMs).toBeLessThanOrEqual(30000);
  const speech=output.clips[0],silent=output.clips[1],speechDuration=speech.sourceOutMs-speech.sourceInMs;
  expect(silent).toMatchObject({sourceInMs:0,sourceOutMs:2000});
  expect(output.subtitles.cues.map((cue:{text:string})=>cue.text).join('').replace(/[^\p{Script=Han}]/gu,'')).toBe('第一步上传素材');
  for(const cue of output.subtitles.cues){expect(cue.startMs).toBeGreaterThanOrEqual(0);expect(cue.endMs).toBeLessThanOrEqual(speechDuration);}
  expect(result.brief.fields.orderedAssetIds).toMatchObject({value:[seed.assetId,visual],sourceMessageId:first.messageId});
  await page.reload();await page.getByRole('button',{name:'查看方案 V1',exact:true}).last().click();await expect(page.getByRole('article',{name:'方案剪辑片段'})).toHaveCount(2);
  for(const clip of await page.getByRole('article',{name:'方案剪辑片段'}).all()){await clip.getByRole('button',{name:'播放源片段',exact:true}).click();await expect.poll(()=>page.getByLabel('视频播放器',{exact:true}).evaluate((video:HTMLVideoElement)=>video.readyState)).toBeGreaterThanOrEqual(1);}
  const confirmed=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/confirmations'));
  await page.getByRole('button',{name:'确认当前版本',exact:true}).click();expect((await confirmed).status()).toBe(201);
  const exported=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/export-requests'));
  await page.getByRole('button',{name:'导出正式成片',exact:true}).click();const render=await exported;expect(render.status()).toBe(202);const request=(await render.json()).data;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/render-requests/${request.requestId}`)).json()).data.status,{timeout:60000}).toBe('SUCCEEDED');
  const completed=(await(await page.request.get(`/api/v1/render-requests/${request.requestId}`)).json()).data;
  const access=(await(await post(`/artifacts/${completed.artifactId}/download-access`,{})).json()).data,bytes=await page.request.get(access.url);expect(bytes.status()).toBe(200);
  const directory=resolve('../.scratch/montage-verification/natural-language');mkdirSync(directory,{recursive:true});writeFileSync(resolve(directory,'montage.mp4'),await bytes.body());
  const docker=process.env.DOCKER_BIN??'docker';
  const probe=JSON.parse(execFileSync(docker,['run','--rm','--entrypoint','ffprobe','-v',`${directory}:/verification:ro`,'agenticvideo-media-worker','-v','error','-show_streams','-show_format','-of','json','/verification/montage.mp4'],{stdio:'pipe'}).toString());
  expect(probe.streams.find((stream:{codec_type:string})=>stream.codec_type==='video')).toMatchObject({width:720,height:1280});expect(Math.abs(Number(probe.format.duration)*1000-output.durationMs)).toBeLessThan(100);
  const pixel=(time:number)=>execFileSync(docker,['run','--rm','--entrypoint','ffmpeg','-v',`${directory}:/verification:ro`,'agenticvideo-media-worker','-nostdin','-v','error','-ss',String(time),'-i','/verification/montage.mp4','-vf','crop=2:2:224:572,scale=1:1','-frames:v','1','-f','rawvideo','-pix_fmt','rgb24','pipe:1'],{stdio:'pipe'});
  const green=pixel(speechDuration/2000),red=pixel(speechDuration/1000+1);expect(green[1]).toBeGreaterThan(60);expect(green[0]).toBeLessThan(50);expect(red[0]).toBeGreaterThan(180);expect(red[1]).toBeLessThan(80);
  const pcm=execFileSync(docker,['run','--rm','--entrypoint','ffmpeg','-v',`${directory}:/verification:ro`,'agenticvideo-media-worker','-nostdin','-v','error','-ss',String(speechDuration/1000+0.25),'-i','/verification/montage.mp4','-t','1','-vn','-ac','1','-f','s16le','pipe:1'],{stdio:'pipe'});
  let energy=0;for(let i=0;i<pcm.length;i+=2)energy+=(pcm.readInt16LE(i)/32768)**2;expect(Math.sqrt(energy/(pcm.length/2))).toBeLessThan(0.001);
  await page.getByRole('button',{name:'播放正式成片',exact:true}).click();await expect.poll(()=>page.getByLabel('正式成片播放器',{exact:true}).evaluate((video:HTMLVideoElement)=>video.readyState)).toBeGreaterThanOrEqual(2);
  const downloading=page.waitForEvent('download');await page.getByRole('button',{name:'下载正式成片',exact:true}).click();expect((await downloading).suggestedFilename()).toMatch(/\.mp4$/);
  await send('另做一条讲话短视频，只用 labelled-speech.mp4 和 labelled-vision-vfr.mp4 作为可选范围，从中选合适的一份即可，不要求两份都出现。介绍视频制作步骤，严格7至11秒，横屏，只生成方案。');
  const optional=await snapshot(),optionalReference=optional.items.at(-1).references.find((item:{type:string})=>item.type==='PLAN_REVISION');expect(optionalReference).toBeTruthy();
  const optionalOutput=(await(await page.request.get(`/api/v1/revisions/${optionalReference.id}`)).json()).data.content.outputs[0];expect(optionalOutput.clips.every((clip:{assetId:string})=>clip.assetId===seed.assetId)).toBe(true);expect(optional.brief.fields.orderedAssetIds).toBeUndefined();
  const conflict=await send('另做一条混剪介绍上传素材，labelled-speech.mp4 和 labelled-vision-vfr.mp4 两份都必须出现，同时禁止使用 labelled-vision-vfr.mp4。这两个条件都必须满足，不能私自舍弃。');
  expect((await(await page.request.get(`/api/v1/tasks/${conflict.taskId}`)).json()).data.status).toBe('WAITING_INPUT');
  const waiting=await snapshot();expect(waiting.questions.some((item:{status:string})=>item.status==='OPEN')).toBe(true);expect(waiting.items.at(-1).references.some((item:{type:string})=>item.type==='PLAN_REVISION')).toBe(false);
});


test('duplicate source names are clarified in the same chat before generating a montage',async({page})=>{
  test.setTimeout(300000);
  const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':crypto.randomUUID()},data});};
  const credentials={username:`duplicate-montage-${Date.now()}`,password:'montage-browser-password'};
  expect((await post('/auth/register',credentials)).status()).toBe(201);expect((await post('/auth/login',credentials)).status()).toBe(200);
  const project=(await(await post('/projects',{name:'重名素材澄清'})).json()).data;await page.goto(`/?project=${project.id}`);
  const ids:string[]=[];
  for(const file of ['labelled-speech.mp4','labelled-vision-vfr.mp4']){
    const uploaded=page.waitForResponse(reply=>reply.url().endsWith('/complete'));
    await page.getByLabel('选择视频',{exact:true}).setInputFiles({name:'same.mp4',mimeType:'video/mp4',buffer:readFileSync(`e2e/fixtures/${file}`)});
    const id=(await(await uploaded).json()).data.assetId;ids.push(id);
    await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${id}`)).json()).data.validationStatus,{timeout:30000}).toBe('VALID');
  }
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${ids[0]}`)).json()).data.analysis?.stages?.INDEX,{timeout:120000}).toBe('SUCCEEDED');
  const created=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/conversations'));
  await page.getByRole('button',{name:'新聊天',exact:true}).click();const session=(await(await created).json()).data;
  const send=async(content:string)=>{
    await page.getByLabel('对话输入',{exact:true}).fill(content);const accepted=page.waitForResponse(reply=>reply.request().method()==='POST'&&reply.url().endsWith('/turns'));
    await page.getByRole('button',{name:'发送需求',exact:true}).click();expect((await accepted).status()).toBe(202);const receipt=(await(await accepted).json()).data;
    await expect.poll(async()=>['SUCCEEDED','WAITING_INPUT','FAILED'].includes((await(await page.request.get(`/api/v1/tasks/${receipt.taskId}`)).json()).data.status),{timeout:150000}).toBe(true);return receipt;
  };
  const first=await send('只用 same.mp4 制作一条讲视频制作步骤的竖屏讲话短视频，严格7至11秒。');
  expect((await(await page.request.get(`/api/v1/tasks/${first.taskId}`)).json()).data.status).toBe('WAITING_INPUT');
  const snapshot=async()=>(await(await page.request.get(`/api/v1/conversations/${session.id}/snapshot`)).json()).data;
  const question=(await snapshot()).questions.find((item:{status:string})=>item.status==='OPEN');expect(question.fields).toContain('assetIds');expect((await snapshot()).items.some((item:{references:Array<{type:string}>})=>item.references.some(ref=>ref.type==='PLAN_REVISION'))).toBe(false);
  await page.reload();await expect(page.getByLabel('澄清回答',{exact:true})).toHaveCount(0);
  const answer=await send('使用那份较长的、约14秒有讲话的 same.mp4，继续原需求。');
  expect((await(await page.request.get(`/api/v1/tasks/${answer.taskId}`)).json()).data.status).toBe('SUCCEEDED');
  const done=await snapshot();expect(done.questions.find((item:{id:string})=>item.id===question.id)).toMatchObject({status:'ANSWERED',answerMessageId:answer.messageId});
  const ref=done.items.at(-1).references.find((item:{type:string})=>item.type==='PLAN_REVISION');expect(ref).toBeTruthy();
  const version=(await(await page.request.get(`/api/v1/revisions/${ref.id}`)).json()).data;
  expect(version.content.outputs[0].clips.every((clip:{assetId:string})=>clip.assetId===ids[0])).toBe(true);expect(version.content.outputs[0].render.profile).toBe('PORTRAIT_720');
});

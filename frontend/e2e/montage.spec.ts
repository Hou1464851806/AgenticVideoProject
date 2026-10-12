import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {mkdirSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';

for(const profile of ['PORTRAIT_720','LANDSCAPE_720'] as const) test(`real Agent exports ${profile} mixed media in explicit source order without reanalysis`,async({page})=>{
  test.setTimeout(300000);
  const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post('/api/v1'+path,{headers:{'X-XSRF-TOKEN':csrf,'Idempotency-Key':randomUUID()},data});};
  const credentials={username:`montage-${Date.now()}`,password:'montage-browser-password'};
  expect((await post('/auth/register',credentials)).status()).toBe(201);expect((await post('/auth/login',credentials)).status()).toBe(200);
  const project=(await(await post('/projects',{name:'人工标注语音与静音混剪'})).json()).data;
  await page.goto(`/?project=${project.id}`);const assets:string[]=[];
  for(const file of ['labelled-speech.mp4','labelled-vision-vfr.mp4']){
    const uploaded=page.waitForResponse(reply=>reply.url().endsWith('/complete'));
    await page.getByLabel('选择视频',{exact:true}).setInputFiles(`e2e/fixtures/${file}`);assets.push((await(await uploaded).json()).data.assetId);
  }
  const stages=async(id:string)=>(await(await page.request.get(`/api/v1/assets/${id}`)).json()).data.analysis?.stages;
  await expect.poll(async()=>(await stages(assets[0]!))?.INDEX,{timeout:120000}).toBe('SUCCEEDED');
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/assets/${assets[1]}`)).json()).data.validationStatus,{timeout:30000}).toBe('VALID');
  expect((await post(`/assets/${assets[1]}/vision-analysis`,{})).status()).toBe(202);
  await expect.poll(async()=>(await stages(assets[1]!))?.INDEX,{timeout:120000}).toBe('SUCCEEDED');
  const session=(await(await post(`/projects/${project.id}/conversations`,{title:'指定顺序的基础混剪',assetIds:assets})).json()).data;
  await page.reload();await page.getByLabel('制作会话',{exact:true}).selectOption(session.id);
  await expect(page.getByLabel('需求类型',{exact:true}).locator('option[value="MONTAGE"]')).toHaveCount(1);
  await page.getByLabel('需求类型',{exact:true}).selectOption('MONTAGE');
  await page.getByLabel('方案主题',{exact:true}).fill('先说明上传素材，再展示证据42');
  await page.getByLabel('最短时长（秒）',{exact:true}).fill('3');await page.getByLabel('最长时长（秒）',{exact:true}).fill('30');
  await page.getByLabel('输出画幅',{exact:true}).selectOption(profile);
  const order=page.getByRole('group',{name:'混剪素材顺序',exact:true});
  // Explicitly select and order the sources through the user interface.
  await order.getByLabel('混剪素材 labelled-speech.mp4',{exact:true}).check();await order.getByLabel('混剪素材 labelled-vision-vfr.mp4',{exact:true}).check();
  if(profile==='LANDSCAPE_720')await order.getByRole('button',{name:'上移混剪素材 labelled-vision-vfr.mp4',exact:true}).click();
  const expectedOrder=profile==='PORTRAIT_720'?assets:[assets[1],assets[0]];
  await expect(order.getByRole('listitem').first()).toContainText(profile==='PORTRAIT_720'?'labelled-speech.mp4':'labelled-vision-vfr.mp4');
  await page.getByLabel('对话输入',{exact:true}).fill('按指定顺序生成一条混剪：从labelled-speech.mp4保留完整的“第一步，上传素材。”原句，须读取原文邻近上下文和词时间，不能截断词；使用labelled-vision-vfr.mp4的0至2000毫秒原视频片段，核查0毫秒证据42画面帧。后者无音轨，不编造字幕或未观察画面。只生成方案，不导出。');
  const accepted=page.waitForResponse(reply=>reply.url().endsWith('/turns')&&reply.request().method()==='POST');await page.getByRole('button',{name:'发送需求',exact:true}).click();const reply=await accepted;expect(reply.status()).toBe(202);const identity=(await reply.json()).data;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${identity.taskId}`)).json()).data.status,{timeout:120000}).toBe('SUCCEEDED');
  const versions=(await(await page.request.get(`/api/v1/plans/${identity.planId}/revisions`)).json()).data.items;expect(versions).toHaveLength(1);
  const revision=(await(await page.request.get(`/api/v1/revisions/${versions[0].id}`)).json()).data;const output=revision.content.outputs[0];expect(output.clips).toHaveLength(2);
  expect(output.clips.map((clip:{assetId:string})=>clip.assetId)).toEqual(expectedOrder);
  const speech=output.clips.find((clip:{assetId:string})=>clip.assetId===assets[0]),silent=output.clips.find((clip:{assetId:string})=>clip.assetId===assets[1]);
  expect(silent).toMatchObject({sourceInMs:0,sourceOutMs:2000});const speechDuration=speech.sourceOutMs-speech.sourceInMs;
  const speechOffset=profile==='PORTRAIT_720'?0:2000,silentOffset=profile==='PORTRAIT_720'?speechDuration:0;
  const caption=output.subtitles.cues.map((cue:{text:string})=>cue.text).join('').replace(/[^\p{Script=Han}]/gu,'');expect(caption).toBe('第一步上传素材');
  for(const cue of output.subtitles.cues){expect(cue.startMs).toBeGreaterThanOrEqual(speechOffset);expect(cue.endMs).toBeLessThanOrEqual(speechOffset+speechDuration);}
  await page.getByRole('button',{name:'查看方案 V1',exact:true}).click();await expect(page.getByRole('article',{name:'方案剪辑片段'})).toHaveCount(2);
  for(const clip of await page.getByRole('article',{name:'方案剪辑片段'}).all()){await clip.getByRole('button',{name:'播放源片段',exact:true}).click();await expect.poll(()=>page.getByLabel('视频播放器',{exact:true}).evaluate((node:HTMLVideoElement)=>node.readyState)).toBeGreaterThanOrEqual(1);}
  const usage=async()=>(await(await page.request.get(`/api/v1/projects/${project.id}/usage/models`)).json()).data;
  const before=await usage();const confirmed=page.waitForResponse(reply=>reply.url().endsWith('/confirmations')&&reply.request().method()==='POST');await page.getByRole('button',{name:'确认当前版本',exact:true}).click();expect((await confirmed).status()).toBe(201);
  const directory=resolve('../.scratch/montage-verification',profile);mkdirSync(directory,{recursive:true});const docker=process.env.DOCKER_BIN??'docker';
  const exported=page.waitForResponse(reply=>reply.url().endsWith('/export-requests')&&reply.request().method()==='POST');await page.getByRole('button',{name:'导出正式成片',exact:true}).click();const render=await exported;expect(render.status()).toBe(202);const request=(await render.json()).data;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/render-requests/${request.requestId}`)).json()).data.status,{timeout:60000}).toBe('SUCCEEDED');
  const completed=(await(await page.request.get(`/api/v1/render-requests/${request.requestId}`)).json()).data;const access=(await(await post(`/artifacts/${completed.artifactId}/download-access`,{})).json()).data;const bytes=await page.request.get(access.url);expect(bytes.status()).toBe(200);writeFileSync(resolve(directory,'montage.mp4'),await bytes.body());
  const probe=JSON.parse(execFileSync(docker,['run','--rm','--entrypoint','ffprobe','-v',`${directory}:/verification:ro`,'agenticvideo-media-worker','-v','error','-show_streams','-show_format','-of','json','/verification/montage.mp4'],{stdio:'pipe'}).toString());
  expect(probe.streams.find((stream:{codec_type:string})=>stream.codec_type==='video')).toMatchObject({width:profile==='PORTRAIT_720'?720:1280,height:profile==='PORTRAIT_720'?1280:720,codec_name:'h264'});expect(probe.streams.find((stream:{codec_type:string})=>stream.codec_type==='audio')).toMatchObject({sample_rate:'48000',channels:2,codec_name:'aac'});expect(Math.abs(Number(probe.format.duration)*1000-output.durationMs)).toBeLessThan(100);
  const ffmpeg=(args:string[])=>execFileSync(docker,['run','--rm','--entrypoint','ffmpeg','-v',`${directory}:/verification:ro`,'agenticvideo-media-worker','-nostdin','-v','error',...args],{stdio:'pipe'});
  const pixel=(time:number,crop:string)=>ffmpeg(['-ss',String(time),'-i','/verification/montage.mp4','-vf',`${crop},scale=1:1`,'-frames:v','1','-f','rawvideo','-pix_fmt','rgb24','pipe:1']);
  // The manually labelled rectangle occupies the upper-left area; sample its interior, not a scaled edge.
  const sample=profile==='PORTRAIT_720'?'crop=2:2:224:572':'crop=2:2:399:239';
  const green=pixel((speechOffset+speechDuration/2)/1000,sample),red=pixel(silentOffset/1000+1,sample),corner=pixel(silentOffset/1000+1,'crop=2:2:10:10');
  expect(green[1]).toBeGreaterThan(60);expect(green[0]).toBeLessThan(50);expect(red[0]).toBeGreaterThan(180);expect(red[1]).toBeLessThan(80);expect([...corner].every(value=>profile==='PORTRAIT_720'?value<8:value>240)).toBe(true);
  const pcm=ffmpeg(['-ss',String(silentOffset/1000+0.25),'-i','/verification/montage.mp4','-t','1','-vn','-ac','1','-f','s16le','pipe:1']);let energy=0;for(let i=0;i<pcm.length;i+=2)energy+=(pcm.readInt16LE(i)/32768)**2;expect(Math.sqrt(energy/(pcm.length/2))).toBeLessThan(0.001);
  const voice=ffmpeg(['-ss',String(output.subtitles.cues[0].startMs/1000+0.1),'-i','/verification/montage.mp4','-t','0.5','-vn','-ac','1','-f','s16le','pipe:1']);let voiceEnergy=0;for(let i=0;i<voice.length;i+=2)voiceEnergy+=(voice.readInt16LE(i)/32768)**2;expect(Math.sqrt(voiceEnergy/(voice.length/2))).toBeGreaterThan(0.005);
  writeFileSync(resolve(directory,'captions.png'),ffmpeg(['-ss',String((output.subtitles.cues[0].startMs+output.subtitles.cues[0].endMs)/2000),'-i','/verification/montage.mp4','-frames:v','1','-f','image2pipe','-vcodec','png','pipe:1']));
  const preview=await post(`/revisions/${revision.id}/previews`,{outputId:output.outputId,previewProfile:'LOW_360'});expect(preview.status()).toBe(202);const previewRequest=(await preview.json()).data;await expect.poll(async()=>(await(await page.request.get(`/api/v1/render-requests/${previewRequest.requestId}`)).json()).data.status,{timeout:60000}).toBe('SUCCEEDED');
  const after=await usage();expect(after.callCount).toBe(before.callCount);expect(after.estimatedCostCny).toBe(before.estimatedCostCny);
  await page.getByRole('button',{name:'播放正式成片',exact:true}).click();await expect.poll(()=>page.getByLabel('正式成片播放器',{exact:true}).evaluate((node:HTMLVideoElement)=>node.readyState)).toBeGreaterThanOrEqual(2);
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'下载正式成片',exact:true}).click();expect((await download).suggestedFilename()).toMatch(/\.mp4$/);
  const impossible=await post(`/conversations/${session.id}/turns`,{content:'两份指定素材都必须保留一个片段，总时长只能1毫秒；无法满足时解释，不发布版本。',context:{editing:{kind:'MONTAGE',orderedAssetIds:assets,topic:'不可满足的混剪',minDurationMs:1,maxDurationMs:1,profile:'LANDSCAPE_720'}}});expect(impossible.status()).toBe(202);const impossibleTask=(await impossible.json()).data;
  await expect.poll(async()=>(await(await page.request.get(`/api/v1/tasks/${impossibleTask.taskId}`)).json()).data.status,{timeout:120000}).toBe('SUCCEEDED');expect((await(await page.request.get(`/api/v1/plans/${impossibleTask.planId}/revisions`)).json()).data.items).toHaveLength(0);
  const explanation=(await(await page.request.get(`/api/v1/conversations/${session.id}/messages`)).json()).data.items.at(-1);expect(explanation.content).toMatch(/无法|不能|不足|不满足|不够/);

});

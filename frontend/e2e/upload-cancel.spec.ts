import {test,expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';

test('cancelling while authorization is in flight stops scheduling and distinguishes cancellation from physical cleanup',async({page})=>{
  test.setTimeout(90000);
  const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(process.env.DOCKER_BIN??'docker',['compose','up','-d','--no-deps','--force-recreate','--wait','business-api'],{env,timeout:60000,stdio:'pipe'});
  let release!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});let puts=0;
  try{
    recreate({...process.env,STORAGE_PART_URL_SECONDS:'3',UPLOAD_CLEANUP_SETTLE_SECONDS:'1',UPLOAD_CLEANUP_INTERVAL_MS:'100'});
    const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf},data});};
    const username=`cancel-browser-${Date.now()}`,password='project-browser-password';
    expect((await post('/auth/register',{username,password})).status()).toBe(201);expect((await post('/auth/login',{username,password})).status()).toBe(200);
    const project=(await(await post('/projects',{name:'取消上传'})).json()).data;
    await page.goto(`/?project=${project.id}`);
    await page.route('**/part-urls',async route=>{const response=await route.fetch();await gate;await route.fulfill({response});});
    page.on('request',request=>{if(request.method()==='PUT'&&new URL(request.url()).port==='9000')puts++;});
    const created=page.waitForResponse(response=>response.request().method()==='POST'&&/\/projects\/[^/]+\/uploads$/.test(new URL(response.url()).pathname));
    const authorization=page.waitForRequest('**/part-urls');
    await page.getByLabel('选择视频',{exact:true}).setInputFiles({name:'cancel.mp4',mimeType:'video/mp4',buffer:Buffer.alloc(1024,37)});
    const id=(await(await created).json()).data.uploadId;await authorization;
    await page.getByRole('button',{name:'cancel.mp4 取消上传',exact:true}).click();
    await expect(page.getByText('已取消',{exact:true})).toBeVisible();
    await expect(page.getByText('残留正在清理，字节占用尚未释放',{exact:true})).toBeVisible();
    release();await expect(page.getByText('残留清理完成，占用已释放',{exact:true})).toBeVisible({timeout:15000});
    expect(puts).toBe(0);
    expect((await(await page.request.get(`/api/v1/uploads/${id}`)).json()).data).toMatchObject({status:'CANCELLED',cleanupStatus:'CLEANED',reservedBytes:0,reservedSlots:0});
    await page.reload();await expect(page.getByText('已取消',{exact:true})).toBeVisible();
    await expect(page.getByLabel('cancel.mp4 重选原文件继续',{exact:true})).toHaveCount(0);
  }finally{release();recreate({...process.env});}
});

test('an expired upload shows its recovery deadline then requires a new upload after refresh',async({page})=>{
  test.setTimeout(90000);
  const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(process.env.DOCKER_BIN??'docker',['compose','up','-d','--no-deps','--force-recreate','--wait','business-api'],{env,timeout:60000,stdio:'pipe'});
  try{
    recreate({...process.env,UPLOAD_INACTIVITY_SECONDS:'3',UPLOAD_CLEANUP_SETTLE_SECONDS:'1',UPLOAD_CLEANUP_INTERVAL_MS:'100'});
    const post=async(path:string,data:unknown)=>{const csrf=(await(await page.request.get('/api/v1/auth/csrf')).json()).data.csrfToken;return page.request.post(`/api/v1${path}`,{headers:{'X-XSRF-TOKEN':csrf},data});};
    const username=`expiry-browser-${Date.now()}`,password='project-browser-password';
    expect((await post('/auth/register',{username,password})).status()).toBe(201);expect((await post('/auth/login',{username,password})).status()).toBe(200);
    const project=(await(await post('/projects',{name:'过期上传'})).json()).data;
    const upload=await post(`/projects/${project.id}/uploads`,{filename:'expired.mp4',sizeBytes:1024,sha256:'e'.repeat(64),idempotencyKey:crypto.randomUUID(),writerId:crypto.randomUUID()});
    expect(upload.status()).toBe(201);
    await page.goto(`/?project=${project.id}`);
    await expect(page.getByText(/^可恢复至 /)).toBeVisible();
    await expect(page.getByText('上传已过期，请重新上传',{exact:true})).toBeVisible({timeout:10000});
    await expect(page.getByText('残留清理完成，占用已释放',{exact:true})).toBeVisible({timeout:10000});
    await page.reload();await expect(page.getByText('上传已过期，请重新上传',{exact:true})).toBeVisible();
    await expect(page.getByLabel('expired.mp4 重选原文件继续',{exact:true})).toHaveCount(0);
  }finally{recreate({...process.env});}
});

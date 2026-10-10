import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';

test('missing provider credentials preserves playback and explicitly marks speech unverified', async ({ page }) => {
  test.setTimeout(90000);
  const docker=process.env.DOCKER_BIN??'docker';
  const recreate=(env:NodeJS.ProcessEnv)=>execFileSync(docker,['compose','up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','40','agent-service'],{env,timeout:60000,stdio:'pipe'});
  try {
    recreate({...process.env,DASHSCOPE_API_KEY:''});
    await page.goto('/');
    await page.getByRole('button',{name:'创建账号',exact:true}).click();
    await page.getByLabel('用户名',{exact:true}).fill(`analysis-no-key-${Date.now()}`);
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
    await page.getByRole('button',{name:'注册',exact:true}).click();
    await expect(page.getByRole('status')).toHaveText('账号已创建，请登录');
    await page.getByLabel('密码',{exact:true}).fill('analysis-browser-password');
    await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.getByLabel('项目名称',{exact:true}).fill('未验证语音');
    await page.getByRole('button',{name:'创建项目',exact:true}).click();
    await page.getByRole('button',{name:'未验证语音',exact:true}).click();
    const completed=page.waitForResponse(response=>response.url().endsWith('/complete'));
    await page.getByLabel('选择视频',{exact:true}).setInputFiles('e2e/fixtures/labelled-speech.mp4');
    const assetId=(await(await completed).json()).data.assetId;
    await expect.poll(async ()=>(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data.analysis?.stages.ASR,{timeout:20000}).toBe('UNVERIFIED');
    const details=(await(await page.request.get(`/api/v1/assets/${assetId}`)).json()).data;
    expect(details.validationStatus).toBe('VALID');
    expect(details.analysis.errors.ASR.message).toBe('未配置百炼凭据，真实语音分析未验证');
    const evidence=(await(await page.request.get(`/api/v1/assets/${assetId}/evidence`)).json()).data;
    expect(evidence.observations).toEqual([]);
    expect(evidence.providerCalls).toEqual([]);
    await page.getByRole('button',{name:'播放 labelled-speech.mp4',exact:true}).click();
    await expect(page.getByText('语音：未验证',{exact:true})).toBeVisible();
    await expect(page.getByText('未配置百炼凭据，真实语音分析未验证',{exact:true})).toBeVisible();
  }finally{recreate({...process.env});}
});

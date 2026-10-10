<script setup lang="ts">
import { onMounted, ref } from 'vue';

type User = { id: string; username: string };
const user = ref<User | null>(null);
const registering = ref(false);
const username = ref('');
const password = ref('');
const busy = ref(false);
const error = ref('');
const notice = ref('');

async function request<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== 'GET') {
    const csrf = await request<{ csrfToken: string }>('/auth/csrf');
    headers['X-XSRF-TOKEN'] = csrf.csrfToken;
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(`/api/v1${path}`, {
    method, headers, credentials: 'same-origin', body: data === undefined ? undefined : JSON.stringify(data),
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error?.message ?? '请求失败，请稍后重试');
  return result.data;
}

async function submit() {
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    const result = await request<{ user: User }>(registering.value ? '/auth/register' : '/auth/login', 'POST', {
      username: username.value, password: password.value,
    });
    password.value = '';
    if (registering.value) {
      registering.value = false;
      notice.value = '账号已创建，请登录';
    } else {
      user.value = result.user;
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '服务暂时不可用';
  } finally {
    busy.value = false;
  }
}

async function logout() {
  busy.value = true;
  error.value = '';
  try {
    await request('/auth/logout', 'POST', {});
    user.value = null;
    notice.value = '';
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '退出失败，请重试';
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  try {
    const result = await request<{ user: User }>('/auth/me');
    user.value = result.user;
  } catch { /* Anonymous visitors use the sign-in form. */ }
});
</script>

<template>
  <div class="shell">
    <header><span class="mark">AV</span><span>Agent 视频工作区</span></header>
    <main>
      <section class="intro"><p class="eyebrow">视频理解 · 内容检索 · 编排剪辑</p><h1>让素材成为<br>你的下一条作品。</h1><p>登录你的私有工作区，开始整理视频素材。</p></section>
      <section class="account" aria-label="账号入口">
        <template v-if="user">
          <h2>欢迎回来</h2>
          <p>已登录：{{ user.username }}</p>
          <button :disabled="busy" @click="logout">退出登录</button>
        </template>
        <template v-else>
          <h2>{{ registering ? '创建你的账号' : '登录工作区' }}</h2>
          <form @submit.prevent="submit">
            <label for="username">用户名</label><input id="username" v-model="username" required maxlength="64" autocomplete="username">
            <label for="password">密码</label><input id="password" v-model="password" required type="password" minlength="8" maxlength="72" :autocomplete="registering ? 'new-password' : 'current-password'">
            <p class="hint">密码至少 8 个字符，编码后不超过 72 字节。</p>
            <button type="submit" :disabled="busy">{{ busy ? '正在处理…' : registering ? '注册' : '登录' }}</button>
          </form>
          <button class="secondary" :disabled="busy" @click="registering = !registering; error = ''; notice = ''">{{ registering ? '已有账号，返回登录' : '创建账号' }}</button>
        </template>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <p role="status" class="notice">{{ notice }}</p>
      </section>
    </main>
  </div>
</template>

<style>
:root { font-family: system-ui, -apple-system, sans-serif; color: #15251f; background: #f3f5f0; font-synthesis: none; }
* { box-sizing: border-box; }
body { margin: 0; }
header { display: flex; align-items: center; gap: 12px; padding: 26px 6vw; font-weight: 650; }
.mark { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 10px; background: #245c45; color: white; font-size: 14px; }
main { max-width: 1120px; min-height: 72vh; margin: auto; padding: 48px 32px; display: grid; grid-template-columns: 1.2fr 1fr; align-items: center; gap: 64px; }
.eyebrow { color: #47735e; font-size: 14px; }
h1 { font-size: clamp(32px, 4.5vw, 56px); letter-spacing: -.03em; line-height: 1.3; margin: 24px 0; }
.intro > p:last-child { color: #69766e; line-height: 1.7; }
.account { background: white; padding: 36px; border-radius: 20px; box-shadow: 0 12px 48px #1835260c; }
h2 { font-size: 23px; margin: 0 0 28px; }
label { display: block; margin: 18px 0 8px; font-size: 14px; }
input { width: 100%; padding: 12px; border: 1px solid #ccd7cf; border-radius: 8px; font: inherit; }
input:focus { outline: 2px solid #69a282; outline-offset: 1px; }
button { width: 100%; margin-top: 18px; padding: 12px; border: 0; border-radius: 8px; font: inherit; cursor: pointer; color: white; background: #245c45; }
button:disabled { opacity: .6; cursor: wait; }
.secondary { background: #edf3ee; color: #245c45; }
.hint { font-size: 12px; color: #69766e; }
.error { color: #b02d29; line-height: 1.6; }
.notice { color: #245c45; line-height: 1.6; }
@media (max-width: 720px) { main { grid-template-columns: 1fr; gap: 32px; padding: 24px; } .account { padding: 28px; } }
</style>

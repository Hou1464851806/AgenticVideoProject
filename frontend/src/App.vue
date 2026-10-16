<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { request } from './api';
import ProjectWorkspace from './ProjectWorkspace.vue';

type User = { id: string; username: string };
const user = ref<User | null>(null);
const registering = ref(false);
const username = ref('');
const password = ref('');
const busy = ref(false);
const error = ref('');
const notice = ref('');

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

function expired(){if(!user.value)return;user.value=null;notice.value='登录已到期，请重新登录';}
onUnmounted(()=>window.removeEventListener('auth-required',expired));
onMounted(async () => {
  window.addEventListener('auth-required',expired);
  try {
    const result = await request<{ user: User }>('/auth/me');
    user.value = result.user;
  } catch { /* Anonymous visitors use the sign-in form. */ }
});
</script>

<template>
  <div class="shell">
    <a class="skip-link" href="#main-content">跳至主内容</a>
    <header v-if="!user" class="brand-header"><span class="mark">叙帧</span><span>叙帧 · 视频工作台</span></header>
    <main v-if="user" id="main-content" class="authenticated" tabindex="-1">
      <ProjectWorkspace :user-id="user.id">
        <template #account>
          <details class="account-menu">
            <summary>账户</summary>
            <div class="account-popover"><p>已登录：{{ user.username }}</p><button class="text-button" :disabled="busy" @click="logout">退出登录</button></div>
          </details>
        </template>
      </ProjectWorkspace>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
    </main>
    <main v-else id="main-content" class="sign-in" tabindex="-1">
      <section class="intro"><p class="eyebrow">视频理解 · 内容检索 · 编排剪辑</p><h1>让素材成为<br>你的下一条作品。</h1><p>登录你的私有工作台，开始整理视频素材。</p></section>
      <section class="account" aria-label="账号入口">
          <h2>{{ registering ? '创建你的账号' : '登录工作台' }}</h2>
          <form @submit.prevent="submit">
            <label for="username">用户名</label><input id="username" v-model="username" required maxlength="64" autocomplete="username">
            <label for="password">密码</label><input id="password" v-model="password" required type="password" minlength="8" maxlength="72" :autocomplete="registering ? 'new-password' : 'current-password'">
            <p class="hint">密码至少 8 个字符，编码后不超过 72 字节。</p>
            <button class="primary" type="submit" :disabled="busy">{{ busy ? '正在处理…' : registering ? '注册' : '登录' }}</button>
          </form>
          <button class="secondary" :disabled="busy" @click="registering = !registering; error = ''; notice = ''">{{ registering ? '已有账号，返回登录' : '创建账号' }}</button>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <p role="status" class="notice">{{ notice }}</p>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { request } from './api';
import UploadPanel from './UploadPanel.vue';
import AssetLibrary, { type Playback } from './AssetLibrary.vue';
import MediaPlayer from './MediaPlayer.vue';
const playback = ref<Playback | null>(null);
function playAsset(value: Playback) { playback.value = value; activePane.value = 'results'; }

type Project = { id: string; name: string; rowVersion: number };
type ProjectPage = { items: Project[]; nextCursor: string | null };
const projects = ref<Project[]>([]);
const selected = ref<Project | null>(null);
const nextCursor = ref<string | null>(null);
const name = ref('');
const busy = ref(false);
const error = ref('');
const activePane = ref('assets');

function showError(failure: unknown) {
  error.value = failure instanceof Error ? failure.message : '项目暂时不可用';
}

async function loadProjects(append = false) {
  busy.value = true;
  error.value = '';
  try {
    const suffix = append && nextCursor.value ? `?cursor=${encodeURIComponent(nextCursor.value)}` : '';
    const page = await request<ProjectPage>(`/projects${suffix}`);
    projects.value = append ? [...projects.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
  } catch (failure) { showError(failure); }
  finally { busy.value = false; }
}

async function createProject() {
  busy.value = true;
  error.value = '';
  try {
    await request<Project>('/projects', 'POST', { name: name.value });
    name.value = '';
    await loadProjects();
  } catch (failure) { showError(failure); }
  finally { busy.value = false; }
}

async function openProject(id: string, updateLocation = true) {
  busy.value = true;
  error.value = '';
  selected.value = null;
  try {
    selected.value = await request<Project>(`/projects/${encodeURIComponent(id)}`);
    activePane.value = 'assets';
    playback.value = null;
    if (updateLocation) {
      const url = new URL(location.href);
      url.searchParams.set('project', id);
      history.pushState(null, '', url);
    }
  } catch (failure) { showError(failure); }
  finally { busy.value = false; }
}

function backToProjects() {
  selected.value = null;
  error.value = '';
  const url = new URL(location.href);
  url.searchParams.delete('project');
  history.pushState(null, '', url);
}

async function restoreSelection() {
  const id = new URL(location.href).searchParams.get('project');
  if (id) await openProject(id, false);
  else selected.value = null;
}

onMounted(async () => {
  window.addEventListener('popstate', restoreSelection);
  await loadProjects();
  await restoreSelection();
});
onUnmounted(() => window.removeEventListener('popstate', restoreSelection));
</script>

<template>
  <section class="projects">
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <template v-if="!selected">
      <h1>我的项目</h1>
      <form class="project-create" @submit.prevent="createProject">
        <div><label for="project-name">项目名称</label><input id="project-name" v-model="name" required maxlength="120" placeholder="例如：演讲短视频"></div>
        <button :disabled="busy" type="submit">创建项目</button>
      </form>
      <p v-if="!busy && projects.length === 0" class="empty">还没有项目。创建一个项目，开始整理素材。</p>
      <div class="project-list">
        <button v-for="project in projects" :key="project.id" :disabled="busy" @click="openProject(project.id)">{{ project.name }}</button>
      </div>
      <button v-if="nextCursor" class="secondary" :disabled="busy" @click="loadProjects(true)">加载更多项目</button>
    </template>
    <template v-else>
      <div class="project-header"><div><p class="eyebrow">私有项目</p><h1>{{ selected.name }}</h1></div><button class="secondary" @click="backToProjects">返回项目列表</button></div>
      <div class="workspace-tabs" role="tablist" aria-label="工作区区域">
        <button id="tab-assets" role="tab" :aria-selected="activePane === 'assets'" aria-controls="panel-assets" @click="activePane = 'assets'">素材</button>
        <button id="tab-conversation" role="tab" :aria-selected="activePane === 'conversation'" aria-controls="panel-conversation" @click="activePane = 'conversation'">对话</button>
        <button id="tab-results" role="tab" :aria-selected="activePane === 'results'" aria-controls="panel-results" @click="activePane = 'results'">播放器与结果</button>
      </div>
      <div class="workspace">
        <section id="panel-assets" class="panel" :class="{ active: activePane === 'assets' }" role="tabpanel" aria-labelledby="tab-assets"><h2>素材库</h2><UploadPanel :project-id="selected.id" /><AssetLibrary :key="selected.id" :project-id="selected.id" @play="playAsset" /></section>
        <section id="panel-conversation" class="panel" :class="{ active: activePane === 'conversation' }" role="tabpanel" aria-labelledby="tab-conversation"><h2>对话</h2><p class="empty">还没有制作会话。</p></section>
        <section id="panel-results" class="panel" :class="{ active: activePane === 'results' }" role="tabpanel" aria-labelledby="tab-results"><h2>播放器与结果</h2><MediaPlayer v-if="playback" :key="playback.assetId" :playback="playback" /><div v-else class="player-empty">选择素材或结果即可播放</div><p class="empty">暂无方案或成片。</p></section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.projects h1 { font-size: 28px; margin: 20px 0 28px; }
.project-create { display: flex; align-items: end; gap: 16px; max-width: 640px; }
.project-create > div { flex: 1; }
.project-create label { margin-top: 0; }
.project-create button { width: auto; white-space: nowrap; }
.project-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 16px; margin-top: 28px; }
.project-list button { margin: 0; padding: 24px; background: white; color: #245c45; border: 1px solid #d5dfd7; text-align: left; }
.project-header { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.project-header button { width: auto; margin: 0; }
.project-header h1 { margin-top: 0; }
.workspace { display: grid; grid-template-columns: 1fr 1.1fr 1.2fr; gap: 18px; }
.panel { background: white; border: 1px solid #dce5de; border-radius: 14px; padding: 22px; min-height: 400px; }
.panel h2 { font-size: 18px; margin: 0 0 20px; }
.empty { color: #69766e; line-height: 1.7; }
.player-empty { display: grid; place-items: center; min-height: 170px; border-radius: 10px; background: #e9efea; color: #69766e; font-size: 14px; }
.workspace-tabs { display: none; }
@media (max-width: 720px) {
  .project-create { flex-direction: column; align-items: stretch; gap: 0; }
  .project-header { align-items: start; }
  .project-header button { padding: 10px; font-size: 13px; }
  .workspace { display: block; }
  .panel { display: none; min-height: 350px; }
  .panel.active { display: block; }
  .workspace-tabs { display: flex; gap: 6px; margin: 0 0 16px; }
  .workspace-tabs button { padding: 10px 6px; margin: 0; font-size: 13px; color: #245c45; background: #e7eee8; }
  .workspace-tabs button[aria-selected="true"] { color: white; background: #245c45; }
}
</style>

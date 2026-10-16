<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { provideProjectEvents } from './projectEvents';
import { request } from './api';
import UsagePanel from './UsagePanel.vue';
import AssetLibrary, { type Playback } from './AssetLibrary.vue';
import MediaPlayer from './MediaPlayer.vue';
import SpeechSearch from './SpeechSearch.vue';
import ConversationPanel from './ConversationPanel.vue';
import PlanPanel, { type PlanSelection, type PlanReference } from './PlanPanel.vue';
const props = defineProps<{ userId: string }>();
const showUsage = ref(false), diagnostics = ref(false);
const usageButton = ref<HTMLButtonElement | null>(null);
const usageClose = ref<HTMLButtonElement | null>(null);
async function toggleUsage() {
  showUsage.value = !showUsage.value;
  await nextTick();
  (showUsage.value ? usageClose.value : usageButton.value)?.focus();
}
const editReference=ref<PlanReference|null>(null);
function referencePlan(value:PlanReference){editReference.value=value;setPane('conversation');}
const planSelection = ref<PlanSelection | null>(null), sessionId = ref('');
function viewPlan(selection: PlanSelection) { planSelection.value = selection; setPane('results'); }
function chooseSession(id: string) { sessionId.value = id; planSelection.value = null;editReference.value=null; }
const playback = ref<Playback | null>(null);
const playbackPane = ref<'assets' | 'conversation' | 'results'>('results');
async function playAsset(value: Playback, pane: 'assets' | 'conversation' | 'results' = 'results') {
  playback.value = value; playbackPane.value = pane; setPane(pane);
  await nextTick();
  const player = document.querySelector<HTMLElement>('[aria-label="原片回看"]');
  player?.focus({ preventScroll: true });
  player?.scrollIntoView({ block: 'start' });
}
async function closePlayback() {
  playback.value = null;
  await nextTick();
  document.getElementById(`panel-${activePane.value}`)?.focus();
}

type Project = { id: string; name: string; rowVersion: number };
type ProjectPage = { items: Project[]; nextCursor: string | null };
const projects = ref<Project[]>([]);
const selected = ref<Project | null>(null);
const eventStatus=provideProjectEvents(computed(()=>selected.value?.id));
const nextCursor = ref<string | null>(null);
const name = ref('');
const busy = ref(false);
const error = ref('');
type Pane = 'assets' | 'conversation' | 'results';
const panes: { id: Pane; label: string }[] = [{ id: 'assets', label: '素材' }, { id: 'conversation', label: '制作' }, { id: 'results', label: '作品' }];
const activePane = ref<Pane>('assets');
const preferenceNotice = ref('');
let restorePane = false;
let selectionRequest = 0;
let mounted = true;

function preferenceKey() { return `workspace-view:${props.userId}:${selected.value!.id}`; }
async function setPane(pane: Pane) {
  const moveFocus = pane !== activePane.value && document.getElementById(`panel-${activePane.value}`)?.contains(document.activeElement);
  activePane.value = pane;
  restorePane = false;
  try { localStorage.setItem(preferenceKey(), pane); }
  catch { preferenceNotice.value = '浏览器无法保存视图偏好，本次仍可正常切换。'; }
  if (moveFocus) {
    await nextTick();
    document.getElementById(`panel-${pane}`)?.focus();
  }
}
function assetsLoaded(count: number) {
  if (!restorePane) return;
  restorePane = false;
  if (count === 0) return;
  try {
    const saved = localStorage.getItem(preferenceKey());
    const pane = panes.find(pane => pane.id === saved);
    if (pane) activePane.value = pane.id;
  } catch { preferenceNotice.value = '浏览器无法读取视图偏好，已进入素材。'; }
}
async function navigateTabs(event: KeyboardEvent, index: number) {
  let target: number;
  if (event.key === 'ArrowRight') target = (index + 1) % panes.length;
  else if (event.key === 'ArrowLeft') target = (index + panes.length - 1) % panes.length;
  else if (event.key === 'Home') target = 0;
  else if (event.key === 'End') target = panes.length - 1;
  else return;
  event.preventDefault();
  setPane(panes[target]!.id);
  await nextTick();
  document.getElementById(`tab-${activePane.value}`)!.focus();
}
function clearSelection() {
  selected.value = null;
  showUsage.value = false; diagnostics.value = false;
  sessionId.value = '';
  planSelection.value = null;
  editReference.value = null;
  playback.value = null;
  activePane.value = 'assets';
  preferenceNotice.value = '';
  restorePane = false;
}

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
  const currentRequest = ++selectionRequest;
  clearSelection();
  try {
    const project = await request<Project>(`/projects/${encodeURIComponent(id)}`);
    if (currentRequest !== selectionRequest) return;
    selected.value = project;
    restorePane = true;
    if (updateLocation) {
      const url = new URL(location.href);
      url.searchParams.set('project', id);
      history.pushState(null, '', url);
    }
  } catch (failure) { if (currentRequest === selectionRequest) showError(failure); }
  finally { if (currentRequest === selectionRequest) busy.value = false; }
}

function backToProjects() {
  ++selectionRequest;
  clearSelection();
  busy.value = false;
  error.value = '';
  const url = new URL(location.href);
  url.searchParams.delete('project');
  history.pushState(null, '', url);
}

async function restoreSelection() {
  const id = new URL(location.href).searchParams.get('project');
  if (id) await openProject(id, false);
  else {
    ++selectionRequest;
    clearSelection();
    error.value = '';
    busy.value = false;
  }
}

onMounted(async () => {
  window.addEventListener('popstate', restoreSelection);
  await loadProjects();
  if (mounted) await restoreSelection();
});
onUnmounted(() => { mounted = false; ++selectionRequest; window.removeEventListener('popstate', restoreSelection); });
</script>

<template>
  <section class="projects" :aria-busy="busy">
    <header class="workspace-header">
      <a class="workspace-brand" href="/" @click.prevent="backToProjects"><span class="mark">叙帧</span><span v-if="!selected">叙帧 · 视频工作台</span></a>
      <template v-if="selected">
        <button class="text-button back-button" @click="backToProjects">← 项目列表</button>
        <h1>{{ selected.name }}</h1>
      </template>
      <span v-else class="header-spacer"></span>
      <button v-if="selected" ref="usageButton" class="text-button" :aria-expanded="showUsage" aria-controls="project-usage" @click="toggleUsage">用量</button>
      <button v-if="selected" class="text-button" aria-label="诊断与高级维护" :aria-expanded="diagnostics" @click="diagnostics = !diagnostics">诊断</button>
      <slot name="account" />
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="eventStatus" role="status">{{ eventStatus }}</p>
    <p v-if="preferenceNotice" role="status">{{ preferenceNotice }}</p>
    <p v-if="busy" role="status">正在加载项目…</p>
    <template v-if="!selected">
      <div class="list-heading"><p class="eyebrow">私有工作空间</p><h1>我的项目</h1><p>选择一个项目，继续整理素材与制作视频。</p></div>
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
      <aside v-if="diagnostics" class="diagnostics-notice" aria-label="项目诊断与高级维护"><p>诊断已开启：制作、作品与用量中可查看业务标识和回执。</p><button class="text-button" @click="setPane('assets')">前往素材索引维护</button><p>进入不会执行分析、重建或模型调用。维护操作仍需明确提交。</p></aside>
      <aside v-if="showUsage" id="project-usage" class="usage-drawer" aria-label="项目用量" @keydown.esc="toggleUsage">
        <div class="usage-heading"><h2>项目用量</h2><button ref="usageClose" class="text-button" @click="toggleUsage">关闭用量</button></div>
        <UsagePanel :key="`usage-${selected.id}`" :project-id="selected.id" :diagnostics="diagnostics" />
      </aside>
      <div class="workspace-tabs" role="tablist" aria-label="工作台区域">
        <button v-for="(pane, index) in panes" :id="`tab-${pane.id}`" :key="pane.id" role="tab" :aria-selected="activePane === pane.id" :aria-controls="`panel-${pane.id}`" :tabindex="activePane === pane.id ? 0 : -1" @click="setPane(pane.id)" @keydown="navigateTabs($event, index)">{{ pane.label }}</button>
      </div>
      <div :key="selected.id" class="workspace">
        <section id="panel-assets" v-show="activePane === 'assets'" class="panel" role="tabpanel" aria-labelledby="tab-assets" tabindex="0"><h2>素材库</h2><p class="view-description">导入视频，查找内容，回看原始素材。</p>
          <MediaPlayer v-if="playback && playbackPane === 'assets'" :key="playback.assetId" :playback="playback" return-label="返回素材列表" @close="closePlayback" />
          <div v-show="!playback || playbackPane !== 'assets'"><AssetLibrary :project-id="selected.id" :diagnostics="diagnostics" @loaded="assetsLoaded" @play="playAsset($event, 'assets')" /><SpeechSearch :project-id="selected.id" @play="playAsset($event, 'assets')" /></div>
        </section>
        <section id="panel-conversation" v-show="activePane === 'conversation'" class="panel" role="tabpanel" aria-labelledby="tab-conversation" tabindex="0"><h2>制作</h2><p class="view-description">与助手讨论内容，生成并确认剪辑草稿。</p><ConversationPanel :project-id="selected.id" :diagnostics="diagnostics" :edit-reference="editReference" :has-source="!!playback && playbackPane === 'conversation'" @play="playAsset($event, 'conversation')" @plan="viewPlan" @session="chooseSession"><template #source><MediaPlayer v-if="playback && playbackPane === 'conversation'" :key="playback.assetId" :playback="playback" return-label="返回草稿" @close="closePlayback" /></template></ConversationPanel></section>
        <section id="panel-results" v-show="activePane === 'results'" class="panel" role="tabpanel" aria-labelledby="tab-results" tabindex="0"><h2>作品</h2><p class="view-description">查看已确认方案，导出与管理成片。</p><MediaPlayer v-if="playback && playbackPane === 'results'" :key="playback.assetId" :playback="playback" @close="closePlayback" /><PlanPanel :project-id="selected.id" :diagnostics="diagnostics" :session-id="sessionId" :selection="planSelection" @play="playAsset" @reference="referencePlan" /></section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.diagnostics-notice { padding: 12px 20px; border: 1px solid var(--line); background: #edf3ee; font-size: 13px; }
.workspace-header { position: relative; display: flex; align-items: center; gap: 8px; min-height: 88px; border-bottom: 1px solid var(--line); }
.workspace-brand { display: flex; gap: 12px; align-items: center; text-decoration: none; font-weight: 600; flex-shrink: 0; }
.workspace-header h1 { flex: 1; min-width: 0; font-size: 20px; margin: 0 16px; }
.workspace-header button { margin: 0; flex-shrink: 0; }
.header-spacer { flex: 1; }
.list-heading { padding: 32px 0 16px; }
.list-heading h1 { font-size: 32px; margin: 8px 0; }
.list-heading p { color: var(--muted); }
.project-create { display: flex; align-items: end; gap: 16px; max-width: 640px; }
.project-create > div { flex: 1; min-width: 0; }
.project-create label { margin-top: 0; }
.project-create button { white-space: nowrap; margin: 0; }
.project-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(230px, 100%), 1fr)); gap: 16px; margin-top: 32px; }
.project-list button { margin: 0; min-height: 100px; padding: 24px; background: white; text-align: left; font-size: 18px; }
.workspace-tabs { position: sticky; top: 0; z-index: 2; background: #f3f5f0; display: flex; gap: 8px; margin: 20px 0; border-bottom: 1px solid var(--line); }
.workspace-tabs button { min-width: 88px; margin: 0 0 -1px; border: 0; border-bottom: 3px solid transparent; border-radius: 0; background: transparent; color: var(--muted); }
.workspace-tabs button[aria-selected='true'] { color: var(--accent); border-bottom-color: var(--accent); font-weight: 600; }
.panel { background: white; border: 1px solid #dce5de; border-radius: 12px; padding: 24px; min-height: 400px; min-width: 0; overflow-wrap: anywhere; }
.panel h2 { font-size: 22px; margin: 0 0 8px; }
.view-description { color: var(--muted); margin: 0 0 24px; font-size: 14px; }
.empty { color: var(--muted); }
.player-empty { display: grid; place-items: center; min-height: 170px; border-radius: 8px; background: #e9efea; color: var(--muted); font-size: 14px; }
.usage-drawer { position: fixed; z-index: 4; inset: 16px 16px 16px auto; width: min(560px, calc(100% - 32px)); padding: 24px; overflow-y: auto; background: #f3f5f0; border: 1px solid #9eafa3; border-radius: 12px; box-shadow: 0 12px 48px #18352626; }
.usage-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.usage-heading h2, .usage-heading button { margin: 0; }
@media (max-width: 720px) {
  .workspace-header { flex-wrap: wrap; padding: 12px 0; gap: 4px; }
  .workspace-header h1 { order: 1; flex-basis: 100%; margin: 8px 0 0; }
  .back-button { flex: 1; text-align: left; white-space: nowrap; }
  .project-create { flex-direction: column; align-items: stretch; gap: 12px; }
  .workspace-tabs { margin: 16px 0; }
  .workspace-tabs button { flex: 1; min-width: 0; }
  .panel { padding: 16px; }
  .usage-drawer { padding: 16px; }
}
</style>

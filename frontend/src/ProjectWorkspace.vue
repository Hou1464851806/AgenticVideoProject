<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { NModal, NButton } from 'naive-ui';
import { provideProjectEvents } from './projectEvents';
import { request } from './api';
import UsagePanel from './UsagePanel.vue';
import MediaPlayer from './MediaPlayer.vue';
import ConversationPanel from './ConversationPanel.vue';
import PlanPanel, { type PlanSelection, type PlanReference } from './PlanPanel.vue';
import WorkspaceRail from './WorkspaceRail.vue';
import AssetDrawer from './AssetDrawer.vue';

const props = defineProps<{ userId: string }>();
type Project = { id: string; name: string; rowVersion: number };
type ProjectPage = { items: Project[]; nextCursor: string | null };
type Session = { id: string; title: string; assetIds: string[] };
type Asset = { id: string; filename: string; validationStatus: string };
type ConversationApi = { createChat: () => Promise<void>; choose: (id: string) => Promise<void> };

const projects = ref<Project[]>([]);
const selected = ref<Project | null>(null);
const nextCursor = ref<string | null>(null);
const loadingProjects = ref(false), creatingProject = ref(false), creatingChat = ref(false);
const error = ref('');
const projectError = ref('');
const showUsage = ref(false), diagnostics = ref(false), usageClose = ref<HTMLButtonElement | null>(null);
const createOpen = ref(false), projectName = ref(''), createError = ref(''), projectNameInput = ref<HTMLInputElement | null>(null);
const selectionRequest = ref(0);
let createTrigger: HTMLElement | null = null;
let mounted = true;
const eventStatus = provideProjectEvents(computed(() => selected.value?.id));

const conversation = ref<ConversationApi | null>(null);
const chatSessions = ref<Session[]>([]);
const activeChatId = ref('');
const activeChatTitle = computed(() => chatSessions.value.find(chat => chat.id === activeChatId.value)?.title ?? '');
const creatingChatBusy = computed(() => creatingChat.value);

const assetItems = ref<Asset[]>([]);
const assetsOpen = ref(false);
const assetsTrigger = ref<HTMLElement | null>(null);
const playback = ref<import('./AssetLibrary.vue').Playback | null>(null);
const mediaTrigger = ref<HTMLElement | null>(null);
const mediaDialog = ref<HTMLElement | null>(null);
const panelCollapsed = ref(window.innerWidth <= 1180), railCollapsed = ref(window.innerWidth <= 960);
const viewportWidth = ref(window.innerWidth);
const narrow = ref(viewportWidth.value <= 960), panelWidth = ref(380);
const workspaceShell = ref<HTMLElement | null>(null);
let panelTrigger: HTMLElement | null = null, railTrigger: HTMLElement | null = null;
const maxPanelWidth = computed(() => Math.max(300, Math.min(680, viewportWidth.value - (railCollapsed.value ? 64 : viewportWidth.value <= 1180 ? 244 : viewportWidth.value <= 1280 ? 272 : 300) - 400)));
function resizePanel(width: number) { panelWidth.value = Math.round(Math.max(300, Math.min(maxPanelWidth.value, width))); }
function startResize(event: PointerEvent) {
  if (event.button !== 0) return;
  event.preventDefault();
  const startX = event.clientX, startWidth = panelWidth.value;
  const move = (e: PointerEvent) => resizePanel(startWidth + startX - e.clientX);
  const end = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', end); window.removeEventListener('pointercancel', end); };
  window.addEventListener('pointermove', move); window.addEventListener('pointerup', end); window.addEventListener('pointercancel', end);
}
function resizeKey(event: KeyboardEvent) {
  if (event.key === 'ArrowLeft') resizePanel(panelWidth.value + 20);
  else if (event.key === 'ArrowRight') resizePanel(panelWidth.value - 20);
  else if (event.key === 'Home') resizePanel(300);
  else if (event.key === 'End') resizePanel(maxPanelWidth.value);
  else return;
  event.preventDefault();
}
function toggleRail() {
  if (narrow.value && railCollapsed.value) { railTrigger = document.activeElement as HTMLElement; panelCollapsed.value = true; }
  railCollapsed.value = !railCollapsed.value;
  if (narrow.value) void nextTick(() => railCollapsed.value ? railTrigger?.focus() : workspaceShell.value?.querySelector<HTMLElement>('.rail-inner [aria-label="收起工作区"]')?.focus());
}

const planSelection = ref<PlanSelection | null>(null);
const editReference = ref<PlanReference | null>(null);

async function loadProjects(append = false) {
  if (append && !nextCursor.value) return;
  loadingProjects.value = true;
  error.value = '';
  try {
    const suffix = append ? `?cursor=${encodeURIComponent(nextCursor.value!)}` : '';
    const page = await request<ProjectPage>(`/projects${suffix}`);
    projects.value = append ? [...projects.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '无法读取项目';
  } finally {
    loadingProjects.value = false;
  }
}

function clearProjectState() {
  chatSessions.value = [];
  activeChatId.value = '';
  assetItems.value = [];
  planSelection.value = null;
  editReference.value = null;
  playback.value = null;
  assetsOpen.value = false;
}

async function openProject(id: string, updateLocation = true) {
  const current = ++selectionRequest.value;
  projectError.value = '';
  clearProjectState();
  try {
    const project = await request<Project>(`/projects/${encodeURIComponent(id)}`);
    if (!mounted || current !== selectionRequest.value) return;
    selected.value = project;
    if (!projects.value.some(item => item.id === project.id)) projects.value.unshift(project);
    if (updateLocation) {
      const url = new URL(location.href);
      url.searchParams.set('project', id);
      history.pushState(null, '', url);
    }
    await loadAssetPreview(project.id);
  } catch (failure) {
    if (mounted && current === selectionRequest.value) projectError.value = failure instanceof Error ? failure.message : '项目暂时不可用';
  }
}

async function loadAssetPreview(projectId = selected.value?.id) {
  if (!projectId) return;
  try {
    const page = await request<{ items: Asset[] }>(`/projects/${projectId}/assets?includePending=true`);
    if (selected.value?.id === projectId) assetItems.value = page.items;
  } catch { /* The asset drawer reports its own error; the rail stays empty. */ }
}

function chooseProject(id: string) {
  if (narrow.value && !railCollapsed.value) toggleRail();
  if (id === selected.value?.id) return;
  void openProject(id);
}

async function createProject() {
  if (creatingProject.value) return;
  creatingProject.value = true;
  createError.value = '';
  try {
    const project = await request<Project>('/projects', 'POST', { name: projectName.value });
    projectName.value = '';
    createOpen.value = false;
    await loadProjects();
    await openProject(project.id);
  } catch (failure) {
    createError.value = failure instanceof Error ? failure.message : '无法创建项目';
  } finally {
    creatingProject.value = false;
  }
}

async function openCreateProject() {
  createTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  createOpen.value = true;
  createError.value = '';
  await nextTick();
  projectNameInput.value?.focus();
}

function closeCreateProject() {
  if (creatingProject.value) return;
  createOpen.value = false;
  createError.value = '';
  void nextTick(() => createTrigger?.isConnected && createTrigger.focus());
}

async function createChat() {
  if (!conversation.value || creatingChat.value) return;
  creatingChat.value = true;
  try {
    await conversation.value.createChat();
  } finally {
    creatingChat.value = false;
  }
}

function chooseChat(id: string) {
  if (narrow.value && !railCollapsed.value) toggleRail();
  if (!conversation.value || id === activeChatId.value) return;
  void conversation.value.choose(id);
}

function onSessions(items: Session[]) { chatSessions.value = items; }
function onSession(id: string) {
  activeChatId.value = id;
  planSelection.value = null;
  editReference.value = null;
}

function viewPlan(selection: PlanSelection) {
  planSelection.value = selection;
  if (panelCollapsed.value) togglePanel();
}

function referencePlan(value: PlanReference) {
  editReference.value = value;
  if (narrow.value && !panelCollapsed.value) panelCollapsed.value = true;
  void nextTick(() => document.querySelector<HTMLElement>('#conversation-input')?.focus());
}

function togglePanel() {
  if (narrow.value && panelCollapsed.value) { panelTrigger = document.activeElement as HTMLElement; railCollapsed.value = true; }
  panelCollapsed.value = !panelCollapsed.value;
  if (narrow.value) void nextTick(() => panelCollapsed.value ? panelTrigger?.focus() : workspaceShell.value?.querySelector<HTMLElement>('.pane-head [aria-label="收起产物面板"]')?.focus());
}


function openAssets() {
  if (!selected.value) return;
  assetsTrigger.value = document.activeElement as HTMLElement | null;
  assetsOpen.value = true;
}

async function closeAssets() {
  assetsOpen.value = false;
  await nextTick();
  if (assetsTrigger.value && document.contains(assetsTrigger.value)) assetsTrigger.value.focus();
  else document.querySelector<HTMLElement>('.chat-head button')?.focus();
}

function openUsage() {
  if (!selected.value) return;
  showUsage.value = true;
  void nextTick(() => usageClose.value?.focus());
}

async function closeUsage() {
  showUsage.value = false;
  await nextTick();
  document.querySelector<HTMLElement>('.rail-links button')?.focus();
}

function playAsset(value: import('./AssetLibrary.vue').Playback) {
  mediaTrigger.value = document.activeElement as HTMLElement | null;
  playback.value = value;
  void nextTick(() => mediaDialog.value?.querySelector<HTMLElement>('.player-heading button')?.focus());
}

async function closeMedia() {
  playback.value = null;
  await nextTick();
  if (mediaTrigger.value && document.contains(mediaTrigger.value)) mediaTrigger.value.focus();
  mediaTrigger.value = null;
}

function trapMediaFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !mediaDialog.value) return;
  const focusable = [...mediaDialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), video[controls], input:not(:disabled), select:not(:disabled), summary, a[href]')]
    .filter(node => node.offsetParent !== null);
  if (!focusable.length) return;
  const first = focusable[0]!, last = focusable[focusable.length - 1]!;
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}

function onKeydown(event: KeyboardEvent) {
  if (event.defaultPrevented) return;
  if (event.key === 'Tab' && narrow.value && !playback.value && !assetsOpen.value && !createOpen.value && !showUsage.value && (!railCollapsed.value || !panelCollapsed.value)) {
    const root = workspaceShell.value?.querySelector<HTMLElement>(!railCollapsed.value ? '.rail-inner' : '.artifact-inner');
    const nodes = [...(root?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, a[href], [tabindex]:not([tabindex="-1"])') ?? [])].filter(node => node.tabIndex >= 0 && node.offsetParent !== null);
    const first = nodes[0], last = nodes[nodes.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    return;
  }
  if (event.key !== 'Escape') return;
  if (playback.value) { event.preventDefault(); closeMedia(); return; }
  if (createOpen.value) return;
  if (showUsage.value) { event.preventDefault(); void closeUsage(); return; }
  if (assetsOpen.value) return;
  if (narrow.value && !panelCollapsed.value) { event.preventDefault(); togglePanel(); return; }
  if (narrow.value && !railCollapsed.value) { event.preventDefault(); toggleRail(); }
}

async function restoreSelection() {
  const id = new URL(location.href).searchParams.get('project');
  if (id) await openProject(id, false);
  else {
    ++selectionRequest.value;
    selected.value = null;
    clearProjectState();
    projectError.value = '';
  }
}

let lastNarrow = false, lastMedium = false;
function applyResponsive() {
  const isNarrow = window.innerWidth <= 960, medium = window.innerWidth <= 1180;
  viewportWidth.value = window.innerWidth; narrow.value = isNarrow; resizePanel(panelWidth.value);
  if (isNarrow && !lastNarrow) { railCollapsed.value = true; panelCollapsed.value = true; }
  else if (medium && !lastMedium) panelCollapsed.value = true;
  lastNarrow = isNarrow; lastMedium = medium;
}
onMounted(async () => {
  lastNarrow = window.innerWidth <= 960;
  lastMedium = window.innerWidth <= 1180;
  window.addEventListener('resize', applyResponsive);
  window.addEventListener('popstate', restoreSelection);
  window.addEventListener('keydown', onKeydown);
  await loadProjects();
  if (mounted) await restoreSelection();
});
onUnmounted(() => {
  mounted = false;
  ++selectionRequest.value;
  window.removeEventListener('resize', applyResponsive);
  window.removeEventListener('popstate', restoreSelection);
  window.removeEventListener('keydown', onKeydown);
});
</script>

<template>
  <div ref="workspaceShell" class="workspace-shell">
    <div v-if="narrow && (!railCollapsed || !panelCollapsed)" class="workspace-scrim" @click="!railCollapsed ? toggleRail() : togglePanel()"></div>
    <WorkspaceRail
      :projects="projects"
      :active-project-id="selected?.id ?? ''"
      :chats="chatSessions"
      :active-chat-id="activeChatId"
      :assets="assetItems"
      :has-more="!!nextCursor"
      :collapsed="railCollapsed"
      :diagnostics="diagnostics"
      :creating-chat="creatingChatBusy"
      @select-project="chooseProject"
      @new-project="openCreateProject"
      @load-more="loadProjects(true)"
      @new-chat="createChat"
      @select-chat="chooseChat"
      @open-assets="openAssets"
      @toggle-rail="toggleRail"
      @usage="openUsage"
      @diagnostics="diagnostics = !diagnostics"
    >
      <template #account><slot name="account" /></template>
    </WorkspaceRail>

    <section class="chat" aria-label="对话工作区" :inert="narrow && (!railCollapsed || !panelCollapsed)">
      <header class="chat-head">
        <div class="head-title">
          <span v-if="selected" class="crumb">{{ selected.name }}</span>
          <h1>{{ activeChatTitle || (selected ? '开始一段对话' : '工作区') }}</h1>
        </div>
        <span class="grow"></span>
        <button v-if="selected" class="icon-btn" type="button" aria-label="打开素材库" @click="openAssets">
          <svg class="ic" viewBox="0 0 16 16" aria-hidden="true"><rect x="2.8" y="3.6" width="10.4" height="8.8" rx="1"/><path d="M5.4 3.6v8.8M10.6 3.6v8.8M2.8 6.1h2.6M2.8 9.9h2.6M10.6 6.1h2.6M10.6 9.9h2.6"/></svg>
        </button>
        <button v-if="selected" class="icon-btn" type="button" :aria-expanded="!panelCollapsed" aria-label="收起或展开产物面板" @click="togglePanel">
          <svg class="ic" viewBox="0 0 16 16" aria-hidden="true"><path d="M3.8 4.2h8.4v7.6H3.8zM10.2 4.2v7.6"/></svg>
        </button>
      </header>
      <p v-if="error" role="alert" class="workspace-alert">{{ error }}</p>
      <p v-if="projectError" role="alert" class="workspace-alert">{{ projectError }}</p>
      <p v-if="eventStatus" role="status" class="workspace-status">{{ eventStatus }}</p>
      <aside v-if="diagnostics" class="diagnostics-notice" aria-label="项目诊断与高级维护">
        <span>诊断已开启：制作、产物与用量中可查看业务标识和回执；进入不会执行分析、重建或模型调用。</span>
        <button class="text-button" type="button" @click="openAssets">前往素材索引维护</button>
      </aside>
      <template v-if="selected">
        <ConversationPanel
          ref="conversation"
          :key="selected.id"
          :project-id="selected.id"
          :diagnostics="diagnostics"
          :edit-reference="editReference"
          @play="playAsset"
          @plan="viewPlan"
          @sessions="onSessions"
          @session="onSession"
        />
      </template>
      <section v-else class="workspace-empty">
        <div class="empty-mark" aria-hidden="true">叙</div>
        <h2>{{ projects.length ? '选择一个项目' : '创建你的第一个项目' }}</h2>
        <p>{{ projects.length ? '从左侧工作区打开项目，素材、对话与产物会在这里展开。' : '项目保存你的素材、对话和成片，方便按主题继续制作。' }}</p>
        <button class="primary" type="button" @click="openCreateProject">＋ 新建项目</button>
        <p v-if="loadingProjects" class="empty-hint" role="status">正在读取项目…</p>
      </section>
    </section>

    <aside v-if="selected" class="artifact-panel" :class="{ collapsed: panelCollapsed }" :style="!narrow && !panelCollapsed ? { width: `${Math.min(panelWidth, maxPanelWidth)}px` } : undefined" aria-label="产物">
      <div v-if="!narrow && !panelCollapsed" class="panel-resizer" role="separator" aria-label="调整产物面板宽度" aria-orientation="vertical" :aria-valuemin="300" :aria-valuemax="maxPanelWidth" :aria-valuenow="Math.min(panelWidth, maxPanelWidth)" tabindex="0" @pointerdown="startResize" @keydown="resizeKey"></div>
      <div class="artifact-inner">
        <header class="pane-head">
          <strong>产物</strong>
          <span class="grow"></span>
          <button class="icon-btn" type="button" aria-label="收起产物面板" @click="togglePanel">
            <svg class="ic" viewBox="0 0 16 16" aria-hidden="true"><path d="M3.8 4.2h8.4v7.6H3.8zM10.2 4.2v7.6"/></svg>
          </button>
        </header>
        <div class="pane-body">
          <PlanPanel
            :key="selected.id"
            :project-id="selected.id"
            :session-id="activeChatId"
            :selection="planSelection"
            :diagnostics="diagnostics"
            @play="playAsset"
            @reference="referencePlan"
          />
        </div>
      </div>
      <button class="artifact-rail" type="button" aria-label="展开产物面板" @click="togglePanel">
        <span>产物</span><svg class="ic" viewBox="0 0 16 16" aria-hidden="true"><path d="M9.8 4.2 6.2 8l3.6 3.8"/></svg>
      </button>
    </aside>
  </div>

  <AssetDrawer v-if="assetsOpen && selected" :key="selected.id" :project-id="selected.id" :blocked="!!playback" :diagnostics="diagnostics" @close="closeAssets" @play="playAsset" @items="assetItems = $event" />

  <div v-if="playback" class="media-overlay" @click.self="closeMedia">
    <div ref="mediaDialog" class="media-dialog" role="dialog" aria-modal="true" aria-label="原片回看" tabindex="-1" @keydown="trapMediaFocus">
      <MediaPlayer :key="playback.assetId" :playback="playback" return-label="关闭原片" @close="closeMedia" />
    </div>
  </div>

  <div v-if="showUsage" class="modal-overlay" @click.self="closeUsage">
    <section class="modal-card usage-modal" role="dialog" aria-modal="true" aria-label="项目用量">
      <header class="modal-head"><h2>项目用量</h2><button ref="usageClose" class="icon-btn" type="button" aria-label="关闭用量" @click="closeUsage">✕</button></header>
      <div class="modal-body"><UsagePanel :key="`usage-${selected?.id}`" :project-id="selected?.id ?? ''" :diagnostics="diagnostics" /></div>
    </section>
  </div>

  <n-modal :show="createOpen" :mask-closable="!creatingProject" :close-on-esc="!creatingProject" @update:show="value => { if (!value) closeCreateProject(); }">
    <div class="modal-card create-modal" tabindex="-1" role="dialog" aria-modal="true" aria-label="新建项目">
      <header class="modal-head"><h2>新建项目</h2><button class="icon-btn" type="button" aria-label="关闭新建项目" @click="closeCreateProject">✕</button></header>
      <form @submit.prevent="createProject">
        <label for="project-name">项目名称</label>
        <input id="project-name" ref="projectNameInput" v-model="projectName" required maxlength="120" placeholder="例如：秋季发布会 · 预告">
        <p v-if="createError" role="alert">{{ createError }}</p>
        <div class="modal-actions"><n-button :disabled="creatingProject" @click="closeCreateProject">取消</n-button><n-button type="primary" attr-type="submit" :disabled="creatingProject">{{ creatingProject ? '正在创建…' : '创建项目' }}</n-button></div>
      </form>
    </div>
  </n-modal>
</template>

<style scoped>
.workspace-scrim { position: fixed; inset: 0; z-index: 18; background: rgba(15, 17, 21, .32); }
.workspace-shell { height: 100%; min-height: 0; display: flex; overflow: hidden; background: #fff; }
.chat { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; background: #fff; }
.chat-head { height: 48px; flex: none; display: flex; align-items: center; gap: 8px; padding: 0 16px 0 22px; border-bottom: 1px solid var(--line); }
.head-title { min-width: 0; display: flex; flex-direction: column; justify-content: center; }
.crumb { color: var(--ink-4); font-size: 10px; line-height: 1.2; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: min(420px, 40vw); }
.chat-head h1 { font-size: 15px; line-height: 1.3; font-weight: 600; margin: 0; letter-spacing: -.01em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: min(520px, 46vw); }
.grow { flex: 1; min-width: 0; }
.icon-btn { width: 30px; height: 30px; min-height: 0; margin: 0; padding: 0; display: grid; place-items: center; border-color: transparent; background: transparent; color: var(--ink-3); }
.icon-btn:hover { background: var(--panel); color: var(--ink); }
.ic { width: 16px; height: 16px; stroke: currentColor; fill: none; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.workspace-alert { margin: 8px 22px 0; color: var(--red); font-size: 12px; }
.workspace-status { margin: 8px 22px 0; color: var(--ink-4); font-size: 11px; }
.diagnostics-notice { margin: 8px 22px 0; padding: 7px 10px; border: 1px solid var(--line); background: var(--panel); font-size: 11.5px; line-height: 1.5; color: var(--ink-2); display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.diagnostics-notice button { margin: 0; font-size: 11px; color: var(--blue); }
.workspace-empty { flex: 1; min-height: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; padding: 40px 24px; }
.empty-mark { width: 44px; height: 44px; border-radius: 11px; background: var(--ink); color: #fff; display: grid; place-items: center; font-size: 16px; margin-bottom: 16px; }
.workspace-empty h2 { font-size: 20px; margin: 0 0 8px; }
.workspace-empty p { color: var(--ink-3); max-width: 360px; line-height: 1.7; font-size: 13px; margin: 0 0 18px; }
.empty-hint { margin-top: 14px !important; font-size: 11px !important; color: var(--ink-4) !important; }
.panel-resizer { position: absolute; left: 0; top: 0; bottom: 0; width: 8px; cursor: col-resize; z-index: 2; touch-action: none; }
.panel-resizer:hover, .panel-resizer:focus-visible { background: var(--blue-weak); }
.artifact-panel { position: relative; width: 380px; min-width: 0; flex: none; border-left: 1px solid var(--line); background: #fff; display: flex; overflow: hidden; }
.artifact-inner { width: 100%; flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; transition: opacity .14s; }
.artifact-panel.collapsed { width: 44px; }
.artifact-panel.collapsed .artifact-inner { opacity: 0; visibility: hidden; pointer-events: none; }
.pane-head { height: 48px; flex: none; display: flex; align-items: center; gap: 8px; padding: 0 14px 0 18px; border-bottom: 1px solid var(--line); }
.pane-head strong { font-size: 13px; font-weight: 600; }
.pane-body { flex: 1; min-height: 0; overflow: auto; padding: 16px 14px 24px; overscroll-behavior: contain; }
.artifact-rail { position: absolute; left: 0; top: 0; bottom: 0; display: none; width: 44px; padding: 0; margin: 0; border: 0; background: #fff; color: var(--ink-3); align-items: center; justify-content: center; gap: 8px; writing-mode: vertical-rl; letter-spacing: .16em; font-size: 11px; }
.artifact-rail:hover { background: var(--panel); color: var(--ink); }
.artifact-panel.collapsed .artifact-rail { display: flex; }
.media-overlay, .modal-overlay { position: fixed; inset: 0; z-index: 60; background: rgba(12, 14, 18, .56); display: flex; align-items: center; justify-content: center; padding: 26px; }
.media-dialog { width: min(1200px, 100%); max-height: 90vh; overflow: auto; background: #fff; border-radius: 12px; box-shadow: 0 24px 80px rgba(0, 0, 0, .3); }
.modal-overlay { z-index: 55; }
.modal-card { width: min(560px, 100%); max-height: 86vh; overflow: auto; background: #fff; border: 1px solid var(--line); border-radius: 10px; box-shadow: 0 24px 80px rgba(0, 0, 0, .25); }
.modal-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 18px; border-bottom: 1px solid var(--line); }
.modal-head h2 { font-size: 16px; margin: 0; }
.modal-body { padding: 18px; }
.create-modal form { padding: 18px; }
.create-modal label { margin-top: 0; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.modal-actions button { margin: 0; }
.usage-modal .modal-body { max-height: 70vh; overflow: auto; }
.usage-modal :deep(.usage) { margin: 0; padding: 0; border: 0; background: transparent; }
.media-dialog :deep(.source-player) { padding: 18px; }
.media-dialog :deep(.source-player h3) { font-size: 15px; }
.media-dialog :deep(video) { border-radius: 7px; }
@media (max-width: 960px) {
  .artifact-panel:not(.collapsed) { position: fixed; z-index: 21; top: 0; right: 0; bottom: 0; width: min(420px, calc(100vw - 32px)); box-shadow: -12px 0 40px rgba(15,17,21,.16); }
}
</style>

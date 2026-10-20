<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
type Project = { id: string; name: string };
type Chat = { id: string; title: string };
type Asset = { id: string; filename: string; validationStatus: string };
const props = defineProps<{
  projects: Project[];
  activeProjectId: string;
  chats: Chat[];
  activeChatId: string;
  assets: Asset[];
  hasMore: boolean;
  collapsed: boolean;
  diagnostics: boolean;
  creatingChat: boolean;
}>();
const emit = defineEmits<{
  'select-project': [id: string];
  'new-project': [];
  'load-more': [];
  'new-chat': [];
  'select-chat': [id: string];
  'open-assets': [];
  'toggle-rail': [];
  usage: [];
  diagnostics: [];
}>();
const projectMenu = ref(false);
const scrollArea = ref<HTMLElement | null>(null);
const activeProject = computed(() => props.projects.find(project => project.id === props.activeProjectId) ?? null);
watch(() => [props.activeProjectId, props.activeChatId, props.chats.length], async () => {
  await nextTick();
  scrollArea.value?.querySelector<HTMLElement>('.chat-btn.active')?.scrollIntoView({ block: 'nearest' });
});
function status(asset: Asset) {
  return asset.validationStatus === 'VALID' ? '媒体验证通过 · 可播放' : '媒体验证中，通过后可播放';
}
</script>

<template>
  <aside class="rail" :class="{ collapsed }" aria-label="工作区">
    <div class="rail-inner">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true"></span>
        <span class="brand-name">叙帧</span>
        <span class="brand-badge">STUDIO</span>
        <span class="grow"></span>
        <button class="icon-btn" type="button" aria-label="收起工作区" @click="emit('toggle-rail')">
          <svg class="ic" viewBox="0 0 16 16" aria-hidden="true"><path d="M3.8 4.2h8.4v7.6H3.8zM5.8 4.2v7.6"/></svg>
        </button>
      </div>
      <button class="new-chat" type="button" :disabled="!activeProjectId || creatingChat" @click="emit('new-chat')">
        <svg class="ic ic-sm" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3.2v9.6M3.2 8h9.6"/></svg>
        {{ creatingChat ? '正在新建…' : '新聊天' }}
      </button>
      <div class="workspace-head">
        <div class="section-label"><span>工作区</span><span class="count">{{ projects.length }}</span><span class="grow"></span><button class="label-plus" type="button" aria-label="新建项目" @click="emit('new-project')">＋</button></div>
        <div class="project-switcher">
          <button class="project-current" type="button" :aria-expanded="projectMenu" aria-haspopup="menu" :data-project-id="activeProjectId" @click="projectMenu = !projectMenu">
            <svg class="ic ic-sm" viewBox="0 0 16 16" aria-hidden="true"><path d="M2.8 5.2c0-.6.4-1 1-1h2.4l1.1 1.2h5c.6 0 1 .4 1 1v5.2c0 .6-.4 1-1 1H3.8c-.6 0-1-.4-1-1z"/></svg>
            <span class="name">{{ activeProject?.name ?? '选择或新建项目' }}</span>
            <svg class="ic ic-sm chev" viewBox="0 0 16 16" aria-hidden="true"><path d="M4.2 6.2 8 10l3.8-3.8"/></svg>
          </button>
          <div v-if="projectMenu" class="project-menu" role="menu" aria-label="切换项目">
            <button v-for="project in projects" :key="project.id" class="project-menu-item" :class="{ active: project.id === activeProjectId }" :data-project-id="project.id" type="button" role="menuitem" @click="emit('select-project', project.id); projectMenu = false">
              <span>{{ project.name }}</span><span v-if="project.id === activeProjectId" aria-hidden="true">✓</span>
            </button>
            <button v-if="hasMore" class="rail-action muted" type="button" @click="emit('load-more')">加载更多项目</button>
            <button class="rail-action" type="button" @click="emit('new-project'); projectMenu = false">＋ 新建项目</button>
          </div>
        </div>
      </div>
      <div ref="scrollArea" class="rail-scroll">
        <ul class="chats" aria-label="聊天会话">
          <li v-for="chat in chats" :key="chat.id">
            <button class="chat-btn" :class="{ active: chat.id === activeChatId }" :data-conversation-id="chat.id" type="button" @click="emit('select-chat', chat.id)">
              <span class="t">{{ chat.title }}</span>
            </button>
          </li>
          <li v-if="activeProject && !chats.length"><span class="rail-empty">还没有聊天</span></li>
        </ul>
      </div>
      <section v-if="activeProjectId" class="asset-block" aria-label="素材">
        <div class="asset-title"><strong>素材</strong><span class="n">{{ assets.length }}</span><span class="grow"></span><button class="up" type="button" @click="emit('open-assets')">
          <svg class="ic ic-sm" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 10.8V3.6M5.4 6.1 8 3.5l2.6 2.6M3.4 11.2v1.2h9.2v-1.2"/></svg>上传</button></div>
        <div class="asset-mini">
          <button v-for="asset in assets.slice(0, 3)" :key="asset.id" class="asset-row" type="button" @click="emit('open-assets')">
            <span class="file-chip"><svg class="ic ic-sm" viewBox="0 0 16 16" aria-hidden="true"><rect x="2.8" y="3.6" width="10.4" height="8.8" rx="1"/><path d="M5.4 3.6v8.8M10.6 3.6v8.8M2.8 6.1h2.6M2.8 9.9h2.6M10.6 6.1h2.6M10.6 9.9h2.6"/></svg></span>
            <span class="asset-main"><span class="asset-name">{{ asset.filename }}</span><span class="asset-sub" :class="{ live: asset.validationStatus === 'VALID' }">{{ status(asset) }}</span></span>
            <span v-if="asset.validationStatus === 'VALID'" class="asset-play" aria-hidden="true"><svg class="ic ic-sm" viewBox="0 0 16 16"><path d="M6 4.6 11 8l-5 3.4z" fill="currentColor" stroke="none"/></svg></span>
          </button>
          <p v-if="!assets.length" class="asset-empty">还没有素材。上传一段视频开始。</p>
        </div>
        <button class="all-assets" type="button" @click="emit('open-assets')">全部素材 <span aria-hidden="true">→</span></button>
      </section>
      <footer class="account">
        <span class="avatar" aria-hidden="true">叙</span>
        <div class="rail-links">
          <button type="button" :disabled="!activeProjectId" @click="emit('usage')">用量</button>
          <button type="button" :disabled="!activeProjectId" :aria-label="'诊断与高级维护'" :aria-pressed="diagnostics" @click="emit('diagnostics')">诊断</button>
        </div>
        <slot name="account" />
      </footer>
    </div>
    <div class="rail-compact">
      <span class="brand-mark" aria-hidden="true"></span>
      <button class="icon-btn" type="button" aria-label="新聊天" :disabled="!activeProjectId || creatingChat" @click="emit('new-chat')">＋</button>
      <button class="icon-btn" type="button" aria-label="展开工作区" @click="emit('toggle-rail')">›</button>
    </div>
  </aside>
</template>

<style scoped>
.rail { width: 300px; min-width: 0; flex: none; background: var(--panel); border-right: 1px solid var(--line); display: flex; overflow: visible; transition: width .26s cubic-bezier(.22,.61,.36,1); position: relative; z-index: 3; }
.rail.collapsed { width: 64px; }
.rail.collapsed .rail-inner { opacity: 0; visibility: hidden; pointer-events: none; }
.rail-inner { width: 300px; flex: none; display: flex; flex-direction: column; padding: 0 12px; transition: opacity .15s; }
.rail-compact { position: absolute; inset: 0 auto 0 0; display: none; width: 64px; flex-direction: column; align-items: center; gap: 10px; padding: 14px 0; background: var(--panel); }
.rail.collapsed .rail-compact { display: flex; }
.rail-compact .brand-mark { margin-bottom: 2px; }
.grow { flex: 1; min-width: 0; }
.ic { width: 16px; height: 16px; flex: none; stroke: currentColor; fill: none; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.ic-sm { width: 14px; height: 14px; }
.brand { height: 56px; display: flex; align-items: center; gap: 8px; padding: 0 6px; flex: none; }
.brand-mark { width: 22px; height: 22px; border: 1.5px solid var(--ink); border-radius: 6px; display: grid; place-items: center; flex: none; position: relative; }
.brand-mark::before { content: ''; width: 0; height: 0; border-left: 6px solid var(--ink); border-top: 4px solid transparent; border-bottom: 4px solid transparent; margin-left: 2px; }
.brand-name { font-size: 17px; font-weight: 650; letter-spacing: .04em; white-space: nowrap; }
.brand-badge { font-family: var(--mono); font-size: 9px; line-height: 1; letter-spacing: .12em; color: #fff; background: var(--ink); padding: 4px 5px 3px; border-radius: 3px; margin-top: 2px; white-space: nowrap; }
.icon-btn { width: 28px; height: 28px; min-height: 0; margin: 0; padding: 0; display: grid; place-items: center; border-color: transparent; background: transparent; color: var(--ink-3); font-size: 16px; }
.icon-btn:hover:not(:disabled) { background: #eef0f2; color: var(--ink); }
.new-chat { width: 100%; height: 34px; min-height: 0; margin: 4px 0 18px; flex: none; display: flex; align-items: center; justify-content: center; gap: 7px; border-color: var(--line-strong); border-radius: 8px; background: #fff; font-size: 12.5px; font-weight: 550; }
.new-chat:hover:not(:disabled) { background: #fcfcfd; border-color: #cfd2d6; }
.workspace-head { flex: none; position: relative; z-index: 4; padding-bottom: 2px; }
.rail-scroll { flex: 1; min-height: 0; overflow: auto; padding: 2px 0 12px; overscroll-behavior: contain; }
.section-label { display: flex; align-items: center; gap: 8px; height: 28px; padding: 0 8px; color: var(--ink-4); font-size: 11px; letter-spacing: .04em; }
.section-label .count { font-family: var(--mono); font-size: 10px; color: var(--ink-4); }
.label-plus { width: 22px; height: 22px; min-height: 0; margin: 0; padding: 0; border-color: transparent; background: transparent; color: var(--ink-3); font-size: 14px; }
.label-plus:hover { background: var(--panel-2); color: var(--ink); }
.project-switcher { position: relative; margin: 0 0 4px; }
.project-current { width: 100%; height: 32px; min-height: 0; margin: 0; padding: 0 8px; display: flex; align-items: center; gap: 7px; border-color: transparent; border-radius: 6px; background: transparent; color: var(--ink); font-size: 12.5px; font-weight: 600; text-align: left; }
.project-current:hover { background: #f0f2f4; }
.project-current .name { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.project-current .chev { transition: transform .18s; }
.project-current[aria-expanded='true'] .chev { transform: rotate(180deg); }
.project-menu { position: absolute; z-index: 8; left: 0; right: 0; top: calc(100% + 2px); max-height: 300px; overflow: auto; padding: 4px; background: #fff; border: 1px solid var(--line-strong); border-radius: 8px; box-shadow: 0 12px 36px rgba(15,17,21,.14); }
.project-menu-item { width: 100%; min-height: 30px; margin: 0; padding: 5px 7px; display: flex; align-items: center; justify-content: space-between; gap: 8px; border-color: transparent; border-radius: 5px; background: transparent; color: var(--ink-2); font-size: 11.5px; text-align: left; }
.project-menu-item:hover { background: var(--panel); }
.project-menu-item.active { background: #eceff3; color: var(--ink); font-weight: 600; }
.project-menu .rail-action { margin-top: 2px; }
.chats { list-style: none; margin: 1px 0 10px; padding: 0 0 0 12px; position: relative; }
.chats::before { content: ''; position: absolute; left: 17px; top: 5px; bottom: 5px; width: 1px; background: var(--line); }
.chat-btn { position: relative; width: 100%; height: 31px; min-height: 0; margin: 0; padding: 0 7px 0 17px; display: flex; align-items: center; border-color: transparent; border-radius: 6px; background: transparent; color: var(--ink-2); font-size: 12.5px; text-align: left; }
.chat-btn::before { content: ''; position: absolute; left: 0; top: 14px; width: 4px; height: 4px; border-radius: 50%; background: #c9ccd1; }
.chat-btn:hover:not(:disabled) { background: #f0f2f4; }
.chat-btn.active { background: #eceff3; color: var(--ink); font-weight: 600; }
.chat-btn.active::before { background: var(--blue); }
.chat-btn .t { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rail-empty { display: block; padding: 4px 0 4px 17px; font-size: 11px; color: var(--ink-4); }
.rail-action { width: 100%; height: 28px; min-height: 0; margin: 2px 0 0; padding: 0 8px; border-color: transparent; background: transparent; color: var(--ink-3); font-size: 11px; text-align: left; }
.rail-action:hover:not(:disabled) { background: #f0f2f4; color: var(--ink); }
.rail-action.muted { color: var(--ink-4); }
.asset-block { flex: none; border-top: 1px solid var(--line); padding: 10px 0 8px; margin-top: 4px; }
.asset-title { display: flex; align-items: center; gap: 7px; height: 28px; padding: 0 8px; font-size: 12px; color: var(--ink-3); }
.asset-title strong { color: var(--ink); font-weight: 600; }
.asset-title .n { font-family: var(--mono); font-size: 10px; color: var(--ink-4); }
.asset-title .up { height: 24px; min-height: 0; margin: 0; padding: 0 6px; display: flex; align-items: center; gap: 4px; border-color: transparent; background: transparent; color: var(--ink-3); font-size: 11px; }
.asset-title .up:hover:not(:disabled) { background: #eef0f2; color: var(--ink); }
.asset-mini { display: flex; flex-direction: column; gap: 1px; }
.asset-row { width: 100%; min-height: 40px; margin: 0; padding: 5px 8px; display: flex; align-items: center; gap: 8px; border-color: transparent; border-radius: 6px; background: transparent; text-align: left; }
.asset-row:hover:not(:disabled) { background: #f0f2f4; }
.file-chip { width: 28px; height: 30px; border: 1px solid var(--line-strong); border-radius: 5px; background: #fff; display: grid; place-items: center; color: var(--ink-3); flex: none; position: relative; overflow: hidden; }
.file-chip::after { content: ''; position: absolute; right: -5px; bottom: -5px; width: 12px; height: 12px; border: 1px solid var(--line-strong); transform: rotate(45deg); background: #f7f8f9; }
.asset-main { flex: 1; min-width: 0; }
.asset-name { display: block; font-size: 12px; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; line-height: 1.4; }
.asset-sub { display: block; font-size: 10px; color: var(--ink-4); line-height: 1.4; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 1px; }
.asset-sub.live { color: #22654a; }
.asset-play { width: 24px; height: 24px; display: grid; place-items: center; border-radius: 5px; color: var(--ink-3); flex: none; }
.asset-empty { padding: 8px; font-size: 11px; color: var(--ink-4); line-height: 1.55; }
.all-assets { height: 28px; min-height: 0; margin: 0; padding: 0 8px; display: flex; align-items: center; gap: 6px; border-color: transparent; background: transparent; color: var(--ink-3); font-size: 11px; }
.all-assets:hover:not(:disabled) { color: var(--ink); }
.account { min-height: 54px; flex: none; border-top: 1px solid var(--line); display: flex; align-items: center; gap: 7px; padding: 7px 2px; flex-wrap: wrap; }
.avatar { width: 24px; height: 24px; border-radius: 50%; background: #e7e9ec; display: grid; place-items: center; font-size: 10px; color: var(--ink-2); flex: none; }
.rail-links { display: flex; gap: 2px; flex: 1; }
.rail-links button { height: 24px; min-height: 0; margin: 0; padding: 0 5px; border-color: transparent; background: transparent; color: var(--ink-3); font-size: 10.5px; }
.rail-links button:hover { background: var(--panel-2); color: var(--ink); }
.account :deep(.account-menu) { flex: none; }
.account :deep(.account-menu summary) { min-height: 24px; padding: 4px 6px; font-size: 10.5px; }
@media (max-width: 1280px) { .rail { width: 272px; } .rail-inner { width: 272px; } .rail.collapsed { width: 64px; } .rail.collapsed .rail-inner { width: 272px; } }
@media (max-width: 1180px) { .rail { width: 244px; } .rail-inner { width: 244px; } .rail.collapsed { width: 64px; } .rail.collapsed .rail-inner { width: 244px; } }
</style>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue';
import { NDrawer } from 'naive-ui';
import AssetLibrary, { type Playback } from './AssetLibrary.vue';
import SpeechSearch from './SpeechSearch.vue';
type Asset = { id: string; filename: string; validationStatus: string };
const props = defineProps<{ projectId: string; blocked?: boolean; diagnostics?: boolean }>();
const emit = defineEmits<{ close: []; play: [value: Playback]; items: [items: Asset[]] }>();
const tab = ref<'library' | 'search'>('library');
const count = ref(0);
const drawer = ref<HTMLElement | null>(null);
const closeButton = ref<HTMLButtonElement | null>(null);
const libraryTab = ref<HTMLButtonElement | null>(null), searchTab = ref<HTMLButtonElement | null>(null);
function moveTab(event: KeyboardEvent) {
  if (!['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return;
  event.preventDefault();
  tab.value = event.key === 'Home' ? 'library' : event.key === 'End' ? 'search' : tab.value === 'library' ? 'search' : 'library';
  void nextTick(() => (tab.value === 'library' ? libraryTab.value : searchTab.value)?.focus());
}
function trapFocus(event: KeyboardEvent) {
  if (props.blocked) return;
  if (event.key === 'Escape') { event.preventDefault(); emit('close'); return; }
  if (event.key !== 'Tab' || !drawer.value) return;
  const nodes = [...drawer.value.querySelectorAll<HTMLElement>('button:not(:disabled):not([tabindex="-1"]), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex]:not([tabindex="-1"])')].filter(node => node.offsetParent !== null && node.tabIndex >= 0);
  const first = nodes[0], last = nodes[nodes.length - 1];
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
}
onMounted(() => { document.addEventListener('keydown', trapFocus); void nextTick(() => closeButton.value?.focus()); });
onUnmounted(() => document.removeEventListener('keydown', trapFocus));
</script>

<template>
  <n-drawer :z-index="40" :show="true" placement="left" :width="560" :trap-focus="false" :auto-focus="false" :mask-closable="!props.blocked" :close-on-esc="!props.blocked" :style="{ maxWidth: '100vw' }" @update:show="value => { if (!value && !props.blocked) emit('close'); }">
    <div ref="drawer" class="asset-drawer" role="dialog" aria-modal="true" aria-label="素材库">
      <header class="drawer-head">
        <div class="drawer-title"><strong>素材库</strong><span class="count">{{ count }}</span></div>
        <button ref="closeButton" class="icon-btn" type="button" aria-label="关闭素材库" @click="emit('close')">✕</button>
      </header>
      <div class="drawer-tabs" role="tablist" aria-label="素材库区域" @keydown="moveTab">
        <button id="asset-library-tab" ref="libraryTab" role="tab" type="button" aria-controls="asset-library-panel" :aria-selected="tab === 'library'" :tabindex="tab === 'library' ? 0 : -1" @click="tab = 'library'">素材</button>
        <button id="asset-search-tab" ref="searchTab" role="tab" type="button" aria-controls="asset-search-panel" :aria-selected="tab === 'search'" :tabindex="tab === 'search' ? 0 : -1" @click="tab = 'search'">查找</button>
      </div>
      <div class="drawer-body">
        <div id="asset-library-panel" v-show="tab === 'library'" role="tabpanel" aria-labelledby="asset-library-tab"><AssetLibrary :key="projectId" :project-id="projectId" :diagnostics="diagnostics" @play="emit('play', $event)" @loaded="count = $event" @items="emit('items', $event)" /></div>
        <div id="asset-search-panel" v-show="tab === 'search'" role="tabpanel" aria-labelledby="asset-search-tab"><SpeechSearch :key="projectId" :project-id="projectId" @play="emit('play', $event)" /></div>
      </div>
    </div>
  </n-drawer>
</template>

<style scoped>
.asset-drawer { height: 100%; background: #fff; display: flex; flex-direction: column; }
.drawer-head { height: 56px; flex: none; display: flex; align-items: center; gap: 8px; padding: 0 14px 0 20px; border-bottom: 1px solid var(--line); }
.drawer-title { flex: 1; display: flex; align-items: center; gap: 8px; }
.drawer-title strong { font-size: 14px; font-weight: 600; }
.drawer-title .count { font-family: var(--mono); font-size: 10px; color: var(--ink-4); }
.icon-btn { width: 30px; height: 30px; min-height: 0; margin: 0; padding: 0; border-color: transparent; background: transparent; color: var(--ink-3); font-size: 14px; display: grid; place-items: center; }
.icon-btn:hover { background: var(--panel); color: var(--ink); }
.drawer-tabs { flex: none; display: flex; gap: 4px; padding: 10px 20px 0; border-bottom: 1px solid var(--line); }
.drawer-tabs button { height: 30px; min-height: 0; margin: 0 0 -1px; padding: 0 10px; border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; color: var(--ink-3); font-size: 12px; }
.drawer-tabs button[aria-selected='true'] { color: var(--ink); border-bottom-color: var(--ink); font-weight: 600; }
.drawer-body { flex: 1; min-height: 0; overflow: auto; padding: 16px 20px 24px; }

</style>

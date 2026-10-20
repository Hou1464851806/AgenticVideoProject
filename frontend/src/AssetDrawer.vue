<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue';
import AssetLibrary, { type Playback } from './AssetLibrary.vue';
import SpeechSearch from './SpeechSearch.vue';
type Asset = { id: string; filename: string; validationStatus: string };
const props = defineProps<{ projectId: string; blocked?: boolean; diagnostics?: boolean }>();
const emit = defineEmits<{ close: []; play: [value: Playback]; items: [items: Asset[]] }>();
const tab = ref<'library' | 'search'>('library');
const count = ref(0);
const drawer = ref<HTMLElement | null>(null);
const closeButton = ref<HTMLButtonElement | null>(null);
function onKeydown(event: KeyboardEvent) {
  if (props.blocked) return;
  if (event.key === 'Escape') { event.preventDefault(); emit('close'); return; }
  if (event.key !== 'Tab' || !drawer.value) return;
  const focusable = [...drawer.value.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex]:not([tabindex="-1"])')]
    .filter(node => node.offsetParent !== null);
  if (!focusable.length) return;
  const first = focusable[0]!, last = focusable[focusable.length - 1]!;
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}
onMounted(async () => {
  document.addEventListener('keydown', onKeydown);
  await nextTick();
  closeButton.value?.focus();
});
onUnmounted(() => document.removeEventListener('keydown', onKeydown));
function onItems(items: Asset[]) { emit('items', items); }
</script>

<template>
  <div class="asset-overlay">
    <div class="asset-scrim" @click="emit('close')"></div>
    <aside ref="drawer" class="asset-drawer" role="dialog" aria-modal="true" aria-label="素材库">
      <header class="drawer-head">
        <div class="drawer-title"><strong>素材库</strong><span class="count">{{ count }}</span></div>
        <button ref="closeButton" class="icon-btn" type="button" aria-label="关闭素材库" @click="emit('close')">✕</button>
      </header>
      <div class="drawer-tabs" role="tablist" aria-label="素材库区域">
        <button role="tab" type="button" :aria-selected="tab === 'library'" :tabindex="tab === 'library' ? 0 : -1" @click="tab = 'library'">素材</button>
        <button role="tab" type="button" :aria-selected="tab === 'search'" :tabindex="tab === 'search' ? 0 : -1" @click="tab = 'search'">查找</button>
      </div>
      <div class="drawer-body">
        <AssetLibrary v-show="tab === 'library'" :key="projectId" :project-id="projectId" :diagnostics="diagnostics" @play="emit('play', $event)" @loaded="count = $event" @items="onItems" />
        <SpeechSearch v-show="tab === 'search'" :key="projectId" :project-id="projectId" @play="emit('play', $event)" />
      </div>
    </aside>
  </div>
</template>

<style scoped>
.asset-overlay { position: fixed; inset: 0; z-index: 40; }
.asset-scrim { position: absolute; inset: 0; background: rgba(15, 17, 21, .32); }
.asset-drawer { position: absolute; left: 0; top: 0; bottom: 0; width: min(560px, 100%); background: #fff; border-right: 1px solid var(--line-strong); box-shadow: 20px 0 60px rgba(15, 17, 21, .16); display: flex; flex-direction: column; animation: drawerIn .28s cubic-bezier(.22,.61,.36,1); }
@keyframes drawerIn { from { transform: translateX(-24px); opacity: .6; } to { transform: none; opacity: 1; } }
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
@media (max-width: 640px) { .asset-drawer { width: 100%; } }
</style>

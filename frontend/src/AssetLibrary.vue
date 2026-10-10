<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { request } from './api';
export type Playback = { assetId: string; filename: string; url: string; durationMs: number; expiresAt: string; startMs?: number };
const props = defineProps<{ projectId: string }>();
const emit = defineEmits<{ play: [value: Playback] }>();
const items = ref<Array<{ id: string; filename: string }>>([]);
const error = ref('');
let timer: ReturnType<typeof setInterval> | undefined;
async function refresh() {
  try { items.value = (await request<{ items: typeof items.value }>(`/projects/${props.projectId}/assets`)).items; }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法查询素材'; }
}
async function play(id: string) {
  try { emit('play', await request<Playback>(`/assets/${id}/playback-access`, 'POST', {})); }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法播放素材'; }
}
onMounted(() => { void refresh(); timer = setInterval(refresh, 1000); });
onUnmounted(() => clearInterval(timer));
</script>
<template>
  <section v-if="items.length" class="library">
    <h3>可用素材</h3>
    <button v-for="asset in items" :key="asset.id" @click="play(asset.id)">播放 {{ asset.filename }}</button>
  </section>
  <p v-if="error" role="alert">{{ error }}</p>
</template>
<style scoped>
.library { border-top: 1px solid #dce5de; padding-top: 12px; }
h3 { font-size: 14px; }
button { font-size: 13px; margin-top: 8px; }
</style>

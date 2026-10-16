<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { request } from './api';
import AnalysisStatus from './AnalysisStatus.vue';
const selectedStatus = ref<string | null>(null);
export type Playback = { assetId: string; filename: string; url: string; durationMs: number; expiresAt: string; startMs?: number; endMs?: number };
const props = defineProps<{ projectId: string }>();
const emit = defineEmits<{ play: [value: Playback]; loaded: [count: number] }>();
const items = ref<Array<{ id: string; filename: string; validationStatus: string }>>([]);
const error = ref('');
let timer: ReturnType<typeof setInterval> | undefined;
async function refresh() {
  try { items.value = (await request<{ items: typeof items.value }>(`/projects/${props.projectId}/assets?includePending=true`)).items; emit('loaded', items.value.length); }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法查询素材'; }
}
async function play(id: string) {
  try { emit('play', await request<Playback>(`/assets/${id}/playback-access`, 'POST', {})); }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法播放素材'; }
}
async function analyzeVision(id:string){
  try{await request(`/assets/${id}/vision-analysis`,'POST',{});error.value='已安排画面分析；可在播放器查看独立状态。';}
  catch(failure){error.value=failure instanceof Error?failure.message:'无法安排画面分析';}
}
onMounted(() => { void refresh(); timer = setInterval(refresh, 1000); });
onUnmounted(() => clearInterval(timer));
</script>
<template>
  <section v-if="items.length" class="library">
    <h3>素材状态与操作</h3>
    <div v-for="asset in items" :key="asset.id"><button v-if="asset.validationStatus === 'VALID'" @click="play(asset.id)">播放 {{ asset.filename }}</button><button v-if="asset.validationStatus === 'VALID'" @click="analyzeVision(asset.id)">分析画面 {{asset.filename}}</button><button @click="selectedStatus=asset.id">查看分析状态 {{asset.filename}}</button></div>
    <div v-if="selectedStatus"><button @click="selectedStatus=null">关闭分析状态</button><AnalysisStatus :asset-id="selectedStatus" /></div>
  </section>
  <p v-if="error" role="alert">{{ error }}</p>
</template>
<style scoped>
.library { border-top: 1px solid #dce5de; padding-top: 12px; }
h3 { font-size: 14px; }
button { font-size: 13px; margin-top: 8px; }
</style>

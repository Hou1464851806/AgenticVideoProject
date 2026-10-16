<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { request } from './api';
import { uploadRows } from './uploads';
import UploadPanel from './UploadPanel.vue';
import AnalysisStatus from './AnalysisStatus.vue';
export type Playback = { assetId: string; filename: string; url: string; durationMs: number; expiresAt: string; startMs?: number; endMs?: number };
const props = defineProps<{ projectId: string }>();
const emit = defineEmits<{ play: [value: Playback]; loaded: [count: number] }>();
type Asset = { id: string; filename: string; validationStatus: string };
const items = ref<Asset[]>([]);
const projectUploads = computed(() => uploadRows.filter(row => row.projectId === props.projectId));
const availableAssetIds = computed(() => items.value.filter(asset => asset.validationStatus === 'VALID').map(asset => asset.id));
const visibleAssets = computed(() => items.value.filter(asset => asset.validationStatus === 'VALID' || !projectUploads.value.some(row => row.upload?.assetId === asset.id)));
const selectedStatus = ref<string | null>(null);
const maintenanceAsset = ref('');
const error = ref('');
const loadError = ref('');
const notice = ref('');
const loading = ref(true);
let timer: ReturnType<typeof setInterval> | undefined;
async function refresh() {
  try {
    items.value = (await request<{ items: Asset[] }>(`/projects/${props.projectId}/assets?includePending=true`)).items;
    loadError.value = '';
    emit('loaded', items.value.length);
  } catch (failure) { loadError.value = failure instanceof Error ? failure.message : '无法查询素材'; }
  finally { loading.value = false; }
}
async function play(id: string) {
  error.value = '';
  try { emit('play', await request<Playback>(`/assets/${id}/playback-access`, 'POST', {})); }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法播放素材'; }
}
async function analyzeVision(id: string) {
  error.value = ''; notice.value = '';
  try { await request(`/assets/${id}/vision-analysis`, 'POST', {}); notice.value = '已安排画面分析，可查看素材的分析状态。'; }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法安排画面分析'; }
}
onMounted(() => { void refresh(); timer = setInterval(refresh, 1000); });
onUnmounted(() => clearInterval(timer));
</script>
<template>
  <section class="library" aria-label="素材列表">
    <UploadPanel :project-id="projectId" :available-asset-ids="availableAssetIds" />
    <p v-if="loading" role="status">正在读取素材…</p>
    <p v-else-if="!items.length && !projectUploads.length && !loadError" class="empty">暂无素材。选择视频，开始整理内容。</p>
    <p v-if="loadError" role="alert">{{ loadError }} <button class="text-button" @click="refresh">重新读取素材</button></p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <article v-for="asset in visibleAssets" :key="asset.id" class="asset-row" :aria-label="`素材：${asset.filename}`">
      <div class="asset-summary"><strong>{{ asset.filename }}</strong><p>{{ asset.validationStatus === 'VALID' ? '传输完成，媒体验证通过' : asset.validationStatus === 'INVALID' ? '媒体验证失败，请查看原因' : '媒体验证中，通过后可播放' }}</p></div>
      <div class="asset-actions">
        <button v-if="asset.validationStatus === 'VALID'" :aria-label="`播放 ${asset.filename}`" @click="play(asset.id)">播放</button>
        <button class="text-button" :aria-label="`查看分析状态 ${asset.filename}`" @click="selectedStatus = selectedStatus === asset.id ? null : asset.id">{{ selectedStatus === asset.id ? '收起状态' : '能力与详情' }}</button>
        <details v-if="asset.validationStatus === 'VALID'"><summary>更多操作</summary><p>补充画面分析会产生模型用量；语音与画面能力独立。</p><button :aria-label="`分析画面 ${asset.filename}`" @click="analyzeVision(asset.id)">分析画面</button></details>
      </div>
      <div v-if="selectedStatus === asset.id" class="asset-details"><AnalysisStatus :key="asset.id" :asset-id="asset.id" /></div>
    </article>
    <details v-if="items.length" class="maintenance">
      <summary>诊断与高级维护</summary><p>仅在需要维护检索索引时使用。打开入口不会执行分析或重建。</p>
      <label for="maintenance-asset">维护素材</label><select id="maintenance-asset" v-model="maintenanceAsset"><option value="">选择素材</option><option v-for="asset in items" :key="asset.id" :value="asset.id">{{ asset.filename }}</option></select>
      <AnalysisStatus v-if="maintenanceAsset" :key="`maintenance-${maintenanceAsset}`" :asset-id="maintenanceAsset" maintenance />
    </details>
  </section>
</template>
<style scoped>
h3 { font-size: 18px; }
.asset-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px 24px; border-top: 1px solid var(--line); padding: 20px 0; }
.asset-summary strong { font-weight: 600; }
.asset-summary p, .asset-actions p, .maintenance p { font-size: 13px; color: var(--muted); margin: 8px 0 0; }
.asset-actions { display: flex; align-items: start; flex-wrap: wrap; gap: 8px; }
.asset-actions button { margin: 0; }
.asset-actions details { max-width: 240px; }
summary { cursor: pointer; min-height: 44px; padding: 10px 0; }
.asset-details { grid-column: 1 / -1; background: #f3f5f0; border-radius: 8px; padding: 16px; }
.maintenance { border-top: 1px solid var(--line); margin-top: 24px; padding-top: 12px; }
.empty { padding: 24px; background: #edf3ee; border-radius: 8px; }
@media (max-width: 720px) { .asset-row { grid-template-columns: minmax(0, 1fr); gap: 12px; } }
</style>

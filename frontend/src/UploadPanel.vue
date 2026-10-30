<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { NProgress } from 'naive-ui';
import { enqueueFiles, loadUploads, uploadRows, pauseUpload, continueUpload, reselectFile, cancelUpload } from './uploads';
const props = defineProps<{ projectId: string; availableAssetIds: string[] }>();
const error = ref('');
const fileInput = ref<HTMLInputElement | null>(null);
const dragOver = ref(false);
const rows = computed(() => uploadRows.filter(row => row.projectId === props.projectId && !(row.upload?.status === 'COMPLETED' && row.upload.assetId && props.availableAssetIds.includes(row.upload.assetId))));
watch(() => props.projectId, async id => {
  try { await loadUploads(id); } catch (failure) { error.value = failure instanceof Error ? failure.message : '无法查询上传'; }
}, { immediate: true });
let timer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  timer = setInterval(() => {
    if (rows.value.some(row => row.upload && (row.upload.status!=='COMPLETED' || row.upload.validationStatus === 'VALIDATING' || row.upload.cleanupStatus && row.upload.cleanupStatus !== 'CLEANED'))) {
      void loadUploads(props.projectId).catch(failure => { error.value = failure instanceof Error ? failure.message : '无法更新验证状态'; });
    }
  }, 1000);
});
onUnmounted(() => clearInterval(timer));
function select(event: Event) {
  const input = event.target as HTMLInputElement;
  enqueueFiles(props.projectId, Array.from(input.files ?? []));
  input.value = '';
}
function openPicker() { fileInput.value?.click(); }
function drop(event: DragEvent) {
  dragOver.value = false;
  const files = Array.from(event.dataTransfer?.files ?? []).filter(file => file.type.startsWith('video/'));
  if (files.length) enqueueFiles(props.projectId, files);
}
function reselect(event: Event, key: string) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file) reselectFile(key, file);
  input.value = '';
}
</script>
<template>
  <section class="upload-panel" aria-label="导入视频">
    <button type="button" class="dropzone" :class="{ 'is-over': dragOver }" @click="openPicker" @dragover.prevent="dragOver = true" @dragenter.prevent="dragOver = true" @dragleave="dragOver = false" @drop.prevent="drop">
      <span class="up-ic" aria-hidden="true"><svg viewBox="0 0 16 16"><path d="M8 10.8V3.6M5.4 6.1 8 3.5l2.6 2.6M3.4 11.2v1.2h9.2v-1.2"/></svg></span>
      <b>拖入视频，或选择文件</b>
      <span>MP4 / MOV · 可同时选择多个 · 上传后验证媒体</span>
    </button>
    <input ref="fileInput" class="sr-only" tabindex="-1" type="file" multiple accept="video/*" aria-label="选择视频" @change="select">
    <p v-if="error" role="alert">{{ error }}</p>
    <article v-for="row in rows" :key="row.key" :aria-label="`上传：${row.filename}`">
      <div class="upload-title"><strong>{{ row.filename }}</strong><span>上传记录</span></div>
      <p>{{ row.state }}</p>
      <button v-if="row.upload?.status !== 'COMPLETED' && row.upload?.status !== 'CANCELLED' && row.upload?.status !== 'EXPIRED'" type="button" :aria-label="`${row.filename} 取消上传`" @click="cancelUpload(row.key)">取消上传</button>
      <button v-if="!row.cancelRequested && row.hasFile && !row.paused && row.upload?.status !== 'COMPLETED'" type="button" :aria-label="`${row.filename} 暂停上传`" @click="pauseUpload(row.key)">暂停</button>
      <button v-if="!row.cancelRequested && row.hasFile && (row.paused || row.state === '上传失败') && !row.running" type="button" :aria-label="`${row.filename} 继续上传`" @click="continueUpload(row.key)">继续上传</button>
      <template v-if="!row.cancelRequested && row.upload && row.upload.status !== 'COMPLETED' && !row.running">
        <p v-if="!row.hasFile" class="upload-hint">刷新后浏览器无法保留原文件，请重选同一个文件以继续已确认的分片。</p>
        <label :for="`resume-file-${row.key}`">{{ row.filename }} 重选原文件继续</label>
        <input :id="`resume-file-${row.key}`" type="file" accept="video/*" @change="reselect($event, row.key)">
        <small>可恢复至 {{ new Date(row.upload.expiresAt).toLocaleString() }}</small>
      </template>
      <n-progress role="progressbar" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="Math.floor(row.confirmed / Math.max(1, row.size) * 100)" type="line" :percentage="Math.floor(row.confirmed / Math.max(1, row.size) * 100)" :show-indicator="false" :aria-label="`${row.filename} 上传进度`" />
      <small>{{ Math.floor(row.confirmed / Math.max(1, row.size) * 100) }}% 已确认</small>
      <small v-if="row.state === '正在计算文件校验值'">校验 {{ Math.floor(row.hashing / Math.max(1, row.size) * 100) }}%</small>
      <small v-if="row.upload?.status === 'CANCELLED' || row.upload?.status === 'EXPIRED'">{{ row.upload.cleanupStatus==='CLEANED'?'残留清理完成，占用已释放':'上传已停止，残留正在清理，字节占用尚未释放' }}</small>
      <small v-else-if="row.upload?.cleanupStatus === 'CLEANED'">失败对象已清理</small>
      <small v-else-if="row.upload?.cleanupStatus">失败对象正在清理，占用尚未释放</small>
      <p v-if="row.error" role="alert">{{ row.error }}</p>
    </article>
  </section>
</template>
<style scoped>
.upload-panel { margin-bottom: 14px; padding-bottom: 12px; border-bottom: 1px solid var(--line); }
.dropzone { width: 100%; height: 96px; min-height: 96px; margin: 0; padding: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; border: 1px dashed #cfd3d8; border-radius: 10px; background: #fcfcfd; color: var(--ink-3); text-align: center; transition: border-color .16s, background .16s; }
.dropzone:hover, .dropzone.is-over { border-color: #9fb4df; background: #f7faff; }
.dropzone .up-ic { width: 26px; height: 26px; margin-bottom: 2px; display: grid; place-items: center; border: 1px solid var(--line-strong); border-radius: 7px; background: #fff; color: var(--ink-2); }
.dropzone .up-ic svg { width: 14px; height: 14px; fill: none; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.dropzone b { font-size: 12px; font-weight: 550; color: var(--ink-2); }
.dropzone > span:last-child { font-size: 10px; color: var(--ink-4); }
.upload-title { display: flex; justify-content: space-between; gap: 12px; }
.upload-title span, .upload-hint { color: var(--ink-4); font-size: 10.5px; line-height: 1.55; }
.upload-hint { margin: 4px 0 8px; }
input { padding: 6px 0; border: 0; font-size: 11px; }
article { border: 1px solid var(--line); border-radius: 8px; padding: 12px; margin-top: 10px; overflow-wrap: anywhere; }
article strong { font-size: 12px; }
article p { font-size: 12px; color: var(--ink-3); line-height: 1.55; margin: 4px 0; }
article button { min-height: 30px; padding: 4px 8px; font-size: 12px; margin: 3px 3px 0 0; }
progress { width: 100%; accent-color: var(--blue); height: 4px; }
small { display: block; margin-top: 4px; color: var(--ink-4); font-size: 12px; }
label { margin-top: 4px; font-size: 11px; }
</style>

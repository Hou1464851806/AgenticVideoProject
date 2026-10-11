<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { enqueueFiles, loadUploads, uploadRows, pauseUpload, continueUpload, reselectFile, cancelUpload } from './uploads';
const props = defineProps<{ projectId: string }>();
const error = ref('');
const rows = computed(() => uploadRows.filter(row => row.projectId === props.projectId));
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
function reselect(event: Event, key: string) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file) reselectFile(key, file);
  input.value = '';
}
</script>
<template>
  <div class="upload-panel">
    <label :for="`video-files-${projectId}`">选择视频</label>
    <input :id="`video-files-${projectId}`" type="file" multiple accept="video/*" @change="select">
    <p v-if="error" role="alert">{{ error }}</p>
    <article v-for="row in rows" :key="row.key">
      <strong>{{ row.filename }}</strong>
      <p>{{ row.state }}</p>
      <button v-if="row.upload?.status !== 'COMPLETED' && row.upload?.status !== 'CANCELLED' && row.upload?.status !== 'EXPIRED'" type="button" :aria-label="`${row.filename} 取消上传`" @click="cancelUpload(row.key)">取消上传</button>
      <button v-if="!row.cancelRequested && row.hasFile && !row.paused && row.upload?.status !== 'COMPLETED'" type="button" :aria-label="`${row.filename} 暂停上传`" @click="pauseUpload(row.key)">暂停</button>
      <button v-if="!row.cancelRequested && row.hasFile && (row.paused || row.state === '上传失败') && !row.running" type="button" :aria-label="`${row.filename} 继续上传`" @click="continueUpload(row.key)">继续上传</button>
      <template v-if="!row.cancelRequested && row.upload && row.upload.status !== 'COMPLETED' && !row.running">
        <label :for="`resume-file-${row.key}`">{{ row.filename }} 重选原文件继续</label>
        <input :id="`resume-file-${row.key}`" type="file" accept="video/*" @change="reselect($event, row.key)">
        <small>可恢复至 {{ new Date(row.upload.expiresAt).toLocaleString() }}</small>
      </template>
      <progress :value="row.confirmed" :max="row.size" :aria-label="`${row.filename} 上传进度`"></progress>
      <small>{{ Math.floor(row.confirmed / Math.max(1, row.size) * 100) }}% 已确认</small>
      <small v-if="row.state === '正在计算文件校验值'">校验 {{ Math.floor(row.hashing / Math.max(1, row.size) * 100) }}%</small>
      <small v-if="row.upload?.status === 'CANCELLED' || row.upload?.status === 'EXPIRED'">{{ row.upload.cleanupStatus==='CLEANED'?'残留清理完成，占用已释放':'残留正在清理，字节占用尚未释放' }}</small>
      <small v-else-if="row.upload?.cleanupStatus === 'CLEANED'">失败对象已清理</small>
      <small v-else-if="row.upload?.cleanupStatus">失败对象正在清理，占用尚未释放</small>
      <p v-if="row.error" role="alert">{{ row.error }}</p>
    </article>
    <p v-if="rows.length === 0" class="empty">暂无素材。</p>
  </div>
</template>
<style scoped>
input { padding: 10px 0; border: 0; font-size: 13px; }
article { border-top: 1px solid #dce5de; padding: 18px 0; overflow-wrap: anywhere; }
article strong { font-size: 14px; }
article p { font-size: 13px; color: #69766e; }
progress { width: 100%; accent-color: #245c45; }
small { display: block; margin-top: 6px; color: #69766e; }
</style>

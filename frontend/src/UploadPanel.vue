<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { enqueueFiles, loadUploads, uploadRows } from './uploads';
const props = defineProps<{ projectId: string }>();
const error = ref('');
const rows = computed(() => uploadRows.filter(row => row.projectId === props.projectId));
watch(() => props.projectId, async id => {
  try { await loadUploads(id); } catch (failure) { error.value = failure instanceof Error ? failure.message : '无法查询上传'; }
}, { immediate: true });
function select(event: Event) {
  const input = event.target as HTMLInputElement;
  enqueueFiles(props.projectId, Array.from(input.files ?? []));
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
      <progress :value="row.confirmed" :max="row.size" :aria-label="`${row.filename} 上传进度`"></progress>
      <small>{{ Math.floor(row.confirmed / Math.max(1, row.size) * 100) }}% 已确认</small>
      <small v-if="row.state === '正在计算文件校验值'">校验 {{ Math.floor(row.hashing / Math.max(1, row.size) * 100) }}%</small>
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

<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { request } from './api';
const props = defineProps<{ artifactId: string }>();
const emit = defineEmits<{ changed: [] }>();
const url = ref(''), error = ref(''), busy = ref(false), video = ref<HTMLVideoElement | null>(null);
let alive = true, renewed = false;
let restore: { seconds: number; playing: boolean } | undefined;
async function access(renew = false) {
  if (busy.value) return;
  if (renew && renewed) { error.value = '视频无法播放，请点击重新获取播放地址'; return; }
  renewed = renew; busy.value = true; error.value = '';
  const position = video.value?.currentTime ?? 0, playing = video.value ? !video.value.paused : false;
  try {
    const result = await request<{ url: string }>(`/artifacts/${props.artifactId}/playback-access`, 'POST', {});
    if (!alive) return;
    restore = renew ? { seconds: position, playing } : undefined; url.value = result.url;
  } catch (failure) { if (alive) { error.value = failure instanceof Error ? failure.message : '无法获取播放地址'; emit('changed'); } }
  finally { busy.value = false; }
}
function loaded() {
  if (restore && video.value) { video.value.currentTime = restore.seconds; if (restore.playing) void video.value.play(); restore = undefined; }
}
onUnmounted(() => { alive = false; });
</script>
<template>
  <div>
    <button type="button" class="secondary" :disabled="busy" @click="access()">{{ url ? '重新获取播放地址' : '播放草稿预览' }}</button>
    <p v-if="error" role="alert">{{ error }}</p>
    <video v-if="url" ref="video" :src="url" controls preload="metadata" aria-label="聊天草稿预览播放器" @error="access(true)" @loadedmetadata="loaded" />
  </div>
</template>
<style scoped>video { width: 100%; max-height: 360px; background: var(--ink); }</style>

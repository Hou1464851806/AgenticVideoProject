<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { request } from './api';
import type { Playback } from './AssetLibrary.vue';
const props = defineProps<{ playback: Playback }>();
type Stream = { codec_type: string; codec_name: string; width?: number; height?: number; avg_frame_rate?: string; r_frame_rate?: string };
const information = ref<{ mediaInfo: { streams: Stream[] }; timeMapping: { hasAudio: boolean } } | null>(null);
const error = ref('');
const stream = computed(() => information.value?.mediaInfo.streams.find(value => value.codec_type === 'video'));
const codec = computed(() => stream.value?.codec_name === 'h264' ? 'H.264' : stream.value?.codec_name.toUpperCase());
watch(() => props.playback.assetId, async id => {
  try { information.value = await request(`/assets/${id}`); }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法查询媒体信息'; }
}, { immediate: true });
const video = ref<HTMLVideoElement | null>(null);
const source = ref(props.playback.url);
let refreshed = false;
let restore: { seconds: number; playing: boolean } | undefined;
watch(() => props.playback, playback => {
  source.value = playback.url;
  refreshed = false;
  restore = undefined;
  error.value = '';
});
async function refreshAccess() {
  if (refreshed) { error.value = '视频无法播放，请重新选择素材或稍后重试'; return; }
  refreshed = true;
  const assetId = props.playback.assetId;
  const position = video.value?.currentTime ?? 0;
  const playing = video.value ? !video.value.paused : false;
  try {
    const access = await request<Playback>(`/assets/${assetId}/playback-access`, 'POST', {});
    if (props.playback.assetId !== assetId) return;
    restore = { seconds: position, playing };
    source.value = access.url;
  } catch (failure) {
    if (props.playback.assetId === assetId) error.value = failure instanceof Error ? failure.message : '无法续期播放权限';
  }
}
function loaded() {
  if (restore && video.value) {
    video.value.currentTime = restore.seconds;
    if (restore.playing) void video.value.play();
    restore = undefined;
  }
}
const seconds = ref('0');
function seek() {
  const requested = Number(seconds.value);
  if (!video.value || !Number.isFinite(requested)) return;
  video.value.currentTime = Math.max(0, Math.min(video.value.duration || 0, requested));
  void video.value.play();
}
</script>
<template>
  <div>
    <p>{{ playback.filename }} · {{ (playback.durationMs / 1000).toFixed(2) }} 秒</p>
    <dl v-if="stream" class="media-information">
      <dt>画面</dt><dd>{{ stream.width }} × {{ stream.height }}</dd>
      <dt>编码</dt><dd>{{ codec }}</dd>
      <dt>声音</dt><dd>{{ information?.timeMapping.hasAudio ? '有音轨' : '无音轨' }}</dd>
      <dt v-if="stream.r_frame_rate !== stream.avg_frame_rate">帧率</dt><dd v-if="stream.r_frame_rate !== stream.avg_frame_rate">可变帧率</dd>
    </dl>
    <p v-if="error" role="alert">{{ error }}</p>
    <video ref="video" :src="source" controls preload="metadata" aria-label="视频播放器"
      @error="refreshAccess" @loadedmetadata="loaded" @loadeddata="refreshed = false"></video>
    <form @submit.prevent="seek"><label for="seek-seconds">定位秒数</label><input id="seek-seconds" v-model="seconds" type="number" min="0" step="0.1"><button type="submit">定位播放</button></form>
  </div>
</template>
<style scoped>
.media-information { display: grid; grid-template-columns: auto 1fr; gap: 6px 12px; font-size: 12px; color: #69766e; }
dd { margin: 0; }
video { width: 100%; max-height: 300px; background: #18271f; border-radius: 10px; }
p { font-size: 13px; overflow-wrap: anywhere; }
</style>

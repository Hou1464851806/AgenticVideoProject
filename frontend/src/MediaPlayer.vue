<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { request } from './api';
import AnalysisStatus from './AnalysisStatus.vue';
import type { Playback } from './AssetLibrary.vue';
const props = defineProps<{ playback: Playback; returnLabel?: string }>();
const emit = defineEmits<{ close: [] }>();
const detailsOpen = ref(false);
type Stream = { codec_type: string; codec_name: string; width?: number; height?: number; avg_frame_rate?: string; r_frame_rate?: string };
const information = ref<{ mediaInfo: { streams: Stream[] }; timeMapping: { hasAudio: boolean }; analysis?: { stages: Record<string, string>; errors: Record<string,{ message: string }> } } | null>(null);
const error = ref('');
const stream = computed(() => information.value?.mediaInfo.streams.find(value => value.codec_type === 'video'));
const codec = computed(() => stream.value?.codec_name === 'h264' ? 'H.264' : stream.value?.codec_name.toUpperCase());
type Observation = { id: string; text: string; startMs: number; endMs: number };
const evidence = ref<{ observations: Observation[]; units: unknown[]; summary: { text?: string } } | null>(null);
async function refreshInformation(id: string) {
  try {
    const [details,observations] = await Promise.all([
      request<NonNullable<typeof information.value>>(`/assets/${id}`),request<NonNullable<typeof evidence.value>>(`/assets/${id}/evidence`),
    ]);
    if(props.playback.assetId===id) { information.value=details;evidence.value=observations; }
  }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法查询媒体信息'; }
}
watch(() => props.playback.assetId, id => { information.value=null;evidence.value=null;void refreshInformation(id); }, { immediate: true });
let informationTimer: ReturnType<typeof setInterval> | undefined;
onMounted(()=> { informationTimer=setInterval(()=> {
  if(Object.values(information.value?.analysis?.stages??{}).some(state=>['PENDING','RUNNING'].includes(state))) void refreshInformation(props.playback.assetId);
},1000); });
onUnmounted(()=>clearInterval(informationTimer));
const video = ref<HTMLVideoElement | null>(null);
const seconds = ref('0');
const source = ref(props.playback.url);
let refreshed = false;
let initialSeconds = props.playback.startMs === undefined ? undefined : props.playback.startMs / 1000;
let restore: { seconds: number; playing: boolean } | undefined;
watch(() => props.playback, playback => {
  const sameSource = source.value === playback.url;
  source.value = playback.url;
  initialSeconds = playback.startMs === undefined ? undefined : playback.startMs / 1000;
  refreshed = false;
  restore = undefined;
  error.value = '';
  if (sameSource && (video.value?.readyState ?? 0) >= 1) applyInitialPosition();
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
function applyInitialPosition() {
  if (initialSeconds !== undefined && video.value) {
    const position = Math.max(0, Math.min(video.value.duration, initialSeconds));
    video.value.currentTime = position;
    seconds.value = String(position);
    initialSeconds = undefined;
    void video.value.play().catch(failure => { error.value = failure instanceof Error ? failure.message : '请点击视频播放按钮开始播放'; });
  }
}
function loaded() {
  if (initialSeconds !== undefined) { restore = undefined; applyInitialPosition(); return; }
  if (restore && video.value) {
    video.value.currentTime = restore.seconds;
    if (restore.playing) void video.value.play();
    restore = undefined;
  }
}
function seek() {
  const requested = Number(seconds.value);
  if (!video.value || !Number.isFinite(requested)) return;
  video.value.currentTime = Math.max(0, Math.min(video.value.duration || 0, requested));
  void video.value.play();
}
function inspectSpeech(observation: Observation) { seconds.value=String(observation.startMs/1000);seek(); }
function enforceClipEnd() {
  if (props.playback.endMs !== undefined && video.value && video.value.currentTime >= props.playback.endMs/1000) {
    video.value.pause();
    if (video.value.currentTime > props.playback.endMs/1000) video.value.currentTime = props.playback.endMs/1000;
  }
}
</script>
<template>
  <section class="source-player" aria-label="原片回看" tabindex="-1">
    <div class="player-heading"><div><p class="eyebrow">原始素材</p><h3>{{ playback.filename }}</h3><p>{{ (playback.durationMs / 1000).toFixed(2) }} 秒</p></div><button class="text-button" @click="emit('close')">{{ returnLabel ?? '关闭原片回看' }}</button></div>
    <video ref="video" :src="source" controls preload="metadata" aria-label="视频播放器"
      @error="refreshAccess" @loadedmetadata="loaded" @loadeddata="refreshed = false" @timeupdate="enforceClipEnd"></video>
    <p v-if="error" role="alert">{{ error }}</p>
    <details @toggle="detailsOpen = ($event.target as HTMLDetailsElement).open"><summary>媒体详情与精确定位</summary>
      <dl v-if="stream" class="media-information">
        <dt>画面</dt><dd>{{ stream.width }} × {{ stream.height }}</dd>
        <dt>编码</dt><dd>{{ codec }}</dd>
        <dt>声音</dt><dd>{{ information?.timeMapping.hasAudio ? '有音轨' : '无音轨' }}</dd>
        <dt v-if="stream.r_frame_rate !== stream.avg_frame_rate">帧率</dt><dd v-if="stream.r_frame_rate !== stream.avg_frame_rate">可变帧率</dd>
      </dl>
      <form @submit.prevent="seek"><label for="seek-seconds">定位秒数</label><input id="seek-seconds" v-model="seconds" type="number" min="0" step="0.1"><button type="submit">定位播放</button></form>
      <AnalysisStatus v-if="detailsOpen" :asset-id="playback.assetId" />
    </details>
    <section v-if="evidence?.observations.length" class="speech-evidence">
      <h3>语音证据</h3>
      <p v-if="evidence.summary.text">原文提要（用于导航）：{{ evidence.summary.text }}</p>
      <button v-for="observation in evidence.observations" :key="observation.id" type="button" :aria-label="`核查语音：${observation.text}`" @click="inspectSpeech(observation)">
        {{ (observation.startMs/1000).toFixed(2) }}–{{ (observation.endMs/1000).toFixed(2) }} 秒 · {{ observation.text }}
      </button>
      <p>模型时间戳供核查，剪辑边界仍需回看原视频。</p>
    </section>
  </section>
</template>
<style scoped>
.source-player { scroll-margin-top: 96px; }
.player-heading { display: flex; align-items: start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.player-heading h3 { margin: 4px 0; font-size: 20px; }
.player-heading p { margin: 0; }
.player-heading button { margin: 0; flex-shrink: 0; }
.media-information { display: grid; grid-template-columns: auto 1fr; gap: 6px 12px; font-size: 13px; color: var(--muted); }
dd { margin: 0; }
video { width: 100%; max-height: min(60vh, 560px); background: #18271f; border-radius: 8px; }
p { font-size: 13px; overflow-wrap: anywhere; }
summary { cursor: pointer; padding: 16px 0; min-height: 44px; }
form { max-width: 400px; }
.speech-evidence { border-top: 1px solid var(--line); margin-top: 24px; }
@media (max-width: 720px) { .player-heading { flex-direction: column; gap: 8px; } }
</style>

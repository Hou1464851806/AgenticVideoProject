<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { request } from './api';
import type { Playback } from './AssetLibrary.vue';
const props = defineProps<{ playback: Playback }>();
type Stream = { codec_type: string; codec_name: string; width?: number; height?: number; avg_frame_rate?: string; r_frame_rate?: string };
const information = ref<{ mediaInfo: { streams: Stream[] }; timeMapping: { hasAudio: boolean }; analysis?: { stages: Record<string, string>; errors: Record<string,{ message: string }> } } | null>(null);
const error = ref('');
const stream = computed(() => information.value?.mediaInfo.streams.find(value => value.codec_type === 'video'));
const codec = computed(() => stream.value?.codec_name === 'h264' ? 'H.264' : stream.value?.codec_name.toUpperCase());
const stageNames: Record<string,string> = { MEDIA:'媒体',ASR:'语音',INDEX:'索引' };
const stageStates: Record<string,string> = { SUCCEEDED:'已就绪',NOT_APPLICABLE:'不适用',NOT_STARTED:'尚未开始',PENDING:'等待执行',RUNNING:'正在分析',FAILED:'失败',UNVERIFIED:'未验证',CANCELLED:'已取消' };
type Observation = { id: string; text: string; startMs: number; endMs: number };
const evidence = ref<{ observations: Observation[]; summary: { text?: string } } | null>(null);
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
  if(['PENDING','RUNNING'].includes(information.value?.analysis?.stages.ASR??'')) void refreshInformation(props.playback.assetId);
},1000); });
onUnmounted(()=>clearInterval(informationTimer));
const video = ref<HTMLVideoElement | null>(null);
const source = ref(props.playback.url);
let refreshed = false;
let initialSeconds = props.playback.startMs === undefined ? undefined : props.playback.startMs / 1000;
let restore: { seconds: number; playing: boolean } | undefined;
watch(() => props.playback, playback => {
  source.value = playback.url;
  initialSeconds = playback.startMs === undefined ? undefined : playback.startMs / 1000;
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
  if (!restore && initialSeconds !== undefined && video.value) {
    video.value.currentTime = Math.max(0, Math.min(video.value.duration, initialSeconds));
    initialSeconds = undefined;
    void video.value.play().catch(() => {});
  }
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
function inspectSpeech(observation: Observation) { seconds.value=String(observation.startMs/1000);seek(); }
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
    <p v-if="information?.analysis?.stages.ASR === 'NOT_APPLICABLE'">无音轨，不能进行语音分析</p>
    <p v-for="(name,key) in stageNames" :key="key">{{ name }}：{{ stageStates[information?.analysis?.stages[key] ?? 'PENDING'] ?? '状态待核对' }}</p>
    <p v-for="(failure,key) in information?.analysis?.errors" :key="`error-${key}`" role="alert">{{ failure.message }}</p>
    <video ref="video" :src="source" controls preload="metadata" aria-label="视频播放器"
      @error="refreshAccess" @loadedmetadata="loaded" @loadeddata="refreshed = false"></video>
    <form @submit.prevent="seek"><label for="seek-seconds">定位秒数</label><input id="seek-seconds" v-model="seconds" type="number" min="0" step="0.1"><button type="submit">定位播放</button></form>
    <section v-if="evidence?.observations.length">
      <h3>语音证据</h3>
      <p v-if="evidence.summary.text">原文提要（用于导航）：{{ evidence.summary.text }}</p>
      <button v-for="observation in evidence.observations" :key="observation.id" type="button" :aria-label="`核查语音：${observation.text}`" @click="inspectSpeech(observation)">
        {{ (observation.startMs/1000).toFixed(2) }}–{{ (observation.endMs/1000).toFixed(2) }} 秒 · {{ observation.text }}
      </button>
      <p>模型时间戳供核查，剪辑边界仍需回看原视频。</p>
    </section>
  </div>
</template>
<style scoped>
.media-information { display: grid; grid-template-columns: auto 1fr; gap: 6px 12px; font-size: 12px; color: #69766e; }
dd { margin: 0; }
video { width: 100%; max-height: 300px; background: #18271f; border-radius: 10px; }
p { font-size: 13px; overflow-wrap: anywhere; }
</style>

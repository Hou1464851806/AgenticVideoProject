<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { NInput, NButton } from 'naive-ui';
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
const evidenceLoading = ref(true), evidenceError = ref('');
const query = ref(''), page = ref(1), selected = ref<string | null>(null);
const expanded = ref<Set<string>>(new Set()), overflowing = ref<Set<string>>(new Set());
const filtered = computed(() => {
  const term = query.value.trim().toLocaleLowerCase();
  return (evidence.value?.observations ?? []).filter(item => item.text.toLocaleLowerCase().includes(term)).slice().sort((a, b) => a.startMs - b.startMs);
});
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / 20)));
const visible = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20));
watch(query, () => { page.value = 1; });
watch(pageCount, count => { page.value = Math.min(page.value, count); });
const vOverflow = {
  mounted(el: HTMLElement, binding: { value: string }) { measure(el, binding.value); },
  updated(el: HTMLElement, binding: { value: string }) { measure(el, binding.value); },
};
function measure(el: HTMLElement, id: string) {
  if (expanded.value.has(id)) return;
  const next = new Set(overflowing.value);
  if (el.scrollHeight > el.clientHeight + 1) next.add(id); else next.delete(id);
  if (next.has(id) !== overflowing.value.has(id)) overflowing.value = next;
}
function toggleText(id: string) {
  const next = new Set(expanded.value);
  if (next.has(id)) next.delete(id); else next.add(id);
  expanded.value = next;
}
function clock(ms: number) {
  const total = Math.floor(ms / 1000), hours = Math.floor(total / 3600);
  const minutes = Math.floor(total / 60) % 60, secs = total % 60;
  return `${hours ? `${hours}:` : ''}${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}
async function refreshInformation(id: string) {
  evidenceError.value = '';
  if (!evidence.value) evidenceLoading.value = true;
  const results = await Promise.allSettled([
    request<NonNullable<typeof information.value>>(`/assets/${id}`),
    request<NonNullable<typeof evidence.value>>(`/assets/${id}/evidence`),
  ]);
  if (props.playback.assetId !== id) return;
  if (results[0].status === 'fulfilled') information.value = results[0].value;
  else error.value = results[0].reason instanceof Error ? results[0].reason.message : '无法查询媒体信息';
  if (results[1].status === 'fulfilled') evidence.value = results[1].value;
  else evidenceError.value = results[1].reason instanceof Error ? results[1].reason.message : '无法查询语音证据';
  evidenceLoading.value = false;
}
watch(() => props.playback.assetId, id => {
  information.value = null; evidence.value = null; query.value = ''; page.value = 1;
  selected.value = null; expanded.value = new Set(); overflowing.value = new Set();
  void refreshInformation(id);
}, { immediate: true });
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
function inspectSpeech(observation: Observation) { selected.value = observation.id; seconds.value=String(observation.startMs/1000);seek(); }
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
    <div class="player-layout">
      <div class="player-column">
        <video ref="video" :src="source" controls preload="metadata" aria-label="视频播放器"
          @error="refreshAccess" @loadedmetadata="loaded" @loadeddata="refreshed = false" @timeupdate="enforceClipEnd"></video>
        <p v-if="error" role="alert">{{ error }}</p>
        <details @toggle="detailsOpen = ($event.target as HTMLDetailsElement).open"><summary>媒体详情与精确定位</summary>
          <dl v-if="stream" class="media-information">
            <dt>画面</dt><dd>{{ stream.width }} × {{ stream.height }}</dd>
            <dt>编码</dt><dd>{{ codec }}</dd><dt>声音</dt><dd>{{ information?.timeMapping.hasAudio ? '有音轨' : '无音轨' }}</dd>
            <dt v-if="stream.r_frame_rate !== stream.avg_frame_rate">帧率</dt><dd v-if="stream.r_frame_rate !== stream.avg_frame_rate">可变帧率</dd>
          </dl>
          <form @submit.prevent="seek"><label for="seek-seconds">定位秒数</label><input id="seek-seconds" v-model="seconds" type="number" min="0" step="0.1"><button type="submit">定位播放</button></form>
          <AnalysisStatus v-if="detailsOpen" :asset-id="playback.assetId" />
        </details>
      </div>
      <section class="speech-evidence" aria-label="语音证据">
        <header><h3>语音证据</h3><span v-if="evidence">共 {{ evidence.observations.length }} 条<span v-if="query.trim()"> · 匹配 {{ filtered.length }} 条</span></span></header>
        <p v-if="evidenceLoading" role="status">正在读取语音证据…</p>
        <p v-if="evidenceError" role="alert">{{ evidenceError }} <button type="button" class="text-button" @click="refreshInformation(playback.assetId)">重试</button></p>
        <template v-if="evidence">
          <details v-if="evidence.summary.text" class="evidence-summary"><summary>原文提要（用于导航）</summary><p>{{ evidence.summary.text }}</p></details>
          <label for="evidence-search">筛选原文</label>
          <n-input :input-props="{ id: 'evidence-search' }" v-model:value="query" placeholder="输入关键词" clearable />
          <p v-if="!evidence.observations.length">暂无语音证据。</p>
          <p v-else-if="!filtered.length">没有匹配的语音证据。</p>
          <div v-else class="evidence-list" aria-label="证据列表">
            <article v-for="observation in visible" :key="observation.id" class="evidence-row" :class="{ selected: selected === observation.id }">
              <time class="evidence-time" :title="`${(observation.startMs / 1000).toFixed(3)}–${(observation.endMs / 1000).toFixed(3)} 秒`">{{ clock(observation.startMs) }}–{{ clock(observation.endMs) }}</time>
              <div class="evidence-content"><p v-overflow="observation.id" :class="{ clamped: !expanded.has(observation.id) }">{{ observation.text }}</p><button v-if="overflowing.has(observation.id) || expanded.has(observation.id)" class="text-button expand" type="button" :aria-expanded="expanded.has(observation.id)" @click="toggleText(observation.id)">{{ expanded.has(observation.id) ? '收起全文' : '展开全文' }}</button></div>
              <n-button size="small" :aria-label="`核查语音：${observation.text}`" @click="inspectSpeech(observation)">定位播放</n-button>
            </article>
          </div>
          <nav v-if="pageCount > 1" class="pagination" aria-label="证据分页"><n-button size="small" :disabled="page === 1" @click="page--">上一页</n-button><span>第 {{ page }} / {{ pageCount }} 页</span><n-button size="small" :disabled="page === pageCount" @click="page++">下一页</n-button></nav>
          <p class="evidence-note">模型时间戳供核查，剪辑边界仍需回看原视频。</p>
        </template>
      </section>
    </div>
  </section>
</template>
<style scoped>
.source-player { scroll-margin-top: 96px; }
.player-heading { display: flex; align-items: start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.player-heading > div { min-width: 0; }
.player-heading h3 { margin: 4px 0; overflow-wrap: anywhere; }
.player-heading p, .evidence-content p { margin: 0; }
.player-heading button { margin: 0; flex-shrink: 0; }
.player-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(320px, .85fr); gap: 20px; align-items: start; }
.player-column { min-width: 0; }
.media-information { display: grid; grid-template-columns: auto 1fr; gap: 6px 12px; font-size: 13px; color: var(--muted); }
dd { margin: 0; }
video { width: 100%; max-height: min(55vh, 520px); background: var(--ink); border-radius: 8px; }
p { font-size: 13px; overflow-wrap: anywhere; }
summary { cursor: pointer; padding: 12px 0; min-height: 40px; }
form { max-width: 400px; }
.speech-evidence { min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.speech-evidence header { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.speech-evidence h3 { margin: 0; }
.speech-evidence header span, .evidence-note { font-size: 12px; color: var(--muted); }
.speech-evidence label { margin: 0; font-size: 12px; }
.evidence-summary summary { padding: 4px 0; min-height: 0; font-size: 12px; }
.evidence-list { max-height: min(48vh, 440px); overflow: auto; border: 1px solid var(--line); border-radius: 7px; }
.evidence-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 8px; align-items: start; padding: 10px; border-bottom: 1px solid var(--line); }
.evidence-row:last-child { border-bottom: 0; }
.evidence-row.selected { background: var(--blue-weak); box-shadow: inset 3px 0 var(--blue); }
.evidence-time { white-space: nowrap; font-size: 12px; font-variant-numeric: tabular-nums; color: var(--ink-3); padding-top: 4px; }
.evidence-content { min-width: 0; }
.evidence-content p { line-height: 1.5; }
.evidence-content p.clamped { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.expand { margin: 0; padding: 2px 0; min-height: 0; font-size: 12px; }
.evidence-note { margin: 0; }
.pagination { display: flex; align-items: center; justify-content: center; gap: 12px; font-size: 12px; }
@media (max-width: 800px) { .player-layout { grid-template-columns: minmax(0, 1fr); } .evidence-list { max-height: 35vh; } }
@media (max-width: 480px) { .player-heading { gap: 8px; } .evidence-row { grid-template-columns: minmax(0, 1fr) auto; } .evidence-time { grid-column: 1 / -1; } }
</style>

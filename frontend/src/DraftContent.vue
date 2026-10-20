<script setup lang="ts">
import type { EditDraft } from './DraftCard.vue';
defineProps<{ draft: EditDraft; assets: { id: string; filename: string }[] }>();
const emit = defineEmits<{ play: [clip: EditDraft['normalizedContent']['outputs'][number]['clips'][number]] }>();
function time(ms: number) {
  const seconds = ms / 1000;
  return `${Math.floor(seconds / 60)}:${(seconds % 60).toFixed(3).padStart(6, '0')}`;
}
</script>
<template>
  <section v-for="(output, index) in draft.normalizedContent.outputs" :key="output.output_key" class="draft-output">
    <h4>{{ output.title }}</h4>
    <p class="output-summary">输出 {{ index + 1 }} · {{ time(output.durationMs) }} · {{ output.render.width }} × {{ output.render.height }} · {{ output.clips.length }} 个片段 · {{ output.subtitles.cues.length ? `${output.subtitles.cues.length} 条字幕` : '无字幕' }}</p>
    <details><summary>片段与选取依据</summary><ol><li v-for="(clip, clipIndex) in output.clips" :key="clipIndex"><strong>{{ assets.find(asset => asset.id === clip.assetId)?.filename ?? '素材名称暂不可用' }}</strong><p>{{ time(clip.sourceInMs) }}–{{ time(clip.sourceOutMs) }} · {{ clip.reason }}</p><button type="button" class="text-button" @click="emit('play', clip)">回看片段 {{clipIndex + 1}}</button></li></ol></details>
    <details v-if="output.subtitles.cues.length"><summary>字幕内容</summary><p v-for="(cue, cueIndex) in output.subtitles.cues" :key="cueIndex">{{ time(cue.startMs) }}–{{ time(cue.endMs) }} · {{ cue.text }}</p></details>
  </section>
</template>
<style scoped>
.draft-output { padding: 10px 0; border-bottom: 1px solid var(--line); }
.draft-output:last-child { border-bottom: 0; }
h4 { margin: 0 0 4px; font-size: 13px; }
p, li { overflow-wrap: anywhere; font-size: 11.5px; line-height: 1.6; }
.output-summary { color: var(--ink-4); font-size: 10.5px; margin: 0; }
summary { min-height: 30px; padding: 6px 0; cursor: pointer; font-size: 11px; color: var(--ink-3); }
ol { padding-left: 18px; }
li { margin-bottom: 8px; }
</style>

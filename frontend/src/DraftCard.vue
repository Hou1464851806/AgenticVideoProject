<script setup lang="ts">
import { ref } from 'vue';
import { request } from './api';
export type DraftBinding = { messageId: string; validationId: string; editGeneration: number; draftHash: string; requirementsHash: string };
export type EditDraft = { id: string; content: string; normalizedContent: { outputs: { output_key: string; title: string; durationMs: number; render: { width: number; height: number }; clips: { assetId: string; sourceInMs: number; sourceOutMs: number; reason: string }[]; subtitles: { cues: { startMs: number; endMs: number; text: string }[] } }[] } };
const props = defineProps<{ conversationId: string; draft: EditDraft; binding: DraftBinding; canPreview: boolean; canAccept: boolean }>();
const emit = defineEmits<{ changed: [] }>();
const busy = ref(false), error = ref(''), choosing = ref(false), outputKey = ref('');
// Operation keys and bodies survive uncertain responses for this immutable draft message.
const acceptKey = crypto.randomUUID();
const previewKeys = new Map<string, string>();
async function accept() {
  if (busy.value || !props.canAccept) return;
  busy.value = true; error.value = '';
  try {
    await request(`/conversations/${props.conversationId}/drafts/accept`, 'POST', props.binding, { 'Idempotency-Key': acceptKey });
    emit('changed');
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '确认未收到响应，请重试'; emit('changed'); }
  finally { busy.value = false; }
}
async function preview() {
  if (busy.value || !props.canPreview) return;
  const outputs = props.draft.normalizedContent.outputs;
  if (outputs.length > 1 && !choosing.value) { choosing.value = true; return; }
  const key = outputs.length === 1 ? outputs[0]!.output_key : outputKey.value;
  if (!key) return;
  busy.value = true; error.value = '';
  const identity = previewKeys.get(key) ?? crypto.randomUUID(); previewKeys.set(key, identity);
  try {
    await request(`/conversations/${props.conversationId}/drafts/previews`, 'POST', { draft: props.binding, outputKey: key, previewProfile: 'LOW_360' }, { 'Idempotency-Key': identity });
    choosing.value = false; emit('changed');
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '预览接纳未确认，请重试'; emit('changed'); }
  finally { busy.value = false; }
}
</script>
<template>
  <section aria-label="剪辑草稿">
    <h4>已校验剪辑草稿</h4>
    <details><summary>草稿与校验标识</summary><p>草稿 {{ binding.messageId }} · 校验 {{ binding.validationId }} · 编辑代次 {{ binding.editGeneration }}</p></details>
    <section v-for="(output, index) in draft.normalizedContent.outputs" :key="output.output_key">
      <h5>输出 {{ index + 1 }} · {{ output.title }}</h5>
      <p>{{ output.output_key }} · {{ (output.durationMs / 1000).toFixed(3) }} 秒 · {{ output.render.width }} × {{ output.render.height }}</p>
      <ol><li v-for="(clip, clipIndex) in output.clips" :key="clipIndex">素材 {{ clip.assetId }} · {{ clip.sourceInMs / 1000 }}–{{ clip.sourceOutMs / 1000 }} 秒 · {{ clip.reason }}</li></ol>
      <details><summary>字幕</summary><p v-for="(cue, cueIndex) in output.subtitles.cues" :key="cueIndex">{{ cue.startMs / 1000 }}–{{ cue.endMs / 1000 }} 秒 · {{ cue.text }}</p></details>
    </section>
    <p v-if="!canAccept && !canPreview">此草稿当前只读；修改中、条件变化或已封存时不能新建预览或确认。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button type="button" :disabled="busy || !canPreview" @click="preview">预览草稿</button>
    <template v-if="choosing">
      <label :for="`draft-output-${binding.messageId}`">选择预览输出</label>
      <select :id="`draft-output-${binding.messageId}`" v-model="outputKey"><option value="">选择一条输出</option><option v-for="output in draft.normalizedContent.outputs" :key="output.output_key" :value="output.output_key">{{ output.title }}</option></select>
      <button type="button" :disabled="busy || !canPreview || !outputKey" @click="preview">预览所选输出</button>
    </template>
    <button class="primary" type="button" :disabled="busy || !canAccept" @click="accept">确认全部输出</button>
  </section>
</template>
<style scoped>
p, li { overflow-wrap: anywhere; font-size: 13px; }
section { border: 1px solid #dce5de; padding: 10px; margin: 8px 0; border-radius: 8px; }
</style>

<script setup lang="ts">
import { ref } from 'vue';
import { request } from './api';
import DraftContent from './DraftContent.vue';
export type DraftBinding = { messageId: string; validationId: string; editGeneration: number; draftHash: string; requirementsHash: string };
export type EditDraft = { id: string; content: string; normalizedContent: { outputs: { output_key: string; title: string; durationMs: number; render: { width: number; height: number }; clips: { assetId: string; sourceInMs: number; sourceOutMs: number; reason: string }[]; subtitles: { cues: { startMs: number; endMs: number; text: string }[] } }[] } };
const props = defineProps<{ conversationId: string; draft: EditDraft; binding: DraftBinding; canPreview: boolean; canAccept: boolean; assets: { id: string; filename: string }[]; readonlyReason: string }>();
const emit = defineEmits<{ changed: [action: 'accept' | 'preview']; play: [clip: EditDraft['normalizedContent']['outputs'][number]['clips'][number]] }>();
const busy = ref(false), error = ref(''), choosing = ref(false), outputKey = ref('');
// Operation keys and bodies survive uncertain responses for this immutable draft message.
const acceptKey = crypto.randomUUID();
const previewKeys = new Map<string, string>();
async function accept() {
  if (busy.value || !props.canAccept) return;
  busy.value = true; error.value = '';
  try {
    await request(`/conversations/${props.conversationId}/drafts/accept`, 'POST', props.binding, { 'Idempotency-Key': acceptKey });
    emit('changed', 'accept');
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '确认未收到响应，请重试'; emit('changed', 'accept'); }
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
    choosing.value = false; emit('changed', 'preview');
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '预览接纳未确认，请重试'; emit('changed', 'preview'); }
  finally { busy.value = false; }
}
</script>
<template>
  <section aria-label="剪辑草稿">
    <h3>当前剪辑草稿</h3>
    <DraftContent :draft="draft" :assets="assets" @play="emit('play', $event)" />
    <p v-if="!canAccept && !canPreview" class="readonly">{{ readonlyReason }}</p>
    <p v-else>确认将接受全部 {{draft.normalizedContent.outputs.length}} 条输出并保存方案；确认后本稿只读，正式导出需另行操作。</p>
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
h3 { margin: 0 0 8px; font-size: 20px; }
.readonly { padding: 12px; background: #edf3ee; }
</style>

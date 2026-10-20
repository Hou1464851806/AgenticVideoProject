<script setup lang="ts">
import { ref } from 'vue';
import { request } from './api';
import DraftContent from './DraftContent.vue';
export type DraftBinding = { messageId: string; validationId: string; editGeneration: number; draftHash: string; requirementsHash: string };
export type EditDraft = { id: string; content: string; normalizedContent: { outputs: { output_key: string; title: string; durationMs: number; render: { width: number; height: number }; clips: { assetId: string; sourceInMs: number; sourceOutMs: number; reason: string }[]; subtitles: { cues: { startMs: number; endMs: number; text: string }[] } }[] } };
const props = defineProps<{ conversationId: string; draft: EditDraft; binding: DraftBinding; canPreview: boolean; canAccept: boolean; assets: { id: string; filename: string }[]; readonlyReason: string }>();
const emit = defineEmits<{ changed: [action: 'accept' | 'preview']; play: [clip: EditDraft['normalizedContent']['outputs'][number]['clips'][number]]; dismiss: [] }>();
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
    <template v-if="choosing">
      <label :for="`draft-output-${binding.messageId}`">选择预览输出</label>
      <select :id="`draft-output-${binding.messageId}`" v-model="outputKey"><option value="">选择一条输出</option><option v-for="output in draft.normalizedContent.outputs" :key="output.output_key" :value="output.output_key">{{ output.title }}</option></select>
      <button type="button" :disabled="busy || !canPreview || !outputKey" @click="preview">预览所选输出</button>
    </template>
    <div class="draft-actions">
      <button class="text-button" type="button" :disabled="busy" @click="emit('dismiss')">暂不采纳</button>
      <span class="grow"></span>
      <button type="button" :disabled="busy || !canPreview" @click="preview">预览草稿</button>
      <button class="primary" type="button" :disabled="busy || !canAccept" @click="accept">确认全部输出</button>
    </div>
  </section>
</template>
<style scoped>
section[aria-label='剪辑草稿'] { border: 1px solid var(--line-strong); border-radius: 11px; background: #fff; padding: 12px 14px; box-shadow: 0 1px 2px rgba(15, 17, 21, .03); }
p, li { overflow-wrap: anywhere; font-size: 12px; }
h3 { margin: 0 0 4px; font-size: 12px; color: var(--ink-3); font-weight: 550; }
.readonly { padding: 8px 10px; border-radius: 6px; background: var(--panel); color: var(--ink-3); font-size: 11px; line-height: 1.55; }
.draft-actions { display: flex; align-items: center; gap: 6px; margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--line); }
.draft-actions .grow { flex: 1; }
.draft-actions button { margin: 0; }
.draft-actions .primary { min-width: 104px; }
:deep(p) { font-size: 11.5px; }
:deep(h4) { font-size: 13px; margin: 10px 0 4px; }
:deep(.output-summary) { color: var(--ink-4); font-size: 10.5px; }
:deep(summary) { min-height: 30px; padding: 6px 0; font-size: 11px; color: var(--ink-3); }
:deep(li) { font-size: 11px; margin-bottom: 8px; }
:deep(ol) { padding-left: 18px; }
</style>

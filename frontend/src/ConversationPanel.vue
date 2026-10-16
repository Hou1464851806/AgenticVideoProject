<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { request } from './api';
import { useProjectEvents } from './projectEvents';
import ArtifactPlayer from './ArtifactPlayer.vue';
import DraftCard, { type EditDraft, type DraftBinding } from './DraftCard.vue';
import type { Playback } from './AssetLibrary.vue';
import type { PlanSelection, PlanReference } from './PlanPanel.vue';
const props = defineProps<{ projectId: string; editReference?: PlanReference | null }>();
const emit = defineEmits<{ play: [value: Playback]; plan: [value: PlanSelection]; session: [value: string] }>();
type Session = { id: string; title: string; assetIds: string[] };
type Asset = { id: string; filename: string };
type Citation = { type: string; id: string; planId?: string; sourceDraftMessageId?: string; outputKey?: string };
type Message = { id: string; role: string; content: string; references: Citation[] };
type Question = { questionId: string; taskId: string; prompt: string };
type Task = { taskId: string; status: string; phase?: string; failure?: { code: string; message: string; recoverable: boolean } };
type Preview = { requestId: string; taskId: string; sourceDraftMessageId: string; outputKey: string; status: string; artifactId?: string; artifactState?: string; error?: { message: string } };
type Agent = { currentProduction: { productionId: string; frozen: boolean; editGeneration: number; requirements: Record<string, unknown>; currentDraft: DraftBinding | null } | null; currentDraft: EditDraft | null; canAcceptDraft: boolean; canPreviewDraft: boolean; pendingQuestions: Question[] };
type Snapshot = { cursor: string; assetIds: string[]; items: Message[]; latestTask: Task | null; agent: Agent; previews: { items: Preview[] } };
const sessions = ref<Session[]>([]), assets = ref<Asset[]>([]), selected = ref('');
const history = ref<Snapshot | null>(null), error = ref('');
const texts = ref<Record<string, string>>({}), references = ref<Record<string, Citation[]>>({});
const text = computed({ get: () => texts.value[selected.value] ?? '', set: value => { texts.value[selected.value] = value; } });
const session = computed(() => sessions.value.find(item => item.id === selected.value));
const running = computed(() => ['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(history.value?.latestTask?.status ?? ''));
const sending = ref(false), creating = ref(false), controlling = ref(false), loading = ref(true);
const pending = new Map<string, { key: string; body: { content: string; references: Citation[] } }>();
const retryKeys = new Map<string, string>();
let creationKey: string | undefined, alive = true;
const cursors = new Map<string, bigint>();
const phaseLabels: Record<string, string> = { SEARCHING: '正在检索证据', READING_EVIDENCE: '正在核查原文', MODELING: '正在组织答复', SELF_CHECKING: '正在自检候选', VALIDATING: '正在验证草稿' };
const statusLabels: Record<string, string> = { PENDING: '等待执行', RUNNING: '正在执行', CANCEL_REQUESTED: '正在停止', CANCELLED: '已停止', SUCCEEDED: '已完成', FAILED: '失败', WAITING_INPUT: '等待补充信息' };
const requirementLabels: Record<string, string> = { topic: '主题', asset_ids: '素材范围', profile: '画幅', subtitles_enabled: '字幕', require_complete_subtitles: '完整字幕', forbidden_content: '禁用内容', outputs: '输出要求' };
const currentDraft = computed(() => history.value?.agent.currentDraft);
function requirementValue(name: string, value: unknown): string {
  if (name === 'profile') return value === 'PORTRAIT_720' ? '竖屏 720p' : '横屏 720p';
  if (typeof value === 'boolean') return value ? '开启' : '关闭';
  if (name === 'outputs') return (value as { output_key: string; duration_min_ms: number | null; duration_max_ms: number | null }[])
    .map(output => {
      const minimum = output.duration_min_ms === null ? '不限最短' : `${output.duration_min_ms / 1000} 秒起`;
      const maximum = output.duration_max_ms === null ? '不限最长' : `${output.duration_max_ms / 1000} 秒止`;
      return `${output.output_key}：${minimum}，${maximum}`;
    }).join('；');
  if (Array.isArray(value)) return value.map(item => name === 'asset_ids' ? assets.value.find(asset => asset.id === item)?.filename ?? String(item) : String(item)).join('、') || '无';
  return String(value);
}
// The current draft is restored even when its message is outside the recent history window.
const messages = computed(() => {
  const items = history.value?.items ?? [], draft = currentDraft.value;
  return draft && !items.some(item => item.id === draft.id)
    ? [{ id: draft.id, role: 'ASSISTANT', content: draft.content, references: [{ type: 'EDIT_DRAFT', id: draft.id }] }, ...items] : items;
});
watch(() => props.editReference, value => {
  if (value && selected.value) references.value[selected.value] = [...(references.value[selected.value] ?? []).filter(item => item.type !== 'PLAN_REVISION'), value];
});
function toggleReference(reference: Citation) {
  const values = references.value[selected.value] ?? [];
  references.value[selected.value] = values.some(item => item.type === reference.type && item.id === reference.id)
    ? values.filter(item => item.type !== reference.type || item.id !== reference.id) : [...values, reference];
}
async function loadHistory(rethrow = false) {
  const id = selected.value;
  if (!id) return;
  try {
    const snapshot = await request<Snapshot>(`/conversations/${id}/snapshot`);
    const sequence = BigInt(snapshot.cursor.split(':')[1]!);
    if (alive && selected.value === id && sequence >= (cursors.get(id) ?? 0n)) {
      cursors.set(id, sequence); history.value = snapshot;
      session.value!.assetIds = snapshot.assetIds;
    }
  } catch (failure) {
    if (alive && selected.value === id) error.value = failure instanceof Error ? failure.message : '无法读取会话';
    if (rethrow) throw failure;
  }
}
async function choose(id: string) {
  selected.value = id; history.value = null; error.value = '';
  references.value[id] ??= [];
  localStorage.setItem(`conversation:${props.projectId}`, id);
  emit('session', id);
  await loadHistory();
}
async function createChat() {
  if (creating.value || loading.value) return;
  creationKey ??= crypto.randomUUID(); creating.value = true; error.value = '';
  try {
    const created = await request<Session>(`/projects/${props.projectId}/conversations`, 'POST', {}, { 'Idempotency-Key': creationKey });
    if (!sessions.value.some(item => item.id === created.id)) sessions.value.push(created);
    creationKey = undefined; await choose(created.id);
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '新聊天创建未确认，请重试'; }
  finally { creating.value = false; }
}
async function send() {
  const id = selected.value, content = text.value;
  if (!id || sending.value || running.value || !content.trim()) return;
  const existing = pending.get(id), body = { content, references: [...(references.value[id] ?? [])] };
  // A lost response replays the same input; changing text or references starts a new operation.
  const identity = existing && JSON.stringify(existing.body) === JSON.stringify(body) ? existing : { key: crypto.randomUUID(), body };
  pending.set(id, identity); sending.value = true; error.value = '';
  try {
    await request(`/conversations/${id}/turns`, 'POST', identity.body, { 'Idempotency-Key': identity.key });
    pending.delete(id); references.value[id] = [];
    if (texts.value[id] === content) texts.value[id] = '';
    if (selected.value === id) await loadHistory();
  } catch (failure) { if (selected.value === id) error.value = failure instanceof Error ? failure.message : '发送未确认，请重试'; }
  finally { sending.value = false; }
}
async function control(taskId: string, retry = false) {
  const id = selected.value;
  if (controlling.value) return;
  controlling.value = true; error.value = '';
  try {
    if (retry) {
      const key = retryKeys.get(taskId) ?? crypto.randomUUID(); retryKeys.set(taskId, key);
      await request(`/conversations/${id}/tasks/${taskId}/retry`, 'POST', {}, { 'Idempotency-Key': key });
    } else await request(`/tasks/${taskId}/cancel`, 'POST', {});
    if (selected.value === id) await loadHistory();
  } catch (failure) { if (selected.value === id) error.value = failure instanceof Error ? failure.message : '操作未确认，请重试'; }
  finally { controlling.value = false; }
}
async function previewAction(preview: Preview, retry: boolean) {
  const id = selected.value;
  try {
    await request(retry ? `/conversations/${id}/drafts/previews/${preview.requestId}/retry` : `/tasks/${preview.taskId}/cancel`, 'POST', {});
    if (selected.value === id) await loadHistory();
  } catch (failure) { if (selected.value === id) error.value = failure instanceof Error ? failure.message : '预览操作未确认，请重试'; }
}
async function inspect(citation: Citation) {
  const id = selected.value;
  try {
    const context = await request<{ assetId: string; observations: { id: string; startMs?: number; observedAtMs?: number }[] }>(`/evidence/${citation.id}/context`);
    const observation = context.observations.find(item => item.id === citation.id)!;
    const access = await request<Playback>(`/assets/${context.assetId}/playback-access`, 'POST', {});
    if (selected.value === id) emit('play', { ...access, startMs: observation.observedAtMs ?? observation.startMs! });
  } catch (failure) { if (selected.value === id) error.value = failure instanceof Error ? failure.message : '无法核查原视频'; }
}
useProjectEvents(async event => {
  if (alive && (event === null || event.conversationId === selected.value || history.value?.previews.items.some(item => item.taskId === event.taskId))) await loadHistory(true);
});
onMounted(async () => {
  try {
    const [available, existing] = await Promise.all([request<{ items: Asset[] }>(`/projects/${props.projectId}/assets`), request<{ items: Session[] }>(`/projects/${props.projectId}/conversations`)]);
    if (!alive) return;
    assets.value = available.items; sessions.value = existing.items;
    const saved = localStorage.getItem(`conversation:${props.projectId}`);
    if (existing.items.length) await choose(existing.items.find(item => item.id === saved)?.id ?? existing.items.at(-1)!.id);
  } catch (failure) { if (alive) error.value = failure instanceof Error ? failure.message : '无法打开对话'; }
  finally { loading.value = false; }
});
onUnmounted(() => { alive = false; });
</script>

<template>
  <section class="conversation-panel">
    <button type="button" :disabled="creating || loading" @click="createChat">新聊天</button>
    <p v-if="!sessions.length">还没有聊天。新建聊天后可以问答、讨论或提出制作需求。</p>
    <template v-if="sessions.length">
      <label for="session-select">制作会话</label>
      <select id="session-select" :value="selected" @change="choose(($event.target as HTMLSelectElement).value)"><option v-for="item in sessions" :key="item.id" :value="item.id">{{ item.title }}</option></select>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="session && history">
      <section v-if="history.agent.currentProduction" aria-label="当前制作">
        <h3>当前制作{{ history.agent.currentProduction.frozen ? ' · 已封存' : '' }}</h3>
        <details><summary>制作标识</summary><p>{{ history.agent.currentProduction.productionId }} · 编辑代次 {{ history.agent.currentProduction.editGeneration }}</p></details>
        <dl><template v-for="(value, name) in history.agent.currentProduction.requirements" :key="name"><dt>{{ requirementLabels[name] ?? name }}</dt><dd>{{ requirementValue(name, value) }}</dd></template></dl>
      </section>
      <article v-for="message in messages" :key="message.id" :aria-label="message.role === 'USER' ? '用户消息' : '助手答复'">
        <strong>{{ message.role === 'USER' ? '你' : '助手' }}</strong>
        <p class="message-content">{{ message.content }}</p>
        <template v-for="citation in message.references" :key="`${citation.type}:${citation.id}`">
          <button v-if="citation.type === 'EVIDENCE'" class="secondary" @click="inspect(citation)">核查原视频证据</button>
          <template v-else-if="citation.type === 'EDIT_DRAFT'">
            <DraftCard v-if="currentDraft?.id === citation.id" :key="`${selected}:${citation.id}`" :conversation-id="selected" :draft="currentDraft" :binding="history.agent.currentProduction!.currentDraft!" :can-preview="history.agent.canPreviewDraft && !sending" :can-accept="history.agent.canAcceptDraft && !sending" @changed="loadHistory()" />
            <p v-else>历史草稿 · {{ citation.id }} · 只读</p>
            <button class="secondary" @click="toggleReference(citation)">引用此草稿继续对话</button>
          </template>
          <template v-else-if="citation.type === 'PREVIEW_RESULT'">
            <p>来源草稿：{{ citation.sourceDraftMessageId }} · 输出 {{ citation.outputKey }}{{ citation.sourceDraftMessageId === currentDraft?.id ? '' : ' · 历史预览' }}</p>
            <ArtifactPlayer :key="citation.id" :artifact-id="citation.id" @changed="loadHistory()" />
          </template>
          <button v-else-if="citation.type === 'PLAN_REVISION'" class="secondary" @click="emit('plan', { planId: citation.planId!, revisionId: citation.id, sessionId: selected })">查看已确认方案</button>
        </template>
      </article>
      <section v-if="history.previews.items.length" aria-label="草稿预览请求">
        <article v-for="preview in history.previews.items" :key="preview.requestId" aria-label="预览请求">
          <p>来源草稿：{{ preview.sourceDraftMessageId }} · 输出 {{ preview.outputKey }} · {{ statusLabels[preview.status] }}</p>
          <p v-if="['UNAVAILABLE', 'REMOVED'].includes(preview.artifactState ?? '')">预览文件已失效；仅当前可操作草稿支持显式重试。</p>
          <p v-if="preview.error" role="alert">{{ preview.error.message }}</p>
          <button v-if="['PENDING', 'RUNNING'].includes(preview.status)" class="secondary" @click="previewAction(preview, false)">停止此预览</button>
          <button v-if="['FAILED', 'CANCELLED'].includes(preview.status) || (preview.status === 'SUCCEEDED' && ['UNAVAILABLE', 'REMOVED'].includes(preview.artifactState ?? ''))" class="secondary" :disabled="!history.agent.canPreviewDraft || sending || preview.sourceDraftMessageId !== currentDraft?.id" @click="previewAction(preview, true)">重试此预览</button>
          <ArtifactPlayer v-if="preview.artifactId && !messages.some(item => item.references.some(ref => ref.type === 'PREVIEW_RESULT' && ref.id === preview.artifactId))" :artifact-id="preview.artifactId" @changed="loadHistory()" />
        </article>
      </section>
      <section v-for="question in history.agent.pendingQuestions" :key="question.questionId" aria-label="待回答问题">
        <p>{{ question.prompt }}</p><p>在下方回答此问题，也可以继续独立问答或明确开始新制作。</p>
        <button class="secondary" @click="toggleReference({ type: 'QUESTION', id: question.questionId })">引用此问题</button>
        <button class="secondary" :disabled="controlling" @click="control(question.taskId)">停止此等待</button>
      </section>
      <template v-if="history.latestTask">
        <p role="status">{{ phaseLabels[history.latestTask.phase ?? ''] ?? statusLabels[history.latestTask.status] }}</p>
        <p v-if="history.latestTask.failure" role="alert">{{ history.latestTask.failure.message }}</p>
        <p v-if="history.latestTask.status === 'CANCELLED'">本次执行已停止，制作条件和历史保留。</p>
        <button v-if="running" :disabled="controlling || history.latestTask.status === 'CANCEL_REQUESTED'" @click="control(history.latestTask.taskId)">停止当前 Agent</button>
        <button v-if="history.latestTask.status === 'FAILED' && history.latestTask.failure?.recoverable && history.latestTask.failure.code === 'EXECUTION_INTERRUPTED'" :disabled="controlling || sending" @click="control(history.latestTask.taskId, true)">重试原需求</button>
      </template>
      <form @submit.prevent="send">
        <fieldset v-if="session.assetIds.length"><legend>本次明确引用素材</legend>
          <label v-for="asset in assets.filter(item => session!.assetIds.includes(item.id))" :key="asset.id" class="choice"><input type="checkbox" :checked="references[selected]?.some(item => item.type === 'ASSET' && item.id === asset.id)" @change="toggleReference({ type: 'ASSET', id: asset.id })">{{ asset.filename }}</label>
        </fieldset>
        <p v-for="reference in references[selected]?.filter(item => item.type !== 'ASSET')" :key="`${reference.type}:${reference.id}`">已引用 {{ reference.type === 'QUESTION' ? '待回答问题' : reference.type === 'EDIT_DRAFT' ? '草稿' : '方案版本' }} · {{ reference.id }} <button type="button" class="secondary" @click="toggleReference(reference)">移除引用</button></p>
        <label for="conversation-input">对话输入</label><textarea id="conversation-input" v-model="text" maxlength="8000" required placeholder="可以问答、讨论、提出制作需求或修改当前草稿"></textarea>
        <button type="submit" :disabled="sending || running || !text.trim()">发送需求</button>
      </form>
    </template>
  </section>
</template>

<style scoped>
select, textarea { width: 100%; padding: 10px; border: 1px solid var(--control-border); border-radius: 8px; font: inherit; }
textarea { min-height: 100px; resize: vertical; }
article { border-bottom: 1px solid #dce5de; padding: 14px 0; font-size: 14px; }
p, dd { overflow-wrap: anywhere; }
.message-content { white-space: pre-wrap; line-height: 1.7; }
.choice { display: flex; align-items: center; gap: 8px; }
.choice input { width: auto; }
</style>

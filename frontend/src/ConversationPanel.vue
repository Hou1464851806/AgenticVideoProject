<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { request } from './api';
import { useProjectEvents } from './projectEvents';
import ArtifactPlayer from './ArtifactPlayer.vue';
import DraftContent from './DraftContent.vue';
import DraftCard, { type EditDraft, type DraftBinding } from './DraftCard.vue';
import type { Playback } from './AssetLibrary.vue';
import type { PlanSelection, PlanReference } from './PlanPanel.vue';

const props = defineProps<{ projectId: string; editReference?: PlanReference | null; diagnostics?: boolean }>();
const emit = defineEmits<{
  play: [value: Playback];
  plan: [value: PlanSelection];
  session: [value: string];
  sessions: [value: Session[]];
}>();
type Session = { id: string; title: string; assetIds: string[] };
type Asset = { id: string; filename: string };
type Citation = { type: string; id: string; planId?: string; sourceDraftMessageId?: string; outputKey?: string };
type Message = { id: string; role: string; content: string; references: Citation[] };
type Question = { id: string; taskId: string; turnId: string; prompt: string; reason?: string; fields?: string[]; status: string };
type Task = { taskId: string; status: string; phase?: string; failure?: { code: string; message: string; recoverable: boolean } };
type Preview = { requestId: string; taskId: string; sourceDraftMessageId: string; outputKey: string; status: string; artifactId?: string; artifactState?: string; error?: { message: string } };
type Agent = { currentProduction: { productionId: string; frozen: boolean; editGeneration: number; requirements: Record<string, unknown>; currentDraft: DraftBinding | null } | null; currentDraft: EditDraft | null; canAcceptDraft: boolean; canPreviewDraft: boolean };
type Snapshot = { cursor: string; assetIds: string[]; items: Message[]; latestTask: Task | null; agent: Agent; previews: { items: Preview[] }; questions?: Question[] };
type PreviewCard = { key: string; request: Preview | null; sourceDraftMessageId: string; outputKey: string; artifactId?: string };

const sessions = ref<Session[]>([]), assets = ref<Asset[]>([]), selected = ref('');
const history = ref<Snapshot | null>(null), error = ref('');
const texts = ref<Record<string, string>>({}), references = ref<Record<string, Citation[]>>({});
const answers = ref<Record<string, string>>({});
const answering = ref(''), answerError = ref('');
const answeredQuestions = ref<string[]>([]);
const dismissedDrafts = ref<string[]>([]);
const composing = ref(false);
const messageLog = ref<HTMLElement | null>(null);
const historicalDraft = ref<Message | null>(null);
const historicalEl = ref<HTMLElement | null>(null);
const draftCache = ref<Record<string, EditDraft>>({});
const followingMessages = ref(true), hasNewMessages = ref(false);
const loading = ref(true), sending = ref(false), creating = ref(false), controlling = ref(false);
const pending = new Map<string, { key: string; body: { content: string; references: Citation[] } }>();
const retryKeys = new Map<string, string>();
const cursors = new Map<string, bigint>();
let creationKey: string | undefined, alive = true;
const phaseLabels: Record<string, string> = { SEARCHING: '正在检索证据', READING_EVIDENCE: '正在核查原文', MODELING: '正在组织答复', SELF_CHECKING: '正在自检候选', VALIDATING: '正在验证草稿' };
const statusLabels: Record<string, string> = { PENDING: '等待执行', RUNNING: '正在执行', CANCEL_REQUESTED: '正在停止', CANCELLED: '已停止', SUCCEEDED: '已完成', FAILED: '失败', WAITING_INPUT: '等待补充信息' };
const requirementLabels: Record<string, string> = { topic: '主题', asset_ids: '素材范围', profile: '画幅', subtitles_enabled: '字幕', require_complete_subtitles: '完整字幕', forbidden_content: '禁用内容', outputs: '输出要求', duration: '时长' };

const session = computed(() => sessions.value.find(item => item.id === selected.value));
const text = computed({ get: () => texts.value[selected.value] ?? '', set: value => { texts.value[selected.value] = value; } });
const running = computed(() => ['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(history.value?.latestTask?.status ?? ''));
const currentProduction = computed(() => history.value?.agent.currentProduction ?? null);
const currentDraft = computed(() => history.value?.agent.currentDraft);
const currentBinding = computed(() => currentProduction.value?.currentDraft ?? null);
const draftDismissed = computed(() => !!currentDraft.value && dismissedDrafts.value.includes(currentDraft.value.id));
const messages = computed(() => (history.value?.items ?? []).filter(message => message.id !== currentDraft.value?.id));
const pendingQuestions = computed(() => (history.value?.questions ?? []).filter(question => question.status === 'OPEN' && !answeredQuestions.value.includes(question.id)));
const previewCards = computed<PreviewCard[]>(() => {
  const requests = history.value?.previews.items ?? [];
  const cards: PreviewCard[] = requests.map(item => ({ key: item.requestId, request: item, sourceDraftMessageId: item.sourceDraftMessageId, outputKey: item.outputKey, artifactId: item.artifactId }));
  const seen = new Set(requests.map(item => item.artifactId));
  for (const message of history.value?.items ?? []) for (const citation of message.references) {
    if (citation.type !== 'PREVIEW_RESULT' || seen.has(citation.id)) continue;
    seen.add(citation.id);
    cards.push({ key: `artifact:${citation.id}`, request: null, sourceDraftMessageId: citation.sourceDraftMessageId!, outputKey: citation.outputKey!, artifactId: citation.id });
  }
  return cards;
});
const readonlyReason = computed(() => currentProduction.value?.frozen
  ? '此制作已确认封存，当前草稿只读。可以继续讨论或开始新制作。'
  : running.value || sending.value ? '正在处理新的需求，本稿暂不可操作；原制作资格以最新快照为准。'
  : '制作条件或编辑状态已变化，本稿只读。失败或停止不会恢复旧稿资格。');

function requirementValue(name: string, value: unknown): string {
  if (name === 'profile') return value === 'PORTRAIT_720' ? '竖屏 720p' : value === 'LANDSCAPE_720' ? '横屏 720p' : String(value);
  if (typeof value === 'boolean') return value ? '开启' : '关闭';
  if (name === 'outputs') return (value as { duration_min_ms: number | null; duration_max_ms: number | null }[]).map((output, index) => {
    const minimum = output.duration_min_ms === null ? '不限最短' : `${output.duration_min_ms / 1000} 秒起`;
    const maximum = output.duration_max_ms === null ? '不限最长' : `${output.duration_max_ms / 1000} 秒止`;
    return `输出 ${index + 1}：${minimum}，${maximum}`;
  }).join('；');
  if (Array.isArray(value)) return value.map(item => name === 'asset_ids' ? assets.value.find(asset => asset.id === item)?.filename ?? '素材名称暂不可用' : String(item)).join('、') || '无';
  return String(value);
}
function draftName(id: string) {
  const known = draftCache.value[id];
  if (known) return known.normalizedContent.outputs.map(output => output.title).join('、');
  const index = (history.value?.items ?? []).filter(message => message.references.some(ref => ref.type === 'EDIT_DRAFT')).findIndex(message => message.id === id);
  return index >= 0 ? `历史草稿 ${index + 1}` : '历史草稿';
}
const referenceNames = ref<Record<string, string>>({});
function referenceLabel(reference: Citation) {
  if (reference.type === 'ASSET') return assets.value.find(asset => asset.id === reference.id)?.filename ?? '素材名称暂不可用';
  if (reference.type === 'QUESTION') return pendingQuestions.value.find(question => question.id === reference.id)?.prompt ?? '历史问题';
  if (reference.type === 'EDIT_DRAFT') return draftName(reference.id);
  if (reference.type === 'PLAN_REVISION') return referenceNames.value[`PLAN_REVISION:${reference.id}`] ?? '方案版本';
  return reference.type;
}
function outputName(card: PreviewCard) {
  return draftCache.value[card.sourceDraftMessageId]?.normalizedContent.outputs.find(output => output.output_key === card.outputKey)?.title ?? '输出名称暂不可用';
}
function trackScroll() {
  const log = messageLog.value;
  if (!log) return;
  followingMessages.value = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
  if (followingMessages.value) hasNewMessages.value = false;
}
function latestMessages() {
  if (messageLog.value) messageLog.value.scrollTop = messageLog.value.scrollHeight;
  followingMessages.value = true; hasNewMessages.value = false;
}
function viewHistoricalDraft(message: Message) {
  historicalDraft.value = message;
  void nextTick(() => {
    historicalEl.value?.scrollIntoView({ block: 'nearest' });
    historicalEl.value?.focus();
  });
}
function closeHistoricalDraft() { historicalDraft.value = null; }
function dismissDraft() { if (currentDraft.value) dismissedDrafts.value = [...dismissedDrafts.value, currentDraft.value.id]; }
function expandDraft() { if (currentDraft.value) dismissedDrafts.value = dismissedDrafts.value.filter(id => id !== currentDraft.value!.id); }
async function loadHistory(rethrow = false) {
  const id = selected.value;
  if (!id) return;
  try {
    const snapshot = await request<Snapshot>(`/conversations/${id}/snapshot`);
    const sequence = BigInt(snapshot.cursor.split(':')[1]!);
    if (alive && selected.value === id && sequence >= (cursors.get(id) ?? 0n)) {
      const changed = history.value?.items.at(-1)?.id !== snapshot.items.at(-1)?.id;
      cursors.set(id, sequence); history.value = snapshot;
      const active = session.value;
      if (active) active.assetIds = snapshot.assetIds;
      if (snapshot.agent.currentDraft) draftCache.value[snapshot.agent.currentDraft.id] = snapshot.agent.currentDraft;
      await nextTick();
      if (changed) { if (followingMessages.value) latestMessages(); else hasNewMessages.value = true; }
    }
  } catch (failure) {
    if (alive && selected.value === id) error.value = failure instanceof Error ? failure.message : '无法读取会话';
    if (rethrow) throw failure;
  }
}
async function choose(id: string) {
  selected.value = id; history.value = null; error.value = ''; answerError.value = ''; answering.value = '';
  composing.value = false; historicalDraft.value = null; draftCache.value = {}; followingMessages.value = true; hasNewMessages.value = false; loading.value = true;
  references.value[id] ??= [];
  localStorage.setItem(`conversation:${props.projectId}`, id);
  emit('session', id);
  try {
    const [available] = await Promise.all([request<{ items: Asset[] }>(`/projects/${props.projectId}/assets?includePending=true`), loadHistory(true)]);
    if (alive && selected.value === id) assets.value = available.items;
  } catch (failure) {
    if (alive && selected.value === id) error.value = failure instanceof Error ? failure.message : '无法打开对话';
  } finally { if (alive && selected.value === id) loading.value = false; }
}
async function createChat() {
  if (creating.value || loading.value) return;
  creating.value = true; error.value = '';
  creationKey ??= crypto.randomUUID();
  try {
    const created = await request<Session>(`/projects/${props.projectId}/conversations`, 'POST', {}, { 'Idempotency-Key': creationKey });
    if (!sessions.value.some(item => item.id === created.id)) sessions.value.push(created);
    creationKey = undefined;
    emit('sessions', sessions.value);
    await choose(created.id);
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '新聊天创建未确认，请重试';
  } finally { creating.value = false; }
}
async function send() {
  const id = selected.value, content = text.value;
  if (!id || sending.value || running.value || composing.value || !content.trim()) return;
  const existing = pending.get(id), body = { content, references: [...(references.value[id] ?? [])] };
  const identity = existing && JSON.stringify(existing.body) === JSON.stringify(body) ? existing : { key: crypto.randomUUID(), body };
  pending.set(id, identity); sending.value = true; error.value = '';
  try {
    await request(`/conversations/${id}/turns`, 'POST', identity.body, { 'Idempotency-Key': identity.key });
    pending.delete(id); references.value[id] = [];
    if (texts.value[id] === content) texts.value[id] = '';
    if (selected.value === id) await loadHistory();
  } catch (failure) {
    if (selected.value === id) error.value = failure instanceof Error ? failure.message : '发送未确认，请重试';
  } finally { sending.value = false; }
}
async function control(taskId: string, retry = false) {
  const id = selected.value;
  if (controlling.value) return;
  controlling.value = true; error.value = '';
  try {
    if (retry) {
      const key = retryKeys.get(taskId) ?? crypto.randomUUID();
      retryKeys.set(taskId, key);
      await request(`/conversations/${id}/tasks/${taskId}/retry`, 'POST', {}, { 'Idempotency-Key': key });
    } else await request(`/tasks/${taskId}/cancel`, 'POST', {});
    if (selected.value === id) await loadHistory();
  } catch (failure) {
    if (selected.value === id) error.value = failure instanceof Error ? failure.message : '操作未确认，请重试';
  } finally { controlling.value = false; }
}
async function answerQuestion(question: Question) {
  const content = (answers.value[question.id] ?? '').trim();
  if (!content || answering.value) return;
  const id = selected.value;
  answering.value = question.id; answerError.value = '';
  try {
    await request(`/conversations/${id}/questions/${question.id}/answers`, 'POST', { content }, { 'Idempotency-Key': crypto.randomUUID() });
    answeredQuestions.value = [...answeredQuestions.value, question.id];
    answers.value[question.id] = '';
    if (selected.value === id) await loadHistory();
  } catch (failure) {
    answerError.value = failure instanceof Error ? failure.message : '回答未确认，请重试';
  } finally { answering.value = ''; }
}
async function previewAction(preview: Preview, retry: boolean) {
  const id = selected.value;
  try {
    await request(retry ? `/conversations/${id}/drafts/previews/${preview.requestId}/retry` : `/tasks/${preview.taskId}/cancel`, 'POST', {});
    if (selected.value === id) await loadHistory();
  } catch (failure) {
    if (selected.value === id) error.value = failure instanceof Error ? failure.message : '预览操作未确认，请重试';
  }
}
async function playClip(clip: EditDraft['normalizedContent']['outputs'][number]['clips'][number]) {
  const id = selected.value;
  try {
    const access = await request<Playback>(`/assets/${clip.assetId}/playback-access`, 'POST', {});
    if (alive && selected.value === id) emit('play', { ...access, startMs: clip.sourceInMs, endMs: clip.sourceOutMs });
  } catch (failure) {
    if (alive && selected.value === id) error.value = failure instanceof Error ? failure.message : '无法回看片段';
  }
}
async function inspect(citation: Citation) {
  const id = selected.value;
  try {
    const context = await request<{ assetId: string; observations: { id: string; startMs?: number; observedAtMs?: number }[] }>(`/evidence/${citation.id}/context`);
    const observation = context.observations.find(item => item.id === citation.id)!;
    const access = await request<Playback>(`/assets/${context.assetId}/playback-access`, 'POST', {});
    if (alive && selected.value === id) emit('play', { ...access, startMs: observation.observedAtMs ?? observation.startMs! });
  } catch (failure) {
    if (alive && selected.value === id) error.value = failure instanceof Error ? failure.message : '无法核查原视频';
  }
}
watch(() => props.editReference, value => {
  if (value && selected.value) {
    const { label, ...reference } = value;
    referenceNames.value[`PLAN_REVISION:${reference.id}`] = label ?? '方案版本';
    references.value[selected.value] = [...(references.value[selected.value] ?? []).filter(item => item.type !== 'PLAN_REVISION'), reference];
  }
});
function toggleReference(reference: Citation) {
  const values = references.value[selected.value] ?? [];
  references.value[selected.value] = values.some(item => item.type === reference.type && item.id === reference.id)
    ? values.filter(item => item.type !== reference.type || item.id !== reference.id)
    : [...values, reference];
}
function inputShortcut(event: KeyboardEvent) {
  if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey) || event.isComposing || composing.value) return;
  event.preventDefault(); void send();
}
async function draftChanged() {
  await loadHistory();
  await nextTick();
  if (followingMessages.value && messageLog.value) latestMessages();
}
useProjectEvents(async event => {
  if (alive && (event === null || event.conversationId === selected.value || history.value?.previews.items.some(item => item.taskId === event.taskId))) await loadHistory(true);
});
onMounted(async () => {
  try {
    const existing = await request<{ items: Session[] }>(`/projects/${props.projectId}/conversations`);
    if (!alive) return;
    sessions.value = existing.items;
    emit('sessions', sessions.value);
    const saved = localStorage.getItem(`conversation:${props.projectId}`);
    if (existing.items.length) await choose(existing.items.find(item => item.id === saved)?.id ?? existing.items.at(-1)!.id);
  } catch (failure) {
    if (alive) error.value = failure instanceof Error ? failure.message : '无法打开对话';
  } finally { if (alive) loading.value = false; }
});
onUnmounted(() => { alive = false; });
defineExpose({ createChat, choose });
</script>

<template>
  <section class="conversation-panel" :data-conversation-id="selected" aria-label="制作对话">
    <p v-if="error" role="alert" class="chat-error">{{ error }}</p>
    <aside v-if="diagnostics && history" class="diagnostics" aria-label="制作诊断">
      <p v-if="currentProduction">制作 {{ currentProduction.productionId }} · 编辑代次 {{ currentProduction.editGeneration }}</p>
      <p v-if="currentBinding">校验 {{ currentBinding.validationId }} · 草稿 {{ currentBinding.messageId }}</p>
      <p v-if="history.latestTask">任务 {{ history.latestTask.taskId }}</p>
    </aside>
    <p v-if="loading" class="chat-loading" role="status">正在读取聊天…</p>
    <template v-else-if="session && history">
      <div ref="messageLog" class="message-log" role="log" aria-label="对话记录" aria-live="polite" tabindex="0" @scroll="trackScroll">
        <div class="message-thread">
          <details v-if="currentProduction?.requirements" class="conditions" aria-label="当前制作条件">
            <summary>{{ currentProduction.requirements.topic ?? '当前制作条件' }}{{ currentProduction.frozen ? ' · 已封存' : '' }}</summary>
            <dl><template v-for="(value, name) in currentProduction.requirements" :key="name"><dt>{{ requirementLabels[name] ?? name }}</dt><dd>{{ requirementValue(name, value) }}</dd></template></dl>
          </details>
          <article v-for="message in messages" :key="message.id" :aria-label="message.role === 'USER' ? '用户消息' : '助手答复'" :class="message.role === 'USER' ? 'msg-user' : 'msg-assistant'">
            <p class="message-content">{{ message.content }}</p>
            <template v-for="citation in message.references" :key="`${citation.type}:${citation.id}`">
              <button v-if="citation.type === 'EVIDENCE'" class="text-button citation-link" type="button" @click="inspect(citation)">核查原视频证据</button>
              <template v-else-if="citation.type === 'EDIT_DRAFT'">
                <p class="citation-summary">{{ draftName(citation.id) }}{{ currentDraft?.id === citation.id ? ' · 当前草稿' : ' · 只读' }}</p>
                <button class="text-button" type="button" @click="viewHistoricalDraft(message)">查看草稿</button>
                <button class="text-button" type="button" @click="toggleReference(citation)">引用此草稿继续对话</button>
              </template>
              <template v-else-if="citation.type === 'PREVIEW_RESULT'">
                <p class="citation-summary">{{ citation.sourceDraftMessageId === currentDraft?.id ? '当前草稿预览' : '历史草稿预览' }}</p>
              </template>
              <button v-else-if="citation.type === 'PLAN_REVISION'" class="text-button" type="button" @click="emit('plan', { planId: citation.planId!, revisionId: citation.id, sessionId: selected })">查看已确认方案</button>
            </template>
          </article>

          <div v-if="currentDraft && currentBinding" class="inline-draft">
            <div v-show="!draftDismissed">
              <DraftCard
                :key="`${selected}:${currentDraft.id}`"
                :conversation-id="selected"
                :draft="currentDraft"
                :binding="currentBinding"
                :can-preview="history.agent.canPreviewDraft && !sending"
                :can-accept="history.agent.canAcceptDraft && !sending"
                :assets="assets"
                :readonly-reason="readonlyReason"
                @changed="draftChanged"
                @play="playClip"
                @dismiss="dismissDraft"
              />
            </div>
            <div v-if="draftDismissed" class="draft-collapsed-inline">
              <span>剪辑草稿已收起 · {{ currentDraft.normalizedContent.outputs.map(output => output.title).join('、') }}</span>
              <button class="text-button" type="button" @click="expandDraft">查看草稿</button>
            </div>
          </div>

          <section v-if="pendingQuestions.length" class="questions" aria-label="待回答问题">
            <article v-for="question in pendingQuestions" :key="question.id">
              <div class="question-heading"><span class="q-mark">?</span><strong>{{ question.prompt }}</strong></div>
              <p v-if="question.reason" class="question-reason">{{ question.reason }}</p>
              <p v-if="question.fields?.length" class="question-fields">需要补充：{{ question.fields.map(field => requirementLabels[field] ?? field).join('、') }}</p>
              <form @submit.prevent="answerQuestion(question)">
                <label :for="`answer-${question.id}`" class="sr-only">回答问题</label>
                <textarea :id="`answer-${question.id}`" v-model="answers[question.id]" maxlength="16000" rows="2" placeholder="补充你的选择或说明…" :disabled="answering === question.id"></textarea>
                <div class="question-actions">
                  <button class="primary" type="submit" :disabled="answering === question.id || !(answers[question.id] ?? '').trim()">{{ answering === question.id ? '正在提交…' : '提交回答' }}</button>
                  <button class="text-button" type="button" :disabled="controlling" @click="control(question.taskId)">停止此等待</button>
                </div>
              </form>
              <p v-if="answerError" role="alert" class="question-error">{{ answerError }}</p>
            </article>
          </section>

          <section v-if="historicalDraft" ref="historicalEl" class="historical-draft" tabindex="-1" aria-label="历史草稿详情">
            <header><h3>{{ draftName(historicalDraft.id) }} · 只读</h3><button class="text-button" type="button" @click="closeHistoricalDraft">关闭</button></header>
            <DraftContent v-if="draftCache[historicalDraft.id]" :draft="draftCache[historicalDraft.id]!" :assets="assets" @play="playClip" />
            <p v-else class="message-content">{{ historicalDraft.content }}</p>
            <p class="historical-note">历史草稿仅供查看与引用，不能恢复编辑或重新确认。</p>
          </section>

          <section v-if="previewCards.length" class="preview-list" aria-label="草稿预览记录">
            <h3>草稿预览</h3>
            <article v-for="(card, index) in previewCards" :key="card.key" class="preview-card" aria-label="预览请求">
              <header><strong>预览 {{ index + 1 }} · {{ card.sourceDraftMessageId === currentDraft?.id ? '当前草稿' : '历史草稿预览' }}</strong><span>{{ draftName(card.sourceDraftMessageId) }} · {{ outputName(card) }} · {{ statusLabels[card.request?.status ?? 'SUCCEEDED'] }}</span></header>
              <p v-if="['UNAVAILABLE', 'REMOVED'].includes(card.request?.artifactState ?? '')">预览文件已失效；仅当前可操作草稿支持显式重试。</p>
              <p v-if="card.request?.error" role="alert">{{ card.request.error.message }}</p>
              <div class="preview-actions">
                <button v-if="card.request && ['PENDING', 'RUNNING'].includes(card.request.status)" class="text-button" type="button" @click="previewAction(card.request, false)">停止此预览</button>
                <button v-if="card.request && (['FAILED', 'CANCELLED'].includes(card.request.status) || (card.request.status === 'SUCCEEDED' && ['UNAVAILABLE', 'REMOVED'].includes(card.request.artifactState ?? '')))" class="text-button" type="button" :disabled="!history.agent.canPreviewDraft || sending || card.sourceDraftMessageId !== currentDraft?.id" @click="previewAction(card.request, true)">重试此预览</button>
              </div>
              <ArtifactPlayer v-if="card.artifactId" :key="card.artifactId" :artifact-id="card.artifactId" @changed="loadHistory()" />
            </article>
          </section>

        </div>
      </div>

      <button v-if="hasNewMessages" class="text-button new-messages" type="button" @click="latestMessages">有新消息，回到底部</button>

      <div v-if="history.latestTask" class="task-status">
        <span role="status">{{ history.latestTask.status === 'RUNNING' ? phaseLabels[history.latestTask.phase ?? ''] ?? statusLabels.RUNNING : statusLabels[history.latestTask.status] }}</span>
        <span v-if="history.latestTask.failure" role="alert">{{ history.latestTask.failure.message }}</span>
        <span v-if="history.latestTask.status === 'CANCELLED'" class="task-note">本次执行已停止，制作条件和历史保留。</span>
        <button v-if="running" class="text-button" type="button" :disabled="controlling || history.latestTask.status === 'CANCEL_REQUESTED'" @click="control(history.latestTask.taskId)">停止回复</button>
        <button v-if="history.latestTask.status === 'FAILED' && history.latestTask.failure?.recoverable && history.latestTask.failure.code === 'EXECUTION_INTERRUPTED'" class="text-button" type="button" :disabled="controlling || sending" @click="control(history.latestTask.taskId, true)">重试原需求</button>
      </div>

      <form class="composer" @submit.prevent="send">
        <div v-if="references[selected]?.length" class="reference-chips" aria-label="本轮引用">
          <span v-for="reference in references[selected]" :key="`${reference.type}:${reference.id}`">{{ referenceLabel(reference) }}<button class="text-button" type="button" :aria-label="`移除引用 ${referenceLabel(reference)}`" @click="toggleReference(reference)">×</button></span>
        </div>
        <details v-if="assets.length" class="asset-choices">
          <summary>引用素材</summary>
          <fieldset><legend>本次明确引用素材</legend>
            <label v-for="asset in assets" :key="asset.id" class="choice"><input type="checkbox" :checked="references[selected]?.some(item => item.type === 'ASSET' && item.id === asset.id)" @change="toggleReference({ type: 'ASSET', id: asset.id })">{{ asset.filename }}</label>
          </fieldset>
        </details>
        <label class="sr-only" for="conversation-input">对话输入</label>
        <textarea id="conversation-input" v-model="text" maxlength="8000" required placeholder="继续修改，或描述你想制作的视频…" @compositionstart="composing = true" @compositionend="composing = false" @keydown="inputShortcut"></textarea>
        <div class="composer-footer">
          <span class="composer-hint">Ctrl / ⌘ + Enter 发送</span>
          <button class="primary" type="submit" :disabled="sending || running || !text.trim()">{{ sending ? '正在发送…' : '发送需求' }}</button>
        </div>
      </form>
    </template>
    <section v-else-if="!loading" class="chat-empty">
      <div class="empty-mark" aria-hidden="true">叙</div>
      <h2>{{ sessions.length ? '选择一个聊天' : '开始一段新对话' }}</h2>
      <p>{{ sessions.length ? '从左侧选择聊天继续。' : '告诉助手你想查找什么内容，或想制作哪种视频。' }}</p>
      <button v-if="!sessions.length" class="primary" type="button" :disabled="creating" @click="createChat">{{ creating ? '正在新建…' : '新聊天' }}</button>
    </section>
  </section>
</template>

<style scoped>
.conversation-panel { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; }
.chat-error { margin: 8px 22px 0; color: var(--red); font-size: 12px; }
.diagnostics { margin: 8px 22px 0; padding: 8px 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--panel); font-size: 10px; color: var(--ink-4); display: flex; gap: 12px; flex-wrap: wrap; }
.diagnostics p { margin: 0; }
.chat-loading { margin: 18px 24px; font-size: 12px; color: var(--ink-4); }
.message-log { flex: 1; min-height: 0; overflow: auto; padding: 18px 28px 10px; overscroll-behavior: contain; }
.message-thread { width: 100%; max-width: 680px; margin: 0 auto; display: flex; flex-direction: column; gap: 14px; }
.conditions { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 0 10px; font-size: 11px; color: var(--ink-3); max-height: 160px; overflow: auto; flex: none; }
.conditions summary { min-height: 30px; padding: 7px 0; color: var(--ink-2); }
.conditions dl { margin: 0 0 8px; }
.conditions dt { color: var(--ink-4); }
.conditions dd { margin: 0 0 6px 10px; }
.msg-user, .msg-assistant { font-size: 14px; line-height: 1.72; color: var(--ink); }
.msg-user { display: flex; justify-content: flex-end; }
.msg-user .message-content { max-width: 78%; background: #f2f3f5; border-radius: 13px 13px 4px 13px; padding: 8px 12px; margin: 0; }
.msg-assistant .message-content { margin: 0; white-space: pre-wrap; }
.message-content { overflow-wrap: anywhere; }
.citation-link { margin: 4px 4px 0 0; font-size: 11px; }
.citation-summary { margin: 6px 0 0; font-size: 10.5px; color: var(--ink-4); }
.msg-assistant .text-button { font-size: 11px; }
.inline-draft { margin-top: 2px; }
.inline-draft :deep(h3) { font-size: 12px; color: var(--ink-3); font-weight: 550; margin: 0 0 6px; }
.draft-collapsed-inline { display: flex; align-items: center; gap: 10px; border: 1px dashed var(--line-strong); border-radius: 8px; padding: 8px 12px; background: #fbfbfc; font-size: 11.5px; color: var(--ink-3); }
.draft-collapsed-inline span { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.draft-collapsed-inline button { margin: 0; }
.questions { display: flex; flex-direction: column; gap: 8px; }
.questions article { border: 1px solid #dbe5fb; background: var(--blue-weak); border-radius: 9px; padding: 10px 12px 11px; }
.question-heading { display: flex; align-items: center; gap: 7px; font-size: 12.5px; }
.question-heading strong { font-weight: 600; }
.q-mark { width: 17px; height: 17px; border-radius: 50%; background: #fff; border: 1px solid #cad9f8; display: grid; place-items: center; font-size: 10px; font-weight: 650; color: #2f5fd0; flex: none; }
.question-reason, .question-fields { margin: 6px 0 0; font-size: 10.5px; line-height: 1.55; color: var(--ink-4); }
.questions textarea { margin-top: 8px; min-height: 56px; resize: vertical; font-size: 12px; }
.question-actions { display: flex; align-items: center; gap: 4px; margin-top: 6px; }
.question-actions button { min-height: 28px; font-size: 11px; }
.question-actions .text-button { margin-left: auto; }
.question-error { margin: 6px 0 0; font-size: 11px; }
.historical-draft { border: 1px solid var(--line); border-radius: 9px; padding: 12px 14px; background: #fff; }
.historical-draft header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.historical-draft h3 { font-size: 13px; margin: 0; }
.historical-draft :deep(h4) { font-size: 13px; }
.historical-note { font-size: 10.5px; color: var(--ink-4); margin: 8px 0 0; }
.preview-list { border-top: 1px solid var(--line); padding-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.preview-list h3 { font-size: 12px; color: var(--ink-3); margin: 0; }
.preview-card { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; background: var(--panel); }
.preview-card header { display: flex; flex-direction: column; gap: 2px; font-size: 11.5px; color: var(--ink-2); }
.preview-card header span { font-size: 10px; color: var(--ink-4); }
.preview-card p { margin: 5px 0; font-size: 10.5px; color: var(--ink-3); }
.preview-actions { display: flex; gap: 4px; }
.preview-actions button { font-size: 10.5px; }
.preview-card :deep(video) { max-height: 280px; margin-top: 6px; border-radius: 6px; background: #0f1115; }
.new-messages { align-self: center; flex: none; margin: 4px 0; padding: 4px 11px; border: 1px solid var(--line); border-radius: 999px; background: #fff; box-shadow: 0 2px 8px rgba(15, 17, 21, .08); font-size: 11px; }
.task-status { flex: none; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-height: 30px; padding: 5px 28px; border-top: 1px solid var(--line); background: var(--panel); font-size: 10.5px; color: var(--ink-3); }
.task-status [role='alert'] { color: var(--red); }
.task-status .text-button { margin: 0; min-height: 22px; font-size: 10.5px; }
.task-note { color: var(--ink-4); }
.composer { flex: none; border-top: 1px solid var(--line); padding: 8px 28px 10px; }
.composer > * { max-width: 680px; margin-left: auto; margin-right: auto; }
.composer textarea { display: block; width: 100%; min-height: 72px; max-height: 160px; resize: vertical; border-radius: 12px; padding: 9px 11px; font-size: 13px; line-height: 1.55; }
.reference-chips { display: flex; flex-wrap: wrap; gap: 5px; margin-bottom: 6px; }
.reference-chips > span { display: inline-flex; align-items: center; gap: 2px; max-width: 100%; padding: 2px 5px; border: 1px solid var(--line); border-radius: 4px; background: var(--panel); font-size: 10.5px; color: var(--ink-3); overflow-wrap: anywhere; }
.reference-chips button { min-height: 18px; margin: 0; padding: 0 3px; font-size: 12px; color: var(--ink-4); }
.asset-choices { margin-bottom: 6px; }
.asset-choices summary { min-height: 26px; padding: 4px 0; font-size: 11px; color: var(--ink-3); }
.asset-choices fieldset { max-height: 120px; overflow: auto; }
.choice { display: flex; align-items: center; gap: 7px; margin: 4px 0; font-size: 11px; color: var(--ink-2); }
.choice input { width: 15px; min-height: 15px; }
.composer-footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 6px; }
.composer-hint { font-size: 10px; color: var(--ink-4); }
.composer-footer button { margin: 0; min-width: 84px; }
.chat-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; padding: 32px; }
.empty-mark { width: 42px; height: 42px; border-radius: 10px; background: var(--ink); color: #fff; display: grid; place-items: center; font-size: 15px; margin-bottom: 14px; }
.chat-empty h2 { font-size: 18px; margin: 0 0 6px; }
.chat-empty p { font-size: 12px; color: var(--ink-3); max-width: 320px; line-height: 1.65; margin: 0 0 14px; }
@media (max-width: 1180px) {
  .message-log { padding-left: 20px; padding-right: 20px; }
  .composer { padding-left: 20px; padding-right: 20px; }
  .task-status { padding-left: 20px; padding-right: 20px; }
}
</style>

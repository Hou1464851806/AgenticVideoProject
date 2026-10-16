<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { request } from './api';
import { useProjectEvents } from './projectEvents';
import ArtifactPlayer from './ArtifactPlayer.vue';
import DraftContent from './DraftContent.vue';
import DraftCard, { type EditDraft, type DraftBinding } from './DraftCard.vue';
import type { Playback } from './AssetLibrary.vue';
import type { PlanSelection, PlanReference } from './PlanPanel.vue';
const props = defineProps<{ projectId: string; editReference?: PlanReference | null; hasSource?: boolean }>();
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
type PreviewCard = { key: string; request: Preview | null; sourceDraftMessageId: string; outputKey: string; artifactId?: string };
const detailMode = ref<'draft' | 'preview' | 'history' | 'source'>('draft');
let sourceRequest = 0;
const sourceReturnMode = ref<'draft' | 'preview' | 'history'>('draft');
const narrowPane = ref<'conversation' | 'preview'>('conversation');
const historicalDraft = ref<Message | null>(null);
const draftCache = ref<Record<string, EditDraft>>({});
const referenceNames = ref<Record<string, string>>({});
const composing = ref(false);
const messageLog = ref<HTMLElement | null>(null);
const followingMessages = ref(true), hasNewMessages = ref(false);
function trackScroll() {
  const log = messageLog.value!;
  followingMessages.value = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
  if (followingMessages.value) hasNewMessages.value = false;
}
function latestMessages() {
  if (messageLog.value) messageLog.value.scrollTop = messageLog.value.scrollHeight;
  followingMessages.value = true; hasNewMessages.value = false;
}
function inputShortcut(event: KeyboardEvent) {
  if (event.key !== 'Enter' || !(event.ctrlKey || event.metaKey) || event.isComposing || composing.value) return;
  event.preventDefault(); void send();
}
async function showDetail(mode: typeof detailMode.value) {
  detailMode.value = mode; narrowPane.value = 'preview';
  await nextTick();
  document.getElementById('production-preview')?.focus({ preventScroll: true });
}
function navigateProduction(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
  event.preventDefault();
  narrowPane.value = event.key === 'Home' ? 'conversation' : event.key === 'End' ? 'preview' : narrowPane.value === 'conversation' ? 'preview' : 'conversation';
  document.getElementById(narrowPane.value === 'conversation' ? 'production-chat-tab' : 'production-preview-tab')!.focus();
}
watch(() => props.hasSource, value => {
  if (!value && detailMode.value === 'source') detailMode.value = sourceReturnMode.value;
});
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
  if (name === 'profile') return value === 'PORTRAIT_720' ? '竖屏 720p' : value === 'LANDSCAPE_720' ? '横屏 720p' : String(value);
  if (typeof value === 'boolean') return value ? '开启' : '关闭';
  if (name === 'outputs') return (value as { output_key: string; duration_min_ms: number | null; duration_max_ms: number | null }[])
    .map((output, index) => {
      const minimum = output.duration_min_ms === null ? '不限最短' : `${output.duration_min_ms / 1000} 秒起`;
      const maximum = output.duration_max_ms === null ? '不限最长' : `${output.duration_max_ms / 1000} 秒止`;
      return `输出 ${index + 1}：${minimum}，${maximum}`;
    }).join('；');
  if (Array.isArray(value)) return value.map(item => name === 'asset_ids' ? assets.value.find(asset => asset.id === item)?.filename ?? '素材名称暂不可用' : String(item)).join('、') || '无';
  return String(value);
}
// The current draft is restored even when its message is outside the recent history window.
const messages = computed(() => {
  const items = history.value?.items ?? [], draft = currentDraft.value;
  return draft && !items.some(item => item.id === draft.id)
    ? [{ id: draft.id, role: 'ASSISTANT', content: draft.content, references: [{ type: 'EDIT_DRAFT', id: draft.id }] }, ...items] : items;
});
const previewCards = computed<PreviewCard[]>(() => {
  const requests = history.value?.previews.items ?? [];
  const cards: PreviewCard[] = requests.map(request => ({ key: request.requestId, request, sourceDraftMessageId: request.sourceDraftMessageId, outputKey: request.outputKey, artifactId: request.artifactId }));
  const seen = new Set(requests.map(request => request.artifactId));
  for (const message of messages.value) for (const citation of message.references) {
    if (citation.type !== 'PREVIEW_RESULT' || seen.has(citation.id)) continue;
    seen.add(citation.id);
    cards.push({ key: `artifact:${citation.id}`, request: null, sourceDraftMessageId: citation.sourceDraftMessageId!, outputKey: citation.outputKey!, artifactId: citation.id });
  }
  return cards;
});
const readonlyReason = computed(() => history.value?.agent.currentProduction?.frozen
  ? '此制作已确认封存，当前草稿只读。可以继续讨论或开始新制作。'
  : running.value || sending.value ? '正在处理新的需求，本稿暂不可操作；原制作资格以最新快照为准。'
  : '制作条件或编辑状态已变化，本稿只读。失败或停止不会恢复旧稿资格。');
function draftName(id: string) {
  const known = draftCache.value[id];
  if (known) return known.normalizedContent.outputs.map(output => output.title).join('、');
  const index = (history.value?.items ?? []).filter(message => message.references.some(ref => ref.type === 'EDIT_DRAFT')).findIndex(message => message.id === id);
  return index >= 0 ? `历史草稿 ${index + 1}` : '历史草稿';
}
function outputName(card: PreviewCard) { return draftCache.value[card.sourceDraftMessageId]?.normalizedContent.outputs.find(output => output.output_key === card.outputKey)?.title ?? '输出名称暂不可用'; }
function referenceLabel(reference: Citation) {
  if (reference.type === 'ASSET') return assets.value.find(asset => asset.id === reference.id)?.filename ?? '素材名称暂不可用';
  if (reference.type === 'QUESTION') return history.value?.agent.pendingQuestions.find(question => question.questionId === reference.id)?.prompt ?? '历史问题';
  if (reference.type === 'EDIT_DRAFT') return draftName(reference.id);
  return referenceNames.value[`${reference.type}:${reference.id}`] ?? '方案版本';
}
function viewHistoricalDraft(message: Message) { historicalDraft.value = message; showDetail('history'); }
async function draftChanged(action: 'accept' | 'preview') { await loadHistory(); if (action === 'preview' && previewCards.value.length) showDetail('preview'); }
function showSource(value: Playback) {
  if (detailMode.value !== 'source') sourceReturnMode.value = detailMode.value;
  emit('play', value);
  void showDetail('source');
}
async function playClip(clip: EditDraft['normalizedContent']['outputs'][number]['clips'][number]) {
  const id = selected.value, operation = ++sourceRequest;
  try {
    const access = await request<Playback>(`/assets/${clip.assetId}/playback-access`, 'POST', {});
    if (alive && selected.value === id && operation === sourceRequest) showSource({ ...access, startMs: clip.sourceInMs, endMs: clip.sourceOutMs });
  } catch (failure) { if (alive && selected.value === id && operation === sourceRequest) error.value = failure instanceof Error ? failure.message : '无法回看片段'; }
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
    ? values.filter(item => item.type !== reference.type || item.id !== reference.id) : [...values, reference];
}
async function loadHistory(rethrow = false) {
  const id = selected.value;
  if (!id) return;
  try {
    const snapshot = await request<Snapshot>(`/conversations/${id}/snapshot`);
    const sequence = BigInt(snapshot.cursor.split(':')[1]!);
    if (alive && selected.value === id && sequence >= (cursors.get(id) ?? 0n)) {
      const changed = history.value?.items.at(-1)?.id !== snapshot.items.at(-1)?.id;
      cursors.set(id, sequence); history.value = snapshot;
      session.value!.assetIds = snapshot.assetIds;
      if (snapshot.agent.currentDraft) draftCache.value[snapshot.agent.currentDraft.id] = snapshot.agent.currentDraft;
      if (!snapshot.agent.currentDraft && detailMode.value === 'draft' && previewCards.value.length) detailMode.value = 'preview';
      await nextTick();
      if (changed) { if (followingMessages.value) latestMessages(); else hasNewMessages.value = true; }
    }
  } catch (failure) {
    if (alive && selected.value === id) error.value = failure instanceof Error ? failure.message : '无法读取会话';
    if (rethrow) throw failure;
  }
}
async function choose(id: string) {
  ++sourceRequest; selected.value = id; history.value = null; error.value = '';
  composing.value = false; draftCache.value = {}; historicalDraft.value = null; detailMode.value = 'draft'; narrowPane.value = 'conversation'; followingMessages.value = true; hasNewMessages.value = false;
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
  if (!id || sending.value || running.value || composing.value || !content.trim()) return;
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
  const id = selected.value, operation = ++sourceRequest;
  try {
    const context = await request<{ assetId: string; observations: { id: string; startMs?: number; observedAtMs?: number }[] }>(`/evidence/${citation.id}/context`);
    const observation = context.observations.find(item => item.id === citation.id)!;
    const access = await request<Playback>(`/assets/${context.assetId}/playback-access`, 'POST', {});
    if (alive && selected.value === id && operation === sourceRequest) showSource({ ...access, startMs: observation.observedAtMs ?? observation.startMs! });
  } catch (failure) { if (alive && selected.value === id && operation === sourceRequest) error.value = failure instanceof Error ? failure.message : '无法核查原视频'; }
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
    <div class="conversation-toolbar"><div><label class="sr-only" for="session-select">制作会话</label><select v-if="sessions.length" id="session-select" :value="selected" @change="choose(($event.target as HTMLSelectElement).value)"><option v-for="item in sessions" :key="item.id" :value="item.id">{{ item.title }}</option></select><span v-else>{{ loading ? '正在读取聊天…' : '还没有聊天' }}</span></div><button type="button" :disabled="creating || loading" @click="createChat">新聊天</button></div>
    <p v-if="!sessions.length && !loading">新建聊天后，可以问答、讨论或提出制作需求。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="session && history">
      <div class="production-tabs" role="tablist" aria-label="制作区域"><button id="production-chat-tab" role="tab" :aria-selected="narrowPane === 'conversation'" aria-controls="production-chat" :tabindex="narrowPane === 'conversation' ? 0 : -1" @keydown="navigateProduction" @click="narrowPane = 'conversation'">对话</button><button id="production-preview-tab" role="tab" :aria-selected="narrowPane === 'preview'" aria-controls="production-preview" :tabindex="narrowPane === 'preview' ? 0 : -1" @keydown="navigateProduction" @click="narrowPane = 'preview'">预览</button></div>
      <div class="production-layout">
        <section id="production-chat" class="chat-column" :class="{ 'narrow-hidden': narrowPane !== 'conversation' }" role="tabpanel" aria-label="制作对话" aria-labelledby="production-chat-tab">
          <details v-if="history.agent.currentProduction" class="conditions" aria-label="当前制作"><summary>{{ history.agent.currentProduction.requirements.topic ?? '当前制作条件' }}{{ history.agent.currentProduction.frozen ? ' · 已封存' : '' }}</summary><dl><template v-for="(value, name) in history.agent.currentProduction.requirements" :key="name"><dt>{{ requirementLabels[name] ?? name }}</dt><dd>{{ requirementValue(name, value) }}</dd></template></dl></details>
          <div ref="messageLog" class="message-log" role="log" aria-label="对话记录" aria-live="polite" tabindex="0" @scroll="trackScroll">
            <article v-for="message in messages" :key="message.id" :aria-label="message.role === 'USER' ? '用户消息' : '助手答复'">
              <strong>{{ message.role === 'USER' ? '你' : '助手' }}</strong><p class="message-content">{{ message.content }}</p>
              <template v-for="citation in message.references" :key="`${citation.type}:${citation.id}`">
                <button v-if="citation.type === 'EVIDENCE'" class="text-button" @click="inspect(citation)">核查原视频证据</button>
                <template v-else-if="citation.type === 'EDIT_DRAFT'"><p class="citation-summary">{{ draftName(citation.id) }}{{ currentDraft?.id === citation.id ? ' · 当前草稿' : ' · 只读' }}</p><button class="text-button" @click="currentDraft?.id === citation.id ? showDetail('draft') : viewHistoricalDraft(message)">查看草稿</button><button class="text-button" @click="toggleReference(citation)">引用此草稿继续对话</button></template>
                <template v-else-if="citation.type === 'PREVIEW_RESULT'"><p>{{ citation.sourceDraftMessageId === currentDraft?.id ? '当前草稿预览' : '历史草稿预览' }}</p><button class="text-button" @click="showDetail('preview')">查看预览记录</button></template>
                <button v-else-if="citation.type === 'PLAN_REVISION'" class="text-button" @click="emit('plan', { planId: citation.planId!, revisionId: citation.id, sessionId: selected })">查看已确认方案</button>
              </template>
            </article>
          </div>
          <button v-if="hasNewMessages" class="text-button new-messages" @click="latestMessages">有新消息，回到底部</button>
          <section v-if="history.agent.pendingQuestions.length" class="questions" aria-label="待回答问题"><div v-for="question in history.agent.pendingQuestions" :key="question.questionId"><p>{{question.prompt}}</p><p>在下方回答，也可继续独立问答或开始新制作。</p><button class="text-button" @click="toggleReference({ type: 'QUESTION', id: question.questionId })">引用此问题</button><button class="text-button" :disabled="controlling" @click="control(question.taskId)">停止此等待</button></div></section>
          <div v-if="history.latestTask" class="task-status"><p role="status">{{ phaseLabels[history.latestTask.phase ?? ''] ?? statusLabels[history.latestTask.status] }}</p><p v-if="history.latestTask.failure" role="alert">{{history.latestTask.failure.message}}</p><p v-if="history.latestTask.status === 'CANCELLED'">本次执行已停止，制作条件和历史保留。</p><button v-if="running" class="text-button" :disabled="controlling || history.latestTask.status === 'CANCEL_REQUESTED'" @click="control(history.latestTask.taskId)">停止回复</button><button v-if="history.latestTask.status === 'FAILED' && history.latestTask.failure?.recoverable && history.latestTask.failure.code === 'EXECUTION_INTERRUPTED'" class="text-button" :disabled="controlling || sending" @click="control(history.latestTask.taskId, true)">重试原需求</button></div>
          <form class="composer" @submit.prevent="send">
            <div v-if="references[selected]?.length" class="reference-chips" aria-label="本轮引用"><span v-for="reference in references[selected]" :key="`${reference.type}:${reference.id}`">{{referenceLabel(reference)}}<button type="button" class="text-button" :aria-label="`移除引用 ${referenceLabel(reference)}`" @click="toggleReference(reference)">×</button></span></div>
            <details v-if="session.assetIds.length" class="asset-choices"><summary>引用素材</summary><fieldset><legend>本次明确引用素材</legend><label v-for="asset in assets.filter(item => session!.assetIds.includes(item.id))" :key="asset.id" class="choice"><input type="checkbox" :checked="references[selected]?.some(item => item.type === 'ASSET' && item.id === asset.id)" @change="toggleReference({ type: 'ASSET', id: asset.id })">{{ asset.filename }}</label></fieldset></details>
            <label class="sr-only" for="conversation-input">对话输入</label><textarea id="conversation-input" v-model="text" maxlength="8000" required placeholder="问答、讨论，或描述你想制作的视频" @compositionstart="composing = true" @compositionend="composing = false" @keydown="inputShortcut"></textarea>
            <div class="composer-footer"><span>Ctrl / ⌘ + Enter 发送</span><button type="submit" :disabled="sending || running || !text.trim()">发送需求</button></div>
          </form>
        </section>
        <section id="production-preview" class="detail-column" :class="{ 'narrow-hidden': narrowPane !== 'preview' }" role="tabpanel" aria-label="制作预览" aria-labelledby="production-preview-tab" tabindex="-1">
          <div class="detail-navigation"><button v-if="currentDraft" class="text-button" :aria-pressed="detailMode === 'draft'" @click="showDetail('draft')">当前草稿</button><button v-if="previewCards.length" class="text-button" :aria-pressed="detailMode === 'preview'" @click="showDetail('preview')">预览记录</button><button v-if="hasSource" class="text-button" :aria-pressed="detailMode === 'source'" @click="showDetail('source')">原片</button></div>
          <div v-show="detailMode === 'source'"><slot name="source" /></div>
          <DraftCard v-if="currentDraft" v-show="detailMode === 'draft'" :key="`${selected}:${currentDraft.id}`" :conversation-id="selected" :draft="currentDraft" :binding="history.agent.currentProduction!.currentDraft!" :can-preview="history.agent.canPreviewDraft && !sending" :can-accept="history.agent.canAcceptDraft && !sending" :assets="assets" :readonly-reason="readonlyReason" @changed="draftChanged" @play="playClip" />
          <section v-if="detailMode === 'history' && historicalDraft" aria-label="历史草稿详情"><h3>{{draftName(historicalDraft.id)}} · 只读</h3><DraftContent v-if="draftCache[historicalDraft.id]" :draft="draftCache[historicalDraft.id]!" :assets="assets" @play="playClip" /><p v-else class="message-content">{{historicalDraft.content}}</p><p>历史草稿仅供查看与引用，不能恢复编辑或重新确认。</p></section>
          <section v-show="detailMode === 'preview'" aria-label="草稿预览请求"><article v-for="(card, index) in previewCards" :key="card.key" aria-label="预览请求"><h3>预览 {{index + 1}} · {{card.sourceDraftMessageId === currentDraft?.id ? '当前草稿' : '历史草稿预览'}}</h3><p>{{draftName(card.sourceDraftMessageId)}} · {{outputName(card)}} · {{statusLabels[card.request?.status ?? 'SUCCEEDED']}}</p><p v-if="['UNAVAILABLE', 'REMOVED'].includes(card.request?.artifactState ?? '')">预览文件已失效；仅当前可操作草稿支持显式重试。</p><p v-if="card.request?.error" role="alert">{{card.request.error.message}}</p><button v-if="card.request && ['PENDING', 'RUNNING'].includes(card.request.status)" class="text-button" @click="previewAction(card.request, false)">停止此预览</button><button v-if="card.request && (['FAILED', 'CANCELLED'].includes(card.request.status) || (card.request.status === 'SUCCEEDED' && ['UNAVAILABLE', 'REMOVED'].includes(card.request.artifactState ?? '')))" class="text-button" :disabled="!history.agent.canPreviewDraft || sending || card.sourceDraftMessageId !== currentDraft?.id" @click="previewAction(card.request, true)">重试此预览</button><ArtifactPlayer v-if="card.artifactId" :key="card.artifactId" :artifact-id="card.artifactId" @changed="loadHistory()" /></article></section>
          <p v-if="detailMode === 'draft' && !currentDraft" class="preview-empty">草稿生成后会在这里显示。可以先与助手讨论，或核查引用的原视频。</p>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.conversation-toolbar { display: flex; gap: 16px; align-items: center; margin-bottom: 16px; }
.conversation-toolbar > div { flex: 1; min-width: 0; }
.conversation-toolbar button { margin: 0; flex-shrink: 0; }
.production-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 24px; }
.chat-column { display: flex; flex-direction: column; min-width: 0; height: max(520px, calc(100dvh - 300px)); max-height: 860px; }
.conditions { margin-bottom: 12px; max-height: 180px; overflow-y: auto; flex-shrink: 0; background: #edf3ee; padding: 0 12px; border-radius: 8px; }
.conditions dl { font-size: 13px; }
.conditions dd { margin: 0 0 8px 12px; }
.message-log { flex: 1; min-height: 140px; overflow-y: auto; padding-right: 12px; overscroll-behavior: contain; }
article { border-bottom: 1px solid var(--line); padding: 16px 0; font-size: 14px; }
article:first-child { padding-top: 0; }
p, dd { overflow-wrap: anywhere; }
.message-content { white-space: pre-wrap; line-height: 1.7; }
.citation-summary, .composer-footer span { color: var(--muted); font-size: 13px; }
.questions { max-height: 180px; overflow-y: auto; background: #fff5df; padding: 12px; }
.questions p, .task-status p { margin: 4px 0; font-size: 13px; }
.task-status { max-height: 140px; overflow-y: auto; flex-shrink: 0; }
.composer { border-top: 1px solid var(--line); margin-top: 12px; padding-top: 8px; }
textarea { width: 100%; min-height: 88px; resize: vertical; }
.composer-footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.composer-footer button { margin-right: 0; }
.reference-chips { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0; }
.reference-chips > span { display: inline-flex; align-items: center; max-width: 100%; overflow-wrap: anywhere; background: #edf3ee; border-radius: 6px; padding-left: 8px; font-size: 13px; }
.reference-chips button { margin: 0; min-width: 44px; }
.asset-choices summary, .conditions summary { min-height: 44px; padding: 10px 0; cursor: pointer; }
.asset-choices fieldset { max-height: 120px; overflow-y: auto; }
.choice { display: flex; align-items: center; gap: 8px; margin: 8px 0; }
.choice input { width: auto; }
.detail-column { min-width: 0; border-left: 1px solid var(--line); padding-left: 24px; }
.detail-navigation { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
.detail-navigation button { margin: 0; }
.detail-navigation button[aria-pressed='true'] { background: #edf3ee; }
.detail-column h3 { font-size: 18px; }
.preview-empty { color: var(--muted); padding: 32px 0; }
.production-tabs { display: none; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media(max-width: 1024px) {
  .production-layout { display: block; }
  .production-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
  .production-tabs button { flex: 1; margin: 0; }
  .production-tabs button[aria-selected='true'] { background: #245c45; color: white; }
  .narrow-hidden { display: none; }
  .detail-column { border-left: 0; padding-left: 0; }
  .chat-column { height: max(540px, calc(100dvh - 300px)); }
}
</style>

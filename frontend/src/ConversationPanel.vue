<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { request } from './api';
import type { Playback } from './AssetLibrary.vue';
const props = defineProps<{ projectId: string }>();
const emit = defineEmits<{ play: [value: Playback] }>();
type Session = { id: string; title: string; assetIds: string[] };
type Asset = { id: string; filename: string };
type Citation = { type: string; id: string; assetId?: string; text?: string; startMs?: number; endMs?: number };
type Message = { id: string; role: string; content: string; references: Citation[] };
type History = { items: Message[]; latestTask: { taskId: string; status: string } | null };
const sessions = ref<Session[]>([]);
const assets = ref<Asset[]>([]);
const scope = ref<string[]>([]);
const title = ref('');
const selected = ref('');
const drafts = ref<Record<string,string>>({});
const references = ref<Record<string,string[]>>({});
const messages = ref<Message[]>([]);
const latestTask = ref<History['latestTask']>(null);
const creating = ref(false);
const sending = ref(false);
const error = ref('');
const pending = new Map<string,{ key: string; serialized: string }>();
const session = computed(() => sessions.value.find(item => item.id === selected.value));
const running = computed(() => ['PENDING','RUNNING','CANCEL_REQUESTED','WAITING_INPUT'].includes(latestTask.value?.status ?? ''));
const draft = computed({ get: () => drafts.value[selected.value] ?? '', set: (value: string) => { drafts.value[selected.value] = value; } });
let alive = true, reading = false, readingAssets = false;
let timer: ReturnType<typeof setInterval> | undefined;

async function loadAssets() {
  if (readingAssets) return;
  readingAssets = true;
  try {
    const available = (await request<{items:Asset[]}>(`/projects/${props.projectId}/assets`)).items;
    if (!alive) return;
    const known = new Set(assets.value.map(asset => asset.id));
    const valid = new Set(available.map(asset => asset.id));
    scope.value = [...scope.value.filter(id => valid.has(id)), ...available.filter(asset => !known.has(asset.id)).map(asset => asset.id)];
    assets.value = available;
  } catch (failure) { if (alive) error.value = failure instanceof Error ? failure.message : '无法读取素材范围'; }
  finally { readingAssets = false; }
}

async function loadHistory() {
  const id = selected.value;
  if (!id || reading) return;
  reading = true;
  try {
    const history = await request<History>(`/conversations/${id}/messages`);
    if (alive && selected.value === id) { messages.value = history.items; latestTask.value = history.latestTask; }
  } catch (failure) { if (alive && selected.value === id) error.value = failure instanceof Error ? failure.message : '无法读取会话'; }
  finally { reading = false; }
}
async function choose(id: string) {
  references.value[id] ??= [];
  selected.value = id; messages.value = []; latestTask.value = null; error.value = '';
  await loadHistory();
}
async function create() {
  creating.value = true; error.value = '';
  try {
    const created = await request<Session>(`/projects/${props.projectId}/conversations`, 'POST', { title: title.value, assetIds: scope.value });
    sessions.value.push(created); title.value = '';
    await choose(created.id);
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '无法创建制作会话'; }
  finally { creating.value = false; }
}
async function send() {
  if (!selected.value || sending.value || running.value || !draft.value.trim()) return;
  const id = selected.value, content = draft.value;
  const body = { content, references: (references.value[id] ?? []).map(assetId => ({ type: 'ASSET', id: assetId })), context: {} };
  const serialized = JSON.stringify(body);
  const identity = pending.get(id)?.serialized === serialized ? pending.get(id)! : { key: crypto.randomUUID(), serialized };
  pending.set(id, identity);
  sending.value = true; error.value = '';
  try {
    const accepted = await request<{taskId:string}>(`/conversations/${id}/turns`, 'POST', body, { 'Idempotency-Key': identity.key });
    pending.delete(id);
    if (drafts.value[id] === content) drafts.value[id] = '';
    if (selected.value === id) { latestTask.value = { taskId: accepted.taskId, status: 'PENDING' }; await loadHistory(); }
  } catch (failure) { if (selected.value === id) error.value = failure instanceof Error ? failure.message : '无法发送需求'; }
  finally { sending.value = false; }
}
async function inspect(citation: Citation) {
  if (!citation.assetId) return;
  try { emit('play', { ...await request<Playback>(`/assets/${citation.assetId}/playback-access`, 'POST', {}), startMs: citation.startMs ?? 0 }); }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法核查原视频'; }
}
onMounted(async () => {
  try {
    const [available, existing] = await Promise.all([
      request<{items:Asset[]}>(`/projects/${props.projectId}/assets`),
      request<{items:Session[]}>(`/projects/${props.projectId}/conversations`),
    ]);
    if (!alive) return;
    assets.value = available.items; scope.value = available.items.map(asset => asset.id); sessions.value = existing.items;
    if (existing.items.length) await choose(existing.items[existing.items.length-1]!.id);
    timer = setInterval(() => { void loadHistory(); void loadAssets(); }, 1000);
  } catch (failure) { if (alive) error.value = failure instanceof Error ? failure.message : '无法打开对话'; }
});
onUnmounted(() => { alive = false; clearInterval(timer); });
</script>

<template>
  <section class="conversation-panel">
    <form @submit.prevent="create">
      <label for="session-title">会话名称</label><input id="session-title" v-model="title" required maxlength="120" placeholder="例如：寻找开场原句">
      <fieldset v-if="assets.length"><legend>新会话的固定素材范围</legend>
        <label v-for="asset in assets" :key="asset.id" class="choice"><input v-model="scope" type="checkbox" :value="asset.id">{{ asset.filename }}</label>
      </fieldset>
      <button type="submit" :disabled="creating">创建制作会话</button>
    </form>
    <p v-if="!sessions.length" class="empty">还没有制作会话。</p>
    <label v-if="sessions.length" for="session-select">制作会话</label>
    <select v-if="sessions.length" id="session-select" :value="selected" @change="choose(($event.target as HTMLSelectElement).value)">
      <option v-for="item in sessions" :key="item.id" :value="item.id">{{ item.title }}</option>
    </select>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="session">
      <p class="scope">本会话固定包含 {{ session.assetIds.length }} 个素材，查询无需创建剪辑方案。</p>
      <div class="messages" aria-live="polite">
        <article v-for="message in messages" :key="message.id" :aria-label="message.role === 'USER' ? '用户需求' : '助手答复'">
          <strong>{{ message.role === 'USER' ? '你' : '助手' }}</strong><p class="message-content">{{ message.content }}</p>
          <div v-for="citation in message.references.filter(item => item.type === 'EVIDENCE')" :key="citation.id" class="citation">
            <p>语音原文：{{ citation.text }}</p><small>{{ ((citation.startMs ?? 0)/1000).toFixed(2) }}–{{ ((citation.endMs ?? 0)/1000).toFixed(2) }} 秒 · 模型观察，建议回看核查</small>
            <button class="secondary" @click="inspect(citation)">核查引用原视频</button>
          </div>
        </article>
      </div>
      <p v-if="running" role="status">正在查询素材；可以继续编辑草稿，完成后再发送。</p>
      <p v-else-if="latestTask?.status === 'FAILED'" role="alert">本次对话未完成，未发布正式答复。</p>
      <form @submit.prevent="send">
        <fieldset v-if="session.assetIds.length"><legend>本次明确引用</legend>
          <label v-for="asset in assets.filter(item => session?.assetIds.includes(item.id))" :key="asset.id" class="choice"><input v-model="references[selected]" type="checkbox" :value="asset.id" :aria-label="`引用素材 ${asset.filename}`">{{ asset.filename }}</label>
        </fieldset>
        <label for="conversation-input">对话输入</label><textarea id="conversation-input" v-model="draft" maxlength="8000" required placeholder="例如：素材里介绍了哪些制作步骤？"></textarea>
        <button type="submit" :disabled="sending || running || !draft.trim()">发送需求</button>
      </form>
    </template>
  </section>
</template>

<style scoped>
fieldset { border: 1px solid #dce5de; border-radius: 8px; margin-top: 12px; padding: 10px; }
legend,.scope { font-size: 12px; color: #69766e; }
.choice { display: flex; align-items: center; gap: 8px; margin: 6px 0; font-size: 13px; overflow-wrap: anywhere; }
.choice input { width: auto; margin: 0; }
select,textarea { width: 100%; box-sizing: border-box; border: 1px solid #c9d8ce; border-radius: 8px; padding: 10px; font: inherit; }
textarea { min-height: 100px; resize: vertical; }
article { border-bottom: 1px solid #dce5de; padding: 14px 0; font-size: 14px; }
.message-content { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
.citation { padding: 10px; border-radius: 8px; background: #f1f5f2; margin: 8px 0; overflow-wrap: anywhere; }
.citation button { font-size: 12px; }
</style>

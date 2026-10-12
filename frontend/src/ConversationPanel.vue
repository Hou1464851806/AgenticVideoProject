<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { request } from './api';
import { useProjectEvents } from './projectEvents';
import type { Playback } from './AssetLibrary.vue';
import type { PlanBase, PlanSelection } from './PlanPanel.vue';
const props = defineProps<{ projectId: string; editingBase?: PlanBase | null }>();
const emit = defineEmits<{ play: [value: Playback]; plan: [value: PlanSelection]; session: [value: string]; exported: [requestId:string] }>();
type Session = { id: string; title: string; assetIds: string[] };
type Asset = { id: string; filename: string };
type Citation = { type: string; id: string; assetId?: string; text?: string; startMs?: number; endMs?: number; observedAtMs?:number; sourceKind?:string; planId?: string; revisionNumber?: number };
type Message = { id: string; role: string; content: string; references: Citation[] };
type AssistantDraft = {messageId:string;attemptId:string;taskId:string;content:string;contentVersion:number;status:string;current:boolean};
type BriefCondition={value:unknown;basis:string;sourceMessageId:string};
type Brief={revision:number;fields:Record<string,BriefCondition>;history:{revision:number;fields:Record<string,BriefCondition>;sourceMessageId:string}[]};
type Question={id:string;taskId:string;turnId:string;prompt:string;reason:string;fields:string[];status:string;answerMessageId:string|null;editing?:Record<string,unknown>|null};
type History = { brief?:Brief;questions?:Question[]; draftsNextCursor?:string|null; draftsTruncated?:boolean; drafts?:AssistantDraft[]; items: Message[]; latestTask: { taskId: string; status: string; phase?:string; externalEffects?:{unknownCost:boolean;stopSupported:boolean;message:string}; failure?: {code:string;message:string} | null } | null };
const sessions = ref<Session[]>([]);
const assets = ref<Asset[]>([]);
const scope = ref<string[]>([]);
const title = ref('');
const selected = ref('');
const drafts = ref<Record<string,string>>({});
const references = ref<Record<string,string[]>>({});
const messages = ref<Message[]>([]);
const brief=ref<Brief|null>(null),questions=ref<Question[]>([]);
const answers=ref<Record<string,string>>({});
const useChangedBase=ref<Record<string,boolean>>({});
const appliedBrief=ref<Record<string,number>>({});
const questionKeys=new Map<string,{serialized:string;key:string}>();
const openQuestions=computed(()=>questions.value.filter(item=>item.status==='OPEN'));
const briefLabels:Record<string,string>={topic:'主题',count:'数量',duration:'硬时长',assetIds:'素材范围',forbiddenAssetIds:'禁用素材',forbiddenContent:'禁用内容',profile:'画幅'};
function briefValue(name:string,value:unknown):string{
  if(name==='duration'&&value&&typeof value==='object'&&'minMs' in value&&'maxMs' in value)return `${Number(value.minMs)/1000}–${Number(value.maxMs)/1000} 秒`;
  if(Array.isArray(value))return value.map(item=>['assetIds','forbiddenAssetIds'].includes(name)?assets.value.find(asset=>asset.id===item)?.filename??String(item):String(item)).join('、')||'无';
  if(name==='profile')return value==='LANDSCAPE_720'?'横屏 720p':'竖屏 720p';
  return String(value);
}
async function resolveQuestion(question:Question,startNew:boolean){
  const id=selected.value,content=startNew?draft.value:answers.value[question.id]??'';if(!content.trim()||controlling.value)return;
  const body=startNew?{content,references:[],context:{requirements:true}}:{content,...(useChangedBase.value[question.id]&&props.editingBase&&question.editing?{editing:{...question.editing,planId:props.editingBase.planId,baseRevisionId:props.editingBase.revisionId}}:{})};
  const serialized=JSON.stringify(body),identityKey=question.id+(startNew?':new':':answer');
  const identity=questionKeys.get(identityKey)?.serialized===serialized?questionKeys.get(identityKey)!:{serialized,key:crypto.randomUUID()};questionKeys.set(identityKey,identity);
  controlling.value=true;error.value='';
  try{
    await request(`/conversations/${id}/questions/${question.id}/${startNew?'new-requirement':'answers'}`,'POST',body,{'Idempotency-Key':identity.key});
    if(selected.value===id){if(startNew)draft.value='';else answers.value[question.id]='';settings.value.mode='REQUIREMENTS';await loadHistory();}
  }catch(failure){if(selected.value===id)error.value=failure instanceof Error?failure.message:'回答未确认，请重试';}
  finally{controlling.value=false;}
}

const assistantDrafts=ref<AssistantDraft[]>([]);
const activity=ref('');
const phaseLabels:Record<string,string>={SEARCHING:'正在检索证据',READING_EVIDENCE:'正在核查原文',MODELING:'正在组织答复',SELF_CHECKING:'正在自检候选',VALIDATING:'正在验证方案'};
const earlierDrafts=ref<AssistantDraft[]>([]),draftCursor=ref<string|null>(null);
const visibleDrafts=computed(()=>[...new Map([...assistantDrafts.value,...earlierDrafts.value].map(item=>[item.messageId,item])).values()]);
const latestTask = ref<History['latestTask']>(null);
const creating = ref(false);
const sending = ref(false);
const controlling=ref(false),acknowledgeUnknownCost=ref(false);
const retryKeys=new Map<string,string>();
const error = ref('');
const pending = new Map<string,{ key: string; serialized: string }>();
type Settings = {mode:string;topic:string;minSeconds:number;maxSeconds:number;profile:string;useBase:boolean;orderedAssetIds:string[]};
const editing = ref<Record<string,Settings>>({});
const settings = computed(() => editing.value[selected.value]!);
const session = computed(() => sessions.value.find(item => item.id === selected.value));
const running = computed(() => ['PENDING','RUNNING','CANCEL_REQUESTED','WAITING_INPUT'].includes(latestTask.value?.status ?? ''));
async function control(retry:boolean){
  const task=latestTask.value,id=selected.value;if(!task||controlling.value)return;
  controlling.value=true;error.value='';
  try {
    if(retry){
      const key=retryKeys.get(task.taskId)??crypto.randomUUID();retryKeys.set(task.taskId,key);
      await request(`/conversations/${id}/tasks/${task.taskId}/retry`,'POST',{acknowledgeUnknownCost:acknowledgeUnknownCost.value},{'Idempotency-Key':key});
    }else await request(`/tasks/${task.taskId}/cancel`,'POST',{});
    if(selected.value===id){acknowledgeUnknownCost.value=false;await loadHistory();}
  }catch(failure){if(selected.value===id)error.value=failure instanceof Error?failure.message:'操作未确认，请重试';}
  finally{controlling.value=false;}
}
const draft = computed({ get: () => drafts.value[selected.value] ?? '', set: (value: string) => { drafts.value[selected.value] = value; } });
let alive = true, readingAssets = false;
const appliedCursors=new Map<string,bigint>();
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

async function loadHistory(rethrow=false) {
  const id = selected.value;
  if (!id) return;
  try {
    const history = await request<History & {cursor:string}>(`/conversations/${id}/snapshot`);
    const sequence=BigInt(history.cursor.split(':')[1]!);
    if (alive && selected.value === id && sequence>=(appliedCursors.get(id)??0n)) { if(latestTask.value?.taskId!==history.latestTask?.taskId)acknowledgeUnknownCost.value=false; appliedCursors.set(id,sequence); messages.value = history.items; brief.value=history.brief??null;questions.value=history.questions??[];latestTask.value = history.latestTask; activity.value=phaseLabels[history.latestTask?.phase??'']??(history.latestTask?.status==='PENDING'?'正在排队':''); assistantDrafts.value=history.drafts??[]; if(!earlierDrafts.value.length)draftCursor.value=history.draftsNextCursor??null; }
  } catch (failure) { if (alive && selected.value === id) error.value = failure instanceof Error ? failure.message : '无法读取会话'; if(rethrow)throw failure; }
}
async function loadEarlierDrafts(){
  const id=selected.value,cursor=draftCursor.value;if(!cursor)return;
  try{
    const page=await request<{items:AssistantDraft[];nextCursor:string|null}>(`/conversations/${id}/drafts?before=${encodeURIComponent(cursor)}`);
    if(selected.value!==id)return;
    earlierDrafts.value.push(...page.items);draftCursor.value=page.nextCursor;
  }catch(failure){error.value=failure instanceof Error?failure.message:'无法读取未完成草稿';}
}
async function choose(id: string) {
  references.value[id] ??= [];
  editing.value[id] ??= {mode:'QUERY',topic:'',minSeconds:10,maxSeconds:30,profile:'LANDSCAPE_720',useBase:false,orderedAssetIds:[]};
  emit('session',id);
  selected.value = id;brief.value=null;questions.value=[];acknowledgeUnknownCost.value=false; messages.value = []; latestTask.value = null; assistantDrafts.value=[]; earlierDrafts.value=[]; draftCursor.value=null; activity.value=''; error.value = '';
  localStorage.setItem(`conversation:${props.projectId}`,id);
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
function applyBrief(mode:string){
  if(!brief.value)return;
  const fields=brief.value.fields;
  if(!fields.topic||!fields.duration){error.value='请先明确简报主题与硬时长';return;}
  if(Number(fields.count?.value??1)!==1){error.value='当前仅支持一次生成一份方案，请明确数量为一';return;}
  const duration=fields.duration.value as {minMs:number;maxMs:number};
  settings.value.mode=mode;settings.value.topic=String(fields.topic.value);settings.value.minSeconds=duration.minMs/1000;settings.value.maxSeconds=duration.maxMs/1000;settings.value.profile=String(fields.profile?.value??'LANDSCAPE_720');
  const forbidden=fields.forbiddenAssetIds?.value as string[]|undefined;
  settings.value.orderedAssetIds=(fields.assetIds?.value as string[]|undefined??session.value?.assetIds??[]).filter(id=>!forbidden?.includes(id));
  appliedBrief.value[selected.value]=brief.value.revision;error.value='';
}
function moveMontage(index:number,delta:number){
  const order=settings.value.orderedAssetIds,target=index+delta;
  if(target<0||target>=order.length)return;
  const [asset]=order.splice(index,1);order.splice(target,0,asset!);
}
async function send() {
  if (!selected.value || sending.value || running.value || !draft.value.trim()) return;
  const id = selected.value, content = draft.value;
  const condition = settings.value;
  const context: Record<string,unknown> = {};
  if(condition.mode==='REQUIREMENTS')context.requirements=true;
  if (['GENERATE_PLAN','MONTAGE'].includes(condition.mode)) {
    if(appliedBrief.value[id])context.briefRevision=appliedBrief.value[id];
    if (!condition.topic.trim() || condition.minSeconds <= 0 || condition.maxSeconds < condition.minSeconds || condition.maxSeconds > 3600) {
      error.value = '请明确主题和合法的硬时长范围'; return;
    }
    if (condition.useBase && !props.editingBase) { error.value = '请先查看要继续生成的方案版本'; return; }
    if(condition.mode==='MONTAGE'&&condition.orderedAssetIds.length<2){error.value='请按顺序选择至少两份素材';return;}
    context.editing = {...(condition.mode==='MONTAGE'?{kind:'MONTAGE',orderedAssetIds:condition.orderedAssetIds}:{}),topic:condition.topic,minDurationMs:Math.round(condition.minSeconds*1000),maxDurationMs:Math.round(condition.maxSeconds*1000),profile:condition.profile,
      ...(condition.useBase && props.editingBase ? {planId:props.editingBase.planId,baseRevisionId:props.editingBase.revisionId} : {})};
  }
  const body = { content, references: (references.value[id] ?? []).map(assetId => ({ type: 'ASSET', id: assetId })), context };
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
  try { emit('play', { ...await request<Playback>(`/assets/${citation.assetId}/playback-access`, 'POST', {}), startMs: citation.observedAtMs ?? citation.startMs ?? 0 }); }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法核查原视频'; }
}
useProjectEvents(async event=>{
  if(!alive)return;
  if(event===null || event.conversationId===selected.value){
    if(event?.payload.phase)activity.value=phaseLabels[String(event.payload.phase)]??'';
    await loadHistory(true);
  }
});
onMounted(async () => {
  try {
    const [available, existing] = await Promise.all([
      request<{items:Asset[]}>(`/projects/${props.projectId}/assets`),
      request<{items:Session[]}>(`/projects/${props.projectId}/conversations`),
    ]);
    if (!alive) return;
    assets.value = available.items; scope.value = available.items.map(asset => asset.id); sessions.value = existing.items;
    const saved=localStorage.getItem(`conversation:${props.projectId}`);
    if (existing.items.length) await choose(existing.items.find(item=>item.id===saved)?.id??existing.items[existing.items.length-1]!.id);
    timer = setInterval(() => { void loadAssets(); }, 5000);
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
      <section v-if="brief&&Object.keys(brief.fields).length" aria-label="制作需求简报">
        <h3>制作需求简报</h3>
        <dl><template v-for="(condition,name) in brief.fields" :key="name"><dt>{{briefLabels[name]??name}}</dt><dd>{{briefValue(name,condition.value)}} · {{condition.basis==='EXPLICIT'?'明确约束':'已说明默认值'}}<small>来源：{{messages.find(item=>item.id===condition.sourceMessageId)?.content??condition.sourceMessageId}}</small></dd></template></dl>
        <p>选择“整理制作需求”，用新消息纠正指定条件；未提及条件保留。</p>
        <button type="button" :disabled="running" @click="applyBrief('GENERATE_PLAN')">应用简报生成讲话方案</button><button type="button" :disabled="running" @click="applyBrief('MONTAGE')">应用简报生成混剪</button>
        <p v-if="appliedBrief[selected]">已应用简报 V{{appliedBrief[selected]}}；发送时会固定该版本条件。简报发生变化后请重新应用。</p>
        <details><summary>查看简报修订历史</summary><article v-for="revision in brief.history" :key="revision.revision"><strong>简报 V{{revision.revision}}</strong><p v-for="(condition,name) in revision.fields" :key="name">{{briefLabels[name]??name}}：{{briefValue(name,condition.value)}}</p></article></details>
      </section>
      <article v-for="question in openQuestions" :key="question.id" aria-label="待回答的澄清问题">
        <h3>{{question.prompt}}</h3><p>{{question.reason}}</p><p>等待明确回答；执行与方案编辑占用已释放。普通新消息不会自动作为回答。</p>
        <label v-if="question.editing" class="choice"><input v-model="useChangedBase[question.id]" type="checkbox" :disabled="!editingBase||editingBase.planId!==question.editing.planId">以当前查看版本恢复方案修改</label>
        <form @submit.prevent="resolveQuestion(question,false)"><label :for="`answer-${question.id}`">澄清回答</label><textarea :id="`answer-${question.id}`" v-model="answers[question.id]" maxlength="8000" required></textarea><button type="submit" :disabled="controlling||!answers[question.id]?.trim()">提交澄清回答</button></form>
        <button type="button" class="secondary" :disabled="controlling||!draft.trim()" @click="resolveQuestion(question,true)">开始新需求</button><p>在下方对话输入中填写新需求，再点击此按钮放弃原等待。</p>
      </article>
      <p class="scope">本会话固定包含 {{ session.assetIds.length }} 个素材，查询无需创建剪辑方案。</p>
      <p v-if="running && activity" role="status">{{ activity }}</p>
      <article v-for="reply in visibleDrafts" :key="reply.messageId" aria-label="助手未完成草稿">
        <p>{{ reply.current && running ? '正在回复，尚未发布正式答复' : '未完成草稿，未发布正式答复' }}</p>
        <div class="message-content">{{ reply.content }}</div>
      </article>
      <button v-if="draftCursor" type="button" class="secondary" @click="loadEarlierDrafts">查看更早的未完成草稿</button>
      <div class="messages" aria-live="polite">
        <article v-for="message in messages" :key="message.id" :aria-label="message.role === 'USER' ? '用户需求' : '助手答复'">
          <strong>{{ message.role === 'USER' ? '你' : '助手' }}</strong><p class="message-content">{{ message.content }}</p>
          <button v-for="result in message.references.filter(item => item.type === 'PLAN_REVISION')" :key="result.id" class="secondary"
            @click="emit('plan',{planId:result.planId!,revisionId:result.id,sessionId:selected})">查看方案 V{{ result.revisionNumber }}</button>
          <button v-for="result in message.references.filter(item => item.type === 'EXPORT_REQUEST')" :key="result.id" class="secondary" @click="emit('exported',result.id)">查看正式导出</button>
          <div v-for="citation in message.references.filter(item => item.type === 'EVIDENCE')" :key="citation.id" class="citation">
            <p>{{citation.sourceKind==='VISUAL'?'画面观察':citation.sourceKind==='VISIBLE_TEXT'?'可见文字':'语音原文'}}：{{ citation.text }}</p><small v-if="citation.observedAtMs!==undefined">{{(citation.observedAtMs/1000).toFixed(2)}} 秒 · 帧观察时刻</small><small v-else>{{ ((citation.startMs ?? 0)/1000).toFixed(2) }}–{{ ((citation.endMs ?? 0)/1000).toFixed(2) }} 秒 · 模型观察，建议回看核查</small>
            <button class="secondary" @click="inspect(citation)">核查引用原视频</button>
          </div>
        </article>
      </div>
      <p v-if="running" role="status">{{latestTask?.status==='WAITING_INPUT'?'正在等待关联澄清回答；也可停止或明确开始新需求。':'正在处理需求；可以继续编辑草稿，完成后再发送。'}}</p>
      <button v-if="running" type="button" class="secondary" :disabled="controlling || latestTask?.status==='CANCEL_REQUESTED'" @click="control(false)">{{latestTask?.status==='CANCEL_REQUESTED'?'正在停止对话':'停止当前对话'}}</button>
      <p v-if="latestTask?.status==='CANCELLED'" role="status">对话已停止；未发布本轮结果。</p>
      <p v-else-if="latestTask?.status === 'FAILED'" role="alert">{{ latestTask.failure?.code === 'MODEL_RECORD_TOO_LARGE' ? latestTask.failure.message : '本次对话未完成，未发布正式答复。' }}</p>
      <p v-if="latestTask?.externalEffects && ['RUNNING','CANCEL_REQUESTED','CANCELLED','FAILED'].includes(latestTask.status)">{{latestTask.externalEffects.message}}</p>
      <template v-if="latestTask?.status==='FAILED'">
        <label v-if="latestTask.externalEffects?.unknownCost" class="choice"><input v-model="acknowledgeUnknownCost" type="checkbox">我理解原调用费用未知，接受重试可能产生新费用</label>
        <button type="button" class="secondary" :disabled="controlling || (latestTask.externalEffects?.unknownCost && !acknowledgeUnknownCost)" @click="control(true)">重试原需求</button>
      </template>
      <form @submit.prevent="send">
        <label for="intent-select">需求类型</label><select id="intent-select" v-model="settings.mode"><option value="QUERY">查询素材</option><option value="REQUIREMENTS">整理制作需求</option><option value="GENERATE_PLAN">生成讲话方案</option><option value="MONTAGE">生成跨素材混剪</option></select>
        <fieldset v-if="['GENERATE_PLAN','MONTAGE'].includes(settings.mode)"><legend>本次明确制作条件</legend>
          <label for="plan-topic">方案主题</label><input id="plan-topic" v-model="settings.topic" required maxlength="120">
          <label for="minimum-duration">最短时长（秒）</label><input id="minimum-duration" v-model.number="settings.minSeconds" type="number" min="0.001" max="3600" step="0.001" required>
          <label for="maximum-duration">最长时长（秒）</label><input id="maximum-duration" v-model.number="settings.maxSeconds" type="number" :min="settings.minSeconds" max="3600" step="0.001" required>
          <label for="output-profile">输出画幅</label><select id="output-profile" v-model="settings.profile"><option value="LANDSCAPE_720">横屏 720p</option><option value="PORTRAIT_720">竖屏 720p</option></select>
          <fieldset v-if="settings.mode==='MONTAGE'"><legend>混剪素材顺序</legend>
            <label v-for="asset in assets.filter(item=>session?.assetIds.includes(item.id))" :key="asset.id" class="choice"><input v-model="settings.orderedAssetIds" type="checkbox" :value="asset.id" :aria-label="`混剪素材 ${asset.filename}`">{{asset.filename}}</label>
            <ol><li v-for="(id,index) in settings.orderedAssetIds" :key="id">{{assets.find(item=>item.id===id)?.filename??id}}
              <button type="button" class="secondary" :disabled="index===0" :aria-label="`上移混剪素材 ${assets.find(item=>item.id===id)?.filename??id}`" @click="moveMontage(index,-1)">上移</button>
              <button type="button" class="secondary" :disabled="index===settings.orderedAssetIds.length-1" :aria-label="`下移混剪素材 ${assets.find(item=>item.id===id)?.filename??id}`" @click="moveMontage(index,1)">下移</button>
            </li></ol>
            <p>选择至少两份素材；Agent 按此顺序选段，只使用已核查证据。无音轨片段保留静音。</p>
          </fieldset>
          <label class="choice"><input v-model="settings.useBase" type="checkbox" :disabled="!editingBase">基于正在查看的方案重新生成</label>
          <p>未勾选时创建独立方案。硬时长无法满足时会解释，内部候选不会成为版本。</p>
        </fieldset>
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
dd small { display:block;overflow-wrap:anywhere;color:#69766e; }
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

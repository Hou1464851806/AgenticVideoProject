<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { request } from './api';
import type { Playback } from './AssetLibrary.vue';
export type PlanSelection = { planId: string; revisionId: string; sessionId: string };
export type PlanBase = { planId: string; revisionId: string };
const props = defineProps<{ projectId: string; sessionId: string; selection: PlanSelection | null }>();
const emit = defineEmits<{ play: [value: Playback]; base: [value: PlanBase | null] }>();
type Plan = { id: string; title: string; editingTaskId: string | null };
type Version = { id: string; revisionNumber: number; parentRevisionId: string | null };
type Clip = { clipId: string; assetId: string; sourceInMs: number; sourceOutMs: number; reason: string; evidenceIds: string[] };
type Output = { outputId:string; title: string; durationMs: number; clips: Clip[]; render: {width:number;height:number}; subtitles: {cues:{startMs:number;endMs:number;text:string}[]} };
type Revision = Version & { planId: string; content: {outputs: Output[]} };
const plans = ref<Plan[]>([]), versions = ref<Version[]>([]);
const selectedPlan = ref(''), selectedRevision = ref('');
const current = ref<Revision | null>(null), names = ref<Record<string,string>>({});
const error = ref(''), stopping = ref(false), loading = ref(false);
type Preview = {requestId:string;taskId:string;revisionId:string;status:string;artifactId?:string;error?:{message:string}};
const preview=ref<Preview|null>(null),previewVersion=ref(0),previewUrl=ref(''),previewBusy=ref(false);
let previewRefreshing=false;
async function generatePreview(output:Output) {
  if(!current.value || previewBusy.value)return;
  const revision=current.value.id,version=current.value.revisionNumber;
  previewBusy.value=true;error.value='';
  try {
    const result=await request<Preview>(`/revisions/${revision}/previews`,'POST',{outputId:output.outputId,previewProfile:'LOW_360'},{'Idempotency-Key':crypto.randomUUID()});
    if(alive){preview.value=result;previewVersion.value=version;previewUrl.value='';}
  }catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法生成预览';}
  finally{if(alive)previewBusy.value=false;}
}
async function refreshPreview(){
  if(!preview.value || previewRefreshing)return;
  const id=preview.value.requestId;previewRefreshing=true;
  try{const result=await request<Preview>(`/render-requests/${id}`);if(alive && preview.value?.requestId===id)preview.value=result;}
  catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法读取预览';}
  finally{previewRefreshing=false;}
}
async function playPreview(){
  const artifact=preview.value?.artifactId;if(!artifact)return;
  try{const result=await request<{url:string}>(`/artifacts/${artifact}/playback-access`,'POST',{});if(alive && preview.value?.artifactId===artifact)previewUrl.value=result.url;}
  catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法播放预览';}
}
async function cancelPreview(){
  const task=preview.value?.taskId;if(!task)return;
  try{await request(`/tasks/${task}/cancel`,'POST',{});await refreshPreview();}
  catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法停止预览';}
}
let alive = true, refreshing = false, epoch = 0;
let timer: ReturnType<typeof setInterval> | undefined;
async function refresh() {
  if (refreshing) return;
  refreshing = true;
  const planId = selectedPlan.value;
  try {
    const listed = await request<{items:Plan[]}>(`/projects/${props.projectId}/plans`);
    const history = planId ? await request<{items:Version[]}>(`/plans/${planId}/revisions`) : null;
    if (!alive) return;
    plans.value = listed.items;
    if (selectedPlan.value === planId && history) versions.value = history.items;
  } catch (failure) { if (alive) error.value = failure instanceof Error ? failure.message : '无法读取方案'; }
  finally { refreshing = false; }
}
async function openRevision(planId: string, revisionId: string, persist = true) {
  const operation = ++epoch;
  const previousPlan = selectedPlan.value, previousRevision = selectedRevision.value;
  selectedPlan.value = planId; selectedRevision.value = revisionId;
  loading.value = true; error.value = ''; emit('base', null);
  try {
    const [revision, history] = await Promise.all([
      request<Revision>(`/revisions/${revisionId}`), request<{items:Version[]}>(`/plans/${planId}/revisions`),
    ]);
    const assetIds = [...new Set(revision.content.outputs.flatMap(output => output.clips.map(clip => clip.assetId)))];
    const information = await Promise.all(assetIds.map(id => request<{id:string;filename:string}>(`/assets/${id}`)));
    if (!alive || operation !== epoch) return;
    if (persist && props.sessionId) {
      const history = await request<{view:{rowVersion:number}}>(`/conversations/${props.sessionId}/messages`);
      if (!alive || operation !== epoch) return;
      await request(`/conversations/${props.sessionId}/view`, 'PATCH', {planId, revisionId, expectedRowVersion:history.view.rowVersion});
    }
    if (!alive || operation !== epoch) return;
    current.value = revision; versions.value = history.items;
    names.value = Object.fromEntries(information.map(asset => [asset.id, asset.filename]));
    emit('base', {planId, revisionId});
  } catch (failure) {
    if (alive && operation === epoch) {
      selectedPlan.value = previousPlan; selectedRevision.value = previousRevision;
      error.value = failure instanceof Error ? failure.message : '无法查看版本';
      emit('base', current.value ? {planId:current.value.planId,revisionId:current.value.id} : null);
    }
  } finally { if (alive && operation === epoch) loading.value = false; }
}
async function choosePlan(id: string) {
  ++epoch; selectedPlan.value = id; selectedRevision.value = ''; current.value = null; versions.value = []; emit('base', null);
  await refresh();
}
async function restoreView() {
  const session = props.sessionId, operation = ++epoch;
  current.value = null; selectedPlan.value = ''; selectedRevision.value = ''; versions.value = []; emit('base', null);
  if (!session) return;
  try {
    const saved = await request<{view:{planId:string|null;revisionId:string|null}}>(`/conversations/${session}/messages`);
    if (!alive || operation !== epoch || props.sessionId !== session) return;
    if (saved.view.planId && saved.view.revisionId) await openRevision(saved.view.planId, saved.view.revisionId, false);
  } catch (failure) { if (alive && operation === epoch) error.value = failure instanceof Error ? failure.message : '无法恢复查看版本'; }
}
async function play(clip: Clip) {
  const operation = epoch;
  try {
    const playback = await request<Playback>(`/assets/${clip.assetId}/playback-access`, 'POST', {});
    if (alive && operation === epoch) emit('play', {...playback, startMs:clip.sourceInMs, endMs:clip.sourceOutMs});
  }
  catch (failure) { if (alive && operation === epoch) error.value = failure instanceof Error ? failure.message : '无法播放源片段'; }
}
async function stop(taskId: string) {
  stopping.value = true; error.value = '';
  try { await request(`/tasks/${taskId}/cancel`, 'POST', {}); await refresh(); }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法停止修改'; }
  finally { stopping.value = false; }
}
watch(() => props.selection, selection => { if (selection) void openRevision(selection.planId,selection.revisionId); });
watch(() => props.sessionId, () => { void restoreView(); });
onMounted(async () => { await refresh(); await restoreView(); timer = setInterval(() => {void refresh();void refreshPreview();},1000); });
onUnmounted(() => { alive = false; ++epoch; clearInterval(timer); });
</script>
<template>
  <section aria-label="编排剪辑方案">
    <h3>编排剪辑方案</h3>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!plans.length">暂无方案，选择“生成讲话方案”并明确主题和时长后发送需求。</p>
    <template v-else>
      <label for="plan-select">查看方案</label><select id="plan-select" :value="selectedPlan" @change="choosePlan(($event.target as HTMLSelectElement).value)">
        <option value="">选择方案</option><option v-for="plan in plans" :key="plan.id" :value="plan.id">{{ plan.title }}</option>
      </select>
      <template v-if="selectedPlan">
        <p v-if="!versions.length">该方案尚无成功版本。</p>
        <label v-else for="revision-select">查看版本</label><select v-if="versions.length" id="revision-select" :value="selectedRevision" @change="openRevision(selectedPlan,($event.target as HTMLSelectElement).value)">
          <option value="">选择版本</option><option v-for="version in versions" :key="version.id" :value="version.id">V{{ version.revisionNumber }}</option>
        </select>
        <p v-if="current && versions.at(-1)?.id !== current.id">已有更新版本，可自行切换；当前查看保持 V{{ current.revisionNumber }}。</p>
        <template v-for="plan in plans.filter(item => item.id === selectedPlan && item.editingTaskId)" :key="plan.id">
          <p role="status">方案正在编辑，仍可查看已有版本。</p><button class="secondary" :disabled="stopping" @click="stop(plan.editingTaskId!)">停止方案修改</button>
        </template>
      </template>
      <p v-if="loading" role="status">正在读取版本…</p>
      <div v-if="current && !loading">
        <h4>V{{ current.revisionNumber }} · {{ current.content.outputs[0]?.title }}</h4>
        <div v-for="output in current.content.outputs" :key="output.title">
          <p>{{ (output.durationMs/1000).toFixed(3) }} 秒 · {{ output.render.width }} × {{ output.render.height }} · 原声与原文字幕</p>
          <article v-for="(clip,index) in output.clips" :key="clip.clipId" aria-label="方案剪辑片段" class="clip">
            <strong>片段 {{ index+1 }} · {{ names[clip.assetId] }}</strong>
            <p>原素材 {{ (clip.sourceInMs/1000).toFixed(3) }}–{{ (clip.sourceOutMs/1000).toFixed(3) }} 秒</p>
            <p>选取依据：{{ clip.reason }}</p><button class="secondary" @click="play(clip)">播放源片段</button>
          </article>
          <button class="secondary" :disabled="previewBusy" @click="generatePreview(output)">生成低清效果预览</button>
          <details><summary>映射后的原文字幕</summary><p v-for="(cue,index) in output.subtitles.cues" :key="index">{{ (cue.startMs/1000).toFixed(3) }}–{{ (cue.endMs/1000).toFixed(3) }} 秒 · {{ cue.text }}</p></details>
        </div>
        <p>当前为编排方案；核查后再决定制作。生成方案不会自动预览或导出。</p>
      </div>
    </template>
    <section v-if="preview" aria-label="低清效果预览">
      <h4>低清效果预览 · 固定 V{{ previewVersion }}</h4>
      <p role="status">{{ ({PENDING:'等待合成',RUNNING:'正在合成',CANCEL_REQUESTED:'正在停止',CANCELLED:'已停止',SUCCEEDED:'合成完成',FAILED:'合成失败'} as Record<string,string>)[preview.status] }}</p>
      <p v-if="preview.error" role="alert">{{ preview.error.message }}</p>
      <button v-if="['PENDING','RUNNING'].includes(preview.status)" class="secondary" @click="cancelPreview">停止预览</button>
      <button v-if="preview.status==='SUCCEEDED'" class="secondary" @click="playPreview">播放低清预览</button>
      <video v-if="previewUrl" :src="previewUrl" controls aria-label="低清合成预览播放器" style="width:100%;max-height:420px" />
    </section>
  </section>
</template>
<style scoped>
section { margin-top: 22px; } h3 { font-size: 16px; } h4 { font-size: 14px; }
p,summary,strong { font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.clip { border: 1px solid #dce5de; border-radius: 8px; padding: 12px; margin: 12px 0; }
button { font-size: 13px; }
</style>

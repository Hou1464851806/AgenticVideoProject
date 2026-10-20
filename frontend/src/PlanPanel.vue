<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { request } from './api';
import type { Playback } from './AssetLibrary.vue';
export type PlanSelection = { planId: string; revisionId: string; sessionId: string };
export type PlanReference = { type: 'PLAN_REVISION'; id: string; outputId?: string; clipId?: string; label?: string };
export type PlanBase = { planId: string; revisionId: string };
const props = defineProps<{ projectId: string; sessionId: string; selection: PlanSelection | null; exportRequestId?:string; previewRequestId?:string; diagnostics?:boolean }>();
const emit = defineEmits<{ play: [value: Playback]; base: [value: PlanBase | null]; reference: [value: PlanReference] }>();
type Plan = { id: string; title: string; editingTaskId: string | null };
type Version = { id: string; revisionNumber: number; parentRevisionId: string | null };
type Clip = { clipId: string; assetId: string; sourceInMs: number; sourceOutMs: number; reason: string; evidenceIds: string[] };
type Output = { outputId:string; title: string; durationMs: number; clips: Clip[]; render: {width:number;height:number}; subtitles: {cues:{startMs:number;endMs:number;text:string}[]} };
type Revision = Version & { planId: string; content: {outputs: Output[];changeSummary?:string} };
const plans = ref<Plan[]>([]), versions = ref<Version[]>([]);
const selectedPlan = ref(''), selectedRevision = ref('');
const current = ref<Revision | null>(null), names = ref<Record<string,string>>({});
const error = ref(''), stopping = ref(false), loading = ref(false);
type RenderRequest = {requestId:string;taskId:string;revisionId:string;status:string;artifactId?:string;createdAt?:string;configuration?:{profile:string};outputId?:string;error?:{message:string}};
const preview=ref<RenderRequest|null>(null),previewVersion=ref(0),previewUrl=ref(''),previewBusy=ref(false),previewTitle=ref('');
const exportHistory=ref<RenderRequest[]>([]);
const exportDeletePending=ref(false), exportUnavailable=ref(false);
const deleteCandidate=ref<{artifactId:string;title:string}|null>(null), deleting=ref(false);
const deleteButton=ref<HTMLButtonElement|null>(null), keepButton=ref<HTMLButtonElement|null>(null), exportSection=ref<HTMLElement|null>(null);
watch(deleteCandidate, async candidate => {
  await nextTick();
  (candidate ? keepButton.value : deleteButton.value ?? exportSection.value)?.focus();
});
const exportTitle=ref(''), exportVideo=ref<HTMLVideoElement|null>(null);
let exportRenewed=false, exportRestore:{seconds:number;playing:boolean}|undefined;
const confirmationId=ref(''),exportProfile=ref('HD_720'),exportBusy=ref(false);
const choosingExport=ref(false),exportOutputId=ref('');
const pendingExports=new Map<string,string>();
function outputExporting(outputId:string){
  return [...exportHistory.value,...(exported.value?[exported.value]:[])].some(item=>item.revisionId===current.value?.id&&item.outputId===outputId&&item.configuration?.profile===exportProfile.value&&['PENDING','RUNNING','CANCEL_REQUESTED'].includes(item.status));
}
const singleExporting=computed(()=>current.value?.content.outputs.length===1&&outputExporting(current.value.content.outputs[0]!.outputId));
function chooseExport(){
  if(!current.value)return;
  if(current.value.content.outputs.length===1)void exportOutput(current.value.content.outputs[0]!);
  else choosingExport.value=true;
}
const exported=ref<RenderRequest|null>(null),exportVersion=ref(0),exportUrl=ref(''),exportRemoved=ref(false);
let exportRefreshing=false,exportHistoryReading=false,exportOpenEpoch=0;
async function confirmVersion(){
  if(!current.value)return;
  const id=current.value.id;exportBusy.value=true;
  try{const result=await request<{confirmationId:string}>(`/revisions/${id}/confirmations`,'POST',{explicitIntent:'CONFIRM'},{'Idempotency-Key':crypto.randomUUID()});if(alive&&current.value?.id===id)confirmationId.value=result.confirmationId;}
  catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法确认版本';}
  finally{if(alive)exportBusy.value=false;}
}
async function exportOutput(output:Output){
  if(!current.value||!confirmationId.value||exportBusy.value||outputExporting(output.outputId))return;
  const revision=current.value.id,version=current.value.revisionNumber;exportBusy.value=true;error.value='';
  const body={outputIds:[output.outputId],confirmationId:confirmationId.value,exportProfile:exportProfile.value,explicitIntent:'EXPORT'};
  const identity=JSON.stringify({revision,...body}),key=pendingExports.get(identity)??crypto.randomUUID();pendingExports.set(identity,key);
  try{const result=await request<RenderRequest>(`/revisions/${revision}/export-requests`,'POST',body,{'Idempotency-Key':key});pendingExports.delete(identity);if(alive){choosingExport.value=false;++exportOpenEpoch;exported.value=result;exportVersion.value=version;exportTitle.value=output.title;exportUrl.value='';exportRemoved.value=false;exportDeletePending.value=false;exportUnavailable.value=false;deleteCandidate.value=null;await refreshExportHistory();}}
  catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法导出成片';}
  finally{if(alive)exportBusy.value=false;}
}
async function readExportArtifact(id:string){
  const artifact=await request<{state:string}>(`/artifacts/${id}`);
  if(!alive||exported.value?.artifactId!==id)return;
  exportRemoved.value=artifact.state==='REMOVED';
  exportDeletePending.value=artifact.state==='DELETE_PENDING';
  exportUnavailable.value=artifact.state==='UNAVAILABLE';
  if(exportRemoved.value||exportDeletePending.value||exportUnavailable.value)exportUrl.value='';
}
async function refreshExport(){
  if(!exported.value||exportRefreshing)return;
  const id=exported.value.requestId;exportRefreshing=true;
  try{
    const value=await request<RenderRequest>(`/render-requests/${id}`);
    if(alive&&exported.value?.requestId===id){exported.value=value;if(value.artifactId)await readExportArtifact(value.artifactId);}
  }catch(failure){if(alive&&exported.value?.requestId===id)error.value=failure instanceof Error?failure.message:'无法读取导出状态';}
  finally{exportRefreshing=false;}
}
async function artifactAction(action:'playback-access'|'download-access', renew=false){
  const id=exported.value?.artifactId;if(!id)return;
  if(renew&&exportRenewed){error.value='成片无法播放，请重新获取播放地址。';return;}
  exportRenewed=renew;error.value='';
  const position=exportVideo.value?.currentTime??0,playing=exportVideo.value?!exportVideo.value.paused:false;
  try{
    const value=await request<{url:string}>(`/artifacts/${id}/${action}`,'POST',{});
    if(!alive||exported.value?.artifactId!==id)return;
    if(action==='playback-access'){exportRestore=renew?{seconds:position,playing}:undefined;exportUrl.value=value.url;}
    else{const link=document.createElement('a');link.href=value.url;link.download=`${exportTitle.value}.mp4`;document.body.appendChild(link);link.click();link.remove();}
  }catch(failure){if(alive&&exported.value?.artifactId===id){error.value=failure instanceof Error?failure.message:'无法访问成片';await refreshExport();}}
}
function exportLoaded(){
  if(exportRestore&&exportVideo.value){exportVideo.value.currentTime=exportRestore.seconds;if(exportRestore.playing)void exportVideo.value.play();exportRestore=undefined;}
}
async function deleteExport(){
  const candidate=deleteCandidate.value;if(!candidate||deleting.value)return;
  deleting.value=true;error.value='';
  try{
    const value=await request<{state:string;removedBytes:number}>(`/artifacts/${candidate.artifactId}`,'DELETE',{explicitIntent:'DELETE'});
    if(alive&&exported.value?.artifactId===candidate.artifactId){exportRemoved.value=value.state==='REMOVED';exportDeletePending.value=value.state==='DELETE_PENDING';exportUrl.value='';deleteCandidate.value=null;}
  }catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法删除成片';}
  finally{deleting.value=false;}
}
async function cancelExport(){const task=exported.value?.taskId;if(!task)return;try{await request(`/tasks/${task}/cancel`,'POST',{});await refreshExport();}catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法停止导出';}}
let previewRefreshing=false;
async function generatePreview(output:Output) {
  if(!current.value || previewBusy.value)return;
  const revision=current.value.id,version=current.value.revisionNumber;
  previewBusy.value=true;error.value='';
  try {
    const result=await request<RenderRequest>(`/revisions/${revision}/previews`,'POST',{outputId:output.outputId,previewProfile:'LOW_360'},{'Idempotency-Key':crypto.randomUUID()});
    if(alive){preview.value=result;previewVersion.value=version;previewTitle.value=output.title;previewUrl.value='';}
  }catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法生成预览';}
  finally{if(alive)previewBusy.value=false;}
}
async function refreshPreview(){
  if(!preview.value || previewRefreshing)return;
  const id=preview.value.requestId;previewRefreshing=true;
  try{const result=await request<RenderRequest>(`/render-requests/${id}`);if(alive && preview.value?.requestId===id)preview.value=result;}
  catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法读取预览';}
  finally{previewRefreshing=false;}
}
async function openStoredPreview(id:string){
  try{const result=await request<RenderRequest>(`/render-requests/${id}`);const version=await request<Revision>(`/revisions/${result.revisionId}`);if(alive&&props.previewRequestId===id){preview.value=result;previewVersion.value=version.revisionNumber;previewTitle.value=version.content.outputs.find(output=>output.outputId===result.outputId)?.title??'输出名称暂不可用';previewUrl.value='';}}
  catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法读取聊天预览';}
}
async function previewAccess(action:'playback-access'|'download-access'){
  const artifact=preview.value?.artifactId;if(!artifact)return;
  try{const result=await request<{url:string}>(`/artifacts/${artifact}/${action}`,'POST',{});if(!alive||preview.value?.artifactId!==artifact)return;
    if(action==='playback-access')previewUrl.value=result.url;
    else{const link=document.createElement('a');link.href=result.url;link.download=`preview-${artifact}.mp4`;document.body.appendChild(link);link.click();link.remove();}}
  catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法访问预览';}
}
async function playPreview(){await previewAccess('playback-access');}
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
  loading.value = true; error.value = ''; choosingExport.value=false;exportOutputId.value='';confirmationId.value=''; exportHistory.value=[]; emit('base', null);
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
    const confirmations=await request<{items:{confirmationId:string}[]}>(`/revisions/${revisionId}/confirmations`);
    if(!alive||operation!==epoch)return;
    confirmationId.value=confirmations.items[0]?.confirmationId??'';
    await refreshExportHistory();
    if(!alive||operation!==epoch)return;
    if(!exported.value&&exportHistory.value[0])await openStoredExport(exportHistory.value[0].requestId);
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
  const operation=++epoch;
  selectedPlan.value=id;selectedRevision.value='';current.value=null;versions.value=[];exportHistory.value=[];emit('base',null);
  if(!id)return;
  loading.value=true;error.value='';
  try{
    const history=await request<{items:Version[]}>(`/plans/${id}/revisions`);
    if(!alive||operation!==epoch)return;
    versions.value=history.items;
    if(history.items.length===1)await openRevision(id,history.items[0]!.id);
  }catch(failure){if(alive&&operation===epoch)error.value=failure instanceof Error?failure.message:'无法读取版本';}
  finally{if(alive&&operation===epoch)loading.value=false;}
}
function clearRenderedSections() {
  preview.value = null; previewVersion.value = 0; previewUrl.value = ''; previewBusy.value = false; previewTitle.value = '';
  exported.value = null; exportVersion.value = 0; exportUrl.value = ''; exportRemoved.value = false; exportDeletePending.value = false; exportUnavailable.value = false; deleteCandidate.value = null;
  exportHistory.value = []; exportHistoryReading = false; exportRenewed = false; exportRestore = undefined;
}
async function restoreView() {
  const session = props.sessionId, operation = ++epoch;
  clearRenderedSections(); error.value = '';
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
async function openStoredExport(id:string){
  if(!id)return;const operation=++exportOpenEpoch;
  try{const result=await request<RenderRequest>(`/render-requests/${id}`);const revision=await request<Revision>(`/revisions/${result.revisionId}`);
    if(!alive||operation!==exportOpenEpoch)return;
    exported.value=result;exportVersion.value=revision.revisionNumber;exportTitle.value=revision.content.outputs.find(output=>output.outputId===result.outputId)?.title??'输出名称暂不可用';exportUrl.value='';exportRemoved.value=false;exportDeletePending.value=false;exportUnavailable.value=false;deleteCandidate.value=null;exportRenewed=false;
    if(result.artifactId)await readExportArtifact(result.artifactId);
  }catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法查看正式导出';}
}
async function refreshExportHistory(){
  const revision=current.value?.id;if(!revision||exportHistoryReading)return;exportHistoryReading=true;
  try{const history=await request<{items:RenderRequest[]}>(`/revisions/${revision}/export-requests`);if(alive&&current.value?.id===revision)exportHistory.value=history.items;}
  catch(failure){if(alive)error.value=failure instanceof Error?failure.message:'无法读取导出记录';}
  finally{exportHistoryReading=false;}
}
function exportLabel(item:RenderRequest){return `${item.createdAt?new Date(item.createdAt).toLocaleString():''} · ${item.configuration?.profile==='FULL_HD_1080'?'1080p':'720p'} · ${({PENDING:'等待',RUNNING:'执行中',SUCCEEDED:'成功',FAILED:'失败',CANCEL_REQUESTED:'停止中',CANCELLED:'已停止'} as Record<string,string>)[item.status]??item.status}`;}
watch(()=>props.previewRequestId,id=>{if(id)void openStoredPreview(id);});
watch(()=>props.exportRequestId,id=>{if(id)void openStoredExport(id);});
watch(() => props.selection, selection => { if (selection) void openRevision(selection.planId,selection.revisionId); });
watch(() => props.sessionId, () => { void restoreView(); });
onMounted(async () => { await refresh(); await restoreView(); timer = setInterval(() => {void refresh();void refreshPreview();void refreshExport();void refreshExportHistory();},1000); });
onUnmounted(() => { alive = false; ++epoch; clearInterval(timer); });
</script>
<template>
  <section class="plan-panel" aria-label="编排剪辑方案">
    <h3>已保存方案</h3>
    <aside v-if="diagnostics" aria-label="作品诊断"><p v-if="current">方案 {{current.planId}} · 版本 {{current.id}}</p><p v-if="exported">导出请求 {{exported.requestId}} · 任务 {{exported.taskId}} · 产物 {{exported.artifactId ?? '未发布'}}</p></aside>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!plans.length">暂无方案。在对话中提出制作需求，确认草稿后会出现在这里。</p>
    <template v-else>
      <label for="plan-select">查看方案</label><select id="plan-select" :value="selectedPlan" @change="choosePlan(($event.target as HTMLSelectElement).value)">
        <option value="">选择方案</option><option v-for="plan in plans" :key="plan.id" :value="plan.id">{{ plan.title }}</option>
      </select>
      <template v-if="selectedPlan">
        <p v-if="!versions.length">该方案尚无成功版本。</p>
        <label v-else-if="versions.length > 1" for="revision-select">查看版本</label><select v-if="versions.length > 1" id="revision-select" :value="selectedRevision" @change="openRevision(selectedPlan,($event.target as HTMLSelectElement).value)">
          <option value="">选择版本</option><option v-for="version in versions" :key="version.id" :value="version.id">V{{ version.revisionNumber }}</option>
        </select>
        <p v-if="current && versions.some(version => version.revisionNumber > current!.revisionNumber)">已有更新版本，可自行切换；当前查看保持 V{{ current.revisionNumber }}。</p>
        <template v-for="plan in plans.filter(item => item.id === selectedPlan && item.editingTaskId)" :key="plan.id">
          <p role="status">方案正在编辑，仍可查看已有版本。</p><button class="secondary" :disabled="stopping" @click="stop(plan.editingTaskId!)">停止方案修改</button>
        </template>
      </template>
      <p v-if="loading" role="status">正在读取版本…</p>
      <div v-if="current && !loading">
        <h4>V{{ current.revisionNumber }} · {{ current.content.outputs[0]?.title }}</h4>
        <p v-if="current.content.changeSummary">变更说明：{{current.content.changeSummary}}</p>
        <button class="secondary" @click="emit('reference',{type:'PLAN_REVISION',id:current.id,label:`V${current.revisionNumber} · ${current.content.outputs[0]?.title ?? '方案'}`})">引用当前版本</button>
        <p v-if="confirmationId">当前版本已确认；导出仍需另行操作。</p><p v-if="confirmationId && !exportHistory.length" class="awaiting" role="status">待导出</p>
        <button v-if="!confirmationId" class="secondary" :disabled="exportBusy" @click="confirmVersion">确认当前版本</button>
        <label v-if="exportHistory.length" for="export-history-select">查看正式导出记录</label><select v-if="exportHistory.length" id="export-history-select" :value="exported?.revisionId===current.id?exported.requestId:''" @change="openStoredExport(($event.target as HTMLSelectElement).value)"><option value="">选择导出记录</option><option v-for="item in exportHistory" :key="item.requestId" :value="item.requestId">{{ exportLabel(item) }}</option></select>
        <article v-for="(output,outputIndex) in current.content.outputs" :key="output.outputId" class="output-card">
          <h5>成片 {{outputIndex+1}} · {{output.title}}</h5>
          <button class="secondary" @click="emit('reference',{type:'PLAN_REVISION',id:current.id,outputId:output.outputId,label:`V${current.revisionNumber} · ${output.title}`})">引用成片 {{outputIndex+1}}</button>
          <p>{{ (output.durationMs/1000).toFixed(3) }} 秒 · {{ output.render.width }} × {{ output.render.height }} · {{output.clips.length}} 个片段 · {{output.subtitles.cues.length ? `${output.subtitles.cues.length} 条原文字幕` : '无字幕'}}</p>
          <details><summary>片段与选取依据（{{output.clips.length}}）</summary><article v-for="(clip,index) in output.clips" :key="clip.clipId" aria-label="方案剪辑片段" class="clip">
            <strong>片段 {{ index+1 }} · {{ names[clip.assetId] }}</strong>
            <p>原素材 {{ (clip.sourceInMs/1000).toFixed(3) }}–{{ (clip.sourceOutMs/1000).toFixed(3) }} 秒</p>
            <p>选取依据：{{ clip.reason }}</p><button class="secondary" @click="emit('reference',{type:'PLAN_REVISION',id:current.id,outputId:output.outputId,clipId:clip.clipId,label:`V${current.revisionNumber} · ${output.title} · 片段 ${index+1}`})">引用片段 {{index+1}}</button><button class="secondary" @click="play(clip)">播放源片段</button>
          </article></details>
          <button class="secondary" :disabled="previewBusy" @click="generatePreview(output)">生成低清效果预览</button>

          <details><summary>映射后的原文字幕</summary><p v-for="(cue,index) in output.subtitles.cues" :key="index">{{ (cue.startMs/1000).toFixed(3) }}–{{ (cue.endMs/1000).toFixed(3) }} 秒 · {{ cue.text }}</p></details>
        </article>
        <label for="export-profile-select">成片导出清晰度</label><select id="export-profile-select" v-model="exportProfile"><option value="HD_720">720p</option><option value="FULL_HD_1080">1080p</option></select>
        <p class="export-spec">本次导出：V{{current.revisionNumber}} · {{exportProfile === 'FULL_HD_1080' ? '1080p' : '720p'}} · 每次一条输出</p>
        <button class="primary" :disabled="!confirmationId || exportBusy || singleExporting" @click="chooseExport">导出正式成片</button>
        <template v-if="choosingExport">
          <label for="export-output-select">选择导出输出</label><select id="export-output-select" v-model="exportOutputId"><option value="">选择一条输出</option><option v-for="output in current.content.outputs" :key="output.outputId" :value="output.outputId">{{ output.title }}</option></select>
          <button :disabled="!confirmationId || exportBusy || !exportOutputId || outputExporting(exportOutputId)" @click="exportOutput(current.content.outputs.find(output=>output.outputId===exportOutputId)!)">导出所选输出</button>
        </template>
        <p v-if="singleExporting || (exportOutputId && outputExporting(exportOutputId))" role="status">此版本、输出与清晰度正在导出，请等待完成或停止当前导出。</p><p>确认的方案不会自动预览或导出。</p>
      </div>
    </template>
    <section v-if="exported" ref="exportSection" aria-label="正式成片导出" tabindex="-1">
      <h4>正式成片 · 固定 V{{ exportVersion }}</h4><p>{{exportTitle}} · {{exported.configuration?.profile === 'FULL_HD_1080' ? '1080p' : '720p'}}；切换查看版本不会改变此任务的来源。</p>
      <p role="status">{{ ({PENDING:'等待导出',RUNNING:'正在导出',CANCEL_REQUESTED:'正在停止',CANCELLED:'已停止',SUCCEEDED:'导出完成',FAILED:'导出失败'} as Record<string,string>)[exported.status] }}</p>
      <p v-if="exported.error" role="alert">{{ exported.error.message }}</p>
      <button v-if="['PENDING','RUNNING'].includes(exported.status)" @click="cancelExport">停止正式导出</button>
      <p v-if="exportRemoved">成片文件已删除，原始素材保留。</p>
      <p v-else-if="exportDeletePending">正在删除成片文件，存储恢复后自动重试。</p>
      <p v-else-if="exportUnavailable" role="status">成片文件不可用。导出记录保留，可选择版本重新明确导出。</p>
      <template v-if="exported.status==='SUCCEEDED' && !exportRemoved && !exportDeletePending">
        <button v-if="!exportUnavailable" @click="artifactAction('playback-access')">{{exportUrl ? '重新获取播放地址' : '播放正式成片'}}</button>
        <button v-if="!exportUnavailable" @click="artifactAction('download-access')">下载正式成片</button>
        <button ref="deleteButton" class="text-button danger" @click="deleteCandidate={artifactId:exported.artifactId!,title:exportTitle}">删除成片文件</button>
      </template>
      <div v-if="deleteCandidate" role="alertdialog" aria-label="确认删除成片文件" class="delete-confirmation" @keydown.esc.stop="!deleting && (deleteCandidate=null)">
        <p>删除“{{deleteCandidate.title}}”的此份成片文件？下载链接将失效，原始素材与方案保留。</p>
        <button ref="keepButton" class="secondary" :disabled="deleting" @click="deleteCandidate=null">保留文件</button><button class="danger" :disabled="deleting" @click="deleteExport">确认删除此成片</button>
      </div>
      <video v-if="exportUrl" ref="exportVideo" :src="exportUrl" @error="artifactAction('playback-access', true)" @loadedmetadata="exportLoaded" controls aria-label="正式成片播放器" style="width:100%;max-height:420px" />
    </section>
    <section v-if="preview" aria-label="低清效果预览">
      <h4>低清效果预览 · 固定 V{{ previewVersion }}</h4><p>{{previewTitle}}；切换查看方案或版本不会改变此预览的来源。</p>
      <p role="status">{{ ({PENDING:'等待合成',RUNNING:'正在合成',CANCEL_REQUESTED:'正在停止',CANCELLED:'已停止',SUCCEEDED:'合成完成',FAILED:'合成失败'} as Record<string,string>)[preview.status] }}</p>
      <p v-if="preview.error" role="alert">{{ preview.error.message }}</p>
      <button v-if="['PENDING','RUNNING'].includes(preview.status)" class="secondary" @click="cancelPreview">停止预览</button>
      <button v-if="preview.status==='SUCCEEDED'" class="secondary" @click="playPreview">播放低清预览</button>
      <button v-if="preview.status==='SUCCEEDED'" class="secondary" @click="previewAccess('download-access')">下载低清预览</button>
      <video v-if="previewUrl" :src="previewUrl" controls aria-label="低清合成预览播放器" style="width:100%;max-height:420px" />
    </section>
  </section>
</template>
<style scoped>
.plan-panel { margin: 0; }
.plan-panel > section, .plan-panel section { margin-top: 16px; }
.plan-panel h3 { font-size: 12px; color: var(--ink-3); font-weight: 550; margin: 0 0 8px; }
.plan-panel h4 { font-size: 14px; margin: 10px 0 4px; }
.plan-panel h5 { font-size: 12px; margin: 4px 0; }
.plan-panel label { margin-top: 8px; font-size: 10.5px; }
.plan-panel select { min-height: 30px; font-size: 11.5px; }
.plan-panel p, .plan-panel summary, .plan-panel strong, .plan-panel li { font-size: 11px; line-height: 1.55; overflow-wrap: anywhere; }
.plan-panel .output-card { border-top: 1px solid var(--line); padding: 10px 0; }
.plan-panel .output-card h5 { font-size: 12px; margin: 4px 0; }
.plan-panel .awaiting, .plan-panel .export-spec { background: var(--panel); padding: 7px 8px; border-radius: 5px; font-size: 10.5px; }
.plan-panel .delete-confirmation { border: 1px solid #d9a29e; padding: 10px; margin: 10px 0; background: #fff6f5; }
.plan-panel summary { cursor: pointer; padding: 6px 0; min-height: 30px; }
.plan-panel .clip { border: 1px solid var(--line); border-radius: 6px; padding: 8px; margin: 6px 0; }
.plan-panel button { font-size: 11px; min-height: 28px; padding: 4px 8px; margin: 4px 4px 0 0; }
.plan-panel video { max-height: 280px; }
</style>

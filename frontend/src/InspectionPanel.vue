<script setup lang="ts">
import {onMounted,onUnmounted,ref} from 'vue';
import {request} from './api';
type Observation={id:string;sourceKind:string;text:string;observedAtMs:number};
type Inspection={id:string;status:string;taskId:string;budget:{maxFrames:number;maxCalls:number;maxSteps:number;maxSeconds:number;maxInputBytes:number};usage:{frames:number;calls:number;steps:number;inputBytes:number};coverage:{startMs:number;endMs:number};observations:Observation[];coverageWarning:string;expansionProposal:Proposal|null};
type Proposal={assetId:string;startMs:number;endMs:number;status:string;budget:{maxFrames:number;maxCalls:number};cost:{explanation:string};childInspectionId:string|null};
const props=defineProps<{projectId:string;assetId:string;filename:string;query:string;sourceKind:string;observedAtMs?:number;existingId?:string}>();
const run=ref<Inspection|null>(null),error=ref('');let stopped=false;
const names:Record<string,string>={PENDING:'等待补查',RUNNING:'正在预算内补查',SUCCEEDED:'已找到新匹配，已停止',NO_NEW_EVIDENCE:'无新匹配，已停止',BUDGET_EXHAUSTED:'预算耗尽，已停止',FAILED:'补查失败，已停止',CANCELLED:'已取消补查'};
onUnmounted(()=>{stopped=true;});
async function start(){
  try{
    let range={};
    if(props.observedAtMs!==undefined){const asset=await request<{durationMs:number}>(`/assets/${props.assetId}`);range={startMs:Math.max(0,props.observedAtMs-1000),endMs:Math.min(asset.durationMs,props.observedAtMs+1000)};}
    run.value=props.existingId?await request<Inspection>(`/visual-inspections/${props.existingId}`):await request<Inspection>(`/projects/${props.projectId}/visual-inspections`,'POST',{assetId:props.assetId,query:props.query,sourceKind:props.sourceKind,...range},{'Idempotency-Key':crypto.randomUUID()});
    while(!stopped&&['PENDING','RUNNING'].includes(run.value.status)){await new Promise(resolve=>setTimeout(resolve,1000));if(!stopped)run.value=await request<Inspection>(`/visual-inspections/${run.value.id}`);}
  }catch(failure){error.value=failure instanceof Error?failure.message:'无法补查';}
}
async function propose(){if(!run.value)return;try{run.value.expansionProposal=await request<Proposal>(`/visual-inspections/${run.value.id}/expansion-proposal`,'POST',{});}catch(failure){error.value=String(failure);}}
async function decline(){if(!run.value)return;try{run.value.expansionProposal=await request<Proposal>(`/visual-inspections/${run.value.id}/expansion-decision`,'POST',{choice:'DECLINE'});}catch(failure){error.value=String(failure);}}
async function approve(){if(!run.value)return;try{run.value.expansionProposal=await request<Proposal>(`/visual-inspections/${run.value.id}/expansion-decision`,'POST',{choice:'APPROVE'});}catch(failure){error.value=String(failure);}}
onMounted(start);
</script>
<template>
  <section role="region" :aria-label="existingId?'扩大补查结果':'预算内补查结果'">
    <p class="eyebrow">{{filename}} · {{query}}</p>
    <h4>{{existingId?'扩大补查结果':'预算内补查结果'}}</h4><p v-if="error" role="alert">{{error}}</p>
    <template v-if="run"><p>{{names[run.status]??run.status}}</p>
      <p>本轮最多 {{run.budget.maxFrames}} 帧、{{run.budget.maxCalls}} 次视觉调用、{{run.budget.maxSteps}} 个步骤、{{run.budget.maxSeconds}} 秒、{{run.budget.maxInputBytes/1048576}} MiB 图片输入。</p>
      <p>已用 {{run.usage.frames}} 帧、{{run.usage.calls}} 次调用、{{run.usage.steps}} 个步骤；范围 {{(run.coverage.startMs/1000).toFixed(2)}}–{{(run.coverage.endMs/1000).toFixed(2)}} 秒。</p>
      <div v-for="observation in run.observations" :key="observation.id" class="observation"><span>{{(observation.observedAtMs/1000).toFixed(2)}} 秒 · {{observation.sourceKind==='VISIBLE_TEXT'?'可见文字':'画面'}}</span><p>{{observation.text}}</p></div>
      <p>{{run.coverageWarning}}</p>
      <button v-if="!['PENDING','RUNNING'].includes(run.status)&&!run.expansionProposal" type="button" @click="propose">查看扩大补查方案</button>
      <section v-if="run.expansionProposal" aria-label="扩大补查方案"><p>拟扩大素材：{{filename}}；范围 {{(run.expansionProposal.startMs/1000).toFixed(2)}}–{{(run.expansionProposal.endMs/1000).toFixed(2)}} 秒；新增最多 {{run.expansionProposal.budget.maxFrames}} 帧、{{run.expansionProposal.budget.maxCalls}} 次视觉调用。</p><p>{{run.expansionProposal.cost.explanation}}</p>
      <button v-if="run.expansionProposal.status==='PROPOSED'" type="button" @click="approve">接受范围与费用，继续补查</button>
      <button v-if="run.expansionProposal.status==='PROPOSED'" type="button" @click="decline">保留结果，不扩大</button><p v-if="run.expansionProposal.status==='DECLINED'">已拒绝扩大，保留当前结果。</p>
      <InspectionPanel v-if="run.expansionProposal.childInspectionId" :project-id="projectId" :asset-id="assetId" :filename="filename" :query="query" :source-kind="sourceKind" :existing-id="run.expansionProposal.childInspectionId" />
      </section>
    </template><p v-else>正在提交补查…</p>
  </section>
</template>

<style scoped>
section { margin-top: 12px; padding: 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 7px; }
h4 { margin: 0 0 6px; font-size: 13px; }
p { font-size: 12px; line-height: 1.6; color: var(--ink-3); }
.observation { padding: 8px 0; border-top: 1px solid var(--line); }
.observation span { color: var(--ink-4); font-size: 12px; }
.observation p { margin: 4px 0 0; color: var(--ink); }
section section { background: #fff; border: 1px solid var(--line); margin-top: 8px; }
button { min-height: 26px; padding: 3px 7px; font-size: 10.5px; margin: 3px 3px 0 0; }
@media(max-width:720px) { section { padding: 10px; } }
</style>

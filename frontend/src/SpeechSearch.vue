<script setup lang="ts">
import { computed,ref } from 'vue';
import { request } from './api';
import InspectionPanel from './InspectionPanel.vue';
import type { Playback } from './AssetLibrary.vue';
const props=defineProps<{projectId:string}>();
const emit=defineEmits<{play:[value:Playback]}>();
type Hit={assetId:string;filename:string;text:string;startMs?:number;endMs?:number;observedAtMs?:number;sourceKind:string;evidenceIds:string[]};
type Coverage={assetId:string;filename:string;sourceKind:string;status:string;indexStatus:string;processedRanges:Array<{startMs:number;endMs:number}>};
type Result={hits:Hit[];semanticAvailable:boolean;coverage:{assets:Coverage[]}};
type Observation={id:string;text:string;startMs?:number;endMs?:number;observedAtMs?:number};
const contexts=ref<Record<string,{observations:Observation[]}>>({});
const loadingContext=ref<string|null>(null);
const query=ref('');
const mode=ref('literal');
const sourceKind=ref('SPEECH');
const searched = ref({ query: '', sourceKind: 'SPEECH', mode: 'literal' });
const sourceNames:Record<string,string>={SPEECH:'语音',VISUAL:'画面',VISIBLE_TEXT:'可见文字'};
const frames=ref<Record<string,string>>({});
const inspection=ref<{key:string;assetId:string;sourceKind:string;query:string;filename:string;observedAtMs?:number}|null>(null);
function refine(assetId:string,kind:string,filename:string,observedAtMs?:number){inspection.value={key:crypto.randomUUID(),assetId,sourceKind:kind,query:searched.value.query,filename,observedAtMs};}
const result=ref<Result|null>(null);
const busy=ref(false);
const error=ref('');
const hasAnalyzedEvidence=computed(()=>result.value?.coverage.assets.some(asset=>asset.status==='SUCCEEDED')??false);
const coverageStates:Record<string,string>={SUCCEEDED:'已分析',PENDING:'等待分析',RUNNING:'正在分析',NOT_APPLICABLE:'无音轨，不适用',FAILED:'分析失败',UNVERIFIED:'未验证',CANCELLED:'已取消'};
async function search(){
  if(busy.value)return;
  busy.value=true;error.value='';result.value=null;contexts.value={};frames.value={};
  searched.value = { query: query.value, sourceKind: sourceKind.value, mode: mode.value };
  try{result.value=await request<Result>(`/projects/${props.projectId}/search`,'POST',{query:searched.value.query,literalPhrase:searched.value.mode==='literal',limit:20,sourceKinds:[searched.value.sourceKind]});}
  catch(failure){error.value=failure instanceof Error?failure.message:'无法查询语音';}
  finally{busy.value=false;}
}
async function context(hit:Hit){
  const id=hit.evidenceIds[0];
  if(!id||loadingContext.value)return;
  loadingContext.value=id;error.value='';
  try{const value=await request<{observations:Observation[]}>(`/evidence/${id}/context?boundedWindowMs=5000`);contexts.value[id]=value;}
  catch(failure){error.value=failure instanceof Error?failure.message:'无法查询邻近原文';}
  finally{loadingContext.value=null;}
}
async function inspect(hit:Hit){
  error.value='';
  try{const access=await request<Playback>(`/assets/${hit.assetId}/playback-access`,'POST',{});emit('play',{...access,startMs:hit.observedAtMs??hit.startMs});}
  catch(failure){error.value=failure instanceof Error?failure.message:'无法核查原视频';}
}
async function frame(hit:Hit){
  try{const access=await request<{url:string}>(`/evidence/${hit.evidenceIds[0]}/frame-access`,'POST',{});frames.value[hit.evidenceIds[0]!]=access.url;}
  catch(failure){error.value=failure instanceof Error?failure.message:'无法读取观察帧';}
}
function time(value:{startMs?:number;endMs?:number;observedAtMs?:number}){
  if(value.observedAtMs!==undefined)return `${(value.observedAtMs/1000).toFixed(2)} 秒 · 帧观察时刻`;
  return `${((value.startMs??0)/1000).toFixed(2)}–${((value.endMs??0)/1000).toFixed(2)} 秒 · 模型时间戳`;
}
</script>
<template>
  <section class="speech-search">
    <h3>查找视频证据</h3>
    <form @submit.prevent="search">
      <label for="speech-query">查找原句</label><div class="query-input"><input id="speech-query" v-model="query" required maxlength="256" placeholder="输入主题、人名、数字或原句"><button type="submit" :disabled="busy">{{busy?'正在查找…':sourceKind==='SPEECH'?'查找语音':'查找证据'}}</button></div>
      <details class="filters"><summary>筛选条件</summary><div class="filter-fields"><div><label for="evidence-kind">证据类型</label><select id="evidence-kind" v-model="sourceKind"><option value="SPEECH">语音</option><option value="VISUAL">画面</option><option value="VISIBLE_TEXT">可见文字</option></select></div><div><label for="search-mode">检索方式</label><select id="search-mode" v-model="mode"><option value="literal">原文短语</option><option value="semantic">主题与近义表达</option></select></div></div></details>
      <p class="filter-summary">当前筛选：{{sourceNames[sourceKind]}} · {{mode==='literal'?'原文短语':'主题与近义表达'}}</p>
    </form>
    <p v-if="error" role="alert">{{error}}</p>
    <template v-if="result"><p class="result-summary">查找“{{searched.query}}” · {{sourceNames[searched.sourceKind]}} · {{result.hits.length}} 条候选</p><p v-if="searched.sourceKind!=='SPEECH'">代表帧为稀疏采样；未命中不能证明画面或文字未出现。</p>
      <p v-if="result.coverage.assets.some(asset => !['SUCCEEDED', 'NOT_APPLICABLE'].includes(asset.status))" role="status">仍有素材尚未完成分析，本次只查找已有证据。可查看查询覆盖了解范围。</p>
      <p v-if="!result.semanticAvailable">当前仅支持原文查找，语义检索尚未就绪</p>
      <p v-if="!result.hits.length && hasAnalyzedEvidence">在已分析的{{sourceNames[searched.sourceKind]}}范围内未找到{{searched.mode==='literal'?'原文匹配':'主题匹配'}}</p>
      <p v-if="!result.hits.length && !hasAnalyzedEvidence">尚无已完成分析的{{sourceNames[searched.sourceKind]}}证据</p>
      <details class="coverage"><summary>查看查询覆盖</summary><section role="region" :aria-label="`${sourceNames[searched.sourceKind]}查询覆盖`">
        <h4>已查素材与{{sourceNames[searched.sourceKind]}}覆盖</h4>
        <p v-if="!result.coverage.assets.length">项目中还没有可分析的有效素材。</p>
        <div v-for="asset in result.coverage.assets" :key="asset.assetId">
          <p>{{asset.filename}} · {{sourceNames[asset.sourceKind]}}：{{coverageStates[asset.status]??'状态待核对'}}</p>
          <p>语义索引：{{asset.indexStatus==='SUCCEEDED'?'已就绪':coverageStates[asset.indexStatus]??'尚未构建'}}</p>
          <button v-if="asset.sourceKind!=='SPEECH'" type="button" @click="refine(asset.assetId,asset.sourceKind,asset.filename)">预算内覆盖补查</button>
          <p v-for="range in asset.processedRanges" :key="range.startMs">{{(range.startMs/1000).toFixed(2)}}–{{(range.endMs/1000).toFixed(2)}} 秒</p>
          <p v-if="asset.sourceKind==='SPEECH'&&asset.status==='SUCCEEDED'&&!asset.processedRanges.length">语音已分析，时间覆盖范围尚无法确认。</p>
        </div>
        <p>识别可能遗漏；未命中不能证明原视频没有该内容。</p>
      </section></details>
      <article v-for="hit in result.hits" :key="hit.evidenceIds.join(',')" :aria-label="`${sourceNames[hit.sourceKind]}检索证据`">
        <h4>{{hit.filename}} · {{sourceNames[hit.sourceKind]}}</h4><p>{{hit.text}}</p>
        <p>{{time(hit)}}</p>
        <button v-if="hit.sourceKind!=='SPEECH'" type="button" @click="frame(hit)">查看观察帧</button><img v-if="frames[hit.evidenceIds[0]!]" :src="frames[hit.evidenceIds[0]!]" alt="证据观察帧" style="max-width:100%"><button type="button" @click="inspect(hit)">核查原视频</button>
        <button type="button" :disabled="loadingContext!==null" @click="context(hit)">{{loadingContext===hit.evidenceIds[0]?'正在读取上下文…':'查看邻近上下文'}}</button>
        <section v-if="contexts[hit.evidenceIds[0]!]" role="region" aria-label="邻近原文">
          <p v-for="observation in contexts[hit.evidenceIds[0]!]!.observations" :key="observation.id">{{time(observation)}} · {{observation.text}}</p>
        </section>
        <p v-if="hit.sourceKind!=='SPEECH'">复查会在现有预算内调用视觉模型；扩大范围与费用须另行接受。</p>
        <button v-if="hit.sourceKind!=='SPEECH'" type="button" @click="refine(hit.assetId,hit.sourceKind,hit.filename,hit.observedAtMs)">预算内复查候选</button>
        <p>检索窗口不是最终剪辑切点，请回看原视频。</p>
      </article>
    </template>
    <InspectionPanel v-if="inspection" :filename="inspection.filename" :key="inspection.key" :project-id="projectId" :asset-id="inspection.assetId" :source-kind="inspection.sourceKind" :query="inspection.query" :observed-at-ms="inspection.observedAtMs" />
  </section>
</template>
<style scoped>
.speech-search{margin-top:24px;border-top:1px solid #dce5de;padding-top:12px}
.query-input{display:flex;align-items:start;gap:12px}.query-input input{flex:1;min-width:0}.query-input button{margin:0;flex-shrink:0}.filter-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px}.filter-summary{color:var(--muted)}summary{cursor:pointer;min-height:44px;padding:10px 0}.result-summary{font-weight:600}.coverage{margin:16px 0}h3{font-size:18px}h4{margin:0;font-size:14px;overflow-wrap:anywhere}
p{font-size:13px;line-height:1.6;overflow-wrap:anywhere}
article{border-top:1px solid var(--line);padding:20px 0;margin-top:12px}@media(max-width:720px){.query-input{flex-wrap:wrap}.query-input input{flex-basis:100%}.filter-fields{grid-template-columns:1fr;gap:0}}
</style>

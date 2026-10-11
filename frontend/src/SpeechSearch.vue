<script setup lang="ts">
import { computed,ref } from 'vue';
import { request } from './api';
import type { Playback } from './AssetLibrary.vue';
const props=defineProps<{projectId:string}>();
const emit=defineEmits<{play:[value:Playback]}>();
type Hit={assetId:string;filename:string;text:string;startMs:number;endMs:number;evidenceIds:string[]};
type Coverage={assetId:string;filename:string;status:string;indexStatus:string;processedRanges:Array<{startMs:number;endMs:number}>};
type Result={hits:Hit[];semanticAvailable:boolean;coverage:{assets:Coverage[]}};
type Observation={id:string;text:string;startMs:number;endMs:number};
const contexts=ref<Record<string,{observations:Observation[]}>>({});
const loadingContext=ref<string|null>(null);
const query=ref('');
const mode=ref('literal');
const result=ref<Result|null>(null);
const busy=ref(false);
const error=ref('');
const hasAnalyzedSpeech=computed(()=>result.value?.coverage.assets.some(asset=>asset.status==='SUCCEEDED')??false);
const coverageStates:Record<string,string>={SUCCEEDED:'已分析',PENDING:'等待分析',RUNNING:'正在分析',NOT_APPLICABLE:'无音轨，不适用',FAILED:'分析失败',UNVERIFIED:'未验证',CANCELLED:'已取消'};
async function search(){
  if(busy.value)return;
  busy.value=true;error.value='';result.value=null;contexts.value={};
  try{result.value=await request<Result>(`/projects/${props.projectId}/search`,'POST',{query:query.value,literalPhrase:mode.value==='literal',limit:20});}
  catch(failure){error.value=failure instanceof Error?failure.message:'无法查询语音';}
  finally{busy.value=false;}
}
async function rebuild(assetId:string){
  error.value='';
  try{await request(`/assets/${assetId}/search-index`,'POST',{});error.value='已安排重建语义索引；稍后重新查询可查看状态。';}
  catch(failure){error.value=failure instanceof Error?failure.message:'无法重建索引';}
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
  try{const access=await request<Playback>(`/assets/${hit.assetId}/playback-access`,'POST',{});emit('play',{...access,startMs:hit.startMs});}
  catch(failure){error.value=failure instanceof Error?failure.message:'无法核查原视频';}
}
</script>
<template>
  <section class="speech-search">
    <h3>查找视频语音</h3>
    <form @submit.prevent="search"><label for="search-mode">检索方式</label><select id="search-mode" v-model="mode"><option value="literal">原文短语</option><option value="semantic">主题与近义表达</option></select><label for="speech-query">查找原句</label><input id="speech-query" v-model="query" required maxlength="256" placeholder="输入主题、人名、数字或原句"><button type="submit" :disabled="busy">{{busy?'正在查找…':'查找语音'}}</button></form>
    <p v-if="error" role="alert">{{error}}</p>
    <template v-if="result">
      <p v-if="!result.semanticAvailable">当前仅支持原文查找，语义检索尚未就绪</p>
      <p v-if="!result.hits.length && hasAnalyzedSpeech">在已分析的语音范围内未找到原文匹配</p>
      <p v-if="!result.hits.length && !hasAnalyzedSpeech">尚无已完成分析的语音证据</p>
      <section role="region" aria-label="语音查询覆盖">
        <h4>已查素材与语音覆盖</h4>
        <p v-if="!result.coverage.assets.length">项目中还没有可分析的有效素材。</p>
        <div v-for="asset in result.coverage.assets" :key="asset.assetId">
          <p>{{asset.filename}} · 语音：{{coverageStates[asset.status]??'状态待核对'}}</p>
          <p>语义索引：{{asset.indexStatus==='SUCCEEDED'?'已就绪':coverageStates[asset.indexStatus]??'尚未构建'}}</p>
          <button v-if="asset.status==='SUCCEEDED'" type="button" @click="rebuild(asset.assetId)">重建语义索引</button>
          <p v-for="range in asset.processedRanges" :key="range.startMs">{{(range.startMs/1000).toFixed(2)}}–{{(range.endMs/1000).toFixed(2)}} 秒</p>
          <p v-if="asset.status==='SUCCEEDED'&&!asset.processedRanges.length">语音已分析，时间覆盖范围尚无法确认。</p>
        </div>
        <p>识别可能遗漏；未命中不能证明原视频没有该内容。</p>
      </section>
      <article v-for="hit in result.hits" :key="hit.evidenceIds.join(',')" aria-label="语音检索证据">
        <h4>{{hit.filename}} · 语音</h4><p>{{hit.text}}</p>
        <p>{{(hit.startMs/1000).toFixed(2)}}–{{(hit.endMs/1000).toFixed(2)}} 秒 · 模型时间戳</p>
        <button type="button" @click="inspect(hit)">核查原视频</button>
        <button type="button" :disabled="loadingContext!==null" @click="context(hit)">{{loadingContext===hit.evidenceIds[0]?'正在读取上下文…':'查看邻近上下文'}}</button>
        <section v-if="contexts[hit.evidenceIds[0]!]" role="region" aria-label="邻近原文">
          <p v-for="observation in contexts[hit.evidenceIds[0]!]!.observations" :key="observation.id">{{(observation.startMs/1000).toFixed(2)}}–{{(observation.endMs/1000).toFixed(2)}} 秒 · {{observation.text}}</p>
        </section>
        <p>检索窗口不是最终剪辑切点，请回看原视频。</p>
      </article>
    </template>
  </section>
</template>
<style scoped>
.speech-search{margin-top:24px;border-top:1px solid #dce5de;padding-top:12px}
h3{font-size:15px}h4{margin:0;font-size:14px;overflow-wrap:anywhere}
p{font-size:13px;line-height:1.6;overflow-wrap:anywhere}
article{border:1px solid #dce5de;border-radius:10px;padding:14px;margin-top:12px}
</style>

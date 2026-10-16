<script setup lang="ts">
import {computed,ref,watch,onMounted,onUnmounted} from 'vue';
import {request} from './api';
const props=defineProps<{assetId:string;maintenance?:boolean}>();
const information=ref<{filename:string;analysis?:{stages:Record<string,string>;errors:Record<string,{message:string}>;mediaRetryable?:boolean}}|null>(null);
const evidence=ref<{units:unknown[]}|null>(null);
const error=ref('');
const stageNames:Record<string,string>={MEDIA:'媒体',ASR:'语音',VISION:'画面',EMBEDDING:'Embedding',INDEX:'索引'};
const stageStates:Record<string,string>={SUCCEEDED:'已就绪',NOT_APPLICABLE:'不适用',NOT_STARTED:'尚未开始',PENDING:'等待执行',RUNNING:'正在分析',FAILED:'失败',UNVERIFIED:'未验证',CANCELLED:'已取消',CANCEL_REQUESTED:'等待停止'};
const capabilities = computed(() => {
  const stages = information.value?.analysis?.stages;
  if (!stages) return '正在读取能力状态…';
  return [
    stages.MEDIA === 'SUCCEEDED' ? '可以播放' : `媒体${stageStates[stages.MEDIA ?? ''] ?? '状态待核对'}`,
    stages.ASR === 'NOT_APPLICABLE' ? '无音轨，不能进行语音分析' : stages.ASR === 'SUCCEEDED' ? '语音已就绪' : `语音${stageStates[stages.ASR ?? ''] ?? '状态待核对'}`,
    stages.VISION === 'SUCCEEDED' ? '画面已就绪' : stages.VISION === 'FAILED' ? '画面分析失败' : `画面${stageStates[stages.VISION ?? ''] ?? '尚未分析'}`,
  ].join(' · ');
});
type IndexStatus = {publishedGeneration: {id:string;model:string;dimension:number} | null;generations: Array<{id:string;model:string;dimension:number;taskId:string;taskStatus:string}>};
const indexStatus = ref<IndexStatus | null>(null);
const recovering = ref(false);
const indexModel = ref('text-embedding-v4');
const indexDimension = ref(1024);
const averageLength = ref(256);
const dimensions = computed(()=>indexModel.value==='text-embedding-v3'?[64,128,256,512,768,1024]:[64,128,256,512,768,1024,1536,2048]);
watch(indexModel,()=>{if(!dimensions.value.includes(indexDimension.value)) indexDimension.value=1024;});
async function recover(stage:string) {
  recovering.value=true;error.value='';
  try{await request(`/assets/${props.assetId}/analysis-stages/${stage}/retry`,'POST',{});await refreshInformation(props.assetId);}
  catch(failure){error.value=failure instanceof Error?failure.message:'恢复未接纳';}
  finally{recovering.value=false;}
}
async function rebuildIndex() {
  recovering.value=true;error.value='';
  try{await request(`/assets/${props.assetId}/search-index`,'POST',{model:indexModel.value,dimension:indexDimension.value,bm25AvgLength:averageLength.value});await refreshInformation(props.assetId);}
  catch(failure){error.value=failure instanceof Error?failure.message:'重建索引未接纳';}
  finally{recovering.value=false;}
}
async function refreshInformation(id:string) {
  try{
    const [details,index,observations]=await Promise.all([request<NonNullable<typeof information.value>>(`/assets/${id}`),props.maintenance ? request<IndexStatus>(`/assets/${id}/search-index`) : null,props.maintenance ? request<NonNullable<typeof evidence.value>>(`/assets/${id}/evidence`) : null]);
    if(props.assetId===id){information.value=details;indexStatus.value=index;evidence.value=observations;}
  }catch(failure){error.value=failure instanceof Error?failure.message:'无法查询分析状态';}
}
watch(()=>props.assetId,id=>{information.value=null;indexStatus.value=null;evidence.value=null;void refreshInformation(id);},{immediate:true});
let timer:ReturnType<typeof setInterval>|undefined;
onMounted(()=>{timer=setInterval(()=>void refreshInformation(props.assetId),1000);});
onUnmounted(()=>clearInterval(timer));
</script>
<template><section :aria-label="maintenance ? '素材索引维护' : '素材分析状态'">
    <p class="capabilities">{{ capabilities }}</p><p v-if="error" role="alert">{{error}}</p>
    <template v-for="(name,key) in stageNames" :key="key">
      <button v-if="['FAILED','UNVERIFIED','CANCELLED'].includes(information?.analysis?.stages[key] ?? '') && (key !== 'MEDIA' || information?.analysis?.mediaRetryable)" :disabled="recovering" @click="recover(String(key))">仅重试失败的 {{ name }}</button>
    </template>
    <details :open="maintenance"><summary>完整分析阶段</summary>
      <p v-for="(name,key) in stageNames" :key="key">{{ name }}：{{ stageStates[information?.analysis?.stages[key] ?? ''] ?? '状态待核对' }}</p>
    </details>
    <p v-if="indexStatus?.publishedGeneration">当前查询使用已发布索引 {{ indexStatus.publishedGeneration.model }} · {{ indexStatus.publishedGeneration.dimension }} 维；重建成功发布前保留此代次。</p>
    <form v-if="maintenance && evidence?.units?.length" @submit.prevent="rebuildIndex">
      <label :for="`index-model-${assetId}`">索引 Embedding 模型</label><select :id="`index-model-${assetId}`" v-model="indexModel"><option>text-embedding-v4</option><option>text-embedding-v3</option></select>
      <label :for="`index-dimension-${assetId}`">索引向量维度</label><select :id="`index-dimension-${assetId}`" v-model.number="indexDimension"><option v-for="dimension in dimensions" :key="dimension" :value="dimension">{{ dimension }}</option></select>
      <label :for="`index-length-${assetId}`">BM25 平均长度</label><input :id="`index-length-${assetId}`" v-model.number="averageLength" type="number" min="1" max="8192" required>
      <p>只修改 BM25 配置会复用成功向量；更换模型或维度可能产生新的 Embedding 用量。原始证据保留。</p>
      <button :disabled="recovering">重建当前素材索引</button>
    </form>
    <p v-for="(failure,key) in information?.analysis?.errors" :key="`error-${key}`" role="alert">{{ failure.message }}</p>
    <p v-if="information?.analysis?.stages.MEDIA === 'FAILED' && !information?.analysis?.mediaRetryable">文件已确认无效或已进入清理，请按失败原因重新上传有效视频。</p>
</section></template>
<style scoped>summary{cursor:pointer;min-height:44px;padding:10px 0}.capabilities{font-weight:500;color:var(--accent)}p{font-size:13px;overflow-wrap:anywhere}label{display:block;font-size:12px;margin:8px 0}button{font-size:12px}</style>

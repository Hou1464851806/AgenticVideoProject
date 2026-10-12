<script setup lang="ts">
import {onMounted,onUnmounted,ref} from 'vue';
import {request} from './api';
const props=defineProps<{projectId:string}>();
type Thresholds={costCny:number|null;calls:number|null;tokens:number|null;audioSeconds:number|null};
type Call={id:string;provider:string;model:string;purpose:string;status:string;retryNumber:number;requestId:string|null;providerTaskId:string|null;usageBasis:string;costBasis:string;estimatedCostCny:number|null;pricingRef:string;reportedUsage:unknown;usage:{inputTokens:number;outputTokens:number;audioSeconds:number;cachedInputTokens:number}};
type Models={callCount:number;reusedBatchCount:number;uncertainCallCount:number;unpricedCallCount:number;estimatedCostCny:number;tokens:number;audioSeconds:number;items:Call[];nextOffset:number};
type Usage={storage:Record<string,{storedBytes:number;reservedBytes:number;pendingCleanupBytes:number}>;models:Models;thresholds:Thresholds;warnings:{metric:string;value:number;threshold:number}[]};
const usage=ref<Usage|null>(null),error=ref(''),notice=ref(''),busy=ref(false);
const form=ref({costCny:'',calls:'',tokens:'',audioSeconds:''});
const categories:Record<string,string>={ORIGINAL:'原始素材',DERIVED:'派生媒体',PREVIEW:'低清预览',EXPORT:'正式成片'};
const purposes:Record<string,string>={ASR:'语音理解',VISION:'画面理解',AGENT:'Agent 对话与编排',INDEX_EMBEDDING:'检索索引向量',QUERY_EMBEDDING:'检索查询向量'};
const metrics:Record<string,string>={costCny:'估算费用',calls:'模型调用次数',tokens:'Token 用量（含估算）',audioSeconds:'音频秒数（含估算）'};
function bytes(value:number){return value===0?'0 B':value<1024?`${value} B`:value<1048576?`${(value/1024).toFixed(2)} KiB`:`${(value/1048576).toFixed(2)} MiB`;}
function money(value:number){return value.toFixed(9).replace(/0+$/,'').replace(/\.$/,'');}
async function load(initial=false){
  if(busy.value)return;busy.value=true;
  try{
    usage.value=await request<Usage>(`/projects/${props.projectId}/usage`);error.value='';
    if(initial)for(const key of Object.keys(form.value) as (keyof Thresholds)[])form.value[key]=usage.value.thresholds[key]?.toString()??'';
  }catch(failure){error.value=failure instanceof Error?failure.message:'用量暂不可用';}finally{busy.value=false;}
}
async function save(){
  notice.value='';error.value='';
  try{
    const values:Record<string,number|null>={};for(const [key,value] of Object.entries(form.value))values[key]=value===''?null:Number(value);
    await request(`/projects/${props.projectId}/usage/thresholds`,'PATCH',values);notice.value='预警阈值已保存';await load();
  }catch(failure){error.value=failure instanceof Error?failure.message:'阈值保存失败';}
}
async function more(){
  if(!usage.value||usage.value.models.nextOffset<0)return;
  try{const page=await request<Models>(`/projects/${props.projectId}/usage/models?offset=${usage.value.models.nextOffset}`);usage.value.models.items.push(...page.items);usage.value.models.nextOffset=page.nextOffset;}
  catch(failure){error.value=failure instanceof Error?failure.message:'记录暂不可用';}
}
let timer:ReturnType<typeof setInterval>|undefined;
onMounted(()=>{void load(true);timer=setInterval(()=>{if(usage.value?.models.nextOffset===-1)void load();},5000);});
onUnmounted(()=>clearInterval(timer));
</script>
<template>
  <section class="usage" aria-label="存储与模型用量">
    <h2>存储与模型用量</h2><button :disabled="busy" @click="load()">刷新用量</button>
    <p v-if="error" role="alert" class="error">{{error}}</p>
    <template v-if="usage">
      <div class="table-scroll"><table><caption>实际对象与未完成预留</caption><thead><tr><th>类别</th><th>已占用</th><th>未完成预留</th><th>待清理占用</th></tr></thead><tbody><tr v-for="(label,key) in categories" :key="key"><td>{{label}}</td><td>{{bytes(usage.storage[key]!.storedBytes)}}</td><td>{{bytes(usage.storage[key]!.reservedBytes)}}</td><td>{{bytes(usage.storage[key]!.pendingCleanupBytes)}}</td></tr></tbody></table></div>
      <p>已占用直接核对对象与上传分片；预留含处理工作区的保守预算，待清理是已占用的一部分。未发布的派生文件也计入占用。</p>
      <p>模型调用：{{usage.models.callCount}}</p><p>估算费用：¥{{money(usage.models.estimatedCostCny)}} · Token：{{usage.models.tokens}} · 音频：{{usage.models.audioSeconds}} 秒</p>
      <p>向量批次复用 {{usage.models.reusedBatchCount}} 次；尚未确认的调用 {{usage.models.uncertainCallCount}} 次。缺价格的调用 {{usage.models.unpricedCallCount}} 次，其费用未包含在合计中。</p>
      <p>费用按 2026-10-12 北京百炼公开价及 DeepSeek 高峰价保守估算，未扣免费额度、百炼缓存优惠和时段折扣；实际账单以提供方为准。无回执用量按输入与输出预算估算，VLM 按 8192 输入、1200 输出 token 估算。</p>
      <p><a href="https://help.aliyun.com/zh/model-studio/model-pricing" target="_blank" rel="noreferrer">百炼价格依据</a> · <a href="https://api-docs.deepseek.com/zh-cn/quick_start/pricing/" target="_blank" rel="noreferrer">DeepSeek 价格依据</a></p>
      <p v-for="warning in usage.warnings" :key="warning.metric" role="status" class="warning">{{metrics[warning.metric]}}已达到预警阈值：{{warning.value}} / {{warning.threshold}}</p>
      <form @submit.prevent="save"><h3>项目累计用量预警</h3><p>留空关闭该项预警。预警提示不会阻止执行。</p>
        <label>估算费用预警阈值（元）<input v-model="form.costCny" type="number" min="0.000000001" step="any"></label>
        <label>模型调用次数预警阈值<input v-model="form.calls" type="number" min="1" step="1"></label>
        <label>Token 预警阈值<input v-model="form.tokens" type="number" min="1" step="1"></label>
        <label>音频秒数预警阈值<input v-model="form.audioSeconds" type="number" min="0.001" step="any"></label>
        <button type="submit">保存预警阈值</button><p v-if="notice" role="status">{{notice}}</p>
      </form>
      <h3>模型调用记录</h3><p v-if="!usage.models.items.length">暂无模型调用</p>
      <details v-for="call in usage.models.items" :key="call.id"><summary>{{purposes[call.purpose]??call.purpose}} · {{call.provider}} / {{call.model}} · {{call.status}} · {{call.usageBasis==='REPORTED'?'提供方报告用量':'估算用量'}} · {{call.estimatedCostCny===null?'费用未知':`估算 ¥${money(call.estimatedCostCny)}`}}</summary>
        <p>记录 {{call.id}} · 重试／轮内调用序号 {{call.retryNumber}} · 请求 {{call.requestId??'尚无回执'}} · 提供方任务 {{call.providerTaskId??'不适用或尚无回执'}}</p>
        <p>输入 {{call.usage.inputTokens}}、输出 {{call.usage.outputTokens}} token；音频 {{call.usage.audioSeconds}} 秒；缓存命中 {{call.usage.cachedInputTokens}} token</p>
        <pre>原始报告用量：{{JSON.stringify(call.reportedUsage)}}</pre><p>{{call.pricingRef}}</p>
      </details><button v-if="usage.models.nextOffset>=0" @click="more">加载更多调用记录</button>
    </template><p v-else-if="!error">正在核对实际用量…</p>
  </section>
</template>
<style scoped>
.usage{margin:20px 0;padding:24px;border:1px solid #dce5de;border-radius:14px;background:white}.usage button{width:auto;margin:8px 0}table{border-collapse:collapse;width:100%;text-align:left}th,td{padding:10px;border-bottom:1px solid #dce5de;white-space:nowrap}.table-scroll{overflow-x:auto}p{line-height:1.6;font-size:14px}form{max-width:560px}.warning{padding:12px;background:#fff2d6;color:#704500}details{margin:12px 0}summary{cursor:pointer;line-height:1.7}pre{white-space:pre-wrap;overflow-wrap:anywhere}a{color:#245c45}
</style>

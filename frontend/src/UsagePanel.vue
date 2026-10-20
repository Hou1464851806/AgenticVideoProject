<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref} from 'vue';
import {request} from './api';
const props=defineProps<{projectId:string;diagnostics?:boolean}>();
type Thresholds={costCny:number|null;calls:number|null;tokens:number|null;audioSeconds:number|null};
type Call={id:string;provider:string;model:string;purpose:string;status:string;retryNumber:number;requestId:string|null;providerTaskId:string|null;usageBasis:string;costBasis:string;estimatedCostCny:number|null;pricingRef:string;reportedUsage:unknown;usage:{inputTokens:number;outputTokens:number;audioSeconds:number;cachedInputTokens:number}};
type Models={callCount:number;reusedBatchCount:number;uncertainCallCount:number;unpricedCallCount:number;estimatedCostCny:number;tokens:number;audioSeconds:number;items:Call[];nextOffset:number};
type Usage={storage:Record<string,{storedBytes:number;reservedBytes:number;pendingCleanupBytes:number}>;models:Models;thresholds:Thresholds;warnings:{metric:string;value:number;threshold:number}[]};
const usage=ref<Usage|null>(null),error=ref(''),notice=ref(''),busy=ref(false);
const form=ref({costCny:'',calls:'',tokens:'',audioSeconds:''});
const storageTotals=computed(()=>Object.values(usage.value!.storage).reduce((total,item)=>({storedBytes:total.storedBytes+item.storedBytes,reservedBytes:total.reservedBytes+item.reservedBytes,pendingCleanupBytes:total.pendingCleanupBytes+item.pendingCleanupBytes}),{storedBytes:0,reservedBytes:0,pendingCleanupBytes:0}));
const reportFields=['input_tokens','output_tokens','prompt_tokens','completion_tokens','total_tokens','seconds','prompt_cache_hit_tokens'];
function safeReport(value:unknown){return Object.fromEntries(Object.entries(value as Record<string,unknown>).filter(([key,value])=>reportFields.includes(key)&&typeof value==='number'));}
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
    <h3>存储与模型用量</h3><button :disabled="busy" @click="load()">刷新用量</button>
    <p v-if="error" role="alert" class="error">{{error}}</p>
    <template v-if="usage">
      <dl class="usage-summary"><div><dt>已用存储</dt><dd>{{bytes(storageTotals.storedBytes)}}</dd></div><div><dt>未完成预留</dt><dd>{{bytes(storageTotals.reservedBytes)}}</dd></div><div><dt>待清理占用</dt><dd>{{bytes(storageTotals.pendingCleanupBytes)}}</dd></div><div><dt>估算费用</dt><dd>¥{{money(usage.models.estimatedCostCny)}}</dd></div></dl>
      <p>费用未知 {{usage.models.unpricedCallCount}} 次，未包含在估算合计中；尚未确认的调用 {{usage.models.uncertainCallCount}} 次。</p>
      <details><summary>存储分类</summary><div class="table-scroll"><table><caption>实际对象与未完成预留</caption><thead><tr><th>类别</th><th>已占用</th><th>未完成预留</th><th>待清理占用</th></tr></thead><tbody><tr v-for="(label,key) in categories" :key="key"><td>{{label}}</td><td>{{bytes(usage.storage[key]!.storedBytes)}}</td><td>{{bytes(usage.storage[key]!.reservedBytes)}}</td><td>{{bytes(usage.storage[key]!.pendingCleanupBytes)}}</td></tr></tbody></table></div></details>
      <p>已占用直接核对对象与上传分片；预留含临时处理空间的保守预算，待清理是已占用的一部分。未发布的派生文件也计入占用。</p>
      <details><summary>用量明细与估算依据</summary><p>模型调用：{{usage.models.callCount}}</p><p>估算费用：¥{{money(usage.models.estimatedCostCny)}} · Token：{{usage.models.tokens}} · 音频：{{usage.models.audioSeconds}} 秒</p>
      <p>向量批次复用 {{usage.models.reusedBatchCount}} 次；尚未确认的调用 {{usage.models.uncertainCallCount}} 次。缺价格的调用 {{usage.models.unpricedCallCount}} 次，其费用未包含在合计中。</p>
      <p>费用按 2026-10-12 北京百炼公开价及 DeepSeek 高峰价保守估算，未扣免费额度、百炼缓存优惠和时段折扣；实际账单以提供方为准。无回执用量按输入与输出预算估算，VLM 按 8192 输入、1200 输出 token 估算。</p>
      <p><a href="https://help.aliyun.com/zh/model-studio/model-pricing" target="_blank" rel="noreferrer">百炼价格依据</a> · <a href="https://api-docs.deepseek.com/zh-cn/quick_start/pricing/" target="_blank" rel="noreferrer">DeepSeek 价格依据</a></p>
      </details>
      <p v-for="warning in usage.warnings" :key="warning.metric" role="status" class="warning">{{metrics[warning.metric]}}已达到预警阈值：{{warning.value}} / {{warning.threshold}}</p>
      <form @submit.prevent="save"><h3>项目累计用量预警</h3><p>留空关闭该项预警。预警提示不会阻止执行。</p>
        <label>估算费用预警阈值（元）<input v-model="form.costCny" type="number" min="0.000000001" step="any"></label>
        <label>模型调用次数预警阈值<input v-model="form.calls" type="number" min="1" step="1"></label>
        <label>Token 预警阈值<input v-model="form.tokens" type="number" min="1" step="1"></label>
        <label>音频秒数预警阈值<input v-model="form.audioSeconds" type="number" min="0.001" step="any"></label>
        <button type="submit">保存预警阈值</button><p v-if="notice" role="status">{{notice}}</p>
      </form>
      <details><summary>模型调用记录</summary><p v-if="!usage.models.items.length">暂无模型调用</p>
      <details v-for="call in usage.models.items" :key="call.id"><summary>{{purposes[call.purpose]??call.purpose}} · {{call.provider}} / {{call.model}} · {{call.status}} · {{call.usageBasis==='REPORTED'?'提供方报告用量':'估算用量'}} · {{call.estimatedCostCny===null?'费用未知':`估算 ¥${money(call.estimatedCostCny)}`}}</summary>
        <p v-if="diagnostics">记录 {{call.id}} · 重试／轮内调用序号 {{call.retryNumber}} · 请求 {{call.requestId??'尚无回执'}} · 提供方任务 {{call.providerTaskId??'不适用或尚无回执'}}</p>
        <p>输入 {{call.usage.inputTokens}}、输出 {{call.usage.outputTokens}} token；音频 {{call.usage.audioSeconds}} 秒；缓存命中 {{call.usage.cachedInputTokens}} token</p>
        <pre v-if="diagnostics">报告用量（数值字段）：{{JSON.stringify(safeReport(call.reportedUsage))}}</pre><p>{{call.pricingRef}}</p>
      </details><button v-if="usage.models.nextOffset>=0" @click="more">加载更多调用记录</button></details>
    </template><p v-else-if="!error">正在核对实际用量…</p>
  </section>
</template>
<style scoped>
.usage{margin:0;padding:0;border:0;background:transparent}.usage h3{font-size:13px}.usage-summary{display:grid;grid-template-columns:1fr 1fr;gap:10px 16px}.usage-summary dt{font-size:10.5px;color:var(--muted)}.usage-summary dd{margin:2px 0 0;font-size:16px;font-variant-numeric:tabular-nums}summary{min-height:30px;padding:6px 0;font-size:11px}.usage button{width:auto;margin:4px 0;font-size:11px}table{border-collapse:collapse;width:100%;text-align:left}th,td{padding:6px;border-bottom:1px solid var(--line);white-space:nowrap;font-size:10.5px}.table-scroll{overflow-x:auto}p{line-height:1.6;font-size:11.5px}form{max-width:560px}.warning{padding:8px 10px;border-radius:5px;background:#fff7e8;color:#8a5a00;font-size:11px}details{margin:8px 0}summary{cursor:pointer;line-height:1.7}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:10px}a{color:var(--blue)}
@media (max-width: 480px) {
  .usage-summary > div:last-child { grid-column: 1 / -1; }
}
</style>

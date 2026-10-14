package com.agenticvideo.business.usage;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Projects all durable provider receipts; cached vectors are reuse, never another paid call. */
@Service
class ModelUsage {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    ModelUsage(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    Object measure(UUID project,int offset) {
        var items=new ArrayList<Map<String,Object>>();
        for(var row:db.queryForList("""
                SELECT c.id::text,c.model,c.status,c.usage_json,c.retry_number,c.request_id,c.provider_task_id,
                    a.duration_ms FROM model_calls c JOIN asset_analysis_revisions r ON r.id=c.analysis_id
                    JOIN assets a ON a.id=r.asset_id WHERE r.project_id=? AND c.status<>'READY'
                """,project))items.add(receipt(row,"BAILIAN","ASR",0,0,((Number)row.get("duration_ms")).doubleValue()/1000));
        for(var row:db.queryForList("""
                SELECT c.id::text,coalesce(c.response_json->>'model',c.request_json->>'model') AS model,
                    c.status,c.usage_json,c.call_number AS retry_number,c.response_json->>'id' AS request_id,
                    octet_length(c.request_json::text) AS input_estimate,coalesce((c.request_json->>'max_tokens')::bigint,2048) AS output_estimate
                FROM conversation_model_calls c JOIN tasks t ON t.id=c.task_id WHERE t.project_id=?
                """,project))items.add(receipt(row,"DEEPSEEK","AGENT",number(row,"input_estimate"),number(row,"output_estimate"),0));
        for(var row:db.queryForList("""
                SELECT c.id::text,c.model,c.status,c.usage_json,c.request_id FROM vision_model_calls c
                JOIN asset_analysis_revisions r ON r.id=c.analysis_id WHERE r.project_id=? AND c.status<>'READY'
                """,project))items.add(receipt(row,"BAILIAN","VISION",8192,1200,0));
        for(var row:db.queryForList("""
                SELECT g.id::text||':'||b.ordinal AS id,g.model,b.status,b.usage_json,b.request_id,
                    octet_length(b.units_json::text) AS input_estimate FROM embedding_batches b
                JOIN search_index_generations g ON g.id=b.generation_id WHERE g.project_id=?
                    AND b.status<>'READY' AND b.owner_attempt_id IS NOT NULL AND NOT coalesce((b.usage_json->>'reused')::boolean,false)
                """,project))items.add(receipt(row,"BAILIAN","INDEX_EMBEDDING",number(row,"input_estimate"),0,0));
        long reused=db.queryForObject("SELECT count(*) FROM embedding_batches b JOIN search_index_generations g ON g.id=b.generation_id WHERE g.project_id=? AND b.usage_json->>'reused'='true'",Long.class,project);
        for(var row:db.queryForList("SELECT id::text,model,status,usage_json,request_id,input_bytes AS input_estimate FROM query_model_calls WHERE project_id=?",project))
            items.add(receipt(row,"BAILIAN","QUERY_EMBEDDING",number(row,"input_estimate"),0,0));
        items.sort(Comparator.comparing(row->row.get("id").toString()));
        BigDecimal cost=BigDecimal.ZERO,audio=BigDecimal.ZERO;long tokens=0,uncertain=0,unpriced=0;
        for(var item:items) {
            if(item.get("estimatedCostCny") instanceof BigDecimal amount)cost=cost.add(amount);else unpriced++;
            var usage=(Map<?,?>)item.get("usage");tokens+=((Number)usage.get("inputTokens")).longValue()+((Number)usage.get("outputTokens")).longValue();
            audio=audio.add(new BigDecimal(usage.get("audioSeconds").toString()));
            if(Set.of("SUBMITTING","SUBMITTED","UNCERTAIN").contains(item.get("status")))uncertain++;
        }
        return Map.of("callCount",items.size(),"reusedBatchCount",reused,"uncertainCallCount",uncertain,"unpricedCallCount",unpriced,
                "estimatedCostCny",cost,"tokens",tokens,"audioSeconds",audio,"items",items.subList(Math.min(offset,items.size()),Math.min(offset+100,items.size())),
                "nextOffset",offset+100<items.size()?offset+100:-1);
    }
    private Map<String,Object> receipt(Map<String,Object> row,String provider,String purpose,long inputEstimate,long outputEstimate,double secondsEstimate) {
        JsonNode actual=parse(row.get("usage_json"));boolean speech="ASR".equals(purpose),embedding=purpose.contains("EMBEDDING");
        JsonNode input=actual.has("input_tokens")?actual.path("input_tokens"):actual.path("prompt_tokens");
        if(embedding)input=actual.path("total_tokens");
        JsonNode output=actual.has("output_tokens")?actual.path("output_tokens"):actual.path("completion_tokens");
        boolean reported=speech?actual.path("seconds").isNumber():input.isNumber()&&(embedding||output.isNumber());
        long in=reported&&!speech?input.asLong():inputEstimate,out=reported&&!speech&&!embedding?output.asLong():outputEstimate;
        double seconds=speech?(reported?actual.path("seconds").asDouble():secondsEstimate):0;
        long cached=reported?actual.path("prompt_cache_hit_tokens").asLong(actual.path("prompt_tokens_details").path("cached_tokens").asLong()):0;
        var item=new LinkedHashMap<String,Object>();
        item.put("id",row.get("id"));item.put("provider",provider);item.put("model",row.get("model"));item.put("purpose",purpose);item.put("status",row.get("status"));
        item.put("retryNumber",row.getOrDefault("retry_number",0));item.put("requestId",row.get("request_id"));item.put("providerTaskId",row.get("provider_task_id"));
        item.put("usageBasis",reported?"REPORTED":"ESTIMATED");item.put("reportedUsage",actual);
        item.put("usage",Map.of("inputTokens",in,"outputTokens",out,"audioSeconds",seconds,"cachedInputTokens",Math.min(cached,in)));
        item.put("estimatedCostCny",ModelPricing.estimate(Objects.toString(row.get("model"),""),in,out,Math.min(cached,in),seconds));
        item.put("pricingRef",ModelPricing.REFERENCE);item.put("costBasis","ESTIMATED_LIST_PRICE");return item;
    }
    private long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    private JsonNode parse(Object value){try{return value==null?json.createObjectNode():json.readTree(value.toString());}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
}

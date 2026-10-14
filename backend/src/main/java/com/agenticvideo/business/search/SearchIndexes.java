package com.agenticvideo.business.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Durable batch recovery and authoritative publication; called within the task transaction. */
@Service
public class SearchIndexes {
    @org.springframework.beans.factory.annotation.Value("${analysis.execution-enabled:false}")
    private boolean analysisEnabled;
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final SearchVectors vectors;
    public SearchIndexes(JdbcTemplate database,ObjectMapper json,SearchVectors vectors) {this.database=database;this.json=json;this.vectors=vectors;}
    public Object status(UUID asset) {
        var result=new LinkedHashMap<String,Object>();
        var heads=database.queryForList("""
                SELECT g.id,g.model,g.dimension,g.collection_name AS "collection",g.status,jsonb_array_length(g.units_json) AS \"unitCount\",g.published_at AS \"publishedAt\"
                FROM search_index_heads sh JOIN search_index_generations g ON g.id=sh.generation_id
                JOIN assets a ON a.id=sh.asset_id AND a.validation_status='VALID'
                JOIN asset_analysis_heads h ON h.asset_id=a.id AND h.analysis_id=g.analysis_id
                JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256
                WHERE sh.asset_id=?
                """,asset);
        result.put("publishedGeneration",heads.isEmpty()?null:heads.get(0));
        result.put("generations",database.queryForList("SELECT g.id,g.model,g.dimension,g.collection_name AS collection,g.status,g.task_id AS \"taskId\",t.status AS \"taskStatus\" FROM search_index_generations g JOIN tasks t ON t.id=g.task_id WHERE g.asset_id=? ORDER BY g.created_at DESC LIMIT 10",asset));
        return result;
    }
    public Object enqueue(UUID asset) {
        var prior=database.queryForList("SELECT * FROM search_index_generations WHERE asset_id=? ORDER BY created_at DESC LIMIT 1",asset);
        return enqueue(asset,prior.isEmpty()?SearchVectors.profile(null,null,null):vectors.profile(prior.get(0)));
    }
    public Object enqueue(UUID asset,SearchVectors.Profile profile) {
        if(!analysisEnabled)throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Java 模型分析执行尚在迁写，本阶段不接纳分析任务");
        var rows=database.queryForList("""
                SELECT a.project_id,h.analysis_id FROM assets a JOIN asset_analysis_heads h ON h.asset_id=a.id
                JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256
                WHERE a.id=? AND a.validation_status='VALID' FOR UPDATE OF a
                """,asset);
        if(rows.isEmpty())throw new ResponseStatusException(CONFLICT,"素材还没有可构建索引的有效分析");
        var source=rows.get(0);UUID project=(UUID)source.get("project_id"),analysis=(UUID)source.get("analysis_id");
        var live=database.queryForList("SELECT t.id,g.model,g.dimension,g.collection_name FROM tasks t JOIN search_index_generations g ON g.task_id=t.id WHERE type='INDEX_BUILD' AND input_json->>'analysisId'=? AND t.status IN ('PENDING','RUNNING','CANCEL_REQUESTED') ORDER BY t.created_at DESC LIMIT 1",analysis.toString());
        if(!live.isEmpty()){if(!profile.collection().equals(live.get(0).get("collection_name")))throw new ResponseStatusException(CONFLICT,"不同配置的索引任务正在执行，请先等待或取消");return Map.of("taskId",live.get(0).get("id"));}
        var units=database.queryForList("SELECT id,text,source_kind AS \"sourceKind\" FROM retrieval_units WHERE analysis_id=? AND project_id=? ORDER BY start_ms,id",analysis,project);
        if(units.isEmpty())throw new ResponseStatusException(CONFLICT,"还没有可索引的证据单元");
        UUID generation=UUID.randomUUID(),task=UUID.randomUUID(),event=UUID.randomUUID();
        var points=new ArrayList<Object>();
        for(var unit:units)points.add(Map.of("id",UUID.nameUUIDFromBytes((generation+":"+unit.get("id")).getBytes(StandardCharsets.UTF_8)),"unitId",unit.get("id"),"text",unit.get("text"),"sourceKind",unit.get("sourceKind")));
        var input=Map.of("assetId",asset,"analysisId",analysis,"generationId",generation,"operation","INDEX_BUILD");
        database.update("INSERT INTO tasks(id,project_id,type,input_json) VALUES (?,?,'INDEX_BUILD',?::jsonb)",task,project,encode(input));
        database.update("INSERT INTO search_index_generations(id,project_id,asset_id,analysis_id,task_id,model,dimension,profile_json,units_json,collection_name) VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",generation,project,asset,analysis,task,profile.model(),profile.dimension(),encode(profile.bm25()),encode(points),profile.collection());
        var saved=new HashMap<String,JsonNode>();var uncertain=new HashSet<String>();
        for(var previous:database.queryForList("SELECT b.*,g.id AS source_generation FROM embedding_batches b JOIN search_index_generations g ON g.id=b.generation_id WHERE g.analysis_id=? AND g.model=? AND g.dimension=? AND g.id<>? ORDER BY g.created_at",analysis,profile.model(),profile.dimension(),generation)) {
            var priorUnits=parse(previous.get("units_json"));
            for(int i=0;i<priorUnits.size();i++) {
                String reuseKey=priorUnits.get(i).path("unitId").asText()+":"+priorUnits.get(i).path("text").asText();
                if("SUCCEEDED".equals(previous.get("status")))saved.put(reuseKey,parse(previous.get("vectors_json")).get(i));
                else if("SUBMITTING".equals(previous.get("status"))||"UNCERTAIN".equals(previous.get("status")))uncertain.add(reuseKey);
            }
        }
        var reusedPoints=new ArrayList<Object>();var reusedVectors=new ArrayList<JsonNode>();var newPoints=new ArrayList<Object>();var blockedPoints=new ArrayList<Object>();
        for(var point:points) {
            var node=json.valueToTree(point);String reuseKey=node.path("unitId").asText()+":"+node.path("text").asText();
            if(saved.containsKey(reuseKey)){reusedPoints.add(point);reusedVectors.add(saved.get(reuseKey));}
            else if(uncertain.contains(reuseKey))blockedPoints.add(point);
            else newPoints.add(point);
        }
        int ordinal=0;
        for(int start=0;start<reusedPoints.size();start+=10,ordinal++)database.update("INSERT INTO embedding_batches(generation_id,ordinal,units_json,status,vectors_json,usage_json) VALUES (?,?,?::jsonb,'SUCCEEDED',?::jsonb,'{\"reused\":true}')",generation,ordinal,encode(reusedPoints.subList(start,Math.min(start+10,reusedPoints.size()))),encode(reusedVectors.subList(start,Math.min(start+10,reusedVectors.size()))));
        for(var group:List.of(blockedPoints,newPoints))for(int start=0;start<group.size();start+=10,ordinal++)database.update("INSERT INTO embedding_batches(generation_id,ordinal,units_json,status) VALUES (?,?,?::jsonb,?)",generation,ordinal,encode(group.subList(start,Math.min(start+10,group.size()))),group==blockedPoints?"UNCERTAIN":"READY");
        database.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status,task_id) VALUES (?,?,'EMBEDDING',?,?) ON CONFLICT(analysis_id,stage_key) DO UPDATE SET status=EXCLUDED.status,task_id=EXCLUDED.task_id,error_json=NULL,updated_at=now()",asset,analysis,newPoints.isEmpty()&&blockedPoints.isEmpty()?"SUCCEEDED":"PENDING",task);
        database.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status,task_id) VALUES (?,?,'INDEX','NOT_STARTED',?) ON CONFLICT(analysis_id,stage_key) DO UPDATE SET status='NOT_STARTED',task_id=EXCLUDED.task_id,error_json=NULL,updated_at=now()",asset,analysis,task);
        database.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'INDEX_BUILD',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','INDEX_BUILD','projectId',?::text,'occurredAt',now()))",event,task,event,task,project);
        return Map.of("taskId",task,"generationId",generation);
    }
    public Object batch(Map<String,Object> task,UUID attempt,String action,JsonNode data) {
        var generation=database.queryForMap("SELECT * FROM search_index_generations WHERE task_id=?",task.get("id"));
        UUID id=(UUID)generation.get("id");
        if("LOOKUP".equals(action)) {
            var batches=new ArrayList<Object>();
            for(var row:database.queryForList("SELECT * FROM embedding_batches WHERE generation_id=? ORDER BY ordinal",id)) {
                var batch=new LinkedHashMap<String,Object>();batch.put("ordinal",row.get("ordinal"));batch.put("status",row.get("status"));batch.put("units",parse(row.get("units_json")));
                if(row.get("response_json")!=null)batch.put("response",parse(row.get("response_json")));
                if(row.get("vectors_json")!=null)batch.put("vectors",parse(row.get("vectors_json")));
                batches.add(batch);
            }
            return Map.of("generationId",id,"projectId",generation.get("project_id"),"assetId",generation.get("asset_id"),"analysisId",generation.get("analysis_id"),"model",generation.get("model"),"dimension",generation.get("dimension"),"collection",generation.get("collection_name"),"bm25",parse(generation.get("profile_json")),"batches",batches);
        }
        requireCurrent(generation,true);
        if("INDEXING".equals(action)) {
            if(database.queryForObject("SELECT count(*) FROM embedding_batches WHERE generation_id=? AND status<>'SUCCEEDED'",Integer.class,id)!=0)throw new ResponseStatusException(CONFLICT,"Embedding 尚未完成，不能开始索引写入");
            database.update("UPDATE analysis_stages SET status='SUCCEEDED',error_json=NULL,updated_at=now() WHERE task_id=? AND stage_key='EMBEDDING'",task.get("id"));
            database.update("UPDATE analysis_stages SET status='RUNNING',updated_at=now() WHERE task_id=? AND stage_key='INDEX'",task.get("id"));
            return Map.of("status","RUNNING");
        }
        if(!data.path("ordinal").isIntegralNumber())throw new ResponseStatusException(BAD_REQUEST,"批次编号无效");
        var rows=database.queryForList("SELECT * FROM embedding_batches WHERE generation_id=? AND ordinal=? FOR UPDATE",id,data.path("ordinal").asInt());
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"批次不存在");
        var row=rows.get(0);
        if("RESUME_RESPONSE".equals(action) && row.get("response_json")!=null) {
            database.update("UPDATE embedding_batches SET status='SUBMITTING',owner_attempt_id=? WHERE generation_id=? AND ordinal=?",attempt,id,row.get("ordinal"));
            return Map.of("status","SUBMITTING");
        }
        if("RESPONSE".equals(action)) {
            if(!"SUBMITTING".equals(row.get("status"))||!attempt.equals(row.get("owner_attempt_id")))throw new ResponseStatusException(CONFLICT,"Embedding 响应没有保存资格");
            var response=data.path("response");
            database.update("UPDATE embedding_batches SET response_json=?::jsonb,request_id=?,usage_json=?::jsonb,updated_at=now() WHERE generation_id=? AND ordinal=?",response.toString(),response.path("request_id").asText(),response.path("usage").toString(),id,row.get("ordinal"));
            return Map.of("status","SUBMITTING");
        }
        if("PREPARE".equals(action)) {
            if(!"READY".equals(row.get("status")))throw new ResponseStatusException(CONFLICT,"Embedding 批次已调用或提交结果不明确，不能自动重复付费");
            database.update("UPDATE analysis_stages SET status='RUNNING',updated_at=now() WHERE task_id=? AND stage_key='EMBEDDING'",task.get("id"));
            database.update("UPDATE embedding_batches SET status='SUBMITTING',owner_attempt_id=?,updated_at=now() WHERE generation_id=? AND ordinal=?",attempt,id,row.get("ordinal"));
            return Map.of("canSubmit",true);
        }
        if("SUCCEEDED".equals(action)) {
            if("SUCCEEDED".equals(row.get("status"))&&attempt.equals(row.get("owner_attempt_id"))) {
                boolean same=Boolean.TRUE.equals(database.queryForObject("SELECT vectors_json=?::jsonb AND request_id=? AND usage_json=?::jsonb FROM embedding_batches WHERE generation_id=? AND ordinal=?",Boolean.class,data.path("vectors").toString(),data.path("requestId").asText(),data.path("usage").toString(),id,row.get("ordinal")));
                if(same)return Map.of("status","SUCCEEDED");
                throw new ResponseStatusException(CONFLICT,"重复 Embedding 回报内容不符");
            }
            if(!"SUBMITTING".equals(row.get("status"))||!attempt.equals(row.get("owner_attempt_id")))throw new ResponseStatusException(CONFLICT,"Embedding 批次没有结果发布资格");
            if(data.path("requestId").asText().isBlank()||!data.path("usage").isObject())throw new ResponseStatusException(CONFLICT,"Embedding 缺少调用身份或实际用量");
            var embedded=data.path("vectors");var units=parse(row.get("units_json"));
            if(!embedded.isArray()||embedded.size()!=units.size())throw new ResponseStatusException(CONFLICT,"Embedding 批次数量不符");
            for(var vector:embedded)vectors.requireVector(vector,((Number)generation.get("dimension")).intValue());
            database.update("UPDATE embedding_batches SET status='SUCCEEDED',vectors_json=?::jsonb,request_id=?,usage_json=?::jsonb,updated_at=now() WHERE generation_id=? AND ordinal=?",embedded.toString(),data.path("requestId").asText(),data.path("usage").toString(),id,row.get("ordinal"));
            if(database.queryForObject("SELECT count(*) FROM embedding_batches WHERE generation_id=? AND status<>'SUCCEEDED'",Integer.class,id)==0)
                database.update("UPDATE analysis_stages SET status='SUCCEEDED',error_json=NULL,updated_at=now() WHERE task_id=? AND stage_key='EMBEDDING'",task.get("id"));
            return Map.of("status","SUCCEEDED");
        }
        throw new ResponseStatusException(BAD_REQUEST,"不支持的索引批次操作");
    }
    public record Verified(UUID taskId,UUID generationId) {}
    public void includeNewObservations(UUID taskId) {
        var generation=database.queryForMap("SELECT asset_id,analysis_id,units_json FROM search_index_generations WHERE task_id=?",taskId);
        boolean changed=Boolean.TRUE.equals(database.queryForObject("SELECT EXISTS(SELECT 1 FROM retrieval_units WHERE analysis_id=? AND id NOT IN (SELECT (value->>'unitId')::uuid FROM jsonb_array_elements(?::jsonb)))",Boolean.class,generation.get("analysis_id"),generation.get("units_json").toString()));
        if(changed)enqueue((UUID)generation.get("asset_id"));
    }
    public Verified verify(Map<String,Object> task) {
        var generation=database.queryForMap("SELECT * FROM search_index_generations WHERE task_id=?",task.get("id"));
        UUID id=(UUID)generation.get("id"),asset=(UUID)generation.get("asset_id");
        requireCurrent(generation,false);
        var units=parse(generation.get("units_json"));
        long unfinished=database.queryForObject("SELECT count(*) FROM embedding_batches WHERE generation_id=? AND status<>'SUCCEEDED'",Long.class,id);
        if(unfinished!=0)throw new ResponseStatusException(CONFLICT,"Embedding 批次尚未全部验证");
        Object filter=SearchVectors.scope((UUID)generation.get("project_id"),List.of(id.toString()));
        var count=vectors.request("POST","/collections/"+generation.get("collection_name")+"/points/count",Map.of("filter",filter,"exact",true));
        if(count.path("result").path("count").asInt(-1)!=units.size())throw new ResponseStatusException(CONFLICT,"索引单元数量不符");
        var ids=new ArrayList<String>();for(var unit:units)ids.add(unit.path("id").asText());
        var stored=vectors.request("POST","/collections/"+generation.get("collection_name")+"/points",Map.of("ids",ids,"with_payload",true,"with_vector",true)).path("result");
        if(stored.size()!=units.size())throw new ResponseStatusException(CONFLICT,"索引单元身份不完整");
        var expected=new HashMap<String,JsonNode>();for(var unit:units)expected.put(unit.path("id").asText(),unit);
        var expectedVectors=new HashMap<String,JsonNode>();
        for(var batch:database.queryForList("SELECT units_json,vectors_json FROM embedding_batches WHERE generation_id=?",id)) {
            var batchUnits=parse(batch.get("units_json"));var batchVectors=parse(batch.get("vectors_json"));
            for(int i=0;i<batchUnits.size();i++)expectedVectors.put(batchUnits.get(i).path("id").asText(),batchVectors.get(i));
        }
        for(var point:stored) {
            var unit=expected.get(point.path("id").asText());var payload=point.path("payload");
            if(unit==null||!payload.path("unitId").asText().equals(unit.path("unitId").asText())||!payload.path("generationId").asText().equals(id.toString())||!payload.path("projectId").asText().equals(generation.get("project_id").toString()))throw new ResponseStatusException(CONFLICT,"索引单元归属不符");
            vectors.requireVector(point.path("vector").path("dense"),((Number)generation.get("dimension")).intValue());
            // Cosine collections store normalized float32 vectors; compare directions after normalization.
            var saved=expectedVectors.get(point.path("id").asText());var storedVector=point.path("vector").path("dense");
            double length=0;for(var value:saved)length+=value.asDouble()*value.asDouble();length=Math.sqrt(length);
            if(length==0)throw new ResponseStatusException(CONFLICT,"Embedding 向量不能为零");
            for(int i=0;i<((Number)generation.get("dimension")).intValue();i++)if(Math.abs(saved.get(i).asDouble()/length-storedVector.get(i).asDouble())>0.00001)throw new ResponseStatusException(CONFLICT,"索引向量与已保存批次不一致");
            if(!point.path("vector").path("bm25").path("indices").isArray())throw new ResponseStatusException(CONFLICT,"原生 BM25 索引缺失");
        }
        if(vectors.query(stored.get(0).path("vector").path("dense"),"dense",filter,filter,1,generation.get("collection_name").toString()).isEmpty()||vectors.query(vectors.document(units.get(0).path("text").asText(),vectors.profile(generation)),"bm25",filter,filter,1,generation.get("collection_name").toString()).isEmpty())throw new ResponseStatusException(CONFLICT,"新代次尚不可查询");
        return new Verified((UUID)task.get("id"),id);
    }
    public Object publish(Map<String,Object> task,Verified verified) {
        var generation=database.queryForMap("SELECT * FROM search_index_generations WHERE task_id=?",task.get("id"));
        UUID id=(UUID)generation.get("id"),asset=(UUID)generation.get("asset_id");
        if(verified==null||!verified.taskId().equals(task.get("id"))||!verified.generationId().equals(id))throw new ResponseStatusException(CONFLICT,"索引核对身份不符");
        requireCurrent(generation,true);
        boolean live=Boolean.TRUE.equals(database.queryForObject("SELECT EXISTS(SELECT 1 FROM tasks t JOIN task_attempts a ON a.id=t.current_attempt_id WHERE t.id=? AND t.status='RUNNING' AND (a.local_execution OR a.lease_expires_at>clock_timestamp()))",Boolean.class,task.get("id")));
        if(!live)throw new ResponseStatusException(CONFLICT,"索引发布尝试已经失去有效租约");
        database.update("UPDATE search_index_generations SET status='PUBLISHED',published_at=now() WHERE id=?",id);
        database.update("INSERT INTO search_index_heads(asset_id,generation_id) VALUES (?,?) ON CONFLICT(asset_id) DO UPDATE SET generation_id=EXCLUDED.generation_id",asset,id);
        database.update("UPDATE analysis_stages SET status='SUCCEEDED',error_json=NULL,updated_at=now() WHERE task_id=? AND stage_key='INDEX'",task.get("id"));
        return Map.of("status","SUCCEEDED","assetId",asset,"generationId",id,"unitCount",parse(generation.get("units_json")).size());
    }
    private void requireCurrent(Map<String,Object> generation,boolean lock) {
        var current=database.queryForList("SELECT a.id FROM assets a JOIN asset_analysis_heads h ON h.asset_id=a.id JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256 WHERE a.id=? AND a.validation_status='VALID' AND h.analysis_id=?"+(lock?" FOR UPDATE OF a,h":""),generation.get("asset_id"),generation.get("analysis_id"));
        if(current.isEmpty())throw new ResponseStatusException(CONFLICT,"分析代次已经失效，不能发布索引");
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    private JsonNode parse(Object value) {try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}

package com.agenticvideo.business.analysis;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.agenticvideo.business.search.SearchIndexes;
import com.agenticvideo.business.storage.StorageCapacity;
import static org.springframework.http.HttpStatus.*;

/** Frame occurrences and paid image observations are distinct: identical pixels can recur later. */
@Service
public class VisualAnalysis {
    public static final String MODEL="qwen3-vl-plus";
    private final JdbcTemplate db;private final ObjectMapper json;private final SearchIndexes indexes;private final StorageCapacity capacity;
    public VisualAnalysis(JdbcTemplate db,ObjectMapper json,SearchIndexes indexes,StorageCapacity capacity){this.db=db;this.json=json;this.indexes=indexes;this.capacity=capacity;}
    public void initialize(UUID asset,UUID analysis,JsonNode media){
        if(media.path("representativeFrames").path("frames").isArray()){
            acceptFrames(asset,analysis,media.path("representativeFrames"));
        }else if(media.has("visionFailure"))db.update("UPDATE analysis_stages SET status='FAILED',error_json=?::jsonb WHERE analysis_id=? AND stage_key='VISION'",media.path("visionFailure").toString(),analysis);
    }
    public void acceptFrames(UUID asset,UUID analysis,JsonNode result){
        var source=current(asset);if(!analysis.equals(source.get("analysis_id")))throw conflict("视觉分析版本已失效");
        JsonNode frames=result.path("frames"),profile=result.path("profile");
        if(!frames.isArray()||frames.isEmpty()||frames.size()>25||!"SPARSE_FRAMES".equals(profile.path("kind").asText())||profile.path("complete").asBoolean(true))throw conflict("代表帧覆盖记录无效");
        long last=-1,duration=((Number)source.get("duration_ms")).longValue();
        for(var frame:frames){
            long time=frame.path("observedAtMs").asLong(-1),size=frame.path("sizeBytes").asLong();
            if(time<=last||time>=duration||size<1||size>1048576||!frame.path("sha256").asText().matches("[a-f0-9]{64}")||!frame.path("objectKey").asText().startsWith("derived/frames/"+asset+"/"))throw conflict("代表帧身份或时间不符");
            db.update("INSERT INTO vision_frames(id,analysis_id,ordinal,observed_at_ms,source_pts,object_key,sha256,size_bytes) VALUES (?,?,?,?,?,?,?,?)",UUID.randomUUID(),analysis,frame.path("ordinal").asInt(),time,frame.path("sourcePts").asText(),frame.path("objectKey").asText(),frame.path("sha256").asText(),size);last=time;
        }
        db.update("UPDATE asset_analysis_revisions SET vision_profile_json=?::jsonb WHERE id=?",profile.toString(),analysis);
    }
    @Transactional
    public Object enqueue(UUID asset){
        var source=current(asset);UUID analysis=(UUID)source.get("analysis_id"),project=(UUID)source.get("project_id");
        var live=db.queryForList("SELECT id FROM tasks WHERE type IN ('VISION_PREPARE','VISION_ANALYZE') AND input_json->>'analysisId'=? AND status IN ('PENDING','RUNNING','CANCEL_REQUESTED') ORDER BY created_at DESC LIMIT 1",analysis.toString());
        if(!live.isEmpty())return Map.of("taskId",live.get(0).get("id"));
        if("SUCCEEDED".equals(db.queryForObject("SELECT status FROM analysis_stages WHERE analysis_id=? AND stage_key='VISION'",String.class,analysis)))return Map.of("status","SUCCEEDED","assetId",asset);
        boolean prepare=db.queryForObject("SELECT count(*) FROM vision_frames WHERE analysis_id=?",Integer.class,analysis)==0;
        UUID task=UUID.randomUUID(),event=UUID.randomUUID();String type=prepare?"VISION_PREPARE":"VISION_ANALYZE";
        var input=json.createObjectNode().put("assetId",asset.toString()).put("analysisId",analysis.toString()).put("operation",type).put("model",MODEL);
        long budget=0;
        if(prepare){
            budget=Math.addExact(((Number)source.get("byte_size")).longValue(),33554432);capacity.require(budget);
            input.put("objectKey",source.get("object_key").toString()).put("sizeBytes",((Number)source.get("byte_size")).longValue()).put("sha256",source.get("sha256").toString()).put("durationMs",((Number)source.get("duration_ms")).longValue());
            input.set("timeMapping",parse(source.get("time_mapping_json")));
        }
        db.update("INSERT INTO tasks(id,project_id,type,input_json,disk_budget_bytes) VALUES (?,?,?,?::jsonb,?)",task,project,type,input.toString(),budget);
        db.update("UPDATE analysis_stages SET status='PENDING',task_id=?,error_json=NULL,updated_at=now() WHERE analysis_id=? AND stage_key='VISION'",task,analysis);
        db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,?,jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType',?::text,'projectId',?::text,'occurredAt',now()))",event,task,type,event,task,type,project);
        return Map.of("taskId",task,"assetId",asset);
    }
    private Map<String,Object> current(UUID asset){
        var rows=db.queryForList("SELECT a.*,h.analysis_id FROM assets a JOIN asset_analysis_heads h ON h.asset_id=a.id JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256 WHERE a.id=? AND a.validation_status='VALID' FOR UPDATE OF a",asset);
        if(rows.isEmpty())throw conflict("素材没有有效的分析版本");return rows.get(0);
    }
    public Object provider(Map<String,Object> task,UUID attempt,String action,JsonNode data){
        UUID analysis=UUID.fromString(parse(task.get("input_json")).path("analysisId").asText());
        if("LOOKUP".equals(action)){
            for(var frame:db.queryForList("SELECT DISTINCT sha256 FROM vision_frames WHERE analysis_id=?",analysis))db.update("INSERT INTO vision_model_calls(id,analysis_id,task_id,frame_sha256,model,parameters_json,status) VALUES (?,?,?,?,?,'{\"prompt\":\"image-observation-v1\",\"enableThinking\":false}'::jsonb,'READY') ON CONFLICT(analysis_id,frame_sha256,model) DO NOTHING",UUID.randomUUID(),analysis,task.get("id"),frame.get("sha256"),MODEL);
            var frames=new ArrayList<Object>();
            for(var row:db.queryForList("SELECT f.*,c.id AS call_id,c.status,c.result_json FROM vision_frames f JOIN vision_model_calls c ON c.analysis_id=f.analysis_id AND c.frame_sha256=f.sha256 AND c.model=? WHERE f.analysis_id=? ORDER BY f.ordinal",MODEL,analysis)){
                var item=new LinkedHashMap<String,Object>();item.put("frameId",row.get("id"));item.put("callId",row.get("call_id"));item.put("objectKey",row.get("object_key"));item.put("sha256",row.get("sha256"));item.put("sizeBytes",row.get("size_bytes"));item.put("observedAtMs",row.get("observed_at_ms"));item.put("status",row.get("status"));if(row.get("result_json")!=null)item.put("observation",parse(row.get("result_json")));frames.add(item);
            }
            return Map.of("model",MODEL,"frames",frames);
        }
        UUID call=UUID.fromString(data.path("callId").asText());var rows=db.queryForList("SELECT * FROM vision_model_calls WHERE id=? AND analysis_id=? FOR UPDATE",call,analysis);
        if(rows.isEmpty())throw conflict("视觉模型调用身份不符");var saved=rows.get(0);
        if("PREPARE".equals(action)){
            if(!"READY".equals(saved.get("status")))throw conflict("视觉调用已提交或结果不明确，不能自动重复付费");
            db.update("UPDATE vision_model_calls SET task_id=?,owner_attempt_id=?,status='SUBMITTING',updated_at=now() WHERE id=?",task.get("id"),attempt,call);return Map.of("canSubmit",true);
        }
        if(!attempt.equals(saved.get("owner_attempt_id"))||!task.get("id").equals(saved.get("task_id")))throw conflict("视觉调用不属于当前尝试");
        if("SUCCEEDED".equals(action)){
            var observation=data.path("observation");
            if(!observation.path("description").isTextual()||observation.path("description").asText().length()>4000||!observation.path("visibleText").isArray()||observation.path("visibleText").size()>30||!data.path("usage").isObject()||data.path("requestId").asText().isBlank())throw conflict("视觉观察缺少内容、来源或实际用量");
            for(var text:observation.path("visibleText"))if(!text.isTextual()||text.asText().isBlank()||text.asText().length()>1000)throw conflict("可见文字观察无效");
            if("SUCCEEDED".equals(saved.get("status"))){if(parse(saved.get("result_json")).equals(observation)&&Objects.equals(saved.get("request_id"),data.path("requestId").asText())&&parse(saved.get("usage_json")).equals(data.path("usage")))return Map.of("status","SUCCEEDED");throw conflict("重复视觉回报内容不符");}
            if(!"SUBMITTING".equals(saved.get("status")))throw conflict("视觉调用没有完成资格");
            db.update("UPDATE vision_model_calls SET status='SUCCEEDED',result_json=?::jsonb,usage_json=?::jsonb,request_id=?,updated_at=now() WHERE id=?",observation.toString(),data.path("usage").toString(),data.path("requestId").asText(),call);
        }else if("UNCERTAIN".equals(action))db.update("UPDATE vision_model_calls SET status='UNCERTAIN',updated_at=now() WHERE id=? AND status='SUBMITTING'",call);
        else throw conflict("视觉模型调用动作不支持");
        return Map.of("status",db.queryForObject("SELECT status FROM vision_model_calls WHERE id=?",String.class,call));
    }
    public void publish(Map<String,Object> task,JsonNode input){
        UUID asset=UUID.fromString(input.path("assetId").asText()),analysis=UUID.fromString(input.path("analysisId").asText());
        if(!analysis.equals(current(asset).get("analysis_id")))throw conflict("视觉分析版本已失效");
        var frames=db.queryForList("SELECT f.*,c.id AS call_id,c.result_json FROM vision_frames f JOIN vision_model_calls c ON c.analysis_id=f.analysis_id AND c.frame_sha256=f.sha256 AND c.model=? AND c.status='SUCCEEDED' WHERE f.analysis_id=? ORDER BY f.ordinal",MODEL,analysis);
        if(frames.isEmpty()||frames.size()!=db.queryForObject("SELECT count(*) FROM vision_frames WHERE analysis_id=?",Integer.class,analysis))throw conflict("视觉观察尚未全部完成");
        int visualOrdinal=0,textOrdinal=0;
        for(var frame:frames){var observation=parse(frame.get("result_json"));String description=observation.path("description").asText();if(!description.isBlank())save(task,asset,analysis,frame,"VISUAL",visualOrdinal++,description,observation);for(var text:observation.path("visibleText"))save(task,asset,analysis,frame,"VISIBLE_TEXT",textOrdinal++,text.asText(),observation);}
        db.update("UPDATE analysis_stages SET status='SUCCEEDED',error_json=NULL,updated_at=now() WHERE analysis_id=? AND stage_key='VISION'",analysis);
        if(db.queryForObject("SELECT count(*) FROM retrieval_units WHERE analysis_id=?",Integer.class,analysis)>0)indexes.enqueue(asset);
    }
    private void save(Map<String,Object> task,UUID asset,UUID analysis,Map<String,Object> frame,String kind,int ordinal,String text,JsonNode observation){
        UUID evidence=UUID.randomUUID(),unit=UUID.randomUUID();Object time=frame.get("observed_at_ms");
        db.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,observation_json,frame_id,vision_call_id) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",evidence,task.get("project_id"),asset,analysis,kind,ordinal,text,time,time,observation.toString(),frame.get("id"),frame.get("call_id"));
        db.update("INSERT INTO retrieval_units(id,project_id,asset_id,analysis_id,source_kind,text,start_ms,end_ms,rules_hash) VALUES (?,?,?,?,?,?,?,?, 'frame-observation-v1')",unit,task.get("project_id"),asset,analysis,kind,text,time,time);
        db.update("INSERT INTO unit_evidence_refs(unit_id,evidence_id,ordinal) VALUES (?,?,0)",unit,evidence);
    }
    public Object view(UUID asset){
        var revisions=db.queryForList("SELECT r.id,r.vision_profile_json FROM asset_analysis_heads h JOIN asset_analysis_revisions r ON r.id=h.analysis_id WHERE h.asset_id=?",asset);
        if(revisions.isEmpty())return Map.of("frames",List.of(),"observations",List.of(),"providerCalls",List.of(),"coverage",Map.of("kind","SPARSE_FRAMES","complete",false));
        var revision=revisions.get(0);
        Object analysis=revision.get("id");
        var frames=db.queryForList("SELECT id,ordinal,observed_at_ms AS \"observedAtMs\",source_pts AS \"sourcePts\",sha256 FROM vision_frames WHERE analysis_id=? ORDER BY ordinal",analysis);
        var calls=new ArrayList<Object>();for(var row:db.queryForList("SELECT id,model,status,request_id,usage_json FROM vision_model_calls WHERE analysis_id=? ORDER BY id",analysis)){var item=new LinkedHashMap<String,Object>();item.put("id",row.get("id"));item.put("model",row.get("model"));item.put("status",row.get("status"));item.put("requestId",row.get("request_id"));if(row.get("usage_json")!=null)item.put("usage",parse(row.get("usage_json")));calls.add(item);}
        var observations=db.queryForList("SELECT id,source_kind AS \"sourceKind\",text,start_ms AS \"observedAtMs\",frame_id AS \"frameId\",vision_call_id AS \"modelCallId\" FROM evidence_segments WHERE analysis_id=? AND source_kind IN ('VISUAL','VISIBLE_TEXT') ORDER BY start_ms,source_kind,ordinal",analysis);
        return Map.of("frames",frames,"observations",observations,"providerCalls",calls,"coverage",revision.get("vision_profile_json")==null?Map.of("kind","SPARSE_FRAMES","complete",false):parse(revision.get("vision_profile_json")));
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    private ResponseStatusException conflict(String text){return new ResponseStatusException(CONFLICT,text);}
}

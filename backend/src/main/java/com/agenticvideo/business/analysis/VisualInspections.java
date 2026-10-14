package com.agenticvideo.business.analysis;

import com.agenticvideo.business.storage.StorageCapacity;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Each inspection owns durable limits; worker restarts never reset its allowance. */
@Service
public class VisualInspections {
    private static final Map<String,Object> INITIAL_BUDGET=Map.of("maxFrames",4,"maxCalls",4,"maxSteps",8,"maxSeconds",180,"maxInputBytes",4194304);
    private final JdbcTemplate db;private final ObjectMapper json;private final StorageCapacity capacity;
    public VisualInspections(JdbcTemplate db,ObjectMapper json,StorageCapacity capacity){this.db=db;this.json=json;this.capacity=capacity;}
    @Transactional
    public Object create(UUID project,UUID asset,String query,String kind,Long requestedStart,Long requestedEnd,String key){
        return createWithBudget(project,asset,query,kind,requestedStart,requestedEnd,key,INITIAL_BUDGET);
    }
    private Object createWithBudget(UUID project,UUID asset,String query,String kind,Long requestedStart,Long requestedEnd,String key,Map<String,Object> budget){
        var sources=db.queryForList("SELECT a.*,h.analysis_id FROM assets a JOIN asset_analysis_heads h ON h.asset_id=a.id JOIN asset_analysis_revisions r ON r.id=h.analysis_id AND r.source_sha256=a.sha256 WHERE a.id=? AND a.project_id=? AND a.validation_status='VALID' FOR UPDATE OF a",asset,project);
        if(sources.isEmpty())throw new ResponseStatusException(NOT_FOUND,"素材没有可补查的有效分析");var source=sources.get(0);
        long start=requestedStart==null?0:requestedStart,end=requestedEnd==null?((Number)source.get("duration_ms")).longValue():requestedEnd;
        if(start<0||end<=start||end>((Number)source.get("duration_ms")).longValue())throw new ResponseStatusException(BAD_REQUEST,"补查范围超出原视频");
        var prior=db.queryForList("SELECT * FROM visual_inspections WHERE project_id=? AND request_key=?",project,key);
        if(!prior.isEmpty()){
            var saved=prior.get(0);if(!asset.equals(saved.get("asset_id"))||!query.equals(saved.get("query"))||!kind.equals(saved.get("source_kind"))||start!=((Number)saved.get("start_ms")).longValue()||end!=((Number)saved.get("end_ms")).longValue())throw new ResponseStatusException(CONFLICT,"补查幂等键内容不同");return view((UUID)saved.get("id"));
        }
        var active=db.queryForList("SELECT id FROM visual_inspections WHERE analysis_id=? AND query=? AND source_kind=? AND start_ms=? AND end_ms=? AND status IN ('PENDING','RUNNING') AND budget_json=?::jsonb",source.get("analysis_id"),query,kind,start,end,encode(budget));
        if(!active.isEmpty())return view((UUID)active.get(0).get("id"));
        UUID run=UUID.randomUUID();String sampling=requestedStart==null&&requestedEnd==null?"BOUNDED_COVERAGE_FRAMES":"BOUNDED_DENSE_FRAMES";
        db.update("INSERT INTO visual_inspections(id,project_id,asset_id,analysis_id,request_key,query,source_kind,start_ms,end_ms,sampling_kind,budget_json) VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)",run,project,asset,source.get("analysis_id"),key,query,kind,start,end,sampling,encode(budget));
        long reserved=Math.addExact(((Number)source.get("byte_size")).longValue(),33554432);capacity.require(reserved);
        var input=json.createObjectNode().put("inspectionId",run.toString()).put("assetId",asset.toString()).put("analysisId",source.get("analysis_id").toString()).put("operation","INSPECTION_PREPARE").put("objectKey",source.get("object_key").toString()).put("sizeBytes",((Number)source.get("byte_size")).longValue()).put("sha256",source.get("sha256").toString()).put("durationMs",((Number)source.get("duration_ms")).longValue()).put("startMs",start).put("endMs",end).put("maxFrames",((Number)budget.get("maxFrames")).intValue()).put("samplingKind",sampling);
        input.set("timeMapping",parse(source.get("time_mapping_json")));enqueue(run,project,"INSPECTION_PREPARE",input,reserved);
        return view(run);
    }
    private void enqueue(UUID run,UUID project,String type,JsonNode input,long reserved){
        UUID task=UUID.randomUUID(),event=UUID.randomUUID();
        db.update("INSERT INTO tasks(id,project_id,type,input_json,disk_budget_bytes) VALUES (?,?,?,?::jsonb,?)",task,project,type,input.toString(),reserved);
        db.update("UPDATE visual_inspections SET current_task_id=? WHERE id=?",task,run);
        db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,?,jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType',?::text,'projectId',?::text,'occurredAt',now()))",event,task,type,event,task,type,project);
    }
    public boolean admit(Map<String,Object> task){
        JsonNode input=parse(task.get("input_json"));if(!input.has("inspectionId"))return true;
        var run=locked(input);var budget=parse(run.get("budget_json"));
        if(!available(run)||((Number)run.get("steps_used")).intValue()>=budget.path("maxSteps").asInt()){stop(run);db.update("UPDATE tasks SET status='FAILED' WHERE id=?",task.get("id"));return false;}
        db.update("UPDATE visual_inspections SET status='RUNNING',steps_used=steps_used+1,deadline_at=COALESCE(deadline_at,clock_timestamp()+make_interval(secs=>?)) WHERE id=?",budget.path("maxSeconds").asInt(),run.get("id"));return true;
    }
    public String deadline(JsonNode input){return db.queryForObject("SELECT deadline_at FROM visual_inspections WHERE id=?",java.sql.Timestamp.class,UUID.fromString(input.path("inspectionId").asText())).toInstant().toString();}
    public boolean expired(Map<String,Object> task){var input=parse(task.get("input_json"));if(!input.has("inspectionId"))return false;return Boolean.TRUE.equals(db.queryForObject("SELECT deadline_at<=clock_timestamp() FROM visual_inspections WHERE id=?",Boolean.class,UUID.fromString(input.path("inspectionId").asText())));}
    public void requireCurrent(Map<String,Object> task){
        var run=locked(parse(task.get("input_json")));if(!task.get("id").equals(run.get("current_task_id"))||!available(run))throw new ResponseStatusException(CONFLICT,"补查任务或预算已经失效");
    }
    public void framesPrepared(Map<String,Object> task,JsonNode manifest){
        var input=parse(task.get("input_json"));var run=locked(input);var budget=parse(run.get("budget_json"));
        int count=manifest.path("frames").size();if(count<1||count>budget.path("maxFrames").asInt())throw new ResponseStatusException(CONFLICT,"补查帧数超过固定预算");
        db.update("UPDATE visual_inspections SET frames_used=? WHERE id=?",count,run.get("id"));
        var next=json.createObjectNode().put("inspectionId",input.path("inspectionId").asText()).put("assetId",input.path("assetId").asText()).put("analysisId",input.path("analysisId").asText()).put("operation","INSPECTION_ANALYZE").put("model",VisualAnalysis.MODEL);
        enqueue((UUID)run.get("id"),(UUID)run.get("project_id"),"INSPECTION_ANALYZE",next,0);
    }
    public boolean grant(Map<String,Object> task,long bytes){
        var input=parse(task.get("input_json"));if(!input.has("inspectionId"))return true;
        var run=locked(input);var budget=parse(run.get("budget_json"));
        if(!available(run)||((Number)run.get("calls_used")).intValue()>=budget.path("maxCalls").asInt()||((Number)run.get("steps_used")).intValue()>=budget.path("maxSteps").asInt()||Math.addExact(((Number)run.get("input_bytes_used")).longValue(),bytes)>budget.path("maxInputBytes").asLong()){stop(run);return false;}
        db.update("UPDATE visual_inspections SET calls_used=calls_used+1,steps_used=steps_used+1,input_bytes_used=input_bytes_used+? WHERE id=?",bytes,run.get("id"));return true;
    }
    public void published(Map<String,Object> task){
        var run=locked(parse(task.get("input_json")));var budget=parse(run.get("budget_json"));
        if(!available(run)||((Number)run.get("steps_used")).intValue()>=budget.path("maxSteps").asInt())throw new ResponseStatusException(CONFLICT,"补查已达到停止条件");
        int matches=db.queryForObject("SELECT count(*) FROM evidence_segments e JOIN vision_frames f ON f.id=e.frame_id WHERE f.inspection_id=? AND e.source_kind=? AND position(lower(?) in lower(e.text))>0",Integer.class,run.get("id"),run.get("source_kind"),run.get("query"));
        db.update("UPDATE visual_inspections SET status=?,steps_used=steps_used+1 WHERE id=?",matches==0?"NO_NEW_EVIDENCE":"SUCCEEDED",run.get("id"));
    }
    public void finished(Map<String,Object> task,String status){
        var input=parse(task.get("input_json"));if(!input.has("inspectionId"))return;
        db.update("UPDATE visual_inspections SET status=? WHERE id=? AND status IN ('PENDING','RUNNING')",status,UUID.fromString(input.path("inspectionId").asText()));
    }
    private boolean available(Map<String,Object> run){return Set.of("PENDING","RUNNING").contains(run.get("status"))&&(run.get("deadline_at")==null||((java.sql.Timestamp)run.get("deadline_at")).toInstant().isAfter(java.time.Instant.now()));}
    private void stop(Map<String,Object> run){db.update("UPDATE visual_inspections SET status='BUDGET_EXHAUSTED' WHERE id=? AND status IN ('PENDING','RUNNING')",run.get("id"));}
    private Map<String,Object> locked(JsonNode input){return db.queryForMap("SELECT * FROM visual_inspections WHERE id=? FOR UPDATE",UUID.fromString(input.path("inspectionId").asText()));}
    @Transactional
    public Object propose(UUID id){
        var run=db.queryForMap("SELECT * FROM visual_inspections WHERE id=? FOR UPDATE",id);
        if(Set.of("PENDING","RUNNING").contains(run.get("status")))throw new ResponseStatusException(CONFLICT,"当前预算内补查尚未结束");
        var existing=db.queryForList("SELECT id FROM visual_inspection_proposals WHERE inspection_id=?",id);
        if(existing.isEmpty()){
            long duration=db.queryForObject("SELECT duration_ms FROM assets WHERE id=?",Long.class,run.get("asset_id"));UUID proposal=UUID.randomUUID();
            var budget=Map.of("maxFrames",8,"maxCalls",8,"maxSteps",16,"maxSeconds",300,"maxInputBytes",8388608);
            var cost=Map.of("currency","CNY","estimatedAmount",0.36,"explanation","费用估算：新增最多 8 次视觉调用，按北京 qwen3-vl-plus 输入每百万 token 1 元、输出 10 元，并保守估算每次 32000 输入与 1200 输出 token，约 0.36 元。实际账单按提供方用量和价格计费；估算不是金额硬上限，已有成功图像调用可复用。","source","https://help.aliyun.com/zh/model-studio/model-pricing","checkedOn","2026-10-12");
            db.update("INSERT INTO visual_inspection_proposals(id,inspection_id,asset_id,start_ms,end_ms,budget_json,cost_json) VALUES (?,?,?,?,?,?::jsonb,?::jsonb)",proposal,id,run.get("asset_id"),Math.max(0,((Number)run.get("start_ms")).longValue()-1000),Math.min(duration,((Number)run.get("end_ms")).longValue()+1000),encode(budget),encode(cost));
        }
        return proposal(id);
    }
    @Transactional
    public Object decide(UUID id,String choice,UUID actor){
        var proposals=db.queryForList("SELECT * FROM visual_inspection_proposals WHERE inspection_id=? FOR UPDATE",id);
        if(proposals.isEmpty())throw new ResponseStatusException(CONFLICT,"请先查看拟扩大范围与费用，再明确选择");
        var proposal=proposals.get(0);
        String next=switch(choice){case "DECLINE"->"DECLINED";case "APPROVE"->"APPROVED";default->throw new ResponseStatusException(BAD_REQUEST,"扩大补查选择无效");};
        if(!"PROPOSED".equals(proposal.get("status"))){if(!next.equals(proposal.get("status")))throw new ResponseStatusException(CONFLICT,"该方案已有不同选择");return proposal(id);}
        UUID child=null;
        if("APPROVE".equals(choice)){
            var run=db.queryForMap("SELECT * FROM visual_inspections WHERE id=?",id);
            Map<String,Object> budget=json.convertValue(parse(proposal.get("budget_json")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            Object created=createWithBudget((UUID)run.get("project_id"),(UUID)run.get("asset_id"),run.get("query").toString(),run.get("source_kind").toString(),((Number)proposal.get("start_ms")).longValue(),((Number)proposal.get("end_ms")).longValue(),"approved-expansion-"+proposal.get("id"),budget);
            child=UUID.fromString(json.valueToTree(created).path("id").asText());
        }
        db.update("UPDATE visual_inspection_proposals SET status=?,child_inspection_id=?,decided_by=?,decided_at=clock_timestamp() WHERE id=?",next,child,actor,proposal.get("id"));return proposal(id);
    }
    private Object proposal(UUID id){
        var rows=db.queryForList("SELECT * FROM visual_inspection_proposals WHERE inspection_id=?",id);if(rows.isEmpty())return null;
        var row=rows.get(0);var result=new LinkedHashMap<String,Object>();result.put("id",row.get("id"));result.put("assetId",row.get("asset_id"));result.put("startMs",row.get("start_ms"));result.put("endMs",row.get("end_ms"));result.put("budget",parse(row.get("budget_json")));result.put("cost",parse(row.get("cost_json")));result.put("status",row.get("status"));result.put("childInspectionId",row.get("child_inspection_id"));return result;
    }
    public UUID project(UUID run){var rows=db.queryForList("SELECT project_id FROM visual_inspections WHERE id=?",run);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"补查不存在或无权访问");return (UUID)rows.get(0).get("project_id");}
    public Object view(UUID id){
        var run=db.queryForMap("SELECT * FROM visual_inspections WHERE id=?",id);var result=new LinkedHashMap<String,Object>();
        result.put("id",id);result.put("assetId",run.get("asset_id"));result.put("analysisId",run.get("analysis_id"));result.put("taskId",run.get("current_task_id"));result.put("query",run.get("query"));result.put("sourceKind",run.get("source_kind"));result.put("status",run.get("status"));result.put("budget",parse(run.get("budget_json")));
        result.put("usage",Map.of("frames",run.get("frames_used"),"calls",run.get("calls_used"),"steps",run.get("steps_used"),"inputBytes",run.get("input_bytes_used")));
        result.put("coverage",Map.of("kind",run.get("sampling_kind"),"startMs",run.get("start_ms"),"endMs",run.get("end_ms"),"complete",false));
        result.put("observations",db.queryForList("SELECT e.id,e.source_kind AS \"sourceKind\",e.text,e.start_ms AS \"observedAtMs\",e.frame_id AS \"frameId\",e.vision_call_id AS \"modelCallId\",'FRAME_OBSERVATION' AS \"temporalPrecision\" FROM evidence_segments e JOIN vision_frames f ON f.id=e.frame_id WHERE f.inspection_id=? ORDER BY e.start_ms,e.source_kind,e.ordinal",id));
        result.put("expansionProposal",proposal(id));
        result.put("coverageWarning","加密采样仍可能遗漏短暂画面和小字；未命中不证明不存在。");return result;
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
}

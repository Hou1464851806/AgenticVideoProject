package com.agenticvideo.business.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

/** Immutable speech observations, rebuildable units, and navigation summaries are distinct records. */
@Service
public class SpeechAnalysis {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final SpeechTimings timings;
    public SpeechAnalysis(JdbcTemplate database,ObjectMapper json,SpeechTimings timings) { this.database=database;this.json=json;this.timings=timings; }
    public void initialize(UUID assetId, UUID projectId, JsonNode media) {
        UUID analysisId=UUID.randomUUID();
        boolean audio=media.path("timeMapping").path("hasAudio").asBoolean();
        JsonNode derived=media.path("derivedAudio");
        database.update("INSERT INTO asset_analysis_revisions (id,project_id,asset_id,source_sha256,profile_hash,audio_json) VALUES (?,?,?,?,?,?::jsonb)",
                analysisId,projectId,assetId,media.path("sha256").asText(),"speech-v1",derived.isObject()?derived.toString():null);
        database.update("INSERT INTO asset_analysis_heads (asset_id,analysis_id) VALUES (?,?)",assetId,analysisId);
        for(var stage:Map.of("MEDIA","SUCCEEDED","ASR",audio?"PENDING":"NOT_APPLICABLE","UNITS",audio?"PENDING":"NOT_APPLICABLE",
                "INDEX",audio?"NOT_STARTED":"NOT_APPLICABLE","VISION","NOT_STARTED").entrySet())
            database.update("INSERT INTO analysis_stages (asset_id,analysis_id,stage_key,status) VALUES (?,?,?,?)",assetId,analysisId,stage.getKey(),stage.getValue());
        if(audio && derived.isObject()) {
            UUID task=UUID.randomUUID(),event=UUID.randomUUID();
            var input=json.createObjectNode().put("assetId",assetId.toString()).put("analysisId",analysisId.toString())
                    .put("model","qwen3-asr-flash-filetrans").put("profile","speech-v1");
            input.set("audio",derived); input.put("durationMs",media.path("durationMs").asLong());
            database.update("INSERT INTO tasks (id,project_id,type,input_json) VALUES (?,?,'CONTENT_ANALYZE',?::jsonb)",task,projectId,input.toString());
            database.update("UPDATE analysis_stages SET task_id=? WHERE analysis_id=? AND stage_key IN ('ASR','UNITS')",task,analysisId);
            database.update("INSERT INTO outbox_events (id,task_id,type,payload_json) VALUES (?,?,'CONTENT_ANALYZE',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','CONTENT_ANALYZE','projectId',?::text,'occurredAt',now()))",event,task,event,task,projectId);
        }
    }
    public Object provider(UUID taskId,UUID attempt,JsonNode input,String action,JsonNode data) {
        UUID analysis=UUID.fromString(input.path("analysisId").asText());
        var rows=database.queryForList("SELECT * FROM model_calls WHERE analysis_id=? FOR UPDATE",analysis);
        if(rows.isEmpty()) {
            if(!"LOOKUP".equals(action)) throw conflict("模型调用尚未登记");
            database.update("INSERT INTO model_calls (id,analysis_id,task_id,owner_attempt_id,model,parameters_json,status) VALUES (?,?,?,?,?,?::jsonb,'READY')",
                    UUID.randomUUID(),analysis,taskId,attempt,input.path("model").asText(),"{\"channel_id\":[0],\"enable_itn\":false,\"enable_words\":true}");
            var call=database.queryForMap("SELECT * FROM model_calls WHERE analysis_id=?",analysis);
            return viewCall(call,false);
        }
        var call=rows.get(0);
        if(!taskId.equals(call.get("task_id"))) throw conflict("模型调用不属于当前任务");
        String state=call.get("status").toString();
        switch(action) {
            case "LOOKUP" -> { return viewCall(call,false); }
            case "PREPARE" -> {
                if(!"READY".equals(state)) return viewCall(call,false);
                database.update("UPDATE model_calls SET status='SUBMITTING',owner_attempt_id=?,updated_at=now() WHERE id=? AND status='READY'",attempt,call.get("id"));
                return viewCall(database.queryForMap("SELECT * FROM model_calls WHERE id=?",call.get("id")),true);
            }
            case "SUBMITTED" -> {
                UUID provider=UUID.fromString(data.path("providerTaskId").asText());
                if(call.get("provider_task_id")!=null && !provider.equals(call.get("provider_task_id"))) throw conflict("提供方任务身份不能变更");
                if(!"SUBMITTING".equals(state) && call.get("provider_task_id")==null) throw conflict("当前调用不能提交");
                database.update("UPDATE model_calls SET provider_task_id=?,request_id=?,status='SUBMITTED',submitted_at=coalesce(submitted_at,now()),updated_at=now() WHERE id=? AND status IN ('SUBMITTING','SUBMITTED')",
                        provider,data.path("requestId").asText(),call.get("id"));
            }
            case "SUCCEEDED" -> {
                if(call.get("provider_task_id")==null || !call.get("provider_task_id").toString().equals(data.path("providerTaskId").asText())) throw conflict("提供方任务身份不符");
                if(!data.path("transcript").path("transcripts").isArray() || !data.path("usage").isObject()) throw conflict("语音结果缺少必要观察或用量");
                database.update("UPDATE model_calls SET status='SUCCEEDED',result_json=?::jsonb,usage_json=?::jsonb,updated_at=now() WHERE id=? AND status='SUBMITTED'",
                        data.path("transcript").toString(),data.path("usage").toString(),call.get("id"));
            }
            case "FAILED" -> {
                if(call.get("provider_task_id")==null || !call.get("provider_task_id").toString().equals(data.path("providerTaskId").asText())) throw conflict("提供方任务身份不符");
                String terminal=data.path("failure").path("status").asText();
                if(!Set.of("FAILED","UNKNOWN").contains(terminal)) throw conflict("提供方失败状态不符");
                var failure=json.createObjectNode().put("status",terminal).put("code",data.path("failure").path("code").asText("UNKNOWN"));
                database.update("UPDATE model_calls SET status='FAILED',result_json=?::jsonb,usage_json=?::jsonb,updated_at=now() WHERE id=? AND status='SUBMITTED'",
                        failure.toString(),data.path("usage").isObject()?data.path("usage").toString():null,call.get("id"));
            }
            case "UNCERTAIN" -> database.update("UPDATE model_calls SET status='UNCERTAIN',updated_at=now() WHERE id=? AND status='SUBMITTING'",call.get("id"));
            default -> throw conflict("模型调用动作不支持");
        }
        return viewCall(database.queryForMap("SELECT * FROM model_calls WHERE id=?",call.get("id")),false);
    }
    private Map<String,Object> viewCall(Map<String,Object> call,boolean submit) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",call.get("id"));result.put("status",call.get("status"));result.put("canSubmit",submit);
        if(call.get("provider_task_id")!=null) result.put("providerTaskId",call.get("provider_task_id"));
        if(call.get("result_json")!=null) result.put("transcript",parse(call.get("result_json")));
        if(call.get("usage_json")!=null) result.put("usage",parse(call.get("usage_json")));
        return result;
    }
    public void publish(Map<String,Object> task,JsonNode input,JsonNode result) {
        UUID analysis=UUID.fromString(input.path("analysisId").asText());
        var calls=database.queryForList("SELECT * FROM model_calls WHERE analysis_id=? AND id=? AND status='SUCCEEDED'",analysis,UUID.fromString(result.path("modelCallId").asText()));
        if(calls.isEmpty()) throw conflict("语音观察尚未完成，不能发布");
        var call=calls.get(0);JsonNode transcript=parse(call.get("result_json"));
        UUID asset=UUID.fromString(input.path("assetId").asText()),project=(UUID)task.get("project_id");
        long offset=input.path("audio").path("sourceOffsetMs").asLong(),duration=input.path("durationMs").asLong();
        int ordinal=0;var references=new ArrayList<String>();var summary=new StringBuilder();
        for(JsonNode channel:transcript.path("transcripts")) for(JsonNode sentence:channel.path("sentences")) {
            long begin=sentence.path("begin_time").asLong(-1),end=sentence.path("end_time").asLong(-1);
            long start=Math.max(0,begin+offset),stop=Math.min(duration,end+offset);
            if(begin<0 || end<=begin || stop<=start || !sentence.path("words").isArray()) throw conflict("语音观察时间不符合原素材映射");
            for(JsonNode word:sentence.path("words")) if(word.path("begin_time").asLong(-1)<begin || word.path("end_time").asLong(-1)>end
                    || word.path("end_time").asLong(-1)<word.path("begin_time").asLong(-1)) throw conflict("字词时间不属于语句");
            UUID evidence=UUID.randomUUID(),unit=UUID.randomUUID();String text=sentence.path("text").asText();
            database.update("INSERT INTO evidence_segments (id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',?,?,?,?,?,?,?::jsonb,?)",
                    evidence,project,asset,analysis,ordinal++,text,start,stop,begin,end,sentence.toString(),call.get("id"));
            database.update("INSERT INTO retrieval_units (id,project_id,asset_id,analysis_id,source_kind,text,start_ms,end_ms,rules_hash) VALUES (?,?,?,?,'SPEECH',?,?,?,'sentence-v1')",unit,project,asset,analysis,text,start,stop);
            database.update("INSERT INTO unit_evidence_refs (unit_id,evidence_id,ordinal) VALUES (?,?,0)",unit,evidence);
            references.add(evidence.toString());if(summary.length()<500) summary.append(text);
        }
        var summaryJson=json.createObjectNode().put("kind","EXTRACTIVE").put("text",summary.toString()).put("purpose","NAVIGATION");
        database.update("INSERT INTO asset_summaries (analysis_id,summary_json,evidence_refs_json) VALUES (?,?::jsonb,?::jsonb)",analysis,summaryJson.toString(),json.valueToTree(references).toString());
        database.update("UPDATE analysis_stages SET status='SUCCEEDED',error_json=NULL,updated_at=now() WHERE analysis_id=? AND stage_key IN ('ASR','UNITS')",analysis);
    }
    public Object stages(UUID asset) {
        var result=new LinkedHashMap<String,Object>();
        var errors=new LinkedHashMap<String,Object>();
        for(var row:database.queryForList("SELECT s.stage_key,s.status,s.error_json FROM analysis_stages s JOIN asset_analysis_heads h ON h.analysis_id=s.analysis_id WHERE h.asset_id=?",asset)) {
            result.put(row.get("stage_key").toString(),row.get("status"));
            if(row.get("error_json")!=null)errors.put(row.get("stage_key").toString(),parse(row.get("error_json")));
        }
        return Map.of("stages",result,"errors",errors);
    }
    public Object evidence(UUID asset) {
        var observations=new ArrayList<Object>();
        for(var row:database.queryForList("SELECT e.* FROM evidence_segments e JOIN asset_analysis_heads h ON h.analysis_id=e.analysis_id WHERE h.asset_id=? ORDER BY e.ordinal",asset)) {
            var words=timings.words(row);
            observations.add(Map.of("id",row.get("id"),"sourceKind",row.get("source_kind"),"text",row.get("text"),"startMs",row.get("start_ms"),"endMs",row.get("end_ms"),
                    "audioStartMs",row.get("audio_start_ms"),"words",words,"analysisId",row.get("analysis_id"),"temporalPrecision","MODEL_TIMESTAMPS"));
        }
        var units=database.queryForList("SELECT u.id,u.text,u.start_ms AS \"startMs\",u.end_ms AS \"endMs\" FROM retrieval_units u JOIN asset_analysis_heads h ON h.analysis_id=u.analysis_id WHERE h.asset_id=? ORDER BY u.start_ms",asset);
        var summaries=database.queryForList("SELECT s.summary_json FROM asset_summaries s JOIN asset_analysis_heads h ON h.analysis_id=s.analysis_id WHERE h.asset_id=?",asset);
        var calls=new ArrayList<Object>();
        for(var row:database.queryForList("SELECT c.* FROM model_calls c JOIN asset_analysis_heads h ON h.analysis_id=c.analysis_id WHERE h.asset_id=?",asset)) {
            var item=new LinkedHashMap<String,Object>();item.put("id",row.get("id"));item.put("taskId",row.get("task_id"));item.put("model",row.get("model"));item.put("status",row.get("status"));
            if(row.get("provider_task_id")!=null)item.put("providerTaskId",row.get("provider_task_id"));
            if(row.get("usage_json")!=null)item.put("usage",parse(row.get("usage_json")));
            if("FAILED".equals(row.get("status")) && row.get("result_json")!=null)item.put("failure",parse(row.get("result_json")));
            calls.add(item);
        }
        return Map.of("observations",observations,"units",units,"summary",summaries.isEmpty()?Map.of():parse(summaries.get(0).get("summary_json")),"providerCalls",calls);
    }
    private JsonNode parse(Object data) { try { return json.readTree(data.toString()); } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); } }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(CONFLICT,message); }
}

package com.agenticvideo.business.render;

import com.agenticvideo.business.agentrun.*;
import com.agenticvideo.business.storage.StorageCapacity;
import com.agenticvideo.business.tools.ToolJson;
import com.agenticvideo.business.upload.MultipartStorage;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Admits media for a seen, validated draft; accepted input outlives later conversation edits. */
@Service
public class DraftPreviews {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentRuns runs;
    private final AgentRunLocks locks;
    private final PreviewRequests previews;
    private final RenderArtifacts artifacts;
    private final StorageCapacity capacity;
    private final MultipartStorage storage;
    private final com.agenticvideo.business.tools.QuestionScopes scopes;
    public DraftPreviews(JdbcTemplate db,ObjectMapper json,AgentRuns runs,AgentRunLocks locks,PreviewRequests previews,RenderArtifacts artifacts,StorageCapacity capacity,MultipartStorage storage,com.agenticvideo.business.tools.QuestionScopes scopes) {
        this.scopes=scopes;this.db=db;this.json=json;this.runs=runs;this.locks=locks;this.previews=previews;this.artifacts=artifacts;this.capacity=capacity;this.storage=storage;
    }
    public record Request(@NotNull @Valid AgentRunContracts.DraftBinding draft,@NotBlank @Size(max=64) String outputKey,@Pattern(regexp="LOW_360") @NotNull String previewProfile) {}
    @Transactional public Object request(UUID conversation,String key,Request request,Authentication account) {
        runs.requireOwned(conversation,account);AgentRuns.key(key);
        var session=locks.conversation(conversation);UUID project=(UUID)session.get("project_id");
        var replay=db.queryForList("SELECT *,request_json=?::jsonb AS same FROM preview_intents WHERE project_id=? AND idempotency_key=?",json.valueToTree(request).toString(),project,key);
        if(!replay.isEmpty()) {
            if(!Boolean.TRUE.equals(replay.get(0).get("same"))||!UUID.fromString(request.draft().messageId()).equals(replay.get(0).get("source_draft_message_id")))throw AgentRuns.conflict("同一请求标识不能改变预览输入");
            return runs.parse(replay.get(0).get("response_json"));
        }
        runs.requireCurrentDraft(session,request.draft());
        Object result=admit(conversation,project,request,null);
        JsonNode response=json.valueToTree(result);
        db.update("INSERT INTO preview_intents(project_id,idempotency_key,source_draft_message_id,request_json,render_request_id,response_json) VALUES (?,?,?,?::jsonb,?,?::jsonb)",project,key,UUID.fromString(request.draft().messageId()),json.valueToTree(request).toString(),UUID.fromString(response.path("requestId").asText()),response.toString());
        return result;
    }
    @Transactional public Object retry(UUID conversation,UUID original,Authentication account) {
        runs.requireOwned(conversation,account);var session=locks.conversation(conversation);
        var row=previews.requestRow(original);
        if(!conversation.equals(row.get("conversation_id")))throw new ResponseStatusException(NOT_FOUND,"草稿预览不存在");
        var successor=db.queryForList("SELECT id FROM render_requests WHERE retry_of=?",original);
        if(!successor.isEmpty())return previews.read((UUID)successor.get(0).get("id"));
        String status=db.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,row.get("task_id"));
        if(!Set.of("FAILED","CANCELLED").contains(status)&&!("SUCCEEDED".equals(status)&&!artifacts.available((UUID)row.get("artifact_id"))))throw AgentRuns.conflict("当前预览不允许重试");
        var d=db.queryForMap("SELECT * FROM conversation_edit_drafts WHERE message_id=?",row.get("source_draft_message_id"));
        var binding=new AgentRunContracts.DraftBinding(d.get("message_id").toString(),d.get("validation_id").toString(),((Number)d.get("edit_generation")).longValue(),d.get("draft_hash").toString(),d.get("requirements_hash").toString());
        runs.requireCurrentDraft(session,binding);
        return admit(conversation,(UUID)session.get("project_id"),new Request(binding,row.get("output_key").toString(),"LOW_360"),original);
    }
    private Object admit(UUID conversation,UUID project,Request request,UUID retryOf) {
        var d=request.draft();UUID message=UUID.fromString(d.messageId()),validation=UUID.fromString(d.validationId());
        var row=db.queryForMap("SELECT * FROM edit_draft_validations WHERE id=?",validation);
        scopes.requireDependencies(scopes.get((UUID)row.get("scope_id")));
        JsonNode output=null;
        for(var candidate:runs.parse(row.get("normalized_content_json")).path("outputs"))if(request.outputKey().equals(candidate.path("output_key").asText()))output=candidate;
        if(output==null)throw new ResponseStatusException(NOT_FOUND,"成片定义不属于当前草稿");
        var sources=new LinkedHashMap<UUID,Map<String,Object>>();
        for(var clip:output.path("clips")) {
            UUID asset=UUID.fromString(clip.path("assetId").asText());if(sources.containsKey(asset))continue;
            var rows=db.queryForList("SELECT * FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'",asset,project);
            if(rows.isEmpty())throw AgentRuns.conflict("草稿依赖的原素材不可用");
            sources.put(asset,previews.source(rows.get(0)));
        }
        var config=PreviewRequests.lowConfiguration(output);
        var fingerprint=json.valueToTree(Map.of("sourceDraftMessageId",message,"validationId",validation,"draftHash",d.draftHash(),"requirementsHash",d.requirementsHash(),"outputKey",request.outputKey(),"configuration",config,"rendererVersion",PreviewRequests.RENDERER_VERSION,"sources",sources.values().stream().map(s->Map.of("assetId",s.get("assetId"),"sha256",s.get("sha256"))).toList()));
        String cache=ToolJson.hash(fingerprint);
        var history=db.queryForList("SELECT r.*,t.status FROM render_requests r JOIN tasks t ON t.id=r.task_id WHERE r.cache_key=? ORDER BY r.created_at DESC,r.id DESC",cache);
        for(var prior:retryOf==null?history:List.<Map<String,Object>>of()) {
            if(Set.of("PENDING","RUNNING","CANCEL_REQUESTED").contains(prior.get("status"))||"SUCCEEDED".equals(prior.get("status"))&&artifacts.available((UUID)prior.get("artifact_id"))) {
                return previews.read((UUID)prior.get("id"));
            }
        }
        if(retryOf==null&&!history.isEmpty())throw AgentRuns.conflict("请对既有预览显式重试");
        for(var source:sources.values())if(!Objects.equals(source.get("sizeBytes"),storage.objectSize(source.get("objectKey").toString())))throw AgentRuns.conflict("草稿依赖的原素材文件不可用");
        UUID id=UUID.randomUUID(),task=UUID.randomUUID(),event=UUID.randomUUID();
        var input=json.valueToTree(Map.of("operation","PREVIEW","requestId",id,"sourceDraftMessageId",message,"outputKey",request.outputKey(),"output",output,"configuration",config,"rendererVersion",PreviewRequests.RENDERER_VERSION,"sources",sources.values()));
        long budget=capacity.renderBudget(input);capacity.require(budget);
        db.update("INSERT INTO render_requests(id,project_id,kind,config_json,renderer_version,cache_key,task_id,conversation_id,source_draft_message_id,production_id,validation_id,draft_hash,requirements_hash,output_key,retry_of) VALUES (?,?,'PREVIEW',?::jsonb,?,?,?,?,?,?,?,?,?,?,?)",id,project,json.valueToTree(config).toString(),PreviewRequests.RENDERER_VERSION,cache,task,conversation,message,row.get("production_id"),validation,d.draftHash(),d.requirementsHash(),request.outputKey(),retryOf);
        db.update("INSERT INTO tasks(id,project_id,type,input_json,disk_budget_bytes) VALUES (?,?,'PREVIEW_RENDER',?::jsonb,?)",task,project,input.toString(),budget);
        db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'PREVIEW_RENDER',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','PREVIEW_RENDER','projectId',?::text,'occurredAt',now()))",event,task,event,task,project);
        return previews.read(id);
    }
    public Object list(UUID conversation) {
        return Map.of("items",db.queryForList("SELECT id FROM render_requests WHERE conversation_id=? ORDER BY created_at,id",conversation).stream().map(r->previews.read((UUID)r.get("id"))).toList());
    }
}

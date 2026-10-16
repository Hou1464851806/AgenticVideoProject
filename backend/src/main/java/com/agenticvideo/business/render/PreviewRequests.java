package com.agenticvideo.business.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Freezes an explicitly requested preview's version, output, and actual renderer input. */
@Service
public class PreviewRequests {
    public static final String RENDERER_VERSION="ffmpeg-6.1.1-noto20230817-hardcut-v2";
    private final com.agenticvideo.business.storage.StorageCapacity capacity;
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final RenderArtifacts artifacts;
    private final com.agenticvideo.business.upload.MultipartStorage storage;
    public PreviewRequests(JdbcTemplate database,ObjectMapper json,PlatformTransactionManager manager,RenderArtifacts artifacts,com.agenticvideo.business.storage.StorageCapacity capacity,com.agenticvideo.business.upload.MultipartStorage storage) {
        this.storage=storage;this.capacity=capacity;this.database=database;this.json=json;transactions=new TransactionTemplate(manager);this.artifacts=artifacts;
    }
    public Map<String,Object> revision(UUID id) {
        var rows=database.queryForList("SELECT * FROM plan_revisions WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"方案版本不存在或无权访问");
        return rows.get(0);
    }
    public Map<String,Object> requestRow(UUID id) {
        var rows=database.queryForList("SELECT * FROM render_requests WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"渲染请求不存在或无权访问");
        return rows.get(0);
    }
    public record Admission(Object result,boolean created) {}
    public static class Rejected extends ResponseStatusException {
        public final String code;
        public Rejected(org.springframework.http.HttpStatus status,String code,String message){super(status,message);this.code=code;}
    }
    private enum Mode { PUBLIC, SAVED, RETRY }
    public Object request(UUID revisionId,JsonNode body,String key) {
        return admit(revisionId,body,key,Mode.PUBLIC).result();
    }
    public Admission requestSaved(UUID revisionId,UUID outputId) {
        return admit(revisionId,json.valueToTree(Map.of("outputId",outputId,"previewProfile","LOW_360")),UUID.randomUUID().toString(),Mode.SAVED);
    }
    private Admission admit(UUID revisionId,JsonNode body,String key,Mode mode) {
        if(key==null || key.isBlank() || key.length()>200)throw new ResponseStatusException(BAD_REQUEST,"请求标识必须为 1–200 个字符");
        if(body==null || !body.isObject())throw new ResponseStatusException(BAD_REQUEST,"请指定成片与支持的预览规格");
        body.fieldNames().forEachRemaining(field -> {
            if(!Set.of("outputId","previewProfile").contains(field))throw new ResponseStatusException(BAD_REQUEST,"预览请求包含不受支持的字段");
        });
        if(!"LOW_360".equals(body.path("previewProfile").asText()))throw new ResponseStatusException(BAD_REQUEST,"不支持该预览规格");
        UUID outputId;
        try {outputId=UUID.fromString(body.path("outputId").asText());}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(BAD_REQUEST,"成片标识不合法");}
        JsonNode normalized=json.valueToTree(Map.of("outputId",outputId,"previewProfile","LOW_360"));
        return transactions.execute(tx -> {
            // Serialize project intent replay and version cache admission in this order.
            UUID projectId=(UUID)revision(revisionId).get("project_id");
            database.queryForMap("SELECT id FROM projects WHERE id=? FOR UPDATE",projectId);
            var intent=mode==Mode.PUBLIC ? database.queryForList("SELECT *,request_json=?::jsonb AS same_input FROM preview_intents WHERE project_id=? AND idempotency_key=?",encode(normalized),projectId,key) : List.<Map<String,Object>>of();
            if(!intent.isEmpty()) {
                if(!revisionId.equals(intent.get(0).get("revision_id")) || !Boolean.TRUE.equals(intent.get(0).get("same_input")))throw new ResponseStatusException(CONFLICT,"同一请求标识不能用于不同预览输入");
                return new Admission(parse(intent.get(0).get("response_json")),false);
            }
            var revision=database.queryForMap("SELECT * FROM plan_revisions WHERE id=? FOR UPDATE",revisionId);
            JsonNode output=null;
            for(var candidate:parse(revision.get("content_json")).path("outputs")) {
                if(outputId.toString().equals(candidate.path("outputId").asText()))output=candidate;
            }
            if(output==null)throw new ResponseStatusException(NOT_FOUND,"成片定义不属于当前版本");
            var history=mode==Mode.PUBLIC ? List.<Map<String,Object>>of() : database.queryForList("SELECT r.id FROM render_requests r JOIN tasks t ON t.id=r.task_id WHERE r.revision_id=? AND r.output_id=? AND r.kind='PREVIEW' AND t.type='PREVIEW_RENDER' AND r.config_json->>'profile'='LOW_360' ORDER BY r.created_at DESC,r.id DESC",revisionId,outputId);
            var sources=new LinkedHashMap<UUID,Map<String,Object>>();
            boolean validSources=true;
            for(var clip:output.path("clips")) {
                UUID asset=UUID.fromString(clip.path("assetId").asText());
                if(sources.containsKey(asset))continue;
                var rows=database.queryForList("SELECT id,object_key,byte_size,sha256,duration_ms,media_info_json,time_mapping_json,validation_status FROM assets WHERE id=? AND project_id=?",asset,revision.get("project_id"));
                if(rows.isEmpty()) {
                    if(mode==Mode.SAVED && !history.isEmpty())return new Admission(read((UUID)history.get(0).get("id")),false);
                    if(mode!=Mode.PUBLIC)throw new Rejected(CONFLICT,"PREVIEW_SOURCE_UNAVAILABLE","方案依赖的原素材不可用");
                    throw new ResponseStatusException(CONFLICT,"方案依赖的原素材不可用");
                }
                var row=rows.get(0);
                if(!"VALID".equals(row.get("validation_status"))) {
                    if(mode==Mode.PUBLIC)throw new ResponseStatusException(CONFLICT,"方案依赖的原素材不可用");
                    validSources=false;
                }
                sources.put(asset,source(row));
            }
            var configuration=lowConfiguration(output);
            String cacheKey=digest(encode(Map.of("revisionId",revisionId,"outputId",outputId,"configuration",configuration,"rendererVersion",RENDERER_VERSION,
                    "sources",sources.values().stream().map(source -> Map.of("assetId",source.get("assetId"),"sha256",source.get("sha256"))).toList())));
            var existing=database.queryForList("SELECT r.*,t.status FROM render_requests r JOIN tasks t ON t.id=r.task_id WHERE r.cache_key=? AND r.kind='PREVIEW' AND t.type='PREVIEW_RENDER' ORDER BY r.created_at DESC,r.id DESC"+(mode==Mode.PUBLIC?" LIMIT 1":""),cacheKey);
            for(var prior:existing) {
                if(Set.of("PENDING","RUNNING","CANCEL_REQUESTED").contains(prior.get("status"))
                        || "SUCCEEDED".equals(prior.get("status")) && prior.get("artifact_id")!=null && artifacts.available((UUID)prior.get("artifact_id")))
                    return new Admission(mode!=Mode.PUBLIC ? read((UUID)prior.get("id")) : remember(projectId,key,revisionId,normalized,read((UUID)prior.get("id"))),false);
            }
            if(mode==Mode.SAVED && !history.isEmpty())return new Admission(read((UUID)history.get(0).get("id")),false);
            if(mode!=Mode.PUBLIC) {
                if(!validSources)throw new Rejected(CONFLICT,"PREVIEW_SOURCE_UNAVAILABLE","方案依赖的原素材不可用");
                for(var source:sources.values())if(!Objects.equals(source.get("sizeBytes"),storage.objectSize(source.get("objectKey").toString())))
                    throw new Rejected(CONFLICT,"PREVIEW_SOURCE_UNAVAILABLE","方案依赖的原素材文件不可用");
            }
            UUID request=UUID.randomUUID(),task=UUID.randomUUID(),event=UUID.randomUUID();
            var input=Map.of("operation","PREVIEW","requestId",request,"revisionId",revisionId,"outputId",outputId,"output",output,
                    "configuration",configuration,"rendererVersion",RENDERER_VERSION,"sources",sources.values());
            long diskBudget=capacity.renderBudget(json.valueToTree(input));
            capacity.require(diskBudget);
            database.update("INSERT INTO render_requests(id,project_id,revision_id,output_id,kind,config_json,renderer_version,cache_key,task_id) VALUES (?,?,?,?,'PREVIEW',?::jsonb,?,?,?)",
                    request,revision.get("project_id"),revisionId,outputId,encode(configuration),RENDERER_VERSION,cacheKey,task);
            database.update("INSERT INTO tasks(id,project_id,type,input_json,disk_budget_bytes) VALUES (?,?,'PREVIEW_RENDER',?::jsonb,?)",task,revision.get("project_id"),encode(input),diskBudget);
            database.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'PREVIEW_RENDER',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','PREVIEW_RENDER','projectId',?::text,'occurredAt',now()))",
                    event,task,event,task,revision.get("project_id"));
            return new Admission(mode!=Mode.PUBLIC ? read(request) : remember(projectId,key,revisionId,normalized,read(request)),true);
        });
    }
    public Admission retryPreview(UUID requestId) {
        return transactions.execute(tx -> {
            var original=previewRow(requestId);UUID project=(UUID)original.get("project_id");
            database.queryForMap("SELECT id FROM projects WHERE id=? FOR UPDATE",project);
            // Public keys remain opaque; only the server writes this receipt shape.
            var successor=database.queryForList("SELECT render_request_id FROM preview_intents WHERE project_id=? AND request_json->>'retry_of'=?",project,requestId.toString());
            if(!successor.isEmpty())return new Admission(read((UUID)successor.get(0).get("render_request_id")),false);
            if(!readPreview(requestId).retry_allowed())throw new Rejected(CONFLICT,"PREVIEW_NOT_RETRYABLE","当前预览不允许重试");
            UUID revision=(UUID)original.get("revision_id");
            var admission=admit(revision,json.valueToTree(Map.of("outputId",original.get("output_id"),"previewProfile","LOW_360")),UUID.randomUUID().toString(),Mode.RETRY);
            remember(project,UUID.randomUUID().toString(),revision,json.valueToTree(Map.of("retry_of",requestId)),admission.result());
            return admission;
        });
    }
    private Object remember(UUID project,String key,UUID revision,JsonNode body,Object response) {
        JsonNode receipt=json.valueToTree(response);
        database.update("INSERT INTO preview_intents(project_id,idempotency_key,revision_id,request_json,render_request_id,response_json) VALUES (?,?,?,?::jsonb,?,?::jsonb)",
                project,key,revision,encode(body),UUID.fromString(receipt.path("requestId").asText()),encode(response));
        return response;
    }
    public Object read(UUID requestId) {
        var request=requestRow(requestId);
        String status=database.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,request.get("task_id"));
        var result=new LinkedHashMap<String,Object>(Map.of("requestId",requestId,"taskId",request.get("task_id"),
                "kind",request.get("kind"),"status",status,"configuration",parse(request.get("config_json"))));
        if(request.get("revision_id")!=null) {
            result.put("revisionId",request.get("revision_id"));result.put("outputId",request.get("output_id"));
        } else {
            result.put("conversationId",request.get("conversation_id"));result.put("sourceDraftMessageId",request.get("source_draft_message_id"));
            result.put("productionId",request.get("production_id"));result.put("validationId",request.get("validation_id"));
            result.put("draftHash",request.get("draft_hash"));result.put("requirementsHash",request.get("requirements_hash"));result.put("outputKey",request.get("output_key"));
        }
        result.put("createdAt",((java.sql.Timestamp)request.get("created_at")).toInstant().toString());
        if(request.get("confirmation_id")!=null)result.put("confirmationId",request.get("confirmation_id"));
        if(request.get("artifact_id")!=null) {
            result.put("artifactId",request.get("artifact_id"));
            result.put("artifactState",database.queryForObject("SELECT state FROM artifacts WHERE id=?",String.class,request.get("artifact_id")));
        }
        if("FAILED".equals(status)) {
            Object failure=database.queryForObject("SELECT result_json FROM tasks WHERE id=?",Object.class,request.get("task_id"));
            if(failure!=null)result.put("error",parse(failure));
        }
        return result;
    }
    public Object list(UUID revision) {
        return Map.of("items",database.queryForList("SELECT id FROM render_requests WHERE revision_id=? AND kind='PREVIEW' ORDER BY created_at,id",revision).stream()
                .map(row -> read((UUID)row.get("id"))).toList());
    }
    /** The internal CLI cannot use this boundary to inspect EXPORT or other task types. */
    public Map<String,Object> previewRow(UUID requestId) {
        var rows=database.queryForList("SELECT r.*,t.status,t.result_json FROM render_requests r JOIN tasks t ON t.id=r.task_id WHERE r.id=? AND r.revision_id IS NOT NULL AND r.kind='PREVIEW' AND t.type='PREVIEW_RENDER'",requestId);
        if(rows.isEmpty())throw new Rejected(NOT_FOUND,"PREVIEW_NOT_FOUND","预览请求不存在");
        return rows.get(0);
    }
    public PreviewContracts.Result readPreview(UUID requestId) {
        return readPreview(requestId,false);
    }
    public PreviewContracts.Result readPreview(UUID requestId,boolean includePlayback) {
        var row=previewRow(requestId);
        var status=PreviewContracts.TaskStatus.valueOf(row.get("status").toString());
        boolean retry=status==PreviewContracts.TaskStatus.FAILED || status==PreviewContracts.TaskStatus.CANCELLED;
        PreviewContracts.Error error=null;
        if(status==PreviewContracts.TaskStatus.FAILED && row.get("result_json")!=null) {
            var failure=parse(row.get("result_json"));error=new PreviewContracts.Error(failure.path("code").asText(),failure.path("message").asText());
        }
        PreviewContracts.Artifact artifact=null;
        PreviewContracts.Playback playback=null;
        if(row.get("artifact_id")!=null) {
            UUID id=(UUID)row.get("artifact_id");
            var access=artifacts.inspect(id,includePlayback);var published=access.artifact();
            if(access.url()!=null)playback=new PreviewContracts.Playback(access.url(),300);
            var state=PreviewContracts.ArtifactState.valueOf(published.get("state").toString());
            artifact=new PreviewContracts.Artifact(id,state,((Number)published.get("duration_ms")).longValue());
            if(status==PreviewContracts.TaskStatus.SUCCEEDED && state!=PreviewContracts.ArtifactState.AVAILABLE) {
                retry=true;error=new PreviewContracts.Error("PREVIEW_ARTIFACT_UNAVAILABLE","预览产物不可用，请显式重试");
            }
        }
        return new PreviewContracts.Result(requestId,(UUID)row.get("project_id"),(UUID)row.get("revision_id"),(UUID)row.get("output_id"),(UUID)row.get("task_id"),
                status,((java.sql.Timestamp)row.get("created_at")).toInstant().toString(),artifact,playback,retry,error);
    }
    static Map<String,Object> lowConfiguration(JsonNode output) {
        boolean portrait="PORTRAIT_720".equals(output.path("render").path("profile").asText());
        return Map.ofEntries(Map.entry("profile","LOW_360"),Map.entry("width",portrait?360:640),Map.entry("height",portrait?640:360),
                    Map.entry("framesPerSecond",30),Map.entry("container","mp4"),Map.entry("videoCodec","h264"),Map.entry("audioCodec","aac"),
                    Map.entry("fit","contain"),Map.entry("videoCrf",28),Map.entry("videoMaxRate",800000),Map.entry("audioBitrate",64000),
                    Map.entry("audioSampleRate",48000),Map.entry("audioChannels",2),Map.entry("subtitleFont","Noto Sans CJK SC"),Map.entry("fontPackage","20230817+repack1-3"));
    }
    Map<String,Object> source(Map<String,Object> row) {
        return Map.of("assetId",row.get("id"),"objectKey",row.get("object_key"),"sizeBytes",row.get("byte_size"),"sha256",row.get("sha256"),
                "durationMs",row.get("duration_ms"),"mediaInfo",parse(row.get("media_info_json")),"timeMapping",parse(row.get("time_mapping_json")));
    }
    private JsonNode parse(Object value) {try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    private String encode(Object value) {try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    private String digest(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}}
}

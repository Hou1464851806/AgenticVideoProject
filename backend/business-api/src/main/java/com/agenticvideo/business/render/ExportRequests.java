package com.agenticvideo.business.render;

import com.agenticvideo.business.upload.MultipartStorage;
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

/** Confirmation authorizes one immutable version; an independent direct export intent starts each job. */
@Service
public class ExportRequests {
    private final com.agenticvideo.business.storage.StorageCapacity capacity;
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final PreviewRequests revisions;
    private final MultipartStorage storage;
    public ExportRequests(JdbcTemplate database,ObjectMapper json,PlatformTransactionManager manager,PreviewRequests revisions,MultipartStorage storage,com.agenticvideo.business.storage.StorageCapacity capacity){this.capacity=capacity;this.database=database;this.json=json;transactions=new TransactionTemplate(manager);this.revisions=revisions;this.storage=storage;}
    public Object confirm(UUID revisionId,UUID actor,String key,JsonNode body) {return confirm(revisionId,actor,key,body,Map.of("type","USER_INTERFACE","explicitIntent","CONFIRM"));}
    public Object confirmFromMessage(UUID revisionId,UUID actor,String key,JsonNode body,UUID message) {return confirm(revisionId,actor,key,body,messageSource(revisionId,actor,message,"CONFIRM"));}
    private Object confirm(UUID revisionId,UUID actor,String key,JsonNode body,Object intentSource) {
        validate(body,Set.of("explicitIntent"),"CONFIRM",key);
        JsonNode normalized=json.valueToTree(Map.of("explicitIntent","CONFIRM"));
        return transactions.execute(tx->{
            var revision=lock(revisionId);UUID project=(UUID)revision.get("project_id");
            Object previous=replay(project,"CONFIRM",key,revisionId,normalized);if(previous!=null)return previous;
            var found=database.queryForList("SELECT * FROM plan_confirmations WHERE revision_id=? AND actor_user_id=?",revisionId,actor);
            UUID confirmation;
            if(found.isEmpty()) {
                confirmation=UUID.randomUUID();
                database.update("INSERT INTO plan_confirmations(id,project_id,revision_id,actor_user_id,source_json) VALUES (?,?,?,?,?::jsonb)",confirmation,project,revisionId,actor,encode(intentSource));
            }else confirmation=(UUID)found.get(0).get("id");
            Object response=confirmation(database.queryForMap("SELECT * FROM plan_confirmations WHERE id=?",confirmation));
            remember(project,"CONFIRM",key,revisionId,normalized,confirmation,null,response);return response;
        });
    }
    public Object list(UUID revision) {return Map.of("items",database.queryForList("SELECT id FROM render_requests WHERE revision_id=? AND kind='EXPORT' ORDER BY created_at DESC,id DESC",revision).stream().map(row->revisions.read((UUID)row.get("id"))).toList());}
    public Object confirmations(UUID revision) {return Map.of("items",database.queryForList("SELECT * FROM plan_confirmations WHERE revision_id=? ORDER BY confirmed_at,id",revision).stream().map(this::confirmation).toList());}
    private Object confirmation(Map<String,Object> row){return Map.of("confirmationId",row.get("id"),"revisionId",row.get("revision_id"),"confirmedAt",((java.sql.Timestamp)row.get("confirmed_at")).toInstant().toString());}
    public Object request(UUID revisionId,UUID actor,String key,JsonNode body) {return request(revisionId,actor,key,body,Map.of("type","USER_INTERFACE","explicitIntent","EXPORT"),null);}
    public Object requestFromMessage(UUID revisionId,UUID actor,String key,JsonNode body,UUID message,Prepared prepared) {return request(revisionId,actor,key,body,messageSource(revisionId,actor,message,"EXPORT"),prepared);}
    private Object request(UUID revisionId,UUID actor,String key,JsonNode body,Object intentSource,Prepared supplied) {
        validate(body,Set.of("outputIds","confirmationId","exportProfile","explicitIntent"),"EXPORT",key);
        String profile=body.path("exportProfile").asText();
        if(!Set.of("HD_720","FULL_HD_1080").contains(profile))throw new ResponseStatusException(BAD_REQUEST,"仅支持 720p 或 1080p 正式导出");
        if(!body.path("outputIds").isArray() || body.path("outputIds").size()!=1)throw new ResponseStatusException(BAD_REQUEST,"请选择一条成片定义导出");
        UUID outputId=identifier(body.path("outputIds").get(0).asText()),confirmation=identifier(body.path("confirmationId").asText());
        JsonNode normalized=json.valueToTree(Map.of("outputIds",List.of(outputId),"confirmationId",confirmation,"exportProfile",profile,"explicitIntent","EXPORT"));
        UUID projectId=(UUID)revisions.revision(revisionId).get("project_id");
        boolean replayExists=database.queryForObject("SELECT count(*) FROM production_intents WHERE project_id=? AND operation='EXPORT' AND idempotency_key=?",Integer.class,projectId,key)>0;
        Prepared prepared=replayExists?null:supplied!=null?supplied:prepare(revisionId,outputId);
        if(prepared!=null && (!revisionId.equals(prepared.revision()) || !outputId.equals(prepared.output())))throw new ResponseStatusException(CONFLICT,"查看版本或成片已变化，请重新发起导出");
        return transactions.execute(tx->{
            var revision=lock(revisionId);UUID project=(UUID)revision.get("project_id");
            Object previous=replay(project,"EXPORT",key,revisionId,normalized);if(previous!=null)return previous;
            if(database.queryForObject("SELECT count(*) FROM plan_confirmations WHERE id=? AND project_id=? AND revision_id=? AND actor_user_id=?",Integer.class,confirmation,project,revisionId,actor)!=1)
                throw new ResponseStatusException(CONFLICT,"请先明确确认要导出的这个版本");
            JsonNode output=null;for(JsonNode candidate:parse(revision.get("content_json")).path("outputs"))if(outputId.toString().equals(candidate.path("outputId").asText()))output=candidate;
            if(output==null)throw new ResponseStatusException(NOT_FOUND,"成片定义不属于该版本");
            Map<UUID,Object> sources=prepared.sources();
            for(var entry:sources.entrySet()) {
                var fixed=json.valueToTree(entry.getValue());
                var current=database.queryForList("SELECT object_key,byte_size,sha256 FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'",entry.getKey(),project);
                if(current.isEmpty() || !fixed.path("objectKey").asText().equals(current.get(0).get("object_key"))
                        || fixed.path("sizeBytes").asLong()!=((Number)current.get(0).get("byte_size")).longValue()
                        || !fixed.path("sha256").asText().equals(current.get(0).get("sha256")))
                    throw new ResponseStatusException(CONFLICT,"素材状态已变化，请重新发起导出");
            }
            boolean portrait="PORTRAIT_720".equals(output.path("render").path("profile").asText()),full="FULL_HD_1080".equals(profile);
            int wide=full?1920:1280,shortSide=full?1080:720;
            var config=Map.ofEntries(Map.entry("profile",profile),Map.entry("width",portrait?shortSide:wide),Map.entry("height",portrait?wide:shortSide),Map.entry("framesPerSecond",30),Map.entry("container","mp4"),Map.entry("videoCodec","h264"),Map.entry("audioCodec","aac"),Map.entry("fit","contain"),Map.entry("videoCrf",23),Map.entry("videoMaxRate",full?8000000:4000000),Map.entry("audioBitrate",full?192000:128000),Map.entry("audioSampleRate",48000),Map.entry("audioChannels",2),Map.entry("subtitleFont","Noto Sans CJK SC"),Map.entry("fontPackage","20230817+repack1-3"));
            UUID request=UUID.randomUUID(),task=UUID.randomUUID(),event=UUID.randomUUID();
            var input=Map.ofEntries(Map.entry("operation","EXPORT"),Map.entry("requestId",request),Map.entry("revisionId",revisionId),Map.entry("outputId",outputId),Map.entry("output",output),Map.entry("configuration",config),Map.entry("rendererVersion",PreviewRequests.RENDERER_VERSION),Map.entry("sources",sources.values()),Map.entry("confirmationId",confirmation),Map.entry("actorUserId",actor));
            long diskBudget=capacity.renderBudget(json.valueToTree(input));
            capacity.require(diskBudget);
            database.update("INSERT INTO render_requests(id,project_id,revision_id,output_id,kind,config_json,renderer_version,cache_key,task_id,confirmation_id,actor_user_id,intent_source_json) VALUES (?,?,?,?,'EXPORT',?::jsonb,?,?,?,?,?,?::jsonb)",request,project,revisionId,outputId,encode(config),PreviewRequests.RENDERER_VERSION,digest(encode(input)),task,confirmation,actor,encode(intentSource));
            database.update("INSERT INTO tasks(id,project_id,type,input_json,disk_budget_bytes) VALUES (?,?,'EXPORT_RENDER',?::jsonb,?)",task,project,encode(input),diskBudget);
            database.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,'EXPORT_RENDER',jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType','EXPORT_RENDER','projectId',?::text,'occurredAt',now()))",event,task,event,task,project);
            Object response=revisions.read(request);remember(project,"EXPORT",key,revisionId,normalized,confirmation,request,response);return response;
        });
    }
    public record Prepared(UUID revision,UUID output,Map<UUID,Object> sources) {}
    /** Storage reads occur before taking any project, revision or conversation lock. */
    public Prepared prepare(UUID revisionId,UUID outputId) {
        var revision=revisions.revision(revisionId);UUID project=(UUID)revision.get("project_id");
        JsonNode output=null;for(var item:parse(revision.get("content_json")).path("outputs"))if(outputId.toString().equals(item.path("outputId").asText()))output=item;
        if(output==null)throw new ResponseStatusException(NOT_FOUND,"成片定义不属于该版本");
        Map<UUID,Object> sources=new LinkedHashMap<>();
        for(var clip:output.path("clips")) {
            UUID asset=identifier(clip.path("assetId").asText());if(sources.containsKey(asset))continue;
            var rows=database.queryForList("SELECT * FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'",asset,project);
            if(rows.isEmpty())throw new ResponseStatusException(CONFLICT,"引用素材不再可用，未开始正式导出");
            var source=rows.get(0);
            if(!Objects.equals(source.get("byte_size"),storage.objectSize(source.get("object_key").toString())))throw new ResponseStatusException(CONFLICT,"引用原素材文件缺失或不完整，未开始正式导出");
            sources.put(asset,Map.of("assetId",asset,"objectKey",source.get("object_key"),"sizeBytes",source.get("byte_size"),"sha256",source.get("sha256"),"durationMs",source.get("duration_ms"),"mediaInfo",parse(source.get("media_info_json")),"timeMapping",parse(source.get("time_mapping_json"))));
        }
        return new Prepared(revisionId,outputId,Map.copyOf(sources));
    }
    private Object messageSource(UUID revision,UUID actor,UUID message,String intent) {
        if(database.queryForObject("SELECT count(*) FROM conversation_messages m JOIN conversations c ON c.id=m.conversation_id JOIN projects p ON p.id=c.project_id JOIN plan_revisions r ON r.project_id=p.id WHERE m.id=? AND m.role='USER' AND p.owner_user_id=? AND r.id=?",Integer.class,message,actor,revision)!=1)
            throw new ResponseStatusException(NOT_FOUND,"用户指令不属于该项目");
        return Map.of("type","USER_MESSAGE","messageId",message,"explicitIntent",intent);
    }
    private Map<String,Object> lock(UUID revision) {
        UUID project=(UUID)revisions.revision(revision).get("project_id");database.queryForMap("SELECT id FROM projects WHERE id=? FOR UPDATE",project);
        return database.queryForMap("SELECT * FROM plan_revisions WHERE id=? FOR UPDATE",revision);
    }
    private Object replay(UUID project,String operation,String key,UUID revision,JsonNode body) {
        var found=database.queryForList("SELECT *,request_json=?::jsonb AS same_input FROM production_intents WHERE project_id=? AND operation=? AND idempotency_key=?",encode(body),project,operation,key);
        if(found.isEmpty())return null;
        if(!revision.equals(found.get(0).get("revision_id")) || !Boolean.TRUE.equals(found.get(0).get("same_input")))throw new ResponseStatusException(CONFLICT,"同一请求标识不能改变版本或制作意图");
        return parse(found.get(0).get("response_json"));
    }
    private void remember(UUID project,String operation,String key,UUID revision,JsonNode body,UUID confirmation,UUID render,Object response) {
        database.update("INSERT INTO production_intents(project_id,operation,idempotency_key,revision_id,request_json,response_json,confirmation_id,render_request_id) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)",project,operation,key,revision,encode(body),encode(response),confirmation,render);
    }
    private void validate(JsonNode body,Set<String> fields,String intent,String key) {
        if(key==null || key.isBlank() || key.length()>200 || body==null || !body.isObject() || !intent.equals(body.path("explicitIntent").asText()))throw new ResponseStatusException(BAD_REQUEST,"请明确提交确认或导出意图及请求标识");
        body.fieldNames().forEachRemaining(field->{if(!fields.contains(field))throw new ResponseStatusException(BAD_REQUEST,"制作请求包含不受支持字段");});
    }
    private UUID identifier(String value){try{return UUID.fromString(value);}catch(IllegalArgumentException invalid){throw new ResponseStatusException(BAD_REQUEST,"制作对象标识无效");}}
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    private String encode(Object value){try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    private String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}}
}

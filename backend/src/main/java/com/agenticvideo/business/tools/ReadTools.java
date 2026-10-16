package com.agenticvideo.business.tools;

import com.agenticvideo.business.search.QuestionEvidence;
import com.agenticvideo.business.search.QuestionEvidence.Scope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Fixed tool gateway: identity admission, strict parameters, bounded feedback and durable replay. */
@Service
public class ReadTools {
    public static final int MAX_BYTES=32768;
    private static final Set<String> KINDS=Set.of("SPEECH","VISUAL","VISIBLE_TEXT");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final QuestionScopes scopes;
    private final QuestionEvidence evidence;
    private final com.agenticvideo.business.plan.ProductionDrafts productions;
    public ReadTools(JdbcTemplate db,ObjectMapper json,QuestionScopes scopes,QuestionEvidence evidence,com.agenticvideo.business.plan.ProductionDrafts productions){this.db=db;this.json=json;this.scopes=scopes;this.evidence=evidence;this.productions=productions;}
    public JsonNode execute(UUID scopeId,JsonNode request) {return execute(scopeId,null,request);}
    public JsonNode execute(UUID scopeId,UUID productionId,JsonNode request) {return execute(scopeId,productionId,request,null);}
    public JsonNode execute(UUID scopeId,UUID productionId,JsonNode request,com.agenticvideo.business.plan.ProductionDrafts.Execution execution) {
        for(String name:List.of("call_id","tool_name","request_hash"))
            if(!request.path(name).isTextual()||request.path(name).asText().isBlank()||request.path(name).asText().length()>200)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"调用身份字段无效");
        if(bytes(request)>65536)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"工具请求超出字节上限");
        if(productionId!=null && execution==null && db.queryForObject("SELECT conversation_id IS NOT NULL FROM edit_productions WHERE id=?",Boolean.class,productionId))
            throw new ToolFailure("BROWSER_EXECUTION_REQUIRED","IDENTITY_CONFLICT","浏览器制作不能通过 CLI 写入口校验");
        Scope scope=scopes.get(scopeId);
        var result=base(request);
        boolean admitted=false;
        try {
            validateRequest(request);
            if(productionId!=null && !"validate_edit_draft".equals(request.path("tool_name").asText()))throw parameter("TOOL_UNAVAILABLE","制作入口只接纳草稿校验");
            if(productionId==null && "validate_edit_draft".equals(request.path("tool_name").asText()))throw new ToolFailure("PRODUCTION_REQUIRED","IDENTITY_CONFLICT","校验须使用可信制作入口");
            var admittedRequest=(ObjectNode)request.deepCopy();
            if(productionId!=null)admittedRequest.put("production_id",productionId.toString());
            // Check dependencies even before replay; never hide an invalid snapshot behind an old receipt.
            scopes.requireDependencies(scope);
            admitted=db.update("INSERT INTO question_tool_receipts(scope_id,call_id,request_json) VALUES (?,?,?::jsonb) ON CONFLICT(scope_id,call_id) DO NOTHING",scopeId,request.path("call_id").asText(),admittedRequest.toString())==1;
            var receipt=db.queryForMap("SELECT request_json,result_json FROM question_tool_receipts WHERE scope_id=? AND call_id=?",scopeId,request.path("call_id").asText());
            if(!parse(receipt.get("request_json")).equals(admittedRequest))throw new ToolFailure("CALL_ID_CONFLICT","IDENTITY_CONFLICT","相同调用身份的工具或参数不同");
            if(receipt.get("result_json")!=null)return parse(receipt.get("result_json"));
            if(!admitted)throw new ToolFailure("CALL_UNCONFIRMED","EXECUTION_UNCONFIRMED","调用仍在执行或结果尚未核对，停止本次尝试");
            if(productionId!=null){((ObjectNode)result.path("coverage")).put("scope_id",scopeId.toString());return productions.validate(scope,productionId,request,result,execution);}
            var args=request.path("arguments");String name=request.path("tool_name").asText();
            validateArguments(name,args);
            int cursor=args.path("cursor").asInt(0);
            List<JsonNode> rows;
            String field;
            var data=(ObjectNode)result.path("data");var coverage=(ObjectNode)result.path("coverage");
            coverage.put("scope_id",scopeId.toString());
            switch(name) {
                case "list_assets" -> {
                    rows=new ArrayList<>();scope.assets().forEach(rows::add);
                    if(cursor>rows.size())throw parameter("CURSOR_INVALID","分页位置超出范围");
                    rows=rows.subList(cursor,Math.min(rows.size(),cursor+101));field="assets";
                }
                case "get_video_overview" -> {
                    var asset=asset(scope,args);coverage.set("asset",asset);field="entries";
                    requireSource(asset);
                    rows=evidence.read(scope,UUID.fromString(asset.path("id").asText()),null,null,cursor).stream().map(record->{
                        var entry=json.createObjectNode().put("excerpt",record.path("text").asText()).put("source_kind",record.path("source_kind").asText()).put("purpose","NAVIGATION");
                        if(record.has("observed_at_ms"))entry.set("observed_at_ms",record.get("observed_at_ms"));
                        else {entry.set("start_ms",record.get("start_ms"));entry.set("end_ms",record.get("end_ms"));}
                        entry.putArray("navigation_ids").add(record.path("id").asText());return (JsonNode)entry;
                    }).toList();
                }
                case "read_video_range" -> {
                    var asset=asset(scope,args);coverage.set("asset",asset);field="records";
                    requireSource(asset);
                    long start=args.path("start_ms").asLong(),end=args.path("end_ms").asLong();
                    if(asset.path("duration_ms").isNull()||end>asset.path("duration_ms").asLong())throw parameter("RANGE_OUTSIDE_ASSET","读取范围超出素材时长");
                    coverage.put("start_ms",start).put("end_ms",end);
                    rows=evidence.read(scope,UUID.fromString(asset.path("id").asText()),start,end,cursor);
                }
                case "search_evidence" -> {
                    var conditions=conditions(scope,args);var selected=json.createArrayNode();
                    scope.assets().forEach(a->{for(var id:conditions.path("asset_ids"))if(a.path("id").equals(id))selected.add(a);});
                    coverage.set("assets",selected);coverage.set("source_kinds",conditions.path("source_kinds"));
                    boolean ready=false;for(var a:selected)for(var kind:conditions.path("source_kinds"))ready|="SUCCEEDED".equals(a.path("sources").path(kind.asText()).asText());
                    if(!ready)throw new ToolFailure("SOURCE_NOT_READY","SOURCE_NOT_READY","所需来源在当前问答快照中尚未就绪，请就绪后发送新消息");
                    // A nonzero cursor must refer to an existing ranking, not start a new paid query.
                    if(cursor>0&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM question_searches WHERE scope_id=? AND conditions_hash=? AND result_json IS NOT NULL)",Boolean.class,scope.id(),ToolJson.hash(conditions))))
                        throw parameter("CURSOR_INVALID","分页条件没有已完成的检索排名");
                    var ranking=evidence.search(scope,conditions,ToolJson.hash(conditions));
                    ranking.fields().forEachRemaining(entry->{if(!"hits".equals(entry.getKey()))data.set(entry.getKey(),entry.getValue());});
                    int total=(int)java.util.stream.StreamSupport.stream(ranking.path("hits").spliterator(),false)
                        .flatMap(hit->java.util.stream.StreamSupport.stream(hit.path("evidence_ids").spliterator(),false)).map(JsonNode::asText).distinct().count();
                    if(cursor>total)throw parameter("CURSOR_INVALID","分页位置超出排名");
                    rows=evidence.searchRecords(scope,ranking,cursor);field="records";
                }
                default -> throw parameter("TOOL_UNAVAILABLE","真实模式仅支持四个读取工具");
            }
            page(result,rows,field,cursor);
            // Results assembled during concurrent invalidation cannot be published as READY.
            scopes.requireDependencies(scope);
        } catch(ToolFailure failure) {
            result=error(request,failure.code,failure.category,failure.getMessage(),result.path("coverage"));
        } catch(QuestionEvidence.ReadFailure failure) {
            result=error(request,failure.code,failure.category,failure.getMessage(),result.path("coverage"));
        } catch(org.springframework.web.server.ResponseStatusException failure) {
            result=error(request,"READ_UNAVAILABLE","DEPENDENCY_UNAVAILABLE","读取依赖暂不可用",result.path("coverage"));
        }
        if(bytes(result)>MAX_BYTES)result=error(request,"RESULT_TOO_LARGE","MODEL_REPAIRABLE","反馈元数据超出 UTF-8 字节上限",json.createObjectNode());
        if(admitted)db.update("UPDATE question_tool_receipts SET result_json=?::jsonb WHERE scope_id=? AND call_id=?",result.toString(),scopeId,request.path("call_id").asText());
        return result;
    }
    private void page(ObjectNode result,List<JsonNode> rows,String field,int cursor) {
        var data=(ObjectNode)result.path("data");var coverage=(ObjectNode)result.path("coverage");
        var page="records".equals(field)?result.putArray("records"):data.putArray(field);
        data.putNull("next_cursor");coverage.put("cursor",cursor).put("page_complete",true);
        if("entries".equals(field))coverage.put("navigation_complete",true);
        int accepted=0;
        for(var row:rows.stream().limit(100).toList()) {
            page.add(row);accepted++;
            boolean more=accepted<rows.size();
            if(more)data.put("next_cursor",cursor+accepted);else data.putNull("next_cursor");
            coverage.put("page_complete",!more);
            if("entries".equals(field))coverage.put("navigation_complete",!more);
            if(bytes(result)>MAX_BYTES) {
                page.remove(page.size()-1);accepted--;
                if(accepted==0)throw parameter("RECORD_TOO_LARGE","单条记录及反馈元数据超出 UTF-8 字节上限，原文未截断");
                data.put("next_cursor",cursor+accepted);coverage.put("page_complete",false);
                if("entries".equals(field))coverage.put("navigation_complete",false);
                break;
            }
        }
        if(rows.size()>100){data.put("next_cursor",cursor+accepted);coverage.put("page_complete",false);if("entries".equals(field))coverage.put("navigation_complete",false);}
    }
    private JsonNode asset(Scope scope,JsonNode args) {
        for(var asset:scope.assets())if(asset.path("id").asText().equals(args.path("asset_id").asText()))return asset;
        throw new ToolFailure("ASSET_OUTSIDE_SCOPE","PERMISSION_DENIED","素材不在可信问答范围内");
    }
    private void requireSource(JsonNode asset) {
        if(asset.path("source_kinds").isEmpty())throw new ToolFailure("SOURCE_NOT_READY","SOURCE_NOT_READY","当前快照没有已就绪来源，请就绪后发送新消息");
    }
    private JsonNode conditions(Scope scope,JsonNode args) {
        var conditions=json.createObjectNode().put("query",args.path("query").asText());
        var selected=new TreeSet<String>();
        if(args.path("asset_ids").isArray())args.path("asset_ids").forEach(id->selected.add(id.asText()));
        else scope.assets().forEach(a->selected.add(a.path("id").asText()));
        for(String id:selected)if(java.util.stream.StreamSupport.stream(scope.assets().spliterator(),false).noneMatch(a->a.path("id").asText().equals(id)))
            throw new ToolFailure("ASSET_OUTSIDE_SCOPE","PERMISSION_DENIED","检索素材不在可信问答范围内");
        var ids=conditions.putArray("asset_ids");selected.forEach(ids::add);
        var kinds=new TreeSet<String>();if(args.has("source_kinds"))args.path("source_kinds").forEach(k->kinds.add(k.asText()));else kinds.add("SPEECH");
        var sources=conditions.putArray("source_kinds");kinds.forEach(sources::add);return conditions;
    }
    private void validateRequest(JsonNode request) {
        fields(request,Set.of("call_id","tool_name","arguments","request_hash"));
        for(String name:List.of("call_id","tool_name","request_hash"))if(!request.path(name).isTextual()||request.path(name).asText().isBlank()||request.path(name).asText().length()>200)
            throw parameter("INVALID_REQUEST","调用身份字段无效");
        if(!request.path("arguments").isObject())throw parameter("INVALID_ARGUMENTS","工具参数必须是对象");
        var hashInput=json.createObjectNode().put("tool",request.path("tool_name").asText());hashInput.set("args",request.path("arguments"));
        if(!ToolJson.hash(hashInput).equals(request.path("request_hash").asText()))throw new ToolFailure("REQUEST_HASH_MISMATCH","IDENTITY_CONFLICT","工具请求哈希核验失败");
    }
    private void validateArguments(String tool,JsonNode args) {
        Set<String> allowed=switch(tool) {
            case "list_assets" -> Set.of("cursor");
            case "get_video_overview" -> Set.of("cursor","asset_id");
            case "read_video_range" -> Set.of("cursor","asset_id","start_ms","end_ms");
            case "search_evidence" -> Set.of("cursor","query","source_kinds","asset_ids");
            default -> throw parameter("TOOL_UNAVAILABLE","真实模式仅支持四个读取工具");
        };
        fields(args,allowed);
        if(args.has("cursor")&&(!args.path("cursor").isIntegralNumber()||!args.path("cursor").canConvertToInt()||args.path("cursor").asInt()<0))throw parameter("INVALID_ARGUMENTS","cursor 必须是非负整数");
        if(allowed.contains("asset_id"))uuid(args.path("asset_id"));
        if("read_video_range".equals(tool)) {
            for(String key:List.of("start_ms","end_ms"))if(!args.path(key).isIntegralNumber()||!args.path(key).canConvertToLong())throw parameter("INVALID_ARGUMENTS","时间必须是整数毫秒");
            if(args.path("start_ms").asLong()<0||args.path("end_ms").asLong()<=args.path("start_ms").asLong())throw parameter("INVALID_ARGUMENTS","读取必须是有效半开时间范围");
        }
        if("search_evidence".equals(tool)) {
            if(!args.path("query").isTextual()||args.path("query").asText().isBlank()||args.path("query").asText().codePointCount(0,args.path("query").asText().length())>256)throw parameter("INVALID_ARGUMENTS","query 必须是1至256字符文本");
            if(args.has("source_kinds")) {
                if(!args.path("source_kinds").isArray()||args.path("source_kinds").isEmpty()||args.path("source_kinds").size()>3)throw parameter("INVALID_ARGUMENTS","来源列表无效");
                for(var kind:args.path("source_kinds"))if(!kind.isTextual()||!KINDS.contains(kind.asText()))throw parameter("INVALID_ARGUMENTS","来源类型无效");
            }
            if(args.has("asset_ids")&&!args.path("asset_ids").isNull()) {
                if(!args.path("asset_ids").isArray()||args.path("asset_ids").isEmpty())throw parameter("INVALID_ARGUMENTS","素材列表不能为空");
                args.path("asset_ids").forEach(this::uuid);
            }
        }
    }
    private void uuid(JsonNode value){try{if(!value.isTextual()||!UUID.fromString(value.asText()).toString().equals(value.asText()))throw new IllegalArgumentException();}catch(IllegalArgumentException invalid){throw parameter("INVALID_ARGUMENTS","素材身份必须是标准 UUID");}}
    private void fields(JsonNode node,Set<String> allowed){if(!node.isObject())throw parameter("INVALID_ARGUMENTS","参数必须是对象");node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw parameter("INVALID_ARGUMENTS","存在未知字段: "+key);});}
    private ToolFailure parameter(String code,String message){return new ToolFailure(code,"MODEL_REPAIRABLE",message);}
    private ObjectNode base(JsonNode request) {
        var result=json.createObjectNode().put("call_id",request.path("call_id").asText()).put("tool_name",request.path("tool_name").asText()).put("request_hash",request.path("request_hash").asText()).put("status","READY").put("mock",false);
        result.putObject("data");result.putObject("coverage");result.putArray("records");result.putNull("dependency");result.putNull("error");return result;
    }
    private ObjectNode error(JsonNode request,String code,String category,String message,JsonNode coverage){var result=base(request).put("status","ERROR");result.set("coverage",coverage);result.putObject("error").put("code",code).put("category",category).put("message",message);return result;}
    private int bytes(JsonNode node){return node.toString().getBytes(StandardCharsets.UTF_8).length;}
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}

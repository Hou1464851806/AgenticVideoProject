package com.agenticvideo.business.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Resolves a chat operation against its sent snapshot, then reuses the existing media capabilities. */
@Service
public class ConversationProduction {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final PreviewRequests previews;
    private final ExportRequests exports;
    public ConversationProduction(JdbcTemplate database,ObjectMapper json,PreviewRequests previews,ExportRequests exports){this.database=database;this.json=json;this.previews=previews;this.exports=exports;}
    public boolean handles(String intent){return Set.of("PREVIEW","CONFIRM","EXPORT").contains(intent);}
    public void validate(JsonNode input,JsonNode request){
        JsonNode authority=input;
        if("ANSWER".equals(request.path("questionAction").asText())&&request.path("intent").asText().equals(input.path("pendingQuestion").path("originalRequest").path("productionIntent").asText()))authority=input.path("pendingQuestion").path("originalRequest");
        String intent=request.path("intent").asText(),content=authority.path("content").asText().replaceAll("\\s+","");
        boolean authorized=switch(intent){case "PREVIEW" -> content.contains("预览")||content.matches(".*(?:看看|看下|看一下).*效果.*");case "CONFIRM" -> content.matches(".*(?:确认|定稿|就用这版).*" );case "EXPORT" -> content.contains("导出")||content.matches(".*(?:生成|输出).*MP4.*");default -> false;};
        if(!authorized||content.matches(".*[“”‘’\"\\?？].*")||content.matches(".*(?:查询|查找|搜索|原句|为什么|能否|能不能|做完|完成后|以后|如果|不要|不需要|不用|别|先不|不导出|不预览|不确认).*" )||content.endsWith("吗。")||content.endsWith("吗")||content.contains("确认")&&content.contains("导出"))throw new ResponseStatusException(BAD_REQUEST,"尚未收到独立、明确的当前制作指令");
        JsonNode target=request.path("target");
        if("ANSWER".equals(request.path("questionAction").asText())&&input.path("pendingQuestion").path("fields").toString().contains("\"target\"")&&!target.has("revisionNumber")&&!target.has("outputNumber")){
            boolean referenced=false;for(var ref:input.path("references"))if("PLAN_REVISION".equals(ref.path("type").asText()))referenced=true;
            if(!referenced)throw new ResponseStatusException(BAD_REQUEST,"待澄清的操作目标尚未明确，不能猜测继续");
        }
        if(request.has("target")){
            if(!target.isObject())throw new ResponseStatusException(BAD_REQUEST,"操作目标必须为对象");
            target.fieldNames().forEachRemaining(name->{if(!Set.of("revisionNumber","outputNumber","profile").contains(name))throw new ResponseStatusException(BAD_REQUEST,"操作目标字段不受支持");});
            for(String number:List.of("revisionNumber","outputNumber"))if(target.has(number)&&(!target.path(number).isIntegralNumber()||target.path(number).asInt()<1))throw new ResponseStatusException(BAD_REQUEST,"版本与成片序号须为正整数");
            String selection=input.path("content").asText();
            if(target.has("revisionNumber")&&!explicitNumber(selection,target.path("revisionNumber").asInt(),true))throw new ResponseStatusException(BAD_REQUEST,"不能用推测的版本覆盖发送时的对象");
            if(target.has("outputNumber")&&!explicitNumber(selection,target.path("outputNumber").asInt(),false))throw new ResponseStatusException(BAD_REQUEST,"请明确要操作的成片序号");
            if("FULL_HD_1080".equals(target.path("profile").asText())&&!selection.contains("1080"))throw new ResponseStatusException(BAD_REQUEST,"1080p需要用户明确选择");
            if(target.has("profile")&&!Set.of("HD_720","FULL_HD_1080").contains(target.path("profile").asText()))throw new ResponseStatusException(BAD_REQUEST,"导出仅支持720p或1080p");
        }
    }
    private boolean explicitNumber(String content,int number,boolean revision){
        String text=content.replaceAll("\\s+","");
        if(revision&&java.util.regex.Pattern.compile("(?i)V"+number+"(?![0-9])").matcher(text).find())return true;
        String unit=revision?"(?:版|版本)":"(?:条|个成片)";
        if(java.util.regex.Pattern.compile("(?<![0-9])(?:第)?"+number+unit+"|"+unit+number+"(?![0-9])").matcher(text).find())return true;
        var chinese=List.of("一","二","三","四","五","六","七","八","九","十");
        return number<=chinese.size()&&java.util.regex.Pattern.compile("(?<![零一二三四五六七八九十百千两])(?:第)?"+chinese.get(number-1)+unit).matcher(text).find();
    }
    private static final String SENT_VERSIONS = """
        FROM plan_revisions r WHERE r.project_id=?
        AND ((r.plan_id=? AND r.revision_number<=?) OR r.id IN (
          SELECT (ref->>'id')::uuid FROM conversation_messages m,
          LATERAL jsonb_array_elements(m.references_json) ref
          WHERE m.conversation_id=? AND m.message_seq<=? AND m.role='ASSISTANT'
            AND ref->>'type'='PLAN_REVISION'))
        """;
    public JsonNode snapshot(UUID conversation,UUID project,Object viewedPlan){
        long sequence=database.queryForObject("SELECT COALESCE(MAX(message_seq),0) FROM conversation_messages WHERE conversation_id=?",Long.class,conversation);
        long viewedMaximum=database.queryForObject("SELECT COALESCE(MAX(revision_number),0) FROM plan_revisions WHERE project_id=? AND plan_id=?",Long.class,project,viewedPlan);
        var versions=database.queryForList("SELECT r.id,r.plan_id,r.revision_number,r.content_json "+SENT_VERSIONS+" ORDER BY r.created_at DESC,r.id DESC LIMIT 12",project,viewedPlan,viewedMaximum,conversation,sequence);
        var candidates=new ArrayList<Object>();
        for(var row:versions){var outputs=new ArrayList<Object>();for(var output:parse(row.get("content_json")).path("outputs"))outputs.add(Map.of("outputId",output.path("outputId").asText(),"title",output.path("title").asText()));candidates.add(Map.of("revisionId",row.get("id"),"planId",row.get("plan_id"),"revisionNumber",row.get("revision_number"),"outputs",outputs));}
        var latest=database.queryForList("SELECT ref->>'id' AS id FROM conversation_messages m,LATERAL jsonb_array_elements(m.references_json) ref WHERE m.conversation_id=? AND m.message_seq<=? AND m.role='ASSISTANT' AND ref->>'type'='PLAN_REVISION' ORDER BY m.message_seq DESC LIMIT 1",conversation,sequence);
        var result=new LinkedHashMap<String,Object>();result.put("candidates",candidates);result.put("viewMaxRevisionNumber",viewedMaximum);result.put("cutoffSequence",sequence);if(!latest.isEmpty())result.put("latestRevisionId",latest.get(0).get("id"));return json.valueToTree(result);
    }
    public JsonNode bind(JsonNode input,JsonNode request){
        JsonNode basis=input;
        if("ANSWER".equals(request.path("questionAction").asText())&&input.path("pendingQuestion").path("originalRequest").has("production"))basis=input.path("pendingQuestion").path("originalRequest");
        var selector=request.path("target");var refs=new LinkedHashSet<String>();for(var ref:input.path("references"))if("PLAN_REVISION".equals(ref.path("type").asText()))refs.add(ref.path("id").asText());
        if(refs.size()>1)return question("你引用了多个版本，请明确要操作哪一个版本。");
        String revision=refs.isEmpty()?null:refs.iterator().next();
        if(revision==null&&selector.has("revisionNumber")){
            JsonNode sent=basis.path("production");
            // Resolve outside the small model catalogue, but never outside the sent snapshot.
            var matches=database.queryForList("SELECT r.id "+SENT_VERSIONS+" AND r.revision_number=? LIMIT 2",identifier(input.path("projectId").asText()),basis.path("view").path("planId").isTextual()?identifier(basis.path("view").path("planId").asText()):null,sent.path("viewMaxRevisionNumber").asLong(),identifier(input.path("conversationId").asText()),sent.path("cutoffSequence").asLong(),selector.path("revisionNumber").asInt());
            if(matches.size()!=1)return question("指定版本不存在或对应多个方案，请明确引用要操作的版本。");revision=matches.get(0).get("id").toString();
        }
        if(revision==null)revision=basis.path("view").path("revisionId").asText(null);
        if(revision==null)revision=basis.path("production").path("latestRevisionId").asText(null);
        if(revision==null)return question("还没有可操作的方案版本，请先生成方案或明确引用已有版本。");
        UUID id=identifier(revision),project=identifier(input.path("projectId").asText());
        var rows=database.queryForList("SELECT id,plan_id,revision_number,content_json FROM plan_revisions WHERE id=? AND project_id=?",id,project);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"方案版本不存在或无权访问");
        var row=rows.get(0);
        if(!refs.isEmpty()&&selector.has("revisionNumber")&&selector.path("revisionNumber").asInt()!=((Number)row.get("revision_number")).intValue())return question("明确引用和文字指定的版本冲突，请只指定一个版本。");
        JsonNode outputs=parse(row.get("content_json")).path("outputs");
        int output=selector.path("outputNumber").asInt(outputs.size()==1?1:0)-1;
        if(output<0||output>=outputs.size())return question("请明确该版本中的成片序号，例如第一条。");
        var target=new LinkedHashMap<String,Object>(Map.of("revisionId",id,"planId",row.get("plan_id"),"revisionNumber",row.get("revision_number"),"outputId",outputs.get(output).path("outputId").asText(),"profile","PREVIEW".equals(request.path("intent").asText())?"LOW_360":selector.path("profile").asText("HD_720")));
        if("EXPORT".equals(request.path("intent").asText())){JsonNode confirmations=json.valueToTree(exports.confirmations(id)).path("items");if(!confirmations.isEmpty())target.put("confirmationId",confirmations.get(0).path("confirmationId").asText());}
        return json.valueToTree(target);
    }
    private JsonNode question(String prompt){return json.valueToTree(Map.of("question",Map.of("prompt",prompt,"reason","PRODUCTION_TARGET_UNCLEAR","fields",List.of("target"))));}
    public record Preparation(JsonNode target,ExportRequests.Prepared sources) {}
    /** Remote storage observation happens before taking authoritative task/publication locks. */
    public Object prepare(JsonNode input){
        if(!handles(input.path("intent").asText())||!input.has("productionTarget"))throw new ResponseStatusException(CONFLICT,"请先接纳并固定操作对象");
        JsonNode target=input.path("productionTarget");ExportRequests.Prepared sources=null;
        if("EXPORT".equals(input.path("intent").asText())&&target.has("confirmationId"))sources=exports.prepareFromMessage(identifier(target.path("revisionId").asText()),identifier(target.path("outputId").asText()),identifier(input.path("messageId").asText()));
        return new Preparation(target,sources);
    }
    public Object execute(JsonNode input,Object prepared){
        if(!(prepared instanceof Preparation preparation)||!preparation.target().equals(input.path("productionTarget")))throw new ResponseStatusException(CONFLICT,"执行对象与固定版本不一致");
        JsonNode target=preparation.target();UUID revision=identifier(target.path("revisionId").asText()),message=identifier(input.path("messageId").asText()),actor=database.queryForObject("SELECT owner_user_id FROM projects WHERE id=?",UUID.class,identifier(input.path("projectId").asText()));
        String intent=input.path("intent").asText();JsonNode receipt;String type,content;
        if("CONFIRM".equals(intent)){
            receipt=json.valueToTree(exports.confirmFromMessage(revision,actor,"message-"+message,json.valueToTree(Map.of("explicitIntent","CONFIRM")),message));type="CONFIRMATION";
            content="已确认方案 V"+target.path("revisionNumber").asInt()+"；尚未导出，正式成片需要另发明确导出指令。";
        }else if("EXPORT".equals(intent)){
            if(!target.has("confirmationId"))return Map.of("content","方案 V"+target.path("revisionNumber").asInt()+" 尚未确认。请先明确确认这版，再另发导出指令。","references",List.of());
            receipt=json.valueToTree(exports.requestFromMessage(revision,actor,"message-"+message,json.valueToTree(Map.of("explicitIntent","EXPORT","outputIds",List.of(target.path("outputId").asText()),"confirmationId",target.path("confirmationId").asText(),"exportProfile",target.path("profile").asText())),message,preparation.sources()));type="EXPORT_REQUEST";
            content="已接纳方案 V"+target.path("revisionNumber").asInt()+" 的正式导出，固定成片和 "+("FULL_HD_1080".equals(target.path("profile").asText())?"1080p":"720p")+" 规格。";
        }else{
            receipt=json.valueToTree(previews.request(revision,json.valueToTree(Map.of("outputId",target.path("outputId").asText(),"previewProfile","LOW_360")),"message-"+message));type="PREVIEW_REQUEST";
            content="已接纳方案 V"+target.path("revisionNumber").asInt()+" 的低清预览；固定使用这条成片，未确认或导出正式视频。";
        }
        var reference=new LinkedHashMap<String,Object>(Map.of("type",type,"id",receipt.path("CONFIRMATION".equals(type)?"confirmationId":"requestId").asText(),"revisionId",revision,"planId",target.path("planId").asText()));if(receipt.has("taskId"))reference.put("taskId",receipt.path("taskId").asText());
        return Map.of("content",content,"references",List.of(reference));
    }
    public List<Object> references(UUID task){
        var rows=database.queryForList("SELECT result_json FROM conversation_tool_receipts WHERE task_id=? AND status='SUCCEEDED' AND request_json->>'name'='execute_production_command'",task);
        var result=new ArrayList<Object>();if(!rows.isEmpty())for(var ref:parse(rows.get(0).get("result_json")).path("references"))result.add(ref);return result;
    }
    private UUID identifier(String value){try{return UUID.fromString(value);}catch(IllegalArgumentException invalid){throw new ResponseStatusException(BAD_REQUEST,"制作对象标识无效");}}
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}

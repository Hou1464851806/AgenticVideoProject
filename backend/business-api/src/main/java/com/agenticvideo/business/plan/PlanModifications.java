package com.agenticvideo.business.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Binds the user's edit scope to the sent version before taking the plan's edit lock. */
@Service
public class PlanModifications {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final EditPlans plans;
    private final com.agenticvideo.business.render.ConversationProduction production;
    public PlanModifications(JdbcTemplate database,ObjectMapper json,EditPlans plans,com.agenticvideo.business.render.ConversationProduction production){this.database=database;this.json=json;this.plans=plans;this.production=production;}

    public void validate(JsonNode input,JsonNode request){
        if(request.path("newRequirement").asBoolean())throw new ResponseStatusException(BAD_REQUEST,"修改须保留原方案硬约束；请另建需求以改变条件");
        var target=request.path("target");
        if(!target.isMissingNode()&&!target.isObject())throw new ResponseStatusException(BAD_REQUEST,"修改目标须为对象");
        target.fieldNames().forEachRemaining(field->{if(!Set.of("revisionNumber","outputNumber","clipNumber","preserveClipNumbers","profile").contains(field))throw new ResponseStatusException(BAD_REQUEST,"修改目标字段不受支持");});
        for(String field:List.of("revisionNumber","outputNumber","clipNumber"))if(target.has(field)&&(!target.path(field).isIntegralNumber()||!target.path(field).canConvertToInt()||target.path(field).asInt()<1))throw new ResponseStatusException(BAD_REQUEST,"修改目标序号须为正整数");
        if(target.has("preserveClipNumbers")){
            if(!target.path("preserveClipNumbers").isArray()||target.path("preserveClipNumbers").size()>10)throw new ResponseStatusException(BAD_REQUEST,"保留片段须为序号列表");
            for(var number:target.path("preserveClipNumbers"))if(!number.isIntegralNumber()||!number.canConvertToInt()||number.asInt()<1)throw new ResponseStatusException(BAD_REQUEST,"保留片段序号无效");
        }
        String content=input.path("content").asText();
        if(target.has("revisionNumber")&&!explicit(content,target.path("revisionNumber").asInt(),"版|版本",true))throw new ResponseStatusException(BAD_REQUEST,"版本须来自用户明确引用");
        if(target.has("outputNumber")&&!explicit(content,target.path("outputNumber").asInt(),"条|个成片|个输出",false))throw new ResponseStatusException(BAD_REQUEST,"成片须来自用户明确引用");
        if(target.has("clipNumber")&&!explicit(content,target.path("clipNumber").asInt(),"段|个片段",false))throw new ResponseStatusException(BAD_REQUEST,"片段须来自用户明确引用");
        if(target.has("clipNumber")&&target.has("preserveClipNumbers"))throw new ResponseStatusException(BAD_REQUEST,"定向替换与整体紧凑化须分别指定");
    }
    private boolean explicit(String text,int number,String unit,boolean version){
        if(version&&java.util.regex.Pattern.compile("(?i)V"+number+"(?![0-9])").matcher(text).find())return true;
        if(java.util.regex.Pattern.compile("(?<![0-9])(?:第)?"+number+"(?:"+unit+")").matcher(text).find())return true;
        var chinese=List.of("一","二","三","四","五","六","七","八","九","十");
        return number<=10&&java.util.regex.Pattern.compile("(?<![零一二三四五六七八九十百千两])(?:第)?"+chinese.get(number-1)+"(?:"+unit+")").matcher(text).find();
    }
    public JsonNode bind(Map<String,Object> task,JsonNode input,JsonNode request){
        validate(input,request);
        ObjectNode sent=input.deepCopy();sent.put("projectId",task.get("project_id").toString());
        JsonNode basis=input;
        if("ANSWER".equals(request.path("questionAction").asText())){
            basis=input.path("pendingQuestion").path("originalRequest");
            if(!"MODIFY_PLAN".equals(basis.path("modificationIntent").asText())&&!basis.path("editing").has("modification"))throw new ResponseStatusException(BAD_REQUEST,"原问题不是方案修改需求");
            // Restore the original sent snapshot, never the view selected while waiting.
            for(String field:List.of("view","production","assetIds"))if(basis.has(field))sent.set(field,basis.path(field));
            if(!input.path("references").isEmpty()){
                for(var ref:input.path("references"))if("PLAN_REVISION".equals(ref.path("type").asText())&&!availableAtSend(task,basis,UUID.fromString(input.path("conversationId").asText()),ref.path("id").asText()))throw new ResponseStatusException(CONFLICT,"澄清只能选择原发送时已有的版本");
            }else if(basis.has("references"))sent.set("references",basis.path("references"));
            if(basis.has("editing")){
                if(basis.has("requirementId"))sent.set("requirementId",basis.path("requirementId"));
                validateConditions(request,basis);
                ObjectNode editing=basis.path("editing").deepCopy();
                sent.put("intent","MODIFY_PLAN");sent.put("modificationIntent","MODIFY_PLAN");
                if(basis.has("brief"))sent.set("brief",basis.path("brief"));
                sent.set("editing",plans.allocate((UUID)task.get("project_id"),(UUID)task.get("id"),editing,sent.path("assetIds")));
                return sent;
            }
        }
        JsonNode answerTarget=request.path("target");
        if(basis!=input&&input.path("references").isEmpty())narrowReferences(sent,answerTarget);
        ObjectNode effective=(ObjectNode)request.deepCopy();
        ObjectNode target=json.createObjectNode();
        if(basis!=input&&basis.path("modificationSelector").isObject())target.setAll((ObjectNode)basis.path("modificationSelector"));
        if(basis!=input)for(var ref:input.path("references"))if("PLAN_REVISION".equals(ref.path("type").asText())){
            if(!answerTarget.has("revisionNumber"))target.remove("revisionNumber");
            if(ref.has("outputId")&&!answerTarget.has("outputNumber"))target.remove("outputNumber");
            if(ref.has("clipId")&&!answerTarget.has("clipNumber"))target.remove("clipNumber");
        }
        if(request.path("target").isObject())target.setAll((ObjectNode)request.path("target"));
        ObjectNode changes=json.createObjectNode();
        if(basis!=input&&basis.path("modificationFields").isObject())changes.setAll((ObjectNode)basis.path("modificationFields"));
        changes.setAll((ObjectNode)request.path("fields"));effective.set("fields",changes);
        effective.set("target",target);request=effective;sent.set("modificationSelector",target);sent.set("modificationFields",changes);
        ObjectNode selector=json.createObjectNode();
        for(String field:List.of("revisionNumber","outputNumber"))if(request.path("target").has(field))selector.set(field,request.path("target").path(field));
        JsonNode resolved=production.bind(sent,json.valueToTree(Map.of("intent","MODIFY_PLAN","target",selector)));
        sent.put("modificationIntent","MODIFY_PLAN");
        if(resolved.has("question")){sent.put("intent","CLARIFY");sent.set("productionQuestion",resolved.path("question"));return sent;}
        UUID revision=UUID.fromString(resolved.path("revisionId").asText());
        var row=database.queryForMap("SELECT r.content_json,coalesce(x.input_json,t.input_json) AS source_input FROM plan_revisions r JOIN tasks t ON t.id=r.source_task_id LEFT JOIN conversation_execution_requests x ON x.task_id=t.id WHERE r.id=? AND r.project_id=?",revision,task.get("project_id"));
        JsonNode content=parse(row.get("content_json")),sourceInput=parse(row.get("source_input"));
        if(!sourceInput.path("editing").isObject())throw new ResponseStatusException(CONFLICT,"原版本缺少可验证的制作硬约束，请重新生成方案");
        validateConditions(request,sourceInput);
        ObjectNode editing=sourceInput.path("editing").deepCopy();
        sent.put("requirementId",sourceInput.path("requirementId").asText(resolved.path("planId").asText()));
        sent.set("brief",sourceInput.path("brief").isObject()?sourceInput.path("brief"):json.createObjectNode());
        var forbidden=new HashSet<String>();for(var id:sourceInput.path("brief").path("fields").path("forbiddenAssetIds").path("value"))forbidden.add(id.asText());
        var originalScope=new HashSet<String>();for(var id:sourceInput.path("assetIds"))originalScope.add(id.asText());
        boolean explicitScope=sourceInput.path("brief").path("fields").path("assetIds").has("value");
        var scope=json.createArrayNode();for(var id:sent.path("assetIds"))if(!forbidden.contains(id.asText())&&(!explicitScope||originalScope.contains(id.asText())))scope.add(id);
        sent.set("assetIds",scope);
        editing.remove("modification");editing.put("planId",resolved.path("planId").asText());editing.put("baseRevisionId",revision.toString());
        JsonNode selected=null;for(var candidate:content.path("outputs"))if(candidate.path("outputId").asText().equals(resolved.path("outputId").asText()))selected=candidate;
        if(selected==null)throw new ResponseStatusException(CONFLICT,"原输出已不存在");
        var refs=new LinkedHashSet<String>();
        for(var ref:sent.path("references"))if("PLAN_REVISION".equals(ref.path("type").asText())&&ref.has("clipId"))refs.add(ref.path("clipId").asText());
        int clip=target.path("clipNumber").asInt(0);
        if(clip>selected.path("clips").size())return question(sent,"指定片段不存在，请明确原版本中的片段序号。");
        if(clip>0)refs.add(selected.path("clips").get(clip-1).path("clipId").asText());
        if(refs.size()>1)return question(sent,"引用的片段与文字目标不一致，请只指定一个替换片段。");
        String command=basis.path("content").asText();
        if(!command.matches(".*(?:替换|更换|换掉|换成|紧凑|精简|缩短).*"))command=input.path("content").asText();
        command=command.replaceAll("\\s+","");
        boolean replace=command.matches(".*(?:替换|更换|换掉|换成).*"),compact=!replace&&command.matches(".*(?:紧凑|精简|缩短).*" );
        if(!compact&&!replace)return question(sent,"请明确需要替换哪个片段，或如何精简当前成片。");
        if(replace&&refs.isEmpty())return question(sent,"请明确要替换的片段，例如第二段，或引用一个片段。");
        if(compact&&!refs.isEmpty())return question(sent,"请分别指定定向替换或整条成片紧凑化。");
        var mutable=json.createArrayNode();var preserved=new HashSet<Integer>();
        for(var number:target.path("preserveClipNumbers")){
            if(!explicit((answerTarget.has("preserveClipNumbers")?input:basis).path("content").asText(),number.asInt(),"段|个片段",false)&&!explicit(input.path("content").asText(),number.asInt(),"段|个片段",false))return question(sent,"请明确要完整保留的片段序号，不能猜测结论位置。");
            preserved.add(number.asInt());
        }
        if(Collections.max(preserved.isEmpty()?Set.of(0):preserved)>selected.path("clips").size())return question(sent,"要保留的片段不存在，请核查序号。");
        if(compact&&command.contains("保留")&&preserved.isEmpty())return question(sent,"需要完整保留哪个片段？请给出结论所在片段的序号。");
        for(int index=0;index<selected.path("clips").size();index++){
            String id=selected.path("clips").get(index).path("clipId").asText();
            if(compact?!preserved.contains(index+1):refs.contains(id))mutable.add(id);
        }
        if(mutable.isEmpty())return question(sent,"没有可修改的片段，请重新明确修改范围。");
        editing.set("modification",json.valueToTree(Map.of("outputId",resolved.path("outputId").asText(),"clipIds",mutable,"mode",compact?"COMPACT":"REPLACE","baseContent",content,"revisionNumber",resolved.path("revisionNumber").asLong())));
        sent.put("intent","MODIFY_PLAN");sent.set("editing",plans.allocate((UUID)task.get("project_id"),(UUID)task.get("id"),editing,sent.path("assetIds")));
        return sent;
    }
    private boolean availableAtSend(Map<String,Object> task,JsonNode basis,UUID conversation,String revision){
        for(var ref:basis.path("references"))if("PLAN_REVISION".equals(ref.path("type").asText())&&revision.equals(ref.path("id").asText()))return true;
        return Boolean.TRUE.equals(database.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM plan_revisions r WHERE r.id=? AND r.project_id=?
            AND ((r.plan_id=? AND r.revision_number<=?) OR r.id IN (
              SELECT (ref->>'id')::uuid FROM conversation_messages m,
              LATERAL jsonb_array_elements(m.references_json) ref
              WHERE m.conversation_id=? AND m.message_seq<=? AND m.role='ASSISTANT'
                AND ref->>'type'='PLAN_REVISION')))
            """,Boolean.class,UUID.fromString(revision),task.get("project_id"),basis.path("view").path("planId").isTextual()?UUID.fromString(basis.path("view").path("planId").asText()):null,basis.path("production").path("viewMaxRevisionNumber").asLong(),conversation,basis.path("production").path("cutoffSequence").asLong()));
    }
    /** An explicit answer can narrow conflicting references without refreshing the sent catalogue. */
    private void narrowReferences(ObjectNode sent,JsonNode answer){
        var refs=json.createArrayNode();
        for(var ref:sent.path("references")){
            if(!"PLAN_REVISION".equals(ref.path("type").asText())){refs.add(ref);continue;}
            var rows=database.queryForList("SELECT revision_number,content_json FROM plan_revisions WHERE id=? AND project_id=?",UUID.fromString(ref.path("id").asText()),UUID.fromString(sent.path("projectId").asText()));
            if(rows.isEmpty())continue;
            if(answer.has("revisionNumber")&&answer.path("revisionNumber").asInt()!=((Number)rows.get(0).get("revision_number")).intValue())continue;
            ObjectNode narrowed=ref.deepCopy();
            if(answer.has("outputNumber")){narrowed.remove("outputId");narrowed.remove("clipId");}
            else if(answer.has("clipNumber"))narrowed.remove("clipId");
            refs.add(narrowed);
        }
        sent.set("references",refs);
    }
    private void validateConditions(JsonNode request,JsonNode source){
        JsonNode editing=source.path("editing");
        if(request.path("target").has("profile")&&!request.path("target").path("profile").equals(editing.path("profile")))throw new ResponseStatusException(CONFLICT,"修改不能改变原画幅");
        request.path("fields").fields().forEachRemaining(field->{
            JsonNode value=field.getValue().path("value");boolean same=switch(field.getKey()){
                case "duration" -> value.path("minMs").asLong(-1)==editing.path("minDurationMs").asLong()&&value.path("maxMs").asLong(-1)==editing.path("maxDurationMs").asLong();
                case "topic","profile","orderedAssetIds","forbiddenContent" -> value.equals(editing.path(field.getKey()));
                case "count" -> value.asInt(-1)==source.path("brief").path("fields").path("count").path("value").asInt(1);
                case "assetIds" -> value.equals(source.path("assetIds"));
                case "forbiddenAssetIds" -> value.equals(source.path("brief").path("fields").path("forbiddenAssetIds").path("value"));
                default -> false;
            };
            if(!same)throw new ResponseStatusException(CONFLICT,"局部修改须保留原硬约束；如需改变条件，请明确开始新制作需求");
        });
    }
    private JsonNode question(ObjectNode input,String prompt){input.put("intent","CLARIFY");input.set("productionQuestion",json.valueToTree(Map.of("prompt",prompt,"reason","MODIFICATION_TARGET_UNCLEAR","fields",List.of("target"))));return input;}
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}

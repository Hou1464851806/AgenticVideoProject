package com.agenticvideo.business.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Business-owned proposal identities and publication; model candidates are not revisions. */
@Service
public class EditPlans {
    private final JdbcTemplate database;
    private final ObjectMapper json;
    private final EditPlanValidation validation;
    public EditPlans(JdbcTemplate database, ObjectMapper json, EditPlanValidation validation) {
        this.database = database;
        this.json = json;
        this.validation = validation;
    }

    public JsonNode allocate(UUID project, UUID task, JsonNode editing, JsonNode scope) {
        if (!editing.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "制作条件必须是对象");
        editing.fieldNames().forEachRemaining(field -> {
            if (!Set.of("topic", "minDurationMs", "maxDurationMs", "profile", "planId", "baseRevisionId", "kind", "orderedAssetIds", "forbiddenContent", "modification").contains(field))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "制作条件字段不受支持");
        });
        if(editing.has("forbiddenContent")){
            var forbidden=editing.path("forbiddenContent");
            if(!forbidden.isArray()||forbidden.size()>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"禁用内容须为最多100项明确文本");
            for(var text:forbidden)if(!text.isTextual()||text.asText().isBlank()||text.asText().length()>256)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"禁用内容必须为明确文本");
        }
        String topic = editing.path("topic").asText().strip();
        long minimum = editing.path("minDurationMs").asLong(-1), maximum = editing.path("maxDurationMs").asLong(-1);
        if (!editing.path("topic").isTextual() || topic.isBlank() || topic.length() > 120
                || !editing.path("minDurationMs").isIntegralNumber() || !editing.path("maxDurationMs").isIntegralNumber()
                || !editing.path("minDurationMs").canConvertToLong() || !editing.path("maxDurationMs").canConvertToLong()
                || minimum < 1 || maximum < minimum || maximum > 3600000
                || !Set.of("LANDSCAPE_720", "PORTRAIT_720").contains(editing.path("profile").asText()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请明确主题、合法的硬时长范围与支持的画幅");
        if (editing.has("kind") && !Set.of("SPEECH", "MONTAGE").contains(editing.path("kind").asText()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "制作类型不受支持");
        if ("MONTAGE".equals(editing.path("kind").asText())) {
            var order=editing.path("orderedAssetIds");var authorized=new java.util.HashSet<String>();
            for(var asset:scope)authorized.add(asset.asText());
            var seen=new java.util.HashSet<String>();
            if(!order.isArray()||order.size()<2||order.size()>10)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"混剪须明确 2 至 10 个素材的顺序");
            for(var asset:order)if(!asset.isTextual()||!authorized.contains(asset.asText())||!seen.add(asset.asText()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"混剪顺序只能包含固定范围内不重复的素材");
        } else if(editing.has("orderedAssetIds"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"素材顺序仅用于混剪制作");
        UUID plan;
        if (!editing.has("planId")) {
            if (editing.hasNonNull("baseRevisionId")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新方案不能引用已有基准版本");
            plan = UUID.randomUUID();
            database.update("INSERT INTO edit_plans(id,project_id,title,editing_task_id) VALUES (?,?,?,?)", plan, project, topic, task);
        } else {
            try { plan = UUID.fromString(editing.path("planId").asText()); }
            catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "方案标识不符合要求"); }
            var rows = database.queryForList("SELECT * FROM edit_plans WHERE id=? AND project_id=? FOR UPDATE", plan, project);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "方案不存在或无权访问");
            if (rows.get(0).get("editing_task_id") != null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "方案正在编辑；可以继续查看历史，完成或取消后再修改");
            if (editing.hasNonNull("baseRevisionId")) {
                UUID base;
                try { base = UUID.fromString(editing.path("baseRevisionId").asText()); }
                catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基准版本标识不符合要求"); }
                if (database.queryForObject("SELECT count(*) FROM plan_revisions WHERE id=? AND plan_id=?", Integer.class, base, plan) != 1)
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "基准版本不属于当前方案");
            } else if (((Number)rows.get(0).get("next_revision_number")).longValue() != 1)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "已有版本的方案必须明确发送基准版本");
            database.update("UPDATE edit_plans SET editing_task_id=?,editing_attempt_id=NULL WHERE id=?", task, plan);
        }
        if(editing.has("modification")){
            if(!editing.hasNonNull("baseRevisionId"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"修改必须固定基准版本");
            var base=database.queryForMap("SELECT r.content_json,coalesce(x.input_json,t.input_json) AS source_input FROM plan_revisions r JOIN tasks t ON t.id=r.source_task_id LEFT JOIN conversation_execution_requests x ON x.task_id=t.id WHERE r.id=? AND r.plan_id=?",UUID.fromString(editing.path("baseRevisionId").asText()),plan);
            JsonNode content=(JsonNode)parse(base.get("content_json")),conditions=((JsonNode)parse(base.get("source_input"))).path("editing");
            if(!content.equals(editing.path("modification").path("baseContent")))throw new ResponseStatusException(HttpStatus.CONFLICT,"修改快照与原版本不一致");
            JsonNode source=(JsonNode)parse(base.get("source_input"));
            var forbidden=new java.util.HashSet<String>();for(var id:source.path("brief").path("fields").path("forbiddenAssetIds").path("value"))forbidden.add(id.asText());
            var originalScope=new java.util.HashSet<String>();for(var id:source.path("assetIds"))originalScope.add(id.asText());
            for(var id:scope)if(forbidden.contains(id.asText())||source.path("brief").path("fields").path("assetIds").has("value")&&!originalScope.contains(id.asText()))throw new ResponseStatusException(HttpStatus.CONFLICT,"修改不能扩大原需求的明确素材范围或使用禁用素材");
            for(String field:java.util.List.of("topic","minDurationMs","maxDurationMs","profile","kind","orderedAssetIds","forbiddenContent"))if(!conditions.path(field).equals(editing.path(field)))throw new ResponseStatusException(HttpStatus.CONFLICT,"修改不能改变原方案硬约束");
            for(var output:content.path("outputs"))for(var clip:output.path("clips")){
                UUID asset=UUID.fromString(clip.path("assetId").asText());
                if(database.queryForObject("SELECT count(*) FROM assets WHERE id=? AND project_id=? AND validation_status='VALID'",Integer.class,asset,project)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"基准素材已失效，不能恢复修改");
            }
        }
        var input = editing.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)input).put("planId", plan.toString());
        return input;
    }

    public Object parse(Object value) {
        try { return json.readTree(value.toString()); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }

    public void bindAttempt(UUID task, UUID attempt) {
        database.update("UPDATE edit_plans SET editing_attempt_id=? WHERE editing_task_id=?", attempt, task);
    }

    public JsonNode publish(Map<String,Object> task, JsonNode input, JsonNode result) {
        if (!input.has("editing")) throw new ResponseStatusException(HttpStatus.CONFLICT, "查询任务不能发布剪辑方案");
        UUID planId = UUID.fromString(input.path("editing").path("planId").asText());
        var plan = database.queryForMap("SELECT * FROM edit_plans WHERE id=? FOR UPDATE", planId);
        if (!task.get("id").equals(plan.get("editing_task_id")) || !task.get("current_attempt_id").equals(plan.get("editing_attempt_id")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务没有当前方案编辑权");
        Boolean approved = database.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM edit_validation_receipts
                    WHERE task_id=? AND proposal_json=?::jsonb AND result_json->>'valid'='true')
                """, Boolean.class, task.get("id"), result.path("proposal").toString());
        if (!Boolean.TRUE.equals(approved))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅可提交已经持久校验通过的候选；请先在有限预算内校验");
        var validated = validation.validate(task, input, result.path("proposal"));
        UUID revision = UUID.randomUUID();
        long number = ((Number)plan.get("next_revision_number")).longValue();
        try {
            UUID base = input.path("editing").hasNonNull("baseRevisionId") ? UUID.fromString(input.path("editing").path("baseRevisionId").asText()) : null;
            database.update("INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,parent_revision_id,source_task_id,content_json) VALUES (?,?,?,?,?,?,?::jsonb)",
                    revision, task.get("project_id"), planId, number, base, task.get("id"), json.writeValueAsString(validated.content()));
        } catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        for (UUID asset : validated.assets()) database.update("INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES (?,?)", revision, asset);
        for (UUID evidence : validated.evidence()) database.update("INSERT INTO revision_evidence_refs(revision_id,evidence_id) VALUES (?,?)", revision, evidence);
        database.update("UPDATE edit_plans SET next_revision_number=next_revision_number+1,editing_task_id=NULL,editing_attempt_id=NULL WHERE id=?", planId);
        var publication = json.createObjectNode();
        String content=result.path("content").asText();
        if(input.path("editing").has("modification")){
            content="方案 V"+number+" 已就绪；基于 V"+input.path("editing").path("modification").path("revisionNumber").asLong()+" 修改。"+json.valueToTree(validated.content()).path("changeSummary").asText()+" 当前查看版本保持不变。";
        }else if(input.path("context").path("naturalLanguage").asBoolean(false)){
            JsonNode output=json.valueToTree(validated.content()).path("outputs").get(0);
            var order=new java.util.ArrayList<String>();
            for(var clip:output.path("clips"))order.add(clip.path("reason").asText());
            content="方案 V"+number+" 已保存："+input.path("editing").path("topic").asText()+"。内容顺序："+String.join(" → ",order)+"。实际时长 "+(output.path("durationMs").asLong()/1000.0)+" 秒，"+("PORTRAIT_720".equals(output.path("render").path("profile").asText())?"竖屏":"横屏")+("MONTAGE".equals(input.path("editing").path("kind").asText())?"，有原声片段保留原声，无原声片段保持静音，字幕来自原语音。":"，保留原声并提供原文字幕。")+"请展开方案核查片段和证据；原视频已有停顿可能随选段保留。";
        }
        publication.put("content", content);
        var citations=new java.util.HashSet<UUID>(validated.evidence());
        if(input.path("editing").has("modification"))citations.clear(); // The complete snapshot retains old citations; the public revision card is the source for this edit.
        publication.set("evidenceIds", json.valueToTree(citations));
        publication.set("planRevision", json.valueToTree(Map.of("planId", planId, "revisionId", revision, "revisionNumber", number)));
        return publication;
    }

    public JsonNode validate(Map<String,Object> task, JsonNode input, String key, JsonNode proposal) {
        if (!input.has("editing")) throw new ResponseStatusException(HttpStatus.CONFLICT, "本任务不是方案制作任务");
        if (proposal.toString().length() > 50000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "候选方案超过大小限制");
        var previous = database.queryForList("SELECT result_json,proposal_json=?::jsonb AS same FROM edit_validation_receipts WHERE task_id=? AND producer_key=?", proposal.toString(), task.get("id"), key);
        if (!previous.isEmpty()) {
            if (!Boolean.TRUE.equals(previous.get(0).get("same"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "候选校验幂等键内容不同");
            return (JsonNode)parse(previous.get(0).get("result_json"));
        }
        var identical = database.queryForList("SELECT result_json FROM edit_validation_receipts WHERE task_id=? AND proposal_json=?::jsonb", task.get("id"), proposal.toString());
        if (!identical.isEmpty()) return (JsonNode)parse(identical.get(0).get("result_json"));
        if (database.queryForObject("SELECT count(*) FROM edit_validation_receipts WHERE task_id=?", Integer.class, task.get("id")) >= 3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮候选校验修正次数已用尽");
        var reply = json.createObjectNode();
        try { validation.validate(task, input, proposal); reply.put("valid", true); }
        catch (ResponseStatusException invalid) {
            reply.put("valid", false);
            reply.set("errors", json.valueToTree(java.util.List.of(Map.of("code", "INVALID_EDIT_PROPOSAL", "message", invalid.getReason()))));
        }
        database.update("INSERT INTO edit_validation_receipts(task_id,producer_key,proposal_json,result_json) VALUES (?,?,?::jsonb,?::jsonb)", task.get("id"), key, proposal.toString(), reply.toString());
        return reply;
    }

    public void release(UUID task) {
        database.update("UPDATE edit_plans SET editing_task_id=NULL,editing_attempt_id=NULL WHERE editing_task_id=?", task);
    }

    public Object candidateWindow(JsonNode input, Map<String,Object> target, java.util.List<Map<String,Object>> observations) {
        long sourceDuration = database.queryForObject("SELECT duration_ms FROM assets WHERE id=?", Long.class, target.get("asset_id"));
        if (!"SPEECH".equals(target.get("source_kind"))) {
            if (!"MONTAGE".equals(input.path("editing").path("kind").asText()))
                return Map.of("available", false, "reason", "讲话方案须核查语音原句；画面帧只提供观察时刻");
            long point=((Number)target.get("start_ms")).longValue();
            long start=Math.max(0,point-1000),end=Math.min(sourceDuration,point+1000);
            return Map.of("available",end>point,"assetId",target.get("asset_id"),"sourceInMs",start,"sourceOutMs",end,
                    "durationMs",end-start,"evidenceIds",java.util.List.of(target.get("id")),"purpose","CANDIDATE",
                    "note","原视频候选范围包含该观察时刻；帧证据不证明整个区间的画面内容，需用户回看核查。可在素材合法范围内调整时长，总时长由全部混剪片段共同满足。");
        }
        long minimum = "MONTAGE".equals(input.path("editing").path("kind").asText()) ? 1 : input.path("editing").path("minDurationMs").asLong(), maximum = input.path("editing").path("maxDurationMs").asLong();
        long sentenceStart = ((Number)target.get("start_ms")).longValue(), sentenceEnd = ((Number)target.get("end_ms")).longValue();
        if (minimum > sourceDuration || sentenceEnd-sentenceStart > maximum)
            return Map.of("available", false, "reason", "该原句完整范围与素材时长无法满足本次硬时长，请比较其他候选或解释限制");
        long start = Math.min(sentenceStart, Math.max(0, sourceDuration-minimum));
        long end = Math.max(sentenceEnd, start+minimum);
        // Extend across any neighboring sentence crossed by the candidate boundary.
        for (int pass=0;pass<=observations.size();pass++) {
            long oldStart=start,oldEnd=end;
            for (var observation:observations) {
                long begin=((Number)observation.get("startMs")).longValue(),stop=((Number)observation.get("endMs")).longValue();
                if (begin<end && stop>start) { start=Math.min(start,begin);end=Math.max(end,stop); }
            }
            if (start==oldStart && end==oldEnd) break;
        }
        var references=new java.util.ArrayList<UUID>();
        for (var observation:observations) {
            if (((Number)observation.get("startMs")).longValue()<end && ((Number)observation.get("endMs")).longValue()>start)
                references.add((UUID)observation.get("evidenceId"));
        }
        int covered=database.queryForObject("SELECT count(*) FROM evidence_segments WHERE asset_id=? AND analysis_id=? AND source_kind='SPEECH' AND start_ms<? AND end_ms>?", Integer.class,
                target.get("asset_id"),target.get("analysis_id"),end,start);
        if (end>sourceDuration || end-start>maximum || covered!=references.size())
            return Map.of("available", false, "reason", "完整候选超出硬时长或本次上下文覆盖，请比较或补读其他原句");
        return Map.of("available",true,"assetId",target.get("asset_id"),"sourceInMs",start,"sourceOutMs",end,
                "durationMs",end-start,"evidenceIds",references,"purpose","CANDIDATE",
                "note","仅为按原句与硬时长计算的候选，不发布版本。允许保留原视频现有停顿，不添加内容；主题与完整观点仍须核查。");
    }
}

package com.agenticvideo.business.agentrun;

import com.agenticvideo.business.plan.ProductionSaves;
import com.agenticvideo.business.render.ExportRequests;
import com.agenticvideo.business.project.ProjectAccess;
import com.agenticvideo.business.tools.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agenticvideo.business.agentrun.AgentRuns.conflict;

/** Accepting a seen draft creates V1 and its UI confirmation in one transaction. */
@Service
public class DraftAcceptances {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentRuns runs;
    private final AgentRunLocks locks;
    private final ProductionSaves saves;
    private final ExportRequests exports;
    private final ProjectAccess access;
    public DraftAcceptances(JdbcTemplate db,ObjectMapper json,AgentRuns runs,AgentRunLocks locks,ProductionSaves saves,ExportRequests exports,ProjectAccess access){this.db=db;this.json=json;this.runs=runs;this.locks=locks;this.saves=saves;this.exports=exports;this.access=access;}
    @Transactional public Object accept(UUID conversation,String key,AgentRunContracts.DraftBinding draft,Authentication account) {
        runs.requireOwned(conversation,account);AgentRuns.key(key);AgentRunContracts.read(json.valueToTree(draft),AgentRunContracts.DraftBinding.class);
        var session=locks.conversation(conversation);UUID actor=access.currentUserId(account);
        var replay=db.queryForList("SELECT response_json,actor_user_id,request_json=?::jsonb AS same FROM conversation_draft_acceptances WHERE conversation_id=? AND request_key=?",json.valueToTree(draft).toString(),conversation,key);
        if(!replay.isEmpty()) {
            if(!Boolean.TRUE.equals(replay.get(0).get("same"))||!actor.equals(replay.get(0).get("actor_user_id")))throw conflict("同一接受键不能改变草稿");
            return runs.parse(replay.get(0).get("response_json"));
        }
        var rows=db.queryForList("SELECT p.*,d.validation_id,d.draft_hash,d.requirements_hash,d.edit_generation AS draft_generation FROM edit_productions p JOIN conversation_edit_drafts d ON d.message_id=p.current_draft_message_id WHERE p.id=? AND d.message_id=? AND p.conversation_id=?",session.get("current_production_id"),UUID.fromString(draft.messageId()),conversation);
        if(rows.isEmpty())throw conflict("所见草稿已不是当前草稿");var p=rows.get(0);UUID production=(UUID)p.get("id");
        if(!draft.validationId().equals(p.get("validation_id").toString())||!draft.draftHash().equals(p.get("draft_hash"))||!draft.requirementsHash().equals(p.get("requirements_hash"))
                ||!draft.editGeneration().equals(p.get("edit_generation"))||!draft.editGeneration().equals(p.get("draft_generation"))||runs.frozen(production))throw conflict("草稿已变化或封存");
        // Undecided messages and production mutations suspend buttons; independent answers keep them available.
        if(runs.hasPendingMutation(conversation))throw conflict("尚有未决消息或制作修改");
        var request=json.createObjectNode().put("project_id",p.get("project_id").toString()).put("requirement_id",production.toString()).put("scope_id",p.get("scope_id").toString())
            .put("validation_id",draft.validationId()).put("draft_hash",draft.draftHash()).put("requirements_hash",draft.requirementsHash());
        request.set("requirements_sources",runs.parse(p.get("requirements_sources_json")));
        var saved=saves.accept(production,request);UUID revision=UUID.fromString(saved.path("revision_id").asText());
        var confirmation=json.valueToTree(exports.confirm(revision,actor,"draft-accept-"+production,json.valueToTree(Map.of("explicitIntent","CONFIRM"))));
        var result=json.createObjectNode().put("planId",saved.path("plan_id").asText()).put("revisionId",revision.toString()).put("revisionNumber",1).put("confirmationId",confirmation.path("confirmationId").asText());
        var outputs=result.putArray("outputs");
        for(var output:saved.path("outputs"))outputs.addObject().put("outputKey",output.path("output_key").asText()).put("outputId",output.path("output_id").asText()).put("durationMs",output.path("duration_ms").asLong());
        db.update("INSERT INTO conversation_draft_acceptances(conversation_id,project_id,request_key,actor_user_id,request_json,response_json,source_draft_message_id,revision_id,confirmation_id) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?)",conversation,p.get("project_id"),key,actor,json.valueToTree(draft).toString(),result.toString(),UUID.fromString(draft.messageId()),revision,UUID.fromString(confirmation.path("confirmationId").asText()));
        var refs=json.createArrayNode();refs.addObject().put("type","PLAN_REVISION").put("id",revision.toString()).put("planId",saved.path("plan_id").asText()).put("sourceDraftMessageId",draft.messageId());
        runs.append(UUID.randomUUID(),conversation,"ASSISTANT","已接受草稿并保存为方案 V1。",refs,null);
        return result;
    }
}

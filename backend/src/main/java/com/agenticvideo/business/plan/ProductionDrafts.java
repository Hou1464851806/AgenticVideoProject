package com.agenticvideo.business.plan;

import com.agenticvideo.business.search.QuestionEvidence.Scope;
import com.agenticvideo.business.tools.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Production binding and atomic validation publication; never creates a plan revision. */
@Service
public class ProductionDrafts {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final QuestionScopes scopes;
    private final EditDraftValidation validation;
    private final TransactionTemplate transaction;
    public ProductionDrafts(JdbcTemplate db,ObjectMapper json,QuestionScopes scopes,EditDraftValidation validation,PlatformTransactionManager transactions) {
        this.db=db;this.json=json;this.scopes=scopes;this.validation=validation;this.transaction=new TransactionTemplate(transactions);
    }
    public Map<String,Object> bind(UUID id,UUID project,UUID scopeId) {
        var scope=scopes.get(scopeId);
        if(!scope.projectId().equals(project))throw new ToolFailure("PRODUCTION_SCOPE_CONFLICT","IDENTITY_CONFLICT","制作项目与范围不匹配");
        scopes.requireDependencies(scope);requireMediaSnapshot(scope);
        db.update("INSERT INTO edit_productions(id,project_id,scope_id) VALUES (?,?,?) ON CONFLICT(id) DO NOTHING",id,project,scopeId);
        var production=get(id);
        if(!production.get("project_id").equals(project) || !production.get("scope_id").equals(scopeId))throw new ToolFailure("PRODUCTION_ID_CONFLICT","IDENTITY_CONFLICT","制作需求身份已经绑定其他范围");
        return Map.of("requirement_id",id,"project_id",project,"scope_id",scopeId);
    }
    public Map<String,Object> get(UUID id) {
        var rows=db.queryForList("SELECT project_id,scope_id FROM edit_productions WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"制作需求不存在");return rows.get(0);
    }
    public JsonNode validate(Scope scope,UUID production,JsonNode request,ObjectNode envelope) {
        requireMediaSnapshot(scope);
        final EditDraftContracts.Input input;
        try {input=EditDraftContracts.validation(request.path("arguments"));}
        catch(IllegalArgumentException invalid){throw new ToolFailure("INVALID_ARGUMENTS","MODEL_REPAIRABLE",invalid.getMessage());}
        if(!ToolJson.hash(json.valueToTree(input.draft())).equals(input.draft_hash()) || !ToolJson.hash(json.valueToTree(input.requirements())).equals(input.requirements_hash()))
            throw new ToolFailure("CONTENT_HASH_MISMATCH","IDENTITY_CONFLICT","草稿或条件哈希核验失败");
        var assetIds=new HashSet<String>();scope.assets().forEach(a->assetIds.add(a.path("id").asText()));
        if(!assetIds.containsAll(input.requirements().asset_ids()))throw new ToolFailure("ASSET_OUTSIDE_SCOPE","PERMISSION_DENIED","制作条件包含固定范围以外的素材");
        UUID id=UUID.randomUUID();var checked=validation.validate(scope,input,id);envelope.set("data",checked.result());
        bound(envelope);
        return transaction.execute(tx->{
            scopes.requireDependencies(scope);
            db.update("""
                    INSERT INTO edit_draft_validations(id,production_id,project_id,scope_id,call_id,draft_hash,requirements_hash,
                        rule_version,draft_json,requirements_json,sources_json,result_json,normalized_content_json)
                    VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb)
                    """,id,production,scope.projectId(),scope.id(),request.path("call_id").asText(),input.draft_hash(),input.requirements_hash(),EditDraftValidation.RULE_VERSION,
                    json.valueToTree(input.draft()).toString(),json.valueToTree(input.requirements()).toString(),checked.sources().toString(),envelope.path("data").toString(),checked.content()==null?null:checked.content().toString());
            db.update("UPDATE question_tool_receipts SET result_json=?::jsonb WHERE scope_id=? AND call_id=?",envelope.toString(),scope.id(),request.path("call_id").asText());
            return envelope;
        });
    }
    private void requireMediaSnapshot(Scope scope) {
        for(var asset:scope.assets())if(!asset.path("has_audio").isBoolean() || !asset.path("coverage").path("audio_ranges").isArray()
                || asset.path("has_audio").asBoolean() && !asset.path("audio_offset_ms").isIntegralNumber())
            throw new ToolFailure("SNAPSHOT_INVALID","DEPENDENCY_INVALID","旧范围缺少固定媒体信息，请明确启动新的制作需求并创建范围");
    }
    private void bound(ObjectNode envelope) {
        var data=(ObjectNode)envelope.path("data");var issues=(ArrayNode)data.path("issues");var warnings=(ArrayNode)data.path("warnings");
        if(bytes(envelope)>EditDraftContracts.RESULT_BYTES && !warnings.isEmpty()) {
            var marker=warnings.addObject().put("code","WARNINGS_TRUNCATED").put("omitted_count",0);int omitted=0;
            while(bytes(envelope)>EditDraftContracts.RESULT_BYTES && warnings.size()>1) {
                warnings.remove(warnings.size()-2);marker.put("omitted_count",++omitted);
            }
        }
        while(bytes(envelope)>EditDraftContracts.RESULT_BYTES && !issues.isEmpty()){issues.remove(issues.size()-1);data.put("issues_truncated",true);}
    }
    private int bytes(JsonNode value){return value.toString().getBytes(StandardCharsets.UTF_8).length;}
}

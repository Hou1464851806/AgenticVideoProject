package com.agenticvideo.business.plan;

import com.agenticvideo.business.tools.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Publishes one immutable version per production; the version is also the durable save receipt. */
@Service
public class ProductionSaves {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final QuestionScopes scopes;
    private final TransactionTemplate transaction;
    public ProductionSaves(JdbcTemplate db,ObjectMapper json,QuestionScopes scopes,PlatformTransactionManager transactions) {
        this.db=db;this.json=json;this.scopes=scopes;this.transaction=new TransactionTemplate(transactions);
    }
    public JsonNode result(UUID production) {
        var rows=db.queryForList("SELECT production_save_result_json FROM plan_revisions WHERE source_production_id=?",production);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"制作需求尚无保存结果");
        return parse(rows.get(0).get("production_save_result_json"));
    }
    public JsonNode save(UUID production,JsonNode body) {return save(production,body,false);}
    public JsonNode accept(UUID production,JsonNode body) {return save(production,body,true);}
    private JsonNode save(UUID production,JsonNode body,boolean browserAcceptance) {
        final EditDraftContracts.SaveRequest input;
        final UUID project,scope,validation;
        try {
            input=EditDraftContracts.read(body,EditDraftContracts.SaveRequest.class);
            project=UUID.fromString(input.project_id());scope=UUID.fromString(input.scope_id());validation=UUID.fromString(input.validation_id());
            if(!production.equals(UUID.fromString(input.requirement_id())))throw new IllegalArgumentException("制作需求与路由不匹配");
        } catch(IllegalArgumentException invalid){throw new ResponseStatusException(BAD_REQUEST,invalid.getMessage());}
        return transaction.execute(tx->{
            var bindings=db.queryForList("SELECT project_id,scope_id,conversation_id FROM edit_productions WHERE id=? FOR UPDATE",production);
            if(bindings.isEmpty())throw new ResponseStatusException(NOT_FOUND,"制作需求不存在");
            var binding=bindings.get(0);
            if(binding.get("conversation_id")!=null && !browserAcceptance)
                throw new ToolFailure("BROWSER_ACCEPTANCE_REQUIRED","IDENTITY_CONFLICT","浏览器制作须由用户接受当前草稿");
            if(!project.equals(binding.get("project_id")) || !scope.equals(binding.get("scope_id")))
                throw new ToolFailure("SAVE_IDENTITY_CONFLICT","IDENTITY_CONFLICT","保存身份与固定制作需求不匹配");
            var receipts=db.queryForList("SELECT * FROM edit_draft_validations WHERE id=? AND production_id=? AND project_id=? AND scope_id=?",validation,production,project,scope);
            if(receipts.isEmpty())throw new ToolFailure("VALIDATION_MISMATCH","IDENTITY_CONFLICT","校验不属于当前制作需求");
            var receipt=receipts.get(0);
            if(!input.draft_hash().equals(receipt.get("draft_hash")) || !input.requirements_hash().equals(receipt.get("requirements_hash")))
                throw new ToolFailure("CONTENT_HASH_MISMATCH","IDENTITY_CONFLICT","草稿或条件已改变，请重新校验");
            var saved=db.queryForList("SELECT source_validation_id,production_save_result_json,production_requirements_sources_json FROM plan_revisions WHERE source_production_id=?",production);
            if(!saved.isEmpty()) {
                if(!validation.equals(saved.get(0).get("source_validation_id")) || !json.valueToTree(input.requirements_sources()).equals(parse(saved.get(0).get("production_requirements_sources_json"))))throw new ToolFailure("PRODUCTION_SAVED","IDENTITY_CONFLICT","制作已经保存，请查询原保存结果");
                return parse(saved.get(0).get("production_save_result_json"));
            }
            if(receipt.get("normalized_content_json")==null || !parse(receipt.get("result_json")).path("valid").asBoolean())
                throw new ToolFailure("DRAFT_NOT_VALID","MODEL_REPAIRABLE","所有输出须整体通过校验后才能保存");
            var latest=db.queryForMap("SELECT draft_hash,requirements_hash FROM edit_draft_validations WHERE production_id=? ORDER BY created_at DESC,id DESC LIMIT 1",production);
            if(!input.draft_hash().equals(latest.get("draft_hash")) || !input.requirements_hash().equals(latest.get("requirements_hash")))
                throw new ToolFailure("VALIDATION_STALE","IDENTITY_CONFLICT","已有不同草稿或条件的校验，请使用当前通过结果");
            // Pin database dependencies against concurrent invalidation for this short commit.
            var fixed=scopes.get(scope);
            for(var asset:fixed.assets()) {
                UUID assetId=UUID.fromString(asset.path("id").asText());
                db.queryForList("SELECT id FROM assets WHERE id=? FOR SHARE",assetId);
                if(!asset.path("analysis_version").isNull())db.queryForList("SELECT id FROM asset_analysis_revisions WHERE id=? FOR SHARE",UUID.fromString(asset.path("analysis_version").asText()));
                if(!asset.path("index_generation").isNull())db.queryForList("SELECT id FROM search_index_generations WHERE id=? FOR SHARE",UUID.fromString(asset.path("index_generation").asText()));
            }
            db.queryForList("SELECT e.id FROM evidence_segments e JOIN question_scope_evidence q ON q.evidence_id=e.id WHERE q.scope_id=? ORDER BY e.id FOR SHARE OF e",scope);
            db.queryForList("SELECT u.id FROM retrieval_units u JOIN question_scope_units q ON q.unit_id=u.id WHERE q.scope_id=? ORDER BY u.id FOR SHARE OF u",scope);
            scopes.requireDependencies(fixed);
            UUID plan=UUID.randomUUID(),revision=UUID.randomUUID();
            var content=(ObjectNode)parse(receipt.get("normalized_content_json"));
            var result=json.createObjectNode().put("status","SAVED").put("plan_id",plan.toString()).put("revision_id",revision.toString()).put("revision_number",1);
            var mapping=result.putArray("outputs");
            for(var item:content.path("outputs")) {
                var output=(ObjectNode)item;String key=output.remove("output_key").asText(),id=UUID.randomUUID().toString();output.put("outputId",id);
                for(var clip:output.path("clips"))((ObjectNode)clip).put("clipId",UUID.randomUUID().toString());
                mapping.addObject().put("output_key",key).put("output_id",id).put("duration_ms",output.path("durationMs").asLong());
            }
            db.update("INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES (?,?,?,2)",plan,project,parse(receipt.get("draft_json")).path("title").asText());
            db.update("""
                    INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_production_id,source_validation_id,production_save_result_json,content_json,production_requirements_sources_json)
                    VALUES (?,?,?,1,?,?,?::jsonb,?::jsonb,?::jsonb)
                    """,revision,project,plan,production,validation,result.toString(),content.toString(),json.valueToTree(input.requirements_sources()).toString());
            var sources=parse(receipt.get("sources_json"));
            for(var asset:sources.path("asset_ids"))db.update("INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES (?,?)",revision,UUID.fromString(asset.asText()));
            for(var evidence:sources.path("evidence_ids"))db.update("INSERT INTO revision_evidence_refs(revision_id,evidence_id) VALUES (?,?)",revision,UUID.fromString(evidence.asText()));
            return result;
        });
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
}

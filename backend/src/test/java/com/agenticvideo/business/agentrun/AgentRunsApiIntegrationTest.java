package com.agenticvideo.business.agentrun;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AgentRunsApiIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-bookworm");
    static final String TOKEN="lifecycle-integration-worker-token";
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);
        p.add("spring.datasource.password",POSTGRES::getPassword);p.add("internal.worker-token",()->TOKEN);
    }
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    String owner; UUID project,conversation;
    @BeforeEach void context() {
        UUID account=UUID.randomUUID();project=UUID.randomUUID();conversation=UUID.randomUUID();owner=account.toString();
        db.update("INSERT INTO users(id,normalized_username,password_hash) VALUES (?,?,'fixture')",account,owner);
        db.update("INSERT INTO projects(id,owner_user_id,name) VALUES (?,?,'lifecycle')",project,account);
        db.update("INSERT INTO conversations(id,project_id,title) VALUES (?,?,'lifecycle')",conversation,project);
    }
    JsonNode browser(String path,Object body,String key,int status) throws Exception {
        return response(http.perform(post("/api/v1/conversations/"+conversation+path).with(user(owner)).with(csrf())
                .header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsBytes(body))),status);
    }
    JsonNode response(ResultActions action,int status) throws Exception {
        var result=action.andReturn().getResponse();assertEquals(status,result.getStatus(),result.getContentAsString());
        return result.getContentAsString().isBlank()?json.nullNode():json.readTree(result.getContentAsString()).path("data");
    }
    JsonNode internal(String task,String action,Object body,int status) throws Exception {
        return response(http.perform(post("/internal/v1/agent-runs/"+task+"/"+action).header("Authorization","Bearer "+TOKEN)
                .contentType("application/json").content(json.writeValueAsBytes(body))),status);
    }
    JsonNode send(String content,String key) throws Exception {return browser("/turns",Map.of("content",content),key,202);}
    JsonNode claim(JsonNode run) throws Exception {return internal(run.path("taskId").asText(),"claim",Map.of("executorId","fixture","claimRequestId",UUID.randomUUID()),200);}
    Map<String,Object> credentials(JsonNode claim) {return new LinkedHashMap<>(Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText()));}
    JsonNode parsed(JsonNode run,JsonNode claim,String mode,String relation,Map<String,Object> patch,String question,int status) throws Exception {
        var body=credentials(claim);var parsed=new LinkedHashMap<String,Object>();parsed.put("mode",mode);parsed.put("relation",relation);parsed.put("conditionsPatch",patch);parsed.put("questionId",question);body.put("parsed",parsed);
        return internal(run.path("taskId").asText(),"parsed",body,status);
    }
    @Test void acceptsFixedInputAndPublishesDiscussionWithoutCreatingProduction() throws Exception {
        var run=send("讨论剪辑思路","send");assertEquals(run,send("讨论剪辑思路","send"));
        browser("/turns",Map.of("content","changed"),"send",409);
        var claim=claim(run);var input=claim.path("input");
        assertEquals(0,input.path("history").size());assertEquals("讨论剪辑思路",input.path("content").asText());
        assertEquals(project.toString(),input.path("identity").path("projectId").asText());
        assertEquals(64,claim.path("inputHash").asText().length());
        AgentRunContracts.read(input,AgentRunContracts.RunInput.class);
        var binding=parsed(run,claim,"DISCUSS","INDEPENDENT",Map.of(),null,200);
        assertTrue(binding.path("production").isNull());
        assertEquals(binding,parsed(run,claim,"DISCUSS","INDEPENDENT",Map.of(),null,200));
        var result=json.createObjectNode();result.put("schemaVersion","1.0");result.set("identity",binding.path("identity"));result.put("status","ANSWER");result.put("mode","DISCUSS");result.put("content","可以先确定故事主线");result.putArray("scopeReceipts");
        result.putNull("productionId");result.putNull("editGeneration");result.putNull("draft");result.putNull("question");result.putNull("failure");
        var report=credentials(claim);report.put("result",result);
        var outcome=internal(run.path("taskId").asText(),"result",report,200);
        assertEquals(outcome,internal(run.path("taskId").asText(),"result",report,200));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM conversation_messages WHERE conversation_id=?",Integer.class,conversation));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM edit_productions WHERE conversation_id=?",Integer.class,conversation));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,project));
        assertEquals(410,responseStatus(post("/internal/v1/tasks/"+run.path("taskId").asText()+"/claim")));
    }
    @Autowired AgentRunRecovery recovery;
    @Test void expiredLeaseCreatesNewAttemptAndRejectsLateReports() throws Exception {
        var run=send("讨论主线","recover");var first=claim(run);
        var binding=parsed(run,first,"DISCUSS","INDEPENDENT",Map.of(),null,200);
        db.update("UPDATE task_attempts SET lease_expires_at=now()-interval '1 second' WHERE id=?",UUID.fromString(first.path("attemptId").asText()));
        recovery.recover();
        var second=claim(run);
        assertNotEquals(first.path("attemptId"),second.path("attemptId"));
        assertEquals(binding,second.path("binding"));
        assertEquals(first.path("input"),second.path("input"));
        publish(run,first,result(binding,"ANSWER","迟到",null,null,null),409);
        publish(run,second,result(binding,"ANSWER","恢复结果",null,null,null),200);
        assertEquals(2,db.queryForObject("SELECT count(*) FROM task_attempts WHERE task_id=?",Integer.class,UUID.fromString(run.path("taskId").asText())));
    }
    @Test void explicitRetryRetainsOriginalExecutionAndReplaysSuccessor() throws Exception {
        var run=send("讨论主线","retry-source");var first=claim(run);
        var binding=parsed(run,first,"DISCUSS","INDEPENDENT",Map.of(),null,200);
        var failure=json.valueToTree(Map.of("code","EXECUTION_INTERRUPTED","message","可恢复中断","recoverable",true));
        publish(run,first,result(binding,"FAILED","可恢复中断",null,null,failure),200);
        var snapshot=response(http.perform(get("/api/v1/conversations/"+conversation+"/snapshot").with(user(owner))),200);
        assertEquals(failure,snapshot.path("latestTask").path("failure"));
        var retry=browser("/tasks/"+run.path("taskId").asText()+"/retry",Map.of(),"retry",202);
        assertNotEquals(run.path("taskId"),retry.path("taskId"));
        assertEquals(retry,browser("/tasks/"+run.path("taskId").asText()+"/retry",Map.of(),"replay-other-key",202));
        var resumed=claim(retry);
        assertEquals(first.path("input"),resumed.path("execution").path("input"));
        assertEquals(binding,resumed.path("execution").path("binding"));
        assertEquals(binding.path("identity").path("executionId"),resumed.path("binding").path("identity").path("executionId"));
        publish(retry,resumed,result(resumed.path("binding"),"ANSWER","沿原执行完成",null,null,null),200);
        assertEquals("FAILED",db.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,UUID.fromString(run.path("taskId").asText())));
        assertEquals(retry,browser("/tasks/"+run.path("taskId").asText()+"/retry",Map.of(),"after-success",202));
    }
    @Test void retryCannotForceUnknownPaymentOrWriteAfterProductionAdvances() throws Exception {
        var uncertain=send("讨论主线","unknown");var uc=claim(uncertain);
        var ub=parsed(uncertain,uc,"DISCUSS","INDEPENDENT",Map.of(),null,200);
        publish(uncertain,uc,result(ub,"INCOMPLETE","提交不确定",null,null,json.valueToTree(Map.of("code","PAID_SUBMISSION_UNKNOWN","message","提交不确定","recoverable",false))),200);
        browser("/tasks/"+uncertain.path("taskId").asText()+"/retry",Map.of(),"reject-unknown",409);
        browser("/tasks/"+uncertain.path("taskId").asText()+"/retry",Map.of("acknowledgeUnknownCost",true),"reject-force",409);
        media();var run=send("制作短片","old-production");var first=claim(run);
        var binding=parsed(run,first,"DRAFT","NEW_PRODUCTION",Map.of("topic","主题"),null,200);
        publish(run,first,result(binding,"FAILED","可恢复中断",null,null,json.valueToTree(Map.of("code","EXECUTION_INTERRUPTED","message","可恢复中断","recoverable",true))),200);
        var newer=send("修改主题","newer");var nc=claim(newer);
        var nb=parsed(newer,nc,"DRAFT","CONTINUE_PRODUCTION",Map.of("topic","新主题"),null,200);
        publish(newer,nc,result(nb,"INCOMPLETE","未完成",null,null,json.valueToTree(Map.of("code","CORE_INCOMPLETE","message","未完成","recoverable",false))),200);
        browser("/tasks/"+run.path("taskId").asText()+"/retry",Map.of(),"reject-obsolete",409);
        UUID other=UUID.randomUUID();db.update("INSERT INTO conversations(id,project_id,title) VALUES (?,?,'other')",other,project);
        UUID originalConversation=conversation;conversation=other;
        try {browser("/tasks/"+run.path("taskId").asText()+"/retry",Map.of(),"wrong-conversation",404);}finally{conversation=originalConversation;}
    }
    record Media(UUID asset,UUID evidence) {}
    Media media() {
        UUID upload=UUID.randomUUID(),asset=UUID.randomUUID(),analysis=UUID.randomUUID(),task=UUID.randomUUID(),model=UUID.randomUUID(),evidence=UUID.randomUUID();String hash="b".repeat(64);
        db.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'speech.mp4',1,?,1,1,?,? FROM projects WHERE id=?",upload,upload.toString(),hash,upload.toString(),UUID.randomUUID(),project);
        db.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status,duration_ms,media_info_json,time_mapping_json) VALUES (?,?,?,?,'speech.mp4',1,?,'VALID',20000,'{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"20\"}]}','{\"hasAudio\":true,\"audioStartMs\":0,\"formatStartMs\":0}')",asset,project,upload,asset.toString(),hash);
        db.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'fixture')",analysis,project,asset,hash);
        db.update("INSERT INTO asset_analysis_heads VALUES (?,?)",asset,analysis);
        db.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status) VALUES (?,?,'ASR','SUCCEEDED')",asset,analysis);
        db.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'CONTENT_ANALYZE','SUCCEEDED','{}')",task,project);
        db.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status) VALUES (?,?,?,'fixture','{}','SUCCEEDED')",model,analysis,task);
        db.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',0,'先确定主题。',0,5000,0,5000,'{}',?)",evidence,project,asset,analysis,model);
        db.update("UPDATE conversations SET asset_ids_json=?::jsonb WHERE id=?",json.valueToTree(List.of(asset)).toString(),conversation);
        return new Media(asset,evidence);
    }
    JsonNode result(JsonNode binding,String status,String content,JsonNode draft,JsonNode question,JsonNode failure) {
        var r=json.createObjectNode().put("schemaVersion","1.0").put("status",status).put("mode",binding.path("parsed").path("mode").asText()).put("content",content);
        r.set("identity",binding.path("identity"));r.putArray("scopeReceipts");
        r.set("productionId",binding.path("production").isNull()?json.nullNode():binding.path("production").path("productionId"));
        r.set("editGeneration",binding.path("production").isNull()?json.nullNode():binding.path("production").path("editGeneration"));
        r.set("draft",draft==null?json.nullNode():draft);r.set("question",question==null?json.nullNode():question);r.set("failure",failure==null?json.nullNode():failure);return r;
    }
    JsonNode publish(JsonNode run,JsonNode claim,JsonNode result,int status) throws Exception {var body=credentials(claim);body.put("result",result);return internal(run.path("taskId").asText(),"result",body,status);}
    JsonNode tool(JsonNode run,JsonNode claim,JsonNode binding,String call,String name,Object args,int status) throws Exception {
        var req=json.createObjectNode().put("call_id",call).put("tool_name",name);req.set("arguments",json.valueToTree(args));
        var hash=json.createObjectNode().put("tool",name);hash.set("args",req.path("arguments"));req.put("request_hash",com.agenticvideo.business.tools.ToolJson.hash(hash));
        var body=credentials(claim);body.put("editGeneration",binding.path("production").isNull()?null:binding.path("production").path("editGeneration").asLong());body.put("request",req);
        return internal(run.path("taskId").asText(),"tools",body,status);
    }
    JsonNode validatedDraft(JsonNode run,JsonNode claim,JsonNode binding,Media media,String call) throws Exception {
        tool(run,claim,binding,"read-"+call,"read_video_range",Map.of("asset_id",media.asset(),"start_ms",0,"end_ms",5000),200);
        var clip=Map.of("asset_id",media.asset(),"source_in_ms",0,"source_out_ms",5000,"evidence_ids",List.of(media.evidence()),"reason","按原文开始");
        var draft=json.valueToTree(Map.of("title","主题","outputs",List.of(Map.of("output_key","out-1","title","主片","clips",List.of(clip)))));
        var req=binding.path("production").path("requirements");
        var args=Map.of("draft",draft,"requirements",req,"draft_hash",com.agenticvideo.business.tools.ToolJson.hash(draft),"requirements_hash",com.agenticvideo.business.tools.ToolJson.hash(req));
        var checked=tool(run,claim,binding,call,"validate_edit_draft",args,200);
        assertTrue(checked.path("data").path("valid").asBoolean(),checked.toString());
        return json.valueToTree(Map.of("messageId",UUID.randomUUID(),"validationId",checked.path("data").path("validation_id").asText(),"editGeneration",binding.path("production").path("editGeneration").asLong(),"draftHash",args.get("draft_hash"),"requirementsHash",args.get("requirements_hash")));
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.agenticvideo.business.upload.MultipartStorage storage;

    @Test void draftPreviewFreezesValidatedContentWithoutSavingAndReplaysAfterModification() throws Exception {
        org.mockito.Mockito.when(storage.objectSize(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        var media=media();var run=send("制作主题短片","preview-production");var c=claim(run);
        var b=parsed(run,c,"DRAFT","NEW_PRODUCTION",Map.of("topic","主题"),null,200);
        var draft=validatedDraft(run,c,b,media,"preview-validation");
        publish(run,c,result(b,"DRAFT_READY","草稿 A",draft,null,null),200);
        var body=Map.of("draft",draft,"outputKey","out-1","previewProfile","LOW_360");
        var preview=browser("/drafts/previews",body,"preview",202);
        assertTrue(preview.path("revisionId").isMissingNode());
        assertEquals(draft.path("messageId"),preview.path("sourceDraftMessageId"));
        assertEquals("out-1",preview.path("outputKey").asText());
        assertEquals(preview.path("requestId"),browser("/drafts/previews",body,"reuse",202).path("requestId"));
        UUID task=UUID.fromString(preview.path("taskId").asText());
        String frozen=db.queryForObject("SELECT input_json::text FROM tasks WHERE id=?",String.class,task);
        var input=json.readTree(frozen);
        assertEquals("先确定主题。",input.path("output").path("subtitles").path("cues").get(0).path("text").asText());
        assertFalse(input.has("revisionId"));assertFalse(input.path("output").has("outputId"));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,project));
        var modification=send("修改主题","modify-preview");var mc=claim(modification);
        parsed(modification,mc,"DRAFT","CONTINUE_PRODUCTION",Map.of("topic","新主题"),null,200);
        assertEquals(preview,browser("/drafts/previews",body,"preview",202));
        browser("/drafts/previews",body,"obsolete",409);
        assertEquals(frozen,db.queryForObject("SELECT input_json::text FROM tasks WHERE id=?",String.class,task));
    }

    @Autowired com.agenticvideo.business.task.TaskExecution mediaTasks;

    @Test void completedHistoricalPreviewPublishesOnePlaybackMessageAndDoesNotRegenerateAfterLoss() throws Exception {
        org.mockito.Mockito.when(storage.objectSize(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        org.mockito.Mockito.when(storage.playbackUrl(org.mockito.ArgumentMatchers.anyString())).thenReturn("https://storage.invalid/renewed-access");
        var m=media();var run=send("制作主题","playable");var c=claim(run);
        var b=parsed(run,c,"DRAFT","NEW_PRODUCTION",Map.of("topic","主题"),null,200);
        var d=validatedDraft(run,c,b,m,"play-validation");publish(run,c,result(b,"DRAFT_READY","A",d,null,null),200);
        var body=Map.of("draft",d,"outputKey","out-1","previewProfile","LOW_360");
        var preview=browser("/drafts/previews",body,"play",202);
        var local=mediaTasks.claimLocal(UUID.fromString(preview.path("taskId").asText()));
        // Accepting while media runs keeps its original draft source.
        browser("/drafts/accept",d,"accept-during-preview",200);
        var manifest=Map.of("artifactId",local.input().path("artifactId").asText(),"objectKey",local.input().path("artifactObjectKey").asText(),"byteSize",1,"sha256","b".repeat(64),"durationMs",5000,
                "mediaInfo",Map.of("streams",List.of(Map.of("codec_type","video","codec_name","h264","width",640,"height",360),Map.of("codec_type","audio","codec_name","aac","channels",2,"sample_rate","48000"))));
        db.execute("ALTER TABLE execution_reports ADD CONSTRAINT reject_preview_commit CHECK (task_id <> '"+local.taskId()+"'::uuid OR report_kind <> 'COMPLETE') NOT VALID");
        try {assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->mediaTasks.completeLocal(local,"PREVIEW_RENDERED",manifest));}
        finally {db.execute("ALTER TABLE execution_reports DROP CONSTRAINT reject_preview_commit");}
        assertEquals("RUNNING",mediaTasks.read(local.taskId()).get("status"));
        assertEquals("STAGED",db.queryForObject("SELECT state FROM artifacts WHERE task_id=?",String.class,local.taskId()));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM conversation_messages WHERE source_task_id=?",Integer.class,local.taskId()));
        mediaTasks.completeLocal(local,"PREVIEW_RENDERED",manifest);
        mediaTasks.completeLocal(local,"PREVIEW_RENDERED",manifest);
        var snapshot=response(http.perform(get("/api/v1/conversations/"+conversation+"/snapshot").with(user(owner))),200);
        var messages=new ArrayList<JsonNode>();
        for(var item:snapshot.path("items"))for(var ref:item.path("references"))if("PREVIEW_RESULT".equals(ref.path("type").asText()))messages.add(ref);
        assertEquals(1,messages.size());assertEquals(d.path("messageId"),messages.get(0).path("sourceDraftMessageId"));
        String artifact=messages.get(0).path("id").asText();
        assertEquals(preview.path("requestId"),messages.get(0).path("requestId"));
        assertEquals("SUCCEEDED",snapshot.path("previews").path("items").get(0).path("status").asText());
        assertEquals("AVAILABLE",snapshot.path("previews").path("items").get(0).path("artifactState").asText());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM public_events WHERE project_id=? AND type='assistant.completed' AND body->>'taskId'=?",Integer.class,project,preview.path("taskId").asText()));
        for(int i=0;i<2;i++)response(http.perform(post("/api/v1/artifacts/"+artifact+"/playback-access").with(user(owner)).with(csrf())),200);
        org.mockito.Mockito.when(storage.objectSize(local.input().path("artifactObjectKey").asText())).thenReturn(null);
        response(http.perform(post("/api/v1/artifacts/"+artifact+"/playback-access").with(user(owner)).with(csrf())),410);
        var unavailable=response(http.perform(get("/api/v1/conversations/"+conversation+"/snapshot").with(user(owner))),200);
        assertEquals("UNAVAILABLE",unavailable.path("previews").path("items").get(0).path("artifactState").asText());
        browser("/drafts/previews/"+preview.path("requestId").asText()+"/retry",Map.of(),"lost-historical",409);
        assertEquals(preview,browser("/drafts/previews",body,"play",202));
        browser("/drafts/previews",body,"new-frozen",409);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM render_requests WHERE conversation_id=?",Integer.class,conversation));
    }

    @Test void explicitDraftRetryHasOneSuccessorAndOwnershipAndInputAreEnforced() throws Exception {
        org.mockito.Mockito.when(storage.objectSize(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        var m=media();var run=send("制作主题","retry-preview");var c=claim(run);var b=parsed(run,c,"DRAFT","NEW_PRODUCTION",Map.of("topic","主题"),null,200);
        var d=validatedDraft(run,c,b,m,"retry-validation");publish(run,c,result(b,"DRAFT_READY","A",d,null,null),200);
        var body=Map.of("draft",d,"outputKey","out-1","previewProfile","LOW_360");
        var preview=browser("/drafts/previews",body,"retry-original",202);
        mediaTasks.cancel(UUID.fromString(preview.path("taskId").asText()));
        assertEquals(preview,browser("/drafts/previews",body,"retry-original",202));
        browser("/drafts/previews",body,"new-key-is-not-retry",409);
        var retry=browser("/drafts/previews/"+preview.path("requestId").asText()+"/retry",Map.of(),"retry-1",202);
        assertNotEquals(preview.path("taskId"),retry.path("taskId"));
        mediaTasks.cancel(UUID.fromString(retry.path("taskId").asText()));
        assertEquals(retry.path("requestId"),browser("/drafts/previews/"+preview.path("requestId").asText()+"/retry",Map.of(),"retry-replay",202).path("requestId"));
        browser("/drafts/previews",Map.of("draft",d,"outputKey","foreign","previewProfile","LOW_360"),"retry-original",409);
        browser("/drafts/previews",Map.of("draft",d,"outputKey","foreign","previewProfile","LOW_360"),"missing-output",404);
        browser("/drafts/previews",Map.of("draft",d,"outputKey","out-1","previewProfile","HD_720"),"wrong-profile",400);
        var forged=d.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)forged).put("draftHash","c".repeat(64));
        browser("/drafts/previews",Map.of("draft",forged,"outputKey","out-1","previewProfile","LOW_360"),"forged",409);
        UUID outsider=UUID.randomUUID();db.update("INSERT INTO users(id,normalized_username,password_hash) VALUES (?,?,'fixture')",outsider,outsider.toString());
        response(http.perform(post("/api/v1/conversations/"+conversation+"/drafts/previews").with(user(outsider.toString())).with(csrf()).header("Idempotency-Key","outsider").contentType("application/json").content(json.writeValueAsBytes(body))),404);
        UUID other=UUID.randomUUID();db.update("INSERT INTO conversations(id,project_id,title) VALUES (?,?,'other')",other,project);
        response(http.perform(post("/api/v1/conversations/"+other+"/drafts/previews/"+preview.path("requestId").asText()+"/retry").with(user(owner)).with(csrf())),404);
        db.update("UPDATE assets SET sha256=? WHERE id=?","c".repeat(64),m.asset());
        browser("/drafts/previews",body,"source-invalid",409);
        assertEquals(preview,browser("/drafts/previews",body,"retry-original",202));
        db.update("UPDATE assets SET sha256=? WHERE id=?","b".repeat(64),m.asset());
        var next=send("修改主题","retry-obsolete");var nc=claim(next);parsed(next,nc,"DRAFT","CONTINUE_PRODUCTION",Map.of("topic","新主题"),null,200);
        browser("/drafts/previews/"+retry.path("requestId").asText()+"/retry",Map.of(),"reject-later",409);
        assertEquals(retry.path("requestId"),browser("/drafts/previews/"+preview.path("requestId").asText()+"/retry",Map.of(),"still-original-successor",202).path("requestId"));
    }

    @Test void draftPreviewRejectsClientContentAndFractionalGenerationAtHttpBoundary() throws Exception {
        org.mockito.Mockito.when(storage.objectSize(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        var m=media();var run=send("制作主题","boundary-preview");var c=claim(run);var b=parsed(run,c,"DRAFT","NEW_PRODUCTION",Map.of("topic","主题"),null,200);
        var d=validatedDraft(run,c,b,m,"boundary-validation");publish(run,c,result(b,"DRAFT_READY","A",d,null,null),200);
        var body=json.createObjectNode().put("outputKey","out-1").put("previewProfile","LOW_360");body.set("draft",d);
        body.putObject("output").put("durationMs",1);
        browser("/drafts/previews",body,"client-content",400);
        body.remove("output");((com.fasterxml.jackson.databind.node.ObjectNode)body.path("draft")).put("editGeneration",1.5);
        browser("/drafts/previews",body,"fractional-generation",400);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM render_requests WHERE conversation_id=?",Integer.class,conversation));
    }

    @Test void strictDurationConditionsRemainEqualAcrossThePublicValidationBoundary() throws Exception {
        var media=media();var run=send("制作严格五秒短片","strict-duration");var claim=claim(run);
        var binding=parsed(run,claim,"DRAFT","NEW_PRODUCTION",Map.of("topic","主题","duration_min_ms",5000,"duration_max_ms",5000),null,200);
        var draft=validatedDraft(run,claim,binding,media,"strict-validation");
        var changed=binding.path("production").path("requirements").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("outputs").get(0)).put("duration_max_ms",6000);
        var clip=Map.of("asset_id",media.asset(),"source_in_ms",0,"source_out_ms",5000,"evidence_ids",List.of(media.evidence()),"reason","按原文开始");
        var content=json.valueToTree(Map.of("title","主题","outputs",List.of(Map.of("output_key","out-1","title","主片","clips",List.of(clip)))));
        var rejected=tool(run,claim,binding,"changed-conditions","validate_edit_draft",Map.of("draft",content,"requirements",changed,"draft_hash",com.agenticvideo.business.tools.ToolJson.hash(content),"requirements_hash",com.agenticvideo.business.tools.ToolJson.hash(changed)),200);
        assertEquals("REQUIREMENTS_MISMATCH",rejected.path("error").path("code").asText(),rejected.toString());
        publish(run,claim,result(binding,"DRAFT_READY","严格五秒草稿",draft,null,null),200);
        var snapshot=response(http.perform(get("/api/v1/conversations/"+conversation+"/snapshot").with(user(owner))),200);
        assertTrue(snapshot.path("agent").path("canAcceptDraft").asBoolean(),snapshot.toString());
        assertEquals(5000,snapshot.path("agent").path("currentDraft").path("normalizedContent").path("outputs").get(0).path("durationMs").asLong());
    }

    @Test void currentDraftSurvivesDiscussionAndAcceptanceCreatesAndConfirmsOnlyOneVersion() throws Exception {
        var media=media();var run=send("制作主题短片","production");var claim=claim(run);
        var binding=parsed(run,claim,"DRAFT","NEW_PRODUCTION",Map.of("topic","主题"),null,200);
        var draft=validatedDraft(run,claim,binding,media,"validate");
        publish(run,claim,result(binding,"DRAFT_READY","当前草稿",draft,null,null),200);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,project));
        var discussion=send("讨论节奏","discussion");var discussionClaim=claim(discussion);
        browser("/drafts/accept",draft,"accept",409);
        var independent=parsed(discussion,discussionClaim,"DISCUSS","INDEPENDENT",Map.of(),null,200);
        var accepted=browser("/drafts/accept",draft,"accept",200);
        assertEquals(accepted,browser("/drafts/accept",draft,"accept",200));
        assertEquals("out-1",accepted.path("outputs").get(0).path("outputKey").asText());
        assertFalse(accepted.path("outputs").get(0).path("outputId").asText().isBlank());
        publish(discussion,discussionClaim,result(independent,"ANSWER","节奏可以舒缓",null,null,null),200);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,project));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM plan_confirmations WHERE project_id=? AND source_json->>'type'='USER_INTERFACE'",Integer.class,project));
        var next=send("修改原稿","after-frozen");var nextClaim=claim(next);
        parsed(next,nextClaim,"DRAFT","CONTINUE_PRODUCTION",Map.of("topic","改变"),null,409);
        var fresh=parsed(next,nextClaim,"DRAFT","NEW_PRODUCTION",Map.of(),null,200);
        assertNotEquals(binding.path("production").path("productionId"),fresh.path("production").path("productionId"));
        assertEquals("",fresh.path("production").path("requirements").path("topic").asText());
        assertEquals("主题",db.queryForObject("SELECT requirements_json->>'topic' FROM edit_productions WHERE id=?",String.class,UUID.fromString(binding.path("production").path("productionId").asText())));
    }
    JsonNode question(JsonNode binding) {
        var q=json.createObjectNode().put("questionId",UUID.randomUUID().toString()).put("prompt","需要什么主题？").put("waitId","wait-1");
        for(String field:List.of("taskId","threadId","executionId"))q.set(field,binding.path("identity").path(field));
        q.set("productionId",binding.path("production").isNull()?json.nullNode():binding.path("production").path("productionId"));
        q.set("editGeneration",binding.path("production").isNull()?json.nullNode():binding.path("production").path("editGeneration"));return q;
    }
    @Test void independentQuestionPreservesWaitAndOnlyExplicitAnswerTransfersOriginalExecution() throws Exception {
        media();var run=send("制作短片","ask");var claim=claim(run);var binding=parsed(run,claim,"DRAFT","NEW_PRODUCTION",Map.of(),null,200);var question=question(binding);
        publish(run,claim,result(binding,"WAITING_INPUT","需要什么主题？",null,question,null),200);
        var query=send("先讨论剪辑手法","independent");var queryClaim=claim(query);var independent=parsed(query,queryClaim,"DISCUSS","INDEPENDENT",Map.of(),null,200);
        publish(query,queryClaim,result(independent,"ANSWER","可以用硬切",null,null,null),200);
        assertEquals("OPEN",db.queryForObject("SELECT status FROM conversation_questions WHERE id=?",String.class,UUID.fromString(question.path("questionId").asText())));
        var answer=send("主题是成本","answer");var answerClaim=claim(answer);
        parsed(answer,answerClaim,"DRAFT","ANSWER_QUESTION",Map.of("topic","成本"),UUID.randomUUID().toString(),409);
        var resumed=parsed(answer,answerClaim,"DRAFT","ANSWER_QUESTION",Map.of("topic","成本"),question.path("questionId").asText(),200);
        assertEquals(binding.path("identity").path("executionId"),resumed.path("identity").path("executionId"));
        assertEquals(binding.path("identity").path("threadId"),resumed.path("identity").path("threadId"));
        assertEquals(binding.path("scope"),resumed.path("scope"));
        assertEquals(answer.path("messageId"),resumed.path("production").path("requirementsSources").path("topic").path("message_id"));
        assertEquals("ANSWERED",db.queryForObject("SELECT status FROM conversation_questions WHERE id=?",String.class,UUID.fromString(question.path("questionId").asText())));
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,UUID.fromString(run.path("taskId").asText())));
        var failure=json.valueToTree(Map.of("code","BUDGET_EXHAUSTED","message","预算已耗尽","recoverable",false));
        publish(answer,answerClaim,result(resumed,"INCOMPLETE","预算已耗尽",null,null,failure),200);
        var replay=internal(run.path("taskId").asText(),"claim",Map.of("executorId","fixture","claimRequestId",UUID.randomUUID()),200);
        assertFalse(replay.path("claimed").asBoolean());
        assertEquals(binding,parsed(run,claim,"DRAFT","NEW_PRODUCTION",Map.of(),null,200));
    }
    @Test void editingInvalidatesOldDraftEvenAfterFailureAndRejectsLateOrExpiredWrites() throws Exception {
        var media=media();var run=send("制作主题","first");var claim=claim(run);var binding=parsed(run,claim,"DRAFT","NEW_PRODUCTION",Map.of("topic","主题"),null,200);
        var draft=validatedDraft(run,claim,binding,media,"first-validation");publish(run,claim,result(binding,"DRAFT_READY","草稿 A",draft,null,null),200);
        var edit=send("修改主题为成本","edit");var editClaim=claim(edit);var changed=parsed(edit,editClaim,"DRAFT","CONTINUE_PRODUCTION",Map.of("topic","成本"),null,200);
        assertEquals(2,changed.path("production").path("editGeneration").asLong());
        browser("/drafts/accept",draft,"stale",409);
        tool(run,claim,binding,"late","validate_edit_draft",Map.of(),409);
        db.update("UPDATE task_attempts SET lease_expires_at=now()-interval '1 second' WHERE id=?",UUID.fromString(editClaim.path("attemptId").asText()));
        tool(edit,editClaim,changed,"expired","validate_edit_draft",Map.of(),409);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM edit_draft_validations WHERE production_id=?",Integer.class,UUID.fromString(changed.path("production").path("productionId").asText())));
        db.update("UPDATE task_attempts SET lease_expires_at=now()+interval '1 minute' WHERE id=?",UUID.fromString(editClaim.path("attemptId").asText()));
        publish(edit,editClaim,result(changed,"FAILED","执行失败",null,null,json.valueToTree(Map.of("code","UNKNOWN","message","提交状态未知","recoverable",false))),200);
        var query=send("聊聊节奏","query");var queryClaim=claim(query);var independent=parsed(query,queryClaim,"DISCUSS","INDEPENDENT",Map.of(),null,200);
        publish(query,queryClaim,result(independent,"ANSWER","保持节奏",null,null,null),200);
        browser("/drafts/accept",draft,"still-stale",409);
        assertEquals("成本",db.queryForObject("SELECT requirements_json->>'topic' FROM edit_productions WHERE id=?",String.class,UUID.fromString(changed.path("production").path("productionId").asText())));
        var snapshot=response(http.perform(get("/api/v1/conversations/"+conversation+"/snapshot").with(user(owner))),200);
        assertFalse(snapshot.path("agent").path("canAcceptDraft").asBoolean());
        assertEquals(draft.path("messageId"),snapshot.path("agent").path("currentProduction").path("currentDraft").path("messageId"));
    }
    @Test void stopPreventsPublicationAndClosesOnlySelectedWait() throws Exception {
        media();var run=send("制作","stop-wait");var claim=claim(run);var binding=parsed(run,claim,"DRAFT","NEW_PRODUCTION",Map.of(),null,200);var q=question(binding);
        publish(run,claim,result(binding,"WAITING_INPUT","主题？",null,q,null),200);
        response(http.perform(post("/api/v1/tasks/"+run.path("taskId").asText()+"/cancel").with(user(owner)).with(csrf())),202);
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM conversation_questions WHERE id=?",String.class,UUID.fromString(q.path("questionId").asText())));
        var next=send("讨论","stop-running");var nextClaim=claim(next);var independent=parsed(next,nextClaim,"DISCUSS","INDEPENDENT",Map.of(),null,200);
        response(http.perform(post("/api/v1/tasks/"+next.path("taskId").asText()+"/cancel").with(user(owner)).with(csrf())),202);
        publish(next,nextClaim,result(independent,"ANSWER","迟到",null,null,null),409);
        var stopped=credentials(nextClaim);stopped.put("processExited",true);internal(next.path("taskId").asText(),"cancelled",stopped,200);
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,UUID.fromString(next.path("taskId").asText())));
    }
    @Test void scopeReceiptsMustBelongToThisRunAndActualReadEvidence() throws Exception {
        var media=media();var run=send("读原文","query");var claim=claim(run);var binding=parsed(run,claim,"QUERY","INDEPENDENT",Map.of(),null,200);
        var answer=(com.fasterxml.jackson.databind.node.ObjectNode)result(binding,"ANSWER","先确定主题",null,null,null);
        answer.withArray("scopeReceipts").addObject().put("scopeId",binding.path("scope").path("scope_id").asText()).put("callId","read").putArray("evidenceIds").add(media.evidence().toString());
        publish(run,claim,answer,409);
        var read=tool(run,claim,binding,"read","read_video_range",Map.of("asset_id",media.asset(),"start_ms",0,"end_ms",5000),200);
        assertEquals("READY",read.path("status").asText());publish(run,claim,answer,200);
        var forged=answer.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)forged.path("scopeReceipts").get(0).path("evidenceIds")).add(UUID.randomUUID().toString());publish(run,claim,forged,409);
    }
    @Test void confirmationFailureRollsBackVersionAndConcurrentSameKeyReturnsOneResult() throws Exception {
        var media=media();var run=send("制作","rollback");var claim=claim(run);var binding=parsed(run,claim,"DRAFT","NEW_PRODUCTION",Map.of(),null,200);
        var draft=validatedDraft(run,claim,binding,media,"validate");publish(run,claim,result(binding,"DRAFT_READY","草稿",draft,null,null),200);
        // Inject the failure at the external transaction boundary after ProductionSaves has inserted V1.
        db.execute("CREATE FUNCTION reject_lifecycle_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture confirmation failure'; END $$");
        db.execute("CREATE TRIGGER reject_lifecycle_confirmation BEFORE INSERT ON plan_confirmations FOR EACH ROW EXECUTE FUNCTION reject_lifecycle_confirmation()");
        try {assertThrows(jakarta.servlet.ServletException.class,()->browser("/drafts/accept",draft,"accept",200));}
        finally {db.execute("DROP TRIGGER reject_lifecycle_confirmation ON plan_confirmations");db.execute("DROP FUNCTION reject_lifecycle_confirmation()");}
        assertEquals(0,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,project));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM conversation_draft_acceptances WHERE conversation_id=?",Integer.class,conversation));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->browser("/drafts/accept",draft,"accept",200));var second=pool.submit(()->browser("/drafts/accept",draft,"accept",200));
            assertEquals(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        assertEquals(1,db.queryForObject("SELECT count(*) FROM plan_revisions WHERE project_id=?",Integer.class,project));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM plan_confirmations WHERE project_id=?",Integer.class,project));
    }
    @Test void projectPermissionsAndInternalCredentialsCannotBeBypassed() throws Exception {
        UUID outsider=UUID.randomUUID();db.update("INSERT INTO users(id,normalized_username,password_hash) VALUES (?,?,'fixture')",outsider,outsider.toString());
        response(http.perform(post("/api/v1/conversations/"+conversation+"/turns").with(user(outsider.toString())).with(csrf()).header("Idempotency-Key","x").contentType("application/json").content("{\"content\":\"偷看\"}")),404);
        var run=send("讨论","auth");assertEquals(401,responseStatus(post("/internal/v1/agent-runs/"+run.path("taskId").asText()+"/claim").contentType("application/json").content("{}")));
        var claim=claim(run);var invalid=credentials(claim);invalid.put("leaseToken","wrong");invalid.put("parsed",Map.of("mode","DISCUSS","relation","INDEPENDENT","conditionsPatch",Map.of(),"questionId",UUID.randomUUID()));
        internal(run.path("taskId").asText(),"parsed",invalid,400);
        invalid.put("parsed",json.readTree("{\"mode\":\"DISCUSS\",\"relation\":\"INDEPENDENT\",\"conditionsPatch\":{},\"questionId\":null}"));
        internal(run.path("taskId").asText(),"parsed",invalid,409);
    }
    @Test void dependencyFailureCanPublishTerminalReasonWithoutReopeningOldScope() throws Exception {
        var media=media();var run=send("问素材","dependency-failure");var claim=claim(run);var binding=parsed(run,claim,"QUERY","INDEPENDENT",Map.of(),null,200);
        db.update("UPDATE assets SET validation_status='INVALID' WHERE id=?",media.asset());
        var failed=result(binding,"FAILED","固定素材失效",null,null,json.valueToTree(Map.of("code","SNAPSHOT_INVALID","message","固定素材失效","recoverable",false)));
        publish(run,claim,failed,200);
        assertEquals("FAILED",db.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,UUID.fromString(run.path("taskId").asText())));
    }
    @Test void queryAnswerResumesPreviouslyReadReceiptsWithoutReadingAgain() throws Exception {
        var media=media();var run=send("读取并澄清","query-wait");var claim=claim(run);var binding=parsed(run,claim,"QUERY","INDEPENDENT",Map.of(),null,200);
        tool(run,claim,binding,"original-read","read_video_range",Map.of("asset_id",media.asset(),"start_ms",0,"end_ms",5000),200);
        var q=question(binding);publish(run,claim,result(binding,"WAITING_INPUT","需要说明哪部分？",null,q,null),200);
        var answer=send("说明主题","query-answer");var answerClaim=claim(answer);var resumed=parsed(answer,answerClaim,"QUERY","ANSWER_QUESTION",Map.of(),q.path("questionId").asText(),200);
        var response=(com.fasterxml.jackson.databind.node.ObjectNode)result(resumed,"ANSWER","先确定主题",null,null,null);
        response.withArray("scopeReceipts").addObject().put("scopeId",binding.path("scope").path("scope_id").asText()).put("callId","original-read").putArray("evidenceIds").add(media.evidence().toString());
        publish(answer,answerClaim,response,200);
        assertEquals(1,db.queryForObject("SELECT count(*) FROM question_tool_receipts WHERE scope_id=?",Integer.class,UUID.fromString(binding.path("scope").path("scope_id").asText())));
    }
    @Test void preparationFailureIsDurableWithoutParsedBinding() throws Exception {
        var run=send("无法理解","prepare-failed");var claim=claim(run);
        var result=json.createObjectNode().put("schemaVersion","1.0").put("status","FAILED").put("mode","QUERY").put("content","理解调用失败");
        result.set("identity",claim.path("input").path("identity"));result.putArray("scopeReceipts");
        for(String key:List.of("productionId","editGeneration","draft","question"))result.putNull(key);
        result.set("failure",json.valueToTree(Map.of("code","MODEL_UNAVAILABLE","message","理解调用失败","recoverable",true)));
        publish(run,claim,result,200);assertEquals(0,db.queryForObject("SELECT count(*) FROM question_scopes WHERE project_id=?",Integer.class,project));
    }
    @Test void browserProductionCannotUseCliValidationOrSaveAndSourcesCannotBeForged() throws Exception {
        var media=media();var run=send("制作主题","no-cli");var claim=claim(run);var binding=parsed(run,claim,"DRAFT","NEW_PRODUCTION",Map.of("topic","主题"),null,200);
        String production=binding.path("production").path("productionId").asText();
        response(http.perform(post("/internal/v1/questions/productions/"+production+"/tools").header("Authorization","Bearer "+TOKEN).contentType("application/json").content("{\"call_id\":\"cli\",\"tool_name\":\"validate_edit_draft\",\"request_hash\":\"hash\"}")),409);
        var draft=validatedDraft(run,claim,binding,media,"valid");publish(run,claim,result(binding,"DRAFT_READY","草稿",draft,null,null),200);
        var body=json.createObjectNode().put("project_id",project.toString()).put("requirement_id",production).put("scope_id",binding.path("scope").path("scope_id").asText()).put("validation_id",draft.path("validationId").asText()).put("draft_hash",draft.path("draftHash").asText()).put("requirements_hash",draft.path("requirementsHash").asText());
        body.set("requirements_sources",json.valueToTree(Map.of("topic",Map.of("kind","USER","message_id",UUID.randomUUID()))));
        response(http.perform(post("/internal/v1/questions/productions/"+production+"/save").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(body.toString())),409);
        var foreign=UUID.randomUUID();db.update("INSERT INTO question_scopes(id,project_id,assets_json) VALUES (?,?,'[]')",foreign,project);
        db.update("INSERT INTO question_tool_receipts(scope_id,call_id,request_json) VALUES (?,'foreign','{}')",foreign);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO agent_run_tool_calls(task_id,scope_id,call_id) VALUES (?,?,'foreign')",UUID.fromString(run.path("taskId").asText()),foreign));
        browser("/drafts/accept",draft,"accept-authoritative",200);
        assertEquals(run.path("messageId").asText(),db.queryForObject("SELECT production_requirements_sources_json->'topic'->>'message_id' FROM plan_revisions WHERE project_id=?",String.class,project));
    }
    @Test void explicitAssetReferencesNarrowOnlyThisQuestionScope() throws Exception {
        var first=media();var second=media();
        db.update("UPDATE conversations SET asset_ids_json=?::jsonb WHERE id=?",json.valueToTree(List.of(first.asset(),second.asset())).toString(),conversation);
        var run=browser("/turns",Map.of("content","只问第一份素材","references",List.of(Map.of("type","ASSET","id",first.asset()))),"selected",202);
        var claim=claim(run);var binding=parsed(run,claim,"QUERY","INDEPENDENT",Map.of(),null,200);
        assertEquals(1,binding.path("scope").path("assets").size());
        assertEquals(first.asset().toString(),binding.path("scope").path("assets").get(0).path("id").asText());
        assertEquals(2,db.queryForObject("SELECT jsonb_array_length(asset_ids_json) FROM conversations WHERE id=?",Integer.class,conversation));
        var denied=tool(run,claim,binding,"outside","read_video_range",Map.of("asset_id",second.asset(),"start_ms",0,"end_ms",5000),200);
        assertEquals("ASSET_OUTSIDE_SCOPE",denied.path("error").path("code").asText());
        var snapshot=response(http.perform(get("/api/v1/conversations/"+conversation+"/snapshot").with(user(owner))),200);
        assertTrue(snapshot.path("agent").path("currentProduction").isNull());
    }
    int responseStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {return http.perform(request).andReturn().getResponse().getStatus();}
}

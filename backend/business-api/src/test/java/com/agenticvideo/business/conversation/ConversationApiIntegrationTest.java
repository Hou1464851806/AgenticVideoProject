package com.agenticvideo.business.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.util.Map;
import java.util.List;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("internal.worker-token", () -> "conversation-integration-worker-token");
        properties.add("app.auth.max-attempts",()->100);
    }
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate fixtures;

    @Test
    void newChatHasDurableWelcomeAndDeduplicatesCreation() throws Exception {
        BrowserApi owner=signedIn("new-chat-owner"),outsider=signedIn("new-chat-outsider");
        String project=json(owner.post("/projects","{\"name\":\"直接开始聊天\"}")).path("data").path("id").asText();
        String path="/projects/"+project+"/conversations";
        var created=owner.post(path,"{}","new-chat-request");
        assertEquals(201,created.statusCode(),created.body());
        JsonNode session=json(created).path("data");
        assertEquals(session,json(owner.post(path,"{}","new-chat-request")).path("data"));
        assertEquals(1,json(owner.get(path)).path("data").path("items").size());
        String conversation=session.path("id").asText();
        JsonNode snapshot=json(owner.get("/conversations/"+conversation+"/snapshot")).path("data");
        assertEquals(1,snapshot.path("items").size());
        assertEquals("ASSISTANT",snapshot.path("items").get(0).path("role").asText());
        org.junit.jupiter.api.Assertions.assertTrue(snapshot.path("items").get(0).path("content").asText().contains("你好"));
        org.junit.jupiter.api.Assertions.assertTrue(snapshot.path("latestTask").isNull());
        assertEquals(snapshot.path("items"),json(owner.get("/conversations/"+conversation+"/snapshot")).path("data").path("items"));
        assertEquals(404,outsider.post(path,"{}","outsider-new-chat").statusCode());
    }

    @Test
    void newChatScopeCanBeNarrowedWithoutChangingTheAcceptedInput() throws Exception {
        BrowserApi owner=signedIn("new-chat-scope");
        String project=json(owner.post("/projects","{\"name\":\"自然语言固定范围\"}")).path("data").path("id").asText();
        java.util.UUID projectId=java.util.UUID.fromString(project);
        var first=newChatAsset(projectId,"A.mp4");var second=newChatAsset(projectId,"B.mp4");
        String session=json(owner.post("/projects/"+project+"/conversations","{}","scope-chat")).path("data").path("id").asText();
        assertEquals(2,json(owner.get("/projects/"+project+"/conversations")).path("data").path("items").get(0).path("assetIds").size());
        var accepted=owner.post("/conversations/"+session+"/turns","{\"content\":\"只用 A.mp4，查找讲话内容\",\"context\":{\"naturalLanguage\":true}}","scope-query");
        assertEquals(202,accepted.statusCode(),accepted.body());
        String task=json(accepted).path("data").path("taskId").asText();
        var claimRequest=Map.of("executorId","natural-scope-test","claimRequestId",java.util.UUID.randomUUID());
        JsonNode claim=json(internal(task,"claim",claimRequest)).path("data");
        var restriction=Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","selected-scope","name","restrict_asset_scope","arguments",Map.of("assetIds",List.of(first)));
        var selected=internal(task,"tool",restriction);assertEquals(200,selected.statusCode(),selected.body());
        assertEquals(json(selected).path("data"),json(internal(task,"tool",restriction)).path("data"));
        var assets=internal(task,"tool",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","limited-assets","name","list_assets","arguments",Map.of()));
        assertEquals(200,assets.statusCode(),assets.body());
        JsonNode visible=json(assets).path("data").path("assets");assertEquals(1,visible.size());assertEquals(first.toString(),visible.get(0).path("assetId").asText());
        assertEquals(claim.path("input"),json(internal(task,"claim",claimRequest)).path("data").path("input"));
        assertEquals(List.of(first.toString()),new ObjectMapper().convertValue(json(owner.get("/projects/"+project+"/conversations")).path("data").path("items").get(0).path("assetIds"),List.class));
        newChatAsset(projectId,"C.mp4");
        assertEquals(1,json(owner.get("/projects/"+project+"/conversations")).path("data").path("items").get(0).path("assetIds").size());
        var outside=internal(task,"tool",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","cannot-expand","name","restrict_asset_scope","arguments",Map.of("assetIds",List.of(second))));
        assertEquals(404,outside.statusCode(),outside.body());
    }

    private java.util.UUID newChatAsset(java.util.UUID project,String filename) {
        var upload=java.util.UUID.randomUUID();var asset=java.util.UUID.randomUUID();
        fixtures.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,?,1,?,1,1,?,? FROM projects WHERE id=?",upload,upload.toString(),filename,"a".repeat(64),upload.toString(),java.util.UUID.randomUUID(),project);
        fixtures.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status) VALUES (?,?,?,?,?,1,?,'VALID')",asset,project,upload,asset.toString(),filename,"a".repeat(64));
        return asset;
    }

    @Test
    void editingBriefCorrectionsKeepConditionSourcesAndDeclaredDefaults() throws Exception {
        BrowserApi owner=signedIn("brief-corrections"),outsider=signedIn("brief-outsider");
        String project=json(owner.post("/projects","{\"name\":\"制作需求简报\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"教程需求\"}")).path("data").path("id").asText();
        var accepted=owner.post("/conversations/"+session+"/turns","{\"content\":\"制作一条教程，7至11秒，不展示姓名\",\"context\":{\"requirements\":true}}","brief-first");
        assertEquals(202,accepted.statusCode(),accepted.body());JsonNode first=json(accepted).path("data");
        String task=first.path("taskId").asText();
        var claimResponse=internal(task,"claim",Map.of("executorId","brief-test","claimRequestId",java.util.UUID.randomUUID()));
        assertEquals(200,claimResponse.statusCode(),claimResponse.body());JsonNode claim=json(claimResponse).path("data");
        Map<String,Object> fields=Map.of("topic",Map.of("value","教程","basis","EXPLICIT"),"count",Map.of("value",1,"basis","DEFAULT"),"duration",Map.of("value",Map.of("minMs",7000,"maxMs",11000),"basis","EXPLICIT"),"forbiddenContent",Map.of("value",List.of("姓名"),"basis","EXPLICIT"));
        var call=Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","brief-update","name","update_editing_brief","arguments",Map.of("fields",fields));
        var update=internal(task,"tool",call);assertEquals(200,update.statusCode(),update.body());
        assertEquals(json(update).path("data"),json(internal(task,"tool",call)).path("data"));
        JsonNode brief=json(owner.get("/conversations/"+session+"/snapshot")).path("data").path("brief");
        assertEquals("教程",brief.path("fields").path("topic").path("value").asText());
        assertEquals(first.path("messageId").asText(),brief.path("fields").path("topic").path("sourceMessageId").asText());
        assertEquals("DEFAULT",brief.path("fields").path("count").path("basis").asText());
        assertEquals(404,outsider.get("/conversations/"+session+"/snapshot").statusCode());
        assertEquals(200,internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","brief-done","resultType","CONVERSATION_ANSWERED","result",Map.of("content","需求简报已记录；默认数量为一条。","evidenceIds",List.of()))).statusCode());
        JsonNode correction=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"时长改为8至10秒，还不能展示电话\",\"context\":{\"requirements\":true}}","brief-correction")).path("data");
        task=correction.path("taskId").asText();claim=json(internal(task,"claim",Map.of("executorId","brief-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var corrected=internal(task,"tool",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","brief-corrected","name","update_editing_brief","arguments",Map.of("fields",Map.of("duration",Map.of("value",Map.of("minMs",8000,"maxMs",10000),"basis","EXPLICIT"),"forbiddenContent",Map.of("value",List.of("姓名","电话"),"basis","EXPLICIT")))));
        assertEquals(200,corrected.statusCode(),corrected.body());
        brief=json(owner.get("/conversations/"+session+"/snapshot")).path("data").path("brief");
        assertEquals(8000,brief.path("fields").path("duration").path("value").path("minMs").asInt());
        assertEquals(correction.path("messageId").asText(),brief.path("fields").path("duration").path("sourceMessageId").asText());
        assertEquals(first.path("messageId").asText(),brief.path("fields").path("topic").path("sourceMessageId").asText());
        assertEquals(2,brief.path("history").size());
        assertEquals(7000,brief.path("history").get(0).path("fields").path("duration").path("value").path("minMs").asInt());
        assertEquals(200,internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","correction-done","resultType","CONVERSATION_ANSWERED","result",Map.of("content","已纠正简报。","evidenceIds",List.of()))).statusCode());
        var fromBrief=owner.post("/conversations/"+session+"/turns","{\"content\":\"按已检查简报生成方案\",\"context\":{\"briefRevision\":2,\"editing\":{\"topic\":\"旧表单主题\",\"minDurationMs\":1000,\"maxDurationMs\":2000,\"profile\":\"LANDSCAPE_720\"}}}","apply-corrected-brief");
        assertEquals(202,fromBrief.statusCode(),fromBrief.body());
        String produced=json(fromBrief).path("data").path("taskId").asText();
        JsonNode fixed=json(internal(produced,"claim",Map.of("executorId","brief-production","claimRequestId",java.util.UUID.randomUUID()))).path("data").path("input");
        assertEquals("教程",fixed.path("editing").path("topic").asText());assertEquals(8000,fixed.path("editing").path("minDurationMs").asInt());assertEquals(10000,fixed.path("editing").path("maxDurationMs").asInt());
        assertEquals(List.of("姓名","电话"),new ObjectMapper().convertValue(fixed.path("editing").path("forbiddenContent"),List.class));
        assertEquals(2,fixed.path("brief").path("revision").asInt());
    }

    @Test
    void clarificationWaitIsDurableAndOnlyItsExplicitAnswerResumesALinkedTask() throws Exception {
        BrowserApi owner=signedIn("clarification-owner"),outsider=signedIn("clarification-outsider");
        String project=json(owner.post("/projects","{\"name\":\"关联澄清\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"等待时长\"}")).path("data").path("id").asText();
        JsonNode original=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"制作教程，请先确认时长\",\"context\":{\"requirements\":true}}","waiting-original")).path("data");
        String task=original.path("taskId").asText();JsonNode claim=json(internal(task,"claim",Map.of("executorId","waiting-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var result=Map.of("question",Map.of("prompt","希望制作多少秒的视频？","fields",List.of("duration"),"reason","关键时长未说明"));
        var publication=Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","ask-duration","resultType","CONVERSATION_WAITING","result",result);
        var waiting=internal(task,"complete",publication);assertEquals(200,waiting.statusCode(),waiting.body());
        JsonNode receipt=json(waiting).path("data");String question=receipt.path("questionId").asText();org.junit.jupiter.api.Assertions.assertFalse(question.isBlank());
        assertEquals(receipt,json(internal(task,"complete",publication)).path("data"));
        assertEquals("WAITING_INPUT",json(owner.get("/tasks/"+task)).path("data").path("status").asText());
        assertEquals(false,json(internal(task,"claim",Map.of("executorId","restart-test","claimRequestId",java.util.UUID.randomUUID()))).path("data").path("claimed").asBoolean());
        assertEquals(409,owner.post("/conversations/"+session+"/turns","{\"content\":\"8至10秒\"}","unlinked-answer").statusCode());
        JsonNode snapshot=json(owner.get("/conversations/"+session+"/snapshot")).path("data");
        assertEquals(1,snapshot.path("questions").size());assertEquals(question,snapshot.path("questions").get(0).path("id").asText());
        String path="/conversations/"+session+"/questions/"+question+"/answers";
        assertEquals(404,outsider.post(path,"{\"content\":\"8至10秒\"}","answer").statusCode());
        var answered=owner.post(path,"{\"content\":\"8至10秒\"}","answer");assertEquals(202,answered.statusCode(),answered.body());JsonNode next=json(answered).path("data");
        org.junit.jupiter.api.Assertions.assertNotEquals(task,next.path("taskId").asText());assertEquals(task,next.path("resumesTaskId").asText());
        assertEquals(next,json(owner.post(path,"{\"content\":\"8至10秒\"}","answer")).path("data"));
        assertEquals(409,owner.post(path,"{\"content\":\"9至11秒\"}","different-answer").statusCode());
        assertEquals(409,internal(task,"heartbeat",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText())).statusCode());
        snapshot=json(owner.get("/conversations/"+session+"/snapshot")).path("data");assertEquals("ANSWERED",snapshot.path("questions").get(0).path("status").asText());
        assertEquals(2,snapshot.path("items").size());assertEquals("8至10秒",snapshot.path("items").get(1).path("content").asText());
        JsonNode resumed=json(internal(next.path("taskId").asText(),"claim",Map.of("executorId","resumed-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        assertEquals(question,resumed.path("input").path("clarification").path("questionId").asText());
        assertEquals("8至10秒",resumed.path("input").path("clarification").path("answer").asText());
    }

    @Test
    void cancellingAWaitingQuestionReleasesTheConversationAndCannotReviveIt() throws Exception {
        BrowserApi owner=signedIn("waiting-cancel");String project=json(owner.post("/projects","{\"name\":\"停止澄清\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"等待教程\"}")).path("data").path("id").asText();
        JsonNode original=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"准备教程方案，先问清楚条件\",\"context\":{\"requirements\":true,\"editing\":{\"topic\":\"教程\",\"minDurationMs\":1000,\"maxDurationMs\":2000,\"profile\":\"LANDSCAPE_720\"}}}","waiting-plan")).path("data");
        String task=original.path("taskId").asText();JsonNode claim=json(internal(task,"claim",Map.of("executorId","wait-plan-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var waiting=internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","ask-plan","resultType","CONVERSATION_WAITING","result",Map.of("question",Map.of("prompt","是否允许展示姓名？","reason","禁用内容未说明","fields",List.of("forbiddenContent")))));
        assertEquals(200,waiting.statusCode(),waiting.body());String question=json(waiting).path("data").path("questionId").asText();
        String other=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"另一会话编辑\"}")).path("data").path("id").asText();
        var reusedPlan=owner.post("/conversations/"+other+"/turns",new ObjectMapper().writeValueAsString(Map.of("content","继续修改这份方案","context",Map.of("editing",Map.of("topic","教程","minDurationMs",1000,"maxDurationMs",2000,"profile","LANDSCAPE_720","planId",original.path("planId").asText())))),"plan-slot-free");
        assertEquals(202,reusedPlan.statusCode(),reusedPlan.body());
        assertEquals(202,owner.post("/tasks/"+task+"/cancel","{}").statusCode());
        assertEquals("CANCELLED",json(owner.get("/tasks/"+task)).path("data").path("status").asText());
        JsonNode snapshot=json(owner.get("/conversations/"+session+"/snapshot")).path("data");assertEquals("CANCELLED",snapshot.path("questions").get(0).path("status").asText());
        assertEquals(409,owner.post("/conversations/"+session+"/questions/"+question+"/answers","{\"content\":\"禁止姓名\"}","late-answer").statusCode());
        assertEquals(202,owner.post("/conversations/"+session+"/turns","{\"content\":\"开始另一个素材查询\"}","after-wait-cancel").statusCode());
    }

    @Test
    void explicitlyStartingANewRequirementClosesTheQuestionAndIsIdempotent() throws Exception {
        BrowserApi owner=signedIn("waiting-new-requirement");String project=json(owner.post("/projects","{\"name\":\"明确另起需求\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"新需求入口\"}")).path("data").path("id").asText();
        JsonNode original=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"制作视频\",\"context\":{\"requirements\":true}}","old-demand")).path("data");
        String task=original.path("taskId").asText();JsonNode claim=json(internal(task,"claim",Map.of("executorId","new-demand-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var waiting=internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","ask-topic","resultType","CONVERSATION_WAITING","result",Map.of("question",Map.of("prompt","希望制作什么主题？","reason","主题缺失","fields",List.of("topic")))));
        String question=json(waiting).path("data").path("questionId").asText();String path="/conversations/"+session+"/questions/"+question+"/new-requirement";
        String body="{\"content\":\"放弃上轮，开始整理旅行视频需求\",\"context\":{\"requirements\":true}}";
        var accepted=owner.post(path,body,"explicit-new");assertEquals(202,accepted.statusCode(),accepted.body());JsonNode fresh=json(accepted).path("data");
        assertEquals(fresh,json(owner.post(path,body,"explicit-new")).path("data"));
        assertEquals("CANCELLED",json(owner.get("/tasks/"+task)).path("data").path("status").asText());
        assertEquals(409,owner.post("/conversations/"+session+"/questions/"+question+"/answers","{\"content\":\"教程\"}","old-answer").statusCode());
        JsonNode history=json(owner.get("/conversations/"+session+"/snapshot")).path("data");assertEquals(2,history.path("items").size());assertEquals("CANCELLED",history.path("questions").get(0).path("status").asText());
        JsonNode next=json(internal(fresh.path("taskId").asText(),"claim",Map.of("executorId","fresh-requirement","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        assertEquals(true,next.path("input").path("startsNewRequirement").asBoolean());
        assertEquals("放弃上轮，开始整理旅行视频需求",next.path("input").path("content").asText());
    }

    @Test
    void answeringAQuestionRechecksTheOriginalFixedAssetAndLeavesInvalidAnswersUnaccepted() throws Exception {
        BrowserApi owner=signedIn("question-fixed-asset");String project=json(owner.post("/projects","{\"name\":\"恢复素材复核\"}")).path("data").path("id").asText();
        var projectId=java.util.UUID.fromString(project);var asset=java.util.UUID.randomUUID();var upload=java.util.UUID.randomUUID();
        fixtures.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'question-source.mp4',1,?,1,1,?,? FROM projects WHERE id=?",upload,upload.toString(),"a".repeat(64),upload.toString(),java.util.UUID.randomUUID(),projectId);
        fixtures.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status) VALUES (?,?,?,?,'question-source.mp4',1,?,'VALID')",asset,projectId,upload,asset.toString(),"a".repeat(64));
        String session=json(owner.post("/projects/"+project+"/conversations",new ObjectMapper().writeValueAsString(Map.of("title","素材恢复","assetIds",List.of(asset))))).path("data").path("id").asText();
        String task=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"制作教程，时长先问我\",\"context\":{\"requirements\":true}}","asset-question")).path("data").path("taskId").asText();
        JsonNode claim=json(internal(task,"claim",Map.of("executorId","question-asset-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        String question=json(internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","ask-asset-duration","resultType","CONVERSATION_WAITING","result",Map.of("question",Map.of("prompt","制作多少秒？","reason","时长缺失","fields",List.of("duration")))))).path("data").path("questionId").asText();
        fixtures.update("UPDATE assets SET validation_status='INVALID' WHERE id=?",asset);
        var answer=owner.post("/conversations/"+session+"/questions/"+question+"/answers","{\"content\":\"8至10秒\"}","invalid-asset-answer");assertEquals(404,answer.statusCode(),answer.body());
        JsonNode snapshot=json(owner.get("/conversations/"+session+"/snapshot")).path("data");assertEquals("WAITING_INPUT",snapshot.path("latestTask").path("status").asText());assertEquals("OPEN",snapshot.path("questions").get(0).path("status").asText());assertEquals(1,snapshot.path("items").size());
    }

    @Test
    void changingThePlanBaseWhileWaitingNeedsAnExplicitBaseAndCreatesALinkedExecution() throws Exception {
        BrowserApi owner=signedIn("question-plan-base");String project=json(owner.post("/projects","{\"name\":\"基准变更恢复\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"编辑澄清\"}")).path("data").path("id").asText();
        JsonNode original=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"制作教程，先确认主题\",\"context\":{\"requirements\":true,\"editing\":{\"topic\":\"教程\",\"minDurationMs\":1000,\"maxDurationMs\":2000,\"profile\":\"LANDSCAPE_720\"}}}","base-question")).path("data");
        String task=original.path("taskId").asText();JsonNode claim=json(internal(task,"claim",Map.of("executorId","base-question-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        String question=json(internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","ask-new-base","resultType","CONVERSATION_WAITING","result",Map.of("question",Map.of("prompt","主题如何调整？","reason","主题条件冲突","fields",List.of("topic")))))).path("data").path("questionId").asText();
        var revision=java.util.UUID.randomUUID();var plan=java.util.UUID.fromString(original.path("planId").asText());
        fixtures.update("INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES (?,?,?,1,?,'{\"schemaVersion\":\"1.0\",\"outputs\":[]}'::jsonb)",revision,java.util.UUID.fromString(project),plan,java.util.UUID.fromString(task));
        fixtures.update("UPDATE edit_plans SET next_revision_number=2 WHERE id=?",plan);
        String path="/conversations/"+session+"/questions/"+question+"/answers";
        assertEquals(409,owner.post(path,"{\"content\":\"改为上传主题\"}","missing-new-base").statusCode());
        assertEquals("WAITING_INPUT",json(owner.get("/tasks/"+task)).path("data").path("status").asText());
        var answer=owner.post(path,new ObjectMapper().writeValueAsString(Map.of("content","改为上传主题","editing",Map.of("topic","上传教程","minDurationMs",1000,"maxDurationMs",2000,"profile","LANDSCAPE_720","planId",plan,"baseRevisionId",revision))),"explicit-base-answer");assertEquals(202,answer.statusCode(),answer.body());
        JsonNode next=json(answer).path("data");assertEquals(task,next.path("resumesTaskId").asText());
        JsonNode resumed=json(internal(next.path("taskId").asText(),"claim",Map.of("executorId","new-base-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        assertEquals(revision.toString(),resumed.path("input").path("editing").path("baseRevisionId").asText());assertEquals("上传教程",resumed.path("input").path("editing").path("topic").asText());
        assertEquals(409,internal(task,"heartbeat",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText())).statusCode());
    }

    @Test
    void multipleSessionsKeepIndependentHistoryAndPrivateOwnershipWithoutAPlan() throws Exception {
        BrowserApi owner = signedIn("conversation-owner");
        BrowserApi outsider = signedIn("conversation-outsider");
        String project = json(owner.post("/projects", "{\"name\":\"查询素材\"}")).path("data").path("id").asText();
        var first = owner.post("/projects/" + project + "/conversations", "{\"title\":\"寻找主题\",\"assetIds\":[]}");
        assertEquals(201, first.statusCode(), first.body());
        String firstId = json(first).path("data").path("id").asText();
        var second = owner.post("/projects/" + project + "/conversations", "{\"title\":\"查找人名\",\"assetIds\":[]}");
        assertEquals(201, second.statusCode(), second.body());
        String secondId = json(second).path("data").path("id").asText();
        org.junit.jupiter.api.Assertions.assertNotEquals(firstId, secondId);
        assertEquals(2, json(owner.get("/projects/" + project + "/conversations")).path("data").path("items").size());
        for (String id : java.util.List.of(firstId, secondId)) {
            var history = owner.get("/conversations/" + id + "/messages");
            assertEquals(200, history.statusCode(), history.body());
            assertEquals(0, json(history).path("data").path("items").size());
            assertEquals(404, outsider.get("/conversations/" + id + "/messages").statusCode());
        }
        assertEquals(404, outsider.get("/projects/" + project + "/conversations").statusCode());
        assertEquals(404, outsider.post("/projects/" + project + "/conversations", "{\"title\":\"侵入\",\"assetIds\":[]}").statusCode());
    }

    @Test
    void acceptedTurnIsIdempotentAndSerializesConcurrentSessionRequests() throws Exception {
        BrowserApi owner = signedIn("turn-owner");
        String project = json(owner.post("/projects", "{\"name\":\"轮次接纳\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"查询\"}")).path("data").path("id").asText();
        String path = "/conversations/" + session + "/turns";
        String body = "{\"content\":\"介绍素材中的制作步骤\",\"references\":[],\"context\":{}}";
        var accepted = owner.post(path, body, "first-query");
        assertEquals(202, accepted.statusCode(), accepted.body());
        JsonNode identities = json(accepted).path("data");
        assertEquals("PENDING", json(owner.get("/tasks/" + identities.path("taskId").asText())).path("data").path("status").asText());
        assertEquals(identities, json(owner.post(path, body, "first-query")).path("data"));
        assertEquals(409, owner.post(path, "{\"content\":\"不同内容\"}", "first-query").statusCode());
        var competing = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return owner.post(path, body, "second-query").statusCode(); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertEquals(409, owner.post(path, body, "third-query").statusCode());
        assertEquals(409, competing.get());
        JsonNode history = json(owner.get("/conversations/" + session + "/messages")).path("data").path("items");
        assertEquals(1, history.size());
        assertEquals("介绍素材中的制作步骤", history.get(0).path("content").asText());
        assertEquals(identities.path("messageId").asText(), history.get(0).path("id").asText());
        String other = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"独立查询\"}")).path("data").path("id").asText();
        assertEquals(202, owner.post("/conversations/" + other + "/turns", body, "first-query").statusCode());
    }

    @Test
    void nonexistentOrUnrecognizedReferencesAreRejectedBeforeAcceptingAMessage() throws Exception {
        BrowserApi owner = signedIn("reference-owner");
        String project = json(owner.post("/projects", "{\"name\":\"固定范围\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"引用核查\"}")).path("data").path("id").asText();
        String path = "/conversations/" + session + "/turns";
        assertEquals(404, owner.post(path, "{\"content\":\"查这个素材\",\"references\":[{\"type\":\"ASSET\",\"id\":\"00000000-0000-0000-0000-000000000001\"}]}", "bad-reference").statusCode());
        assertEquals(400, owner.post(path, "{\"content\":\"执行命令\",\"references\":[{\"type\":\"SHELL\",\"id\":\"ls\"}]}", "unknown-reference").statusCode());
        assertEquals(400, owner.post(path, "{\"content\":\"读取别的会话\",\"context\":{\"conversationId\":\"00000000-0000-0000-0000-000000000001\"}}", "bad-context").statusCode());
        assertEquals(0, json(owner.get("/conversations/" + session + "/messages")).path("data").path("items").size());
        assertEquals(202, owner.post(path, "{\"content\":\"查询范围内的素材\"}", "valid-reference").statusCode());
    }

    @Test
    void onlyCurrentAttemptCanPublishOnePublicAnswerAndDuplicatePublicationReturnsReceipt() throws Exception {
        BrowserApi owner = signedIn("publication-owner");
        String project = json(owner.post("/projects", "{\"name\":\"公开答复\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"空范围查询\"}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"找到制作步骤\"}", "publish-query")).path("data").path("taskId").asText();
        var claim = internal(task, "claim", java.util.Map.of("executorId", "conversation-test", "claimRequestId", java.util.UUID.randomUUID()));
        assertEquals(200, claim.statusCode(), claim.body());
        JsonNode credentials = json(claim).path("data");
        assertEquals(session, credentials.path("input").path("conversationId").asText());
        assertEquals("找到制作步骤", credentials.path("input").path("content").asText());
        var body = new java.util.HashMap<String,Object>();
        body.put("attemptId", java.util.UUID.randomUUID()); body.put("leaseToken", credentials.path("leaseToken").asText());
        body.put("producerKey", "answer-one"); body.put("resultType", "CONVERSATION_ANSWERED");
        body.put("result", java.util.Map.of("content", "当前素材范围内没有语音证据，无法确定制作步骤。", "evidenceIds", java.util.List.of()));
        assertEquals(409, internal(task, "complete", body).statusCode());
        assertEquals(1, json(owner.get("/conversations/" + session + "/messages")).path("data").path("items").size());
        body.put("attemptId", credentials.path("attemptId").asText());
        var completed = internal(task, "complete", body);
        assertEquals(200, completed.statusCode(), completed.body());
        assertEquals(json(completed).path("data"), json(internal(task, "complete", body)).path("data"));
        assertEquals("SUCCEEDED", json(owner.get("/tasks/" + task)).path("data").path("status").asText());
        JsonNode history = json(owner.get("/conversations/" + session + "/messages")).path("data").path("items");
        assertEquals(2, history.size());
        assertEquals("ASSISTANT", history.get(1).path("role").asText());
        assertEquals("当前素材范围内没有语音证据，无法确定制作步骤。", history.get(1).path("content").asText());
        body.put("result", java.util.Map.of("content", "改变结果", "evidenceIds", java.util.List.of()));
        assertEquals(409, internal(task, "complete", body).statusCode());
    }

    @Test
    void agentReadsOnlyAuthorizedBusinessToolsUnderItsCurrentLease() throws Exception {
        BrowserApi owner = signedIn("tool-owner");
        String project = json(owner.post("/projects", "{\"name\":\"工具权限\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"受控查询\",\"assetIds\":[]}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"找制作步骤\"}", "tool-query")).path("data").path("taskId").asText();
        JsonNode claim = json(internal(task, "claim", java.util.Map.of("executorId", "tool-test", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var request = new java.util.HashMap<String,Object>();
        request.put("attemptId", claim.path("attemptId").asText()); request.put("leaseToken", claim.path("leaseToken").asText());
        request.put("producerKey", "search-one"); request.put("name", "search_evidence"); request.put("arguments", java.util.Map.of("query", "步骤"));
        var reply = internal(task, "tool", request);
        assertEquals(200, reply.statusCode(), reply.body());
        assertEquals(0, json(reply).path("data").path("hits").size());
        assertEquals(json(reply).path("data"), json(internal(task, "tool", request)).path("data"));
        request.put("producerKey", "unknown-tool"); request.put("name", "shell");
        assertEquals(400, internal(task, "tool", request).statusCode());
        request.put("producerKey", "forged-project"); request.put("name", "search_evidence");
        request.put("arguments", java.util.Map.of("query", "步骤", "projectId", java.util.UUID.randomUUID()));
        assertEquals(400, internal(task, "tool", request).statusCode());
        request.put("arguments", java.util.Map.of("query", "步骤")); request.put("attemptId", java.util.UUID.randomUUID());
        assertEquals(409, internal(task, "tool", request).statusCode());
    }

    @Test
    void paidModelSubmissionGrantCannotBeReusedAndToolBudgetCannotBeReset() throws Exception {
        BrowserApi owner = signedIn("budget-owner");
        String project = json(owner.post("/projects", "{\"name\":\"调用预算\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"预算核查\"}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"查找素材\"}", "budget-query")).path("data").path("taskId").asText();
        JsonNode claim = json(internal(task, "claim", java.util.Map.of("executorId", "budget-test", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var request = new java.util.HashMap<String,Object>();
        request.put("attemptId", claim.path("attemptId").asText()); request.put("leaseToken", claim.path("leaseToken").asText());
        request.put("action", "PREPARE"); request.put("data", java.util.Map.of("callNumber",1,"request",java.util.Map.of("model","deepseek-flash","messages",java.util.List.of())));
        var grant = internal(task,"provider-call",request);
        assertEquals(200,grant.statusCode(),grant.body());
        assertEquals(true,json(grant).path("data").path("canSubmit").asBoolean());
        var repeated = json(internal(task,"provider-call",request)).path("data");
        assertEquals(false,repeated.path("canSubmit").asBoolean());
        assertEquals("UNCERTAIN",repeated.path("status").asText());
        request.put("data", java.util.Map.of("callNumber",7,"request",java.util.Map.of("model","deepseek-flash")));
        assertEquals(409,internal(task,"provider-call",request).statusCode());
        request.remove("action"); request.remove("data"); request.put("name","list_assets"); request.put("arguments",java.util.Map.of());
        for (int call=1;call<=12;call++) {
            request.put("producerKey","bounded-tool-"+call);
            assertEquals(200,internal(task,"tool",request).statusCode());
        }
        request.put("producerKey","bounded-tool-13");
        assertEquals(409,internal(task,"tool",request).statusCode());
        request.put("producerKey","bounded-tool-1");
        assertEquals(200,internal(task,"tool",request).statusCode());
    }

    @Test
    void explicitEvidenceReferenceCannotBePublishedUntilThisTurnReadsItsEvidence() throws Exception {
        BrowserApi owner = signedIn("citation-provenance-owner");
        String project = json(owner.post("/projects", "{\"name\":\"引用来源核查\"}")).path("data").path("id").asText();
        java.util.UUID projectId = java.util.UUID.fromString(project);
        java.util.UUID asset = java.util.UUID.randomUUID(), upload = java.util.UUID.randomUUID();
        java.util.UUID analysis = java.util.UUID.randomUUID(), model = java.util.UUID.randomUUID(), fixtureTask = java.util.UUID.randomUUID();
        java.util.UUID evidence = java.util.UUID.randomUUID();
        String hash = "a".repeat(64);
        // Seed already-published speech observations: producing them through the public API
        // requires Silo/media/model services. All behavior under test uses real HTTP/PostgreSQL.
        fixtures.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'speech.mp4',1,?,1,1,?,? FROM projects WHERE id=?",
                upload, upload.toString(), hash, upload.toString(), java.util.UUID.randomUUID(), projectId);
        fixtures.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status) VALUES (?,?,?,?,'speech.mp4',1,?,'VALID')",
                asset, projectId, upload, asset.toString(), hash);
        fixtures.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'speech-v1')", analysis, projectId, asset, hash);
        fixtures.update("INSERT INTO asset_analysis_heads(asset_id,analysis_id) VALUES (?,?)", asset, analysis);
        fixtures.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'CONTENT_ANALYZE','SUCCEEDED','{}')", fixtureTask, projectId);
        fixtures.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status) VALUES (?,?,?,'fixture','{}','SUCCEEDED')", model, analysis, fixtureTask);
        fixtures.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',0,'先上传素材',0,1000,0,1000,'{}',?)",
                evidence, projectId, asset, analysis, model);
        String session = json(owner.post("/projects/" + project + "/conversations", new ObjectMapper().writeValueAsString(java.util.Map.of("title", "引用核查", "assetIds", java.util.List.of(asset))))).path("data").path("id").asText();
        String turnBody = new ObjectMapper().writeValueAsString(java.util.Map.of("content", "核查这条原句", "references", java.util.List.of(java.util.Map.of("type", "EVIDENCE", "id", evidence))));
        String task = json(owner.post("/conversations/" + session + "/turns", turnBody, "citation-query")).path("data").path("taskId").asText();
        JsonNode claim = json(internal(task, "claim", java.util.Map.of("executorId", "citation-test", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var publication = new java.util.HashMap<String,Object>();
        publication.put("attemptId", claim.path("attemptId").asText()); publication.put("leaseToken", claim.path("leaseToken").asText());
        publication.put("producerKey", "citation-answer"); publication.put("resultType", "CONVERSATION_ANSWERED");
        publication.put("result", java.util.Map.of("content", "第一步是上传素材。", "evidenceIds", java.util.List.of(evidence)));
        var unread = internal(task, "complete", publication);
        assertEquals(409, unread.statusCode(), unread.body());
        assertEquals(1, json(owner.get("/conversations/" + session + "/messages")).path("data").path("items").size());
        var read = new java.util.HashMap<String,Object>();
        read.put("attemptId", claim.path("attemptId").asText()); read.put("leaseToken", claim.path("leaseToken").asText());
        read.put("producerKey", "citation-read"); read.put("name", "read_evidence_context"); read.put("arguments", java.util.Map.of("evidenceId", evidence));
        var observed = internal(task, "tool", read);
        assertEquals(200, observed.statusCode(), observed.body());
        assertEquals(evidence.toString(), json(observed).path("data").path("observations").get(0).path("evidenceId").asText());
        var completed = internal(task, "complete", publication);
        assertEquals(200, completed.statusCode(), completed.body());
        assertEquals(evidence.toString(), json(owner.get("/conversations/" + session + "/messages")).path("data").path("items").get(1).path("references").get(0).path("id").asText());
    }

    @Test
    void largeModelRecordsRemainReplayableWithoutTheOldCharacterLimit() throws Exception {
        BrowserApi owner = signedIn("large-record-owner");
        String project = json(owner.post("/projects", "{\"name\":\"长上下文\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"长证据\"}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"核查长素材\"}", "large-record")).path("data").path("taskId").asText();
        JsonNode claim = json(internal(task, "claim", Map.of("executorId", "large-record", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var request = new java.util.HashMap<String,Object>();
        request.put("attemptId", claim.path("attemptId").asText()); request.put("leaseToken", claim.path("leaseToken").asText());
        Object payload = Map.of("model", "deepseek-flash", "messages", List.of(Map.of("role", "tool", "content", "原文".repeat(60000))));
        request.put("action", "PREPARE"); request.put("data", Map.of("callNumber", 1, "request", payload));
        var accepted = internal(task, "provider-call", request);
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals(true, json(accepted).path("data").path("canSubmit").asBoolean());
        assertEquals("UNCERTAIN", json(internal(task, "provider-call", request)).path("data").path("status").asText());
        Object response = Map.of("message", Map.of("content", "答复".repeat(60000)), "usage", Map.of("prompt_tokens", 120000));
        request.put("action", "SUCCEEDED"); request.put("data", Map.of("callNumber", 1, "response", response));
        assertEquals(200, internal(task, "provider-call", request).statusCode());
        request.put("action", "PREPARE"); request.put("data", Map.of("callNumber", 1, "request", payload));
        var replay = json(internal(task, "provider-call", request)).path("data");
        assertEquals(false, replay.path("canSubmit").asBoolean());
        assertEquals(new ObjectMapper().valueToTree(response), replay.path("response"));
        request.put("data", Map.of("callNumber", 1, "request", Map.of("model", "different")));
        assertEquals(409, internal(task, "provider-call", request).statusCode());
    }

    @Test
    void failedTurnRetryKeepsOneUserMessageAndReturnsTheCommittedResultOnReplay() throws Exception {
        BrowserApi owner=signedIn("retry-original-owner"), outsider=signedIn("retry-original-outsider");
        String project=json(owner.post("/projects","{\"name\":\"原需求重试\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"失败重试\"}")).path("data").path("id").asText();
        JsonNode original=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"核查当前范围\"}","original-query")).path("data");
        String task=original.path("taskId").asText();
        JsonNode claim=json(internal(task,"claim",Map.of("executorId","retry-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        Map<String,Object> lease=Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText());
        var failure=new java.util.HashMap<String,Object>(lease);failure.putAll(Map.of("producerKey","failed","code","CONVERSATION_FAILED","message","对话暂时失败"));
        assertEquals(200,internal(task,"fail",failure).statusCode());
        String path="/conversations/"+session+"/tasks/"+task+"/retry";
        var retry=owner.post(path,"{}","retry-once");
        assertEquals(202,retry.statusCode(),retry.body());
        JsonNode retried=json(retry).path("data");
        org.junit.jupiter.api.Assertions.assertNotEquals(task,retried.path("taskId").asText());
        assertEquals(original.path("messageId"),retried.path("messageId"));
        assertEquals(task,retried.path("retryOfTaskId").asText());
        assertEquals(retried,json(owner.post(path,"{}","retry-once")).path("data"));
        assertEquals(404,outsider.post(path,"{}","outsider").statusCode());
        assertEquals(409,owner.post(path,"{}","competing-retry").statusCode());
        assertEquals("FAILED",json(owner.get("/tasks/"+task)).path("data").path("status").asText());
        assertEquals(1,json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
        String next=retried.path("taskId").asText();
        JsonNode nextClaim=json(internal(next,"claim",Map.of("executorId","retry-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        assertEquals(200,internal(next,"fail",Map.of("attemptId",nextClaim.path("attemptId").asText(),"leaseToken",nextClaim.path("leaseToken").asText(),"producerKey","second-failure","code","CONVERSATION_FAILED","message","再次暂时失败")).statusCode());
        var again=owner.post("/conversations/"+session+"/tasks/"+next+"/retry","{}","retry-twice");
        assertEquals(202,again.statusCode(),again.body());next=json(again).path("data").path("taskId").asText();
        nextClaim=json(internal(next,"claim",Map.of("executorId","retry-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var publication=new java.util.HashMap<String,Object>();
        publication.putAll(Map.of("attemptId",nextClaim.path("attemptId").asText(),"leaseToken",nextClaim.path("leaseToken").asText(),"producerKey","answer","resultType","CONVERSATION_ANSWERED","result",Map.of("content","本轮无素材，不能核查视频事实。","evidenceIds",List.of())));
        assertEquals(200,internal(next,"complete",publication).statusCode());
        assertEquals(2,json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
        JsonNode replay=json(owner.post("/conversations/"+session+"/tasks/"+next+"/retry","{}","after-success")).path("data");
        assertEquals(next,replay.path("taskId").asText());
        var revisited=owner.post(path,"{}","revisit-original-success");
        assertEquals(202,revisited.statusCode(),revisited.body());
        assertEquals(next,json(revisited).path("data").path("taskId").asText());
        assertEquals(2,json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
        publication.putAll(lease);
        assertEquals(409,internal(task,"complete",publication).statusCode());
    }

    @Test
    void uncertainModelCallRequiresExplicitUnknownCostAcknowledgementBeforeFreshRetry() throws Exception {
        BrowserApi owner=signedIn("retry-unknown-cost");
        String project=json(owner.post("/projects","{\"name\":\"费用未知\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"未知回执\"}")).path("data").path("id").asText();
        String task=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"核查素材\"}","uncertain")).path("data").path("taskId").asText();
        JsonNode claim=json(internal(task,"claim",Map.of("executorId","uncertain-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var body=new java.util.HashMap<String,Object>();body.putAll(Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText()));
        body.putAll(Map.of("action","PREPARE","data",Map.of("callNumber",1,"request",Map.of("model","deepseek-flash","messages",List.of()))));
        assertEquals(200,internal(task,"provider-call",body).statusCode());
        body.remove("action");body.remove("data");body.putAll(Map.of("producerKey","uncertain-failed","code","CONVERSATION_UNVERIFIED","message","模型回执不明确"));
        assertEquals(200,internal(task,"fail",body).statusCode());
        String path="/conversations/"+session+"/tasks/"+task+"/retry";
        assertEquals(409,owner.post(path,"{}","new-paid-call").statusCode());
        JsonNode state=json(owner.get("/conversations/"+session+"/snapshot")).path("data").path("latestTask").path("externalEffects");
        assertEquals(true,state.path("unknownCost").asBoolean());assertEquals(false,state.path("stopSupported").asBoolean(true));
        assertEquals(202,owner.post(path,"{\"acknowledgeUnknownCost\":true}","new-paid-call").statusCode());
        assertEquals(1,json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
    }

    @Test
    void stoppingAndCompletingRaceNeverReclassifiesACommittedAnswerOrPublishesAfterStop() throws Exception {
        BrowserApi owner=signedIn("stop-completion-race");
        String project=json(owner.post("/projects","{\"name\":\"停止竞争\"}")).path("data").path("id").asText();
        for(int round=0;round<5;round++) {
            String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"竞争\"}")).path("data").path("id").asText();
            String task=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"查询\"}","race")).path("data").path("taskId").asText();
            JsonNode claim=json(internal(task,"claim",Map.of("executorId","race-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
            Map<String,Object> publication=Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","racing-answer","resultType","CONVERSATION_ANSWERED","result",Map.of("content","无素材，不能核查。","evidenceIds",List.of()));
            var completion=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try{return internal(task,"complete",publication);}catch(Exception failure){throw new RuntimeException(failure);}
            });
            assertEquals(202,owner.post("/tasks/"+task+"/cancel","{}").statusCode());
            var result=completion.get();
            String status=json(owner.get("/tasks/"+task)).path("data").path("status").asText();
            if(result.statusCode()==200) {
                assertEquals("SUCCEEDED",status);
                assertEquals(200,internal(task,"complete",publication).statusCode());
                assertEquals(2,json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
            } else {
                assertEquals(409,result.statusCode(),result.body());assertEquals("CANCEL_REQUESTED",status);
                var stopped=new java.util.HashMap<String,Object>();stopped.putAll(Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"processExited",false));
                assertEquals(409,internal(task,"cancelled",stopped).statusCode());
                stopped.put("processExited",true);assertEquals(200,internal(task,"cancelled",stopped).statusCode());
                assertEquals("CANCELLED",json(owner.get("/tasks/"+task)).path("data").path("status").asText());
                assertEquals(1,json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
                assertEquals(409,internal(task,"complete",publication).statusCode());
            }
            assertEquals(202,owner.post("/tasks/"+task+"/cancel","{}").statusCode());
            assertEquals(result.statusCode()==200?"SUCCEEDED":"CANCELLED",json(owner.get("/tasks/"+task)).path("data").path("status").asText());
            assertEquals(202,owner.post("/conversations/"+session+"/turns","{\"content\":\"下一轮\"}","next").statusCode());
        }
    }

    @Test
    void retryReacquiresTheSamePlanAndPendingStopReleasesItsEditingSlot() throws Exception {
        BrowserApi owner=signedIn("retry-editing-slot");
        String project=json(owner.post("/projects","{\"name\":\"原方案占用\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"原方案重试\"}")).path("data").path("id").asText();
        String request="{\"content\":\"生成方案\",\"context\":{\"editing\":{\"topic\":\"原方案\",\"minDurationMs\":1000,\"maxDurationMs\":2000,\"profile\":\"LANDSCAPE_720\"}}}";
        JsonNode original=json(owner.post("/conversations/"+session+"/turns",request,"editing-failed")).path("data");
        String task=original.path("taskId").asText();
        JsonNode claim=json(internal(task,"claim",Map.of("executorId","editing-retry","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        assertEquals(200,internal(task,"fail",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","editing-failed","code","CONVERSATION_FAILED","message","方案执行失败")).statusCode());
        var reply=owner.post("/conversations/"+session+"/tasks/"+task+"/retry","{}","editing-retry");
        assertEquals(202,reply.statusCode(),reply.body());JsonNode retried=json(reply).path("data");
        assertEquals(original.path("planId"),retried.path("planId"));
        assertEquals(202,owner.post("/tasks/"+retried.path("taskId").asText()+"/cancel","{}").statusCode());
        assertEquals("CANCELLED",json(owner.get("/tasks/"+retried.path("taskId").asText())).path("data").path("status").asText());
        String next=request.replace("\"topic\":\"原方案\"","\"planId\":\""+original.path("planId").asText()+"\",\"topic\":\"原方案\"");
        var accepted=owner.post("/conversations/"+session+"/turns",next,"next-editing");
        assertEquals(202,accepted.statusCode(),accepted.body());
        assertEquals(original.path("planId"),json(accepted).path("data").path("planId"));
    }

    @Test
    void retryRechecksTheOriginalFixedAssetBeforeCreatingAnotherTask() throws Exception {
        BrowserApi owner=signedIn("retry-fixed-object");
        String project=json(owner.post("/projects","{\"name\":\"固定素材复核\"}")).path("data").path("id").asText();
        var projectId=java.util.UUID.fromString(project);var asset=java.util.UUID.randomUUID();var upload=java.util.UUID.randomUUID();
        fixtures.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'source.mp4',1,?,1,1,?,? FROM projects WHERE id=?",upload,upload.toString(),"a".repeat(64),upload.toString(),java.util.UUID.randomUUID(),projectId);
        fixtures.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status) VALUES (?,?,?,?,'source.mp4',1,?,'VALID')",asset,projectId,upload,asset.toString(),"a".repeat(64));
        String session=json(owner.post("/projects/"+project+"/conversations",new ObjectMapper().writeValueAsString(Map.of("title","固定范围","assetIds",List.of(asset))))).path("data").path("id").asText();
        String task=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"核查原素材\"}","fixed-original")).path("data").path("taskId").asText();
        JsonNode claim=json(internal(task,"claim",Map.of("executorId","fixed-retry","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        assertEquals(200,internal(task,"fail",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","failed","code","CONVERSATION_FAILED","message","暂时失败")).statusCode());
        fixtures.update("UPDATE assets SET validation_status='INVALID' WHERE id=?",asset);
        var retry=owner.post("/conversations/"+session+"/tasks/"+task+"/retry","{}","fixed-retry");
        assertEquals(404,retry.statusCode(),retry.body());
        assertEquals(task,json(owner.get("/conversations/"+session+"/snapshot")).path("data").path("latestTask").path("taskId").asText());
        assertEquals(1,json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
    }

    private HttpResponse<String> internal(String task, String action, Object body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/tasks/" + task + "/" + action))
                .header("Authorization", "Bearer conversation-integration-worker-token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private BrowserApi signedIn(String username) throws Exception {
        BrowserApi browser = new BrowserApi();
        String credentials = new ObjectMapper().writeValueAsString(java.util.Map.of("username", username, "password", "project-strong-password"));
        assertEquals(201, browser.post("/auth/register", credentials).statusCode());
        assertEquals(200, browser.post("/auth/login", credentials).statusCode());
        return browser;
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return new ObjectMapper().readTree(response.body());
    }

    private final class BrowserApi {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body) throws Exception { return post(path, body, "unused"); }
        HttpResponse<String> post(String path, String body, String key) throws Exception {
            String token = json(get("/auth/csrf")).path("data").path("csrfToken").asText();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token).header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}

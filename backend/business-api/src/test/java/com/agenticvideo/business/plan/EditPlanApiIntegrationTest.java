package com.agenticvideo.business.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.max-attempts=100")
class EditPlanApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("internal.worker-token", () -> "conversation-integration-worker-token");
    }
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate fixtures;

    @Test
    void hardDurationCannotOverflowTheAcceptedIntegerRange() throws Exception {
        BrowserApi owner = signedIn("duration-overflow-owner");
        String project = json(owner.post("/projects", "{\"name\":\"硬约束整数\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/"+project+"/conversations", "{\"title\":\"拒绝溢出\"}")).path("data").path("id").asText();
        String body = "{\"content\":\"生成方案\",\"context\":{\"editing\":{\"topic\":\"步骤\",\"minDurationMs\":18446744073709559616,\"maxDurationMs\":18446744073709563616,\"profile\":\"LANDSCAPE_720\"}}}";
        assertEquals(400, owner.post("/conversations/"+session+"/turns", body, "overflow").statusCode());
        assertEquals(0, json(owner.get("/projects/"+project+"/plans")).path("data").path("items").size());
        assertEquals(0, json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
    }

    @Test
    void backendAllocatesMultiplePrivatePlansWithoutPublishingCandidateVersions() throws Exception {
        BrowserApi owner = signedIn("plan-owner"), outsider = signedIn("plan-outsider");
        String project = json(owner.post("/projects", "{\"name\":\"讲话选段\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"生成一条方案\"}")).path("data").path("id").asText();
        String body = "{\"content\":\"制作步骤主题，生成8至12秒短视频方案\",\"context\":{\"editing\":{\"topic\":\"制作步骤\",\"minDurationMs\":8000,\"maxDurationMs\":12000,\"profile\":\"LANDSCAPE_720\"}}}";
        var accepted = owner.post("/conversations/" + session + "/turns", body, "initial-proposal");
        assertEquals(202, accepted.statusCode(), accepted.body());
        String plan = json(accepted).path("data").path("planId").asText();
        org.junit.jupiter.api.Assertions.assertFalse(plan.isBlank());
        assertEquals(plan, json(owner.post("/conversations/" + session + "/turns", body, "initial-proposal")).path("data").path("planId").asText());
        var listed = owner.get("/projects/" + project + "/plans");
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(1, json(listed).path("data").path("items").size());
        assertEquals(0, json(owner.get("/plans/" + plan + "/revisions")).path("data").path("items").size());
        assertEquals(404, outsider.get("/projects/" + project + "/plans").statusCode());
        assertEquals(404, outsider.get("/plans/" + plan + "/revisions").statusCode());
        String second = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"另一条独立方案\"}")).path("data").path("id").asText();
        var another = owner.post("/conversations/" + second + "/turns", body, "another-proposal");
        assertEquals(202, another.statusCode(), another.body());
        org.junit.jupiter.api.Assertions.assertNotEquals(plan, json(another).path("data").path("planId").asText());
        assertEquals(2, json(owner.get("/projects/" + project + "/plans")).path("data").path("items").size());
    }

    @Test
    void successfulProposalPublishesOneImmutableVersionWithMappedOriginalSubtitles() throws Exception {
        BrowserApi owner = signedIn("revision-owner");
        String project = json(owner.post("/projects", "{\"name\":\"完整讲话\"}")).path("data").path("id").asText();
        var speech = speechFixture(project);
        String session = json(owner.post("/projects/" + project + "/conversations", new ObjectMapper().writeValueAsString(java.util.Map.of("title", "步骤初稿", "assetIds", java.util.List.of(speech.asset()))))).path("data").path("id").asText();
        String body = "{\"content\":\"制作步骤8至12秒\",\"context\":{\"editing\":{\"topic\":\"制作步骤\",\"minDurationMs\":8000,\"maxDurationMs\":12000,\"profile\":\"LANDSCAPE_720\"}}}";
        JsonNode accepted = json(owner.post("/conversations/" + session + "/turns", body, "draft-one")).path("data");
        String task = accepted.path("taskId").asText(), plan = accepted.path("planId").asText();
        JsonNode claim = json(internal(task, "claim", java.util.Map.of("executorId", "plan-test", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var credentials = new java.util.HashMap<String,Object>();
        credentials.put("attemptId", claim.path("attemptId").asText()); credentials.put("leaseToken", claim.path("leaseToken").asText());
        var read = new java.util.HashMap<String,Object>(credentials);
        read.put("producerKey", "context-one"); read.put("name", "read_evidence_context"); read.put("arguments", java.util.Map.of("evidenceId", speech.first()));
        assertEquals(200, internal(task, "tool", read).statusCode());
        var proposal = java.util.Map.of("title", "两个完整步骤", "clips", java.util.List.of(java.util.Map.of("assetId", speech.asset(), "sourceInMs", 2000, "sourceOutMs", 10000, "evidenceIds", java.util.List.of(speech.first(),speech.second()), "reason", "保留上传和核查两个完整步骤")));
        var publication = new java.util.HashMap<String,Object>(credentials);
        publication.put("producerKey", "publish-plan"); publication.put("resultType", "EDIT_PROPOSAL");
        publication.put("result", java.util.Map.of("content", "已依据原文生成两个完整步骤的初始方案。", "proposal", proposal));
        assertEquals(409, internal(task, "complete", publication).statusCode());
        var candidate = new java.util.HashMap<String,Object>(credentials);
        candidate.put("producerKey", "validated-first"); candidate.put("proposal", proposal);
        assertEquals(true, json(internal(task, "validate-edit", candidate)).path("data").path("valid").asBoolean());
        var completed = internal(task, "complete", publication);
        assertEquals(200, completed.statusCode(), completed.body());
        String revision = json(completed).path("data").path("revisionId").asText();
        assertEquals(json(completed).path("data"), json(internal(task, "complete", publication)).path("data"));
        publication.put("resultType", "CONVERSATION_ANSWERED");
        assertEquals(409, internal(task, "complete", publication).statusCode());
        JsonNode stored = json(owner.get("/revisions/" + revision)).path("data");
        assertEquals(plan, stored.path("planId").asText());
        assertEquals(1, stored.path("revisionNumber").asInt());
        JsonNode output = stored.path("content").path("outputs").get(0);
        assertEquals(8000, output.path("durationMs").asLong());
        assertEquals(1280, output.path("render").path("width").asInt());
        assertEquals(720, output.path("render").path("height").asInt());
        assertEquals("original", output.path("clips").get(0).path("audio").asText());
        JsonNode cues = output.path("subtitles").path("cues");
        assertEquals(2, cues.size());
        assertEquals("第一步，上传素材。", cues.get(0).path("text").asText());
        assertEquals(0, cues.get(0).path("startMs").asLong());
        assertEquals(3000, cues.get(0).path("endMs").asLong());
        assertEquals(3000, cues.get(1).path("startMs").asLong());
        assertEquals(8000, cues.get(1).path("endMs").asLong());
        assertEquals(1, json(owner.get("/plans/" + plan + "/revisions")).path("data").path("items").size());
        assertEquals(2, json(owner.get("/conversations/" + session + "/messages")).path("data").path("items").size());
    }

    @Test
    void invalidCandidatesHaveBoundedCorrectionsAndNeverPublishVersions() throws Exception {
        BrowserApi owner = signedIn("invalid-candidate-owner");
        String project = json(owner.post("/projects", "{\"name\":\"受限修正\"}")).path("data").path("id").asText();
        var speech=speechFixture(project);
        String session=json(owner.post("/projects/"+project+"/conversations",new ObjectMapper().writeValueAsString(java.util.Map.of("title","硬时长","assetIds",java.util.List.of(speech.asset()))))).path("data").path("id").asText();
        String body="{\"content\":\"8至12秒制作步骤\",\"context\":{\"editing\":{\"topic\":\"制作步骤\",\"minDurationMs\":8000,\"maxDurationMs\":12000,\"profile\":\"LANDSCAPE_720\"}}}";
        JsonNode accepted=json(owner.post("/conversations/"+session+"/turns",body,"invalid-round")).path("data");
        String task=accepted.path("taskId").asText(),plan=accepted.path("planId").asText();
        JsonNode claim=json(internal(task,"claim",java.util.Map.of("executorId","validation-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var request=new java.util.HashMap<String,Object>();
        request.put("attemptId",claim.path("attemptId").asText());request.put("leaseToken",claim.path("leaseToken").asText());
        var read=new java.util.HashMap<String,Object>(request);read.put("producerKey","read-context");read.put("name","read_evidence_context");read.put("arguments",java.util.Map.of("evidenceId",speech.first()));
        assertEquals(200,internal(task,"tool",read).statusCode());
        var completeClip=java.util.Map.of("assetId",speech.asset(),"sourceInMs",2000,"sourceOutMs",10000,"evidenceIds",java.util.List.of(speech.first(),speech.second()),"reason","完整步骤");
        for(int attempt=1;attempt<=3;attempt++) {
            request.put("producerKey","candidate-"+attempt);
            Object clips=switch(attempt) {
                case 1 -> java.util.List.of(java.util.Map.of("assetId",speech.asset(),"sourceInMs",2000,"sourceOutMs",21000,"evidenceIds",java.util.List.of(speech.first()),"reason","越界区间"));
                case 2 -> java.util.List.of(completeClip,completeClip);
                default -> java.util.List.of(java.util.Map.of("assetId",speech.asset(),"sourceInMs",2000,"sourceOutMs",5000,"evidenceIds",java.util.List.of(speech.first()),"reason","时长不足"));
            };
            request.put("proposal",java.util.Map.of("title","非法候选"+attempt,"clips",clips));
            var validation=internal(task,"validate-edit",request);
            assertEquals(200,validation.statusCode(),validation.body());
            assertEquals(false,json(validation).path("data").path("valid").asBoolean());
            String expected=switch(attempt) {case 1 -> "素材时长";case 2 -> "重叠";default -> "硬时长";};
            org.junit.jupiter.api.Assertions.assertTrue(json(validation).path("data").path("errors").get(0).path("message").asText().contains(expected),validation.body());
        }
        request.put("producerKey","candidate-4");request.put("proposal",java.util.Map.of("title","第四次尝试","clips",java.util.List.of()));
        assertEquals(409,internal(task,"validate-edit",request).statusCode());
        assertEquals(0,json(owner.get("/plans/"+plan+"/revisions")).path("data").path("items").size());
        assertEquals(1,json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
        var explanation=new java.util.HashMap<String,Object>();
        explanation.put("attemptId",claim.path("attemptId").asText());explanation.put("leaseToken",claim.path("leaseToken").asText());
        explanation.put("producerKey","cannot-satisfy");explanation.put("resultType","CONVERSATION_ANSWERED");explanation.put("result",java.util.Map.of("content","在有限修正内未得到合法方案，请调整时长条件。","evidenceIds",java.util.List.of()));
        assertEquals(200,internal(task,"complete",explanation).statusCode());
        assertEquals(0,json(owner.get("/plans/"+plan+"/revisions")).path("data").path("items").size());
        org.junit.jupiter.api.Assertions.assertTrue(json(owner.get("/projects/"+project+"/plans")).path("data").path("items").get(0).path("editingTaskId").isNull());
    }

    @Test
    void runningCancellationRetainsTheLockUntilTheCurrentExecutorAcknowledgesExit() throws Exception {
        BrowserApi owner=signedIn("running-plan-cancel-owner");
        String project=json(owner.post("/projects","{\"name\":\"停止有效编辑\"}")).path("data").path("id").asText();
        String first=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"运行中\"}")).path("data").path("id").asText();
        String second=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"继续编辑\"}")).path("data").path("id").asText();
        var editing=new java.util.HashMap<String,Object>(java.util.Map.of("topic","步骤","minDurationMs",8000,"maxDurationMs",12000,"profile","LANDSCAPE_720"));
        JsonNode accepted=acceptPlan(owner,first,editing,"running-cancel");
        String task=accepted.path("taskId").asText(),plan=accepted.path("planId").asText();
        JsonNode claim=json(internal(task,"claim",java.util.Map.of("executorId","running-cancel-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var credentials=new java.util.HashMap<String,Object>();credentials.put("attemptId",claim.path("attemptId").asText());credentials.put("leaseToken",claim.path("leaseToken").asText());
        var stop=new java.util.HashMap<String,Object>(credentials);stop.put("processExited",false);
        assertEquals("CANCEL_REQUESTED",json(owner.post("/tasks/"+task+"/cancel","{}")).path("data").path("status").asText());
        editing.put("planId",plan);
        String body=new ObjectMapper().writeValueAsString(java.util.Map.of("content","继续编辑","context",java.util.Map.of("editing",editing)));
        assertEquals(409,owner.post("/conversations/"+second+"/turns",body,"cancel-retry").statusCode());
        assertEquals(409,internal(task,"cancelled",stop).statusCode());
        var late=new java.util.HashMap<String,Object>(credentials);late.put("producerKey","late-result");late.put("resultType","CONVERSATION_ANSWERED");late.put("result",java.util.Map.of("content","迟到结果","evidenceIds",java.util.List.of()));
        assertEquals(409,internal(task,"complete",late).statusCode());
        stop.put("processExited",true);assertEquals(200,internal(task,"cancelled",stop).statusCode());
        assertEquals(202,owner.post("/conversations/"+second+"/turns",body,"cancel-retry").statusCode());
        assertEquals(0,json(owner.get("/plans/"+plan+"/revisions")).path("data").path("items").size());
    }

    @Test
    void planExclusiveEditingRejectsAnotherSessionAndCancellationReleasesIt() throws Exception {
        BrowserApi owner=signedIn("edit-lock-owner");
        String project=json(owner.post("/projects","{\"name\":\"方案独占\"}")).path("data").path("id").asText();
        String first=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"先开始\"}")).path("data").path("id").asText();
        String second=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"另一个会话\"}")).path("data").path("id").asText();
        var editing=new java.util.HashMap<String,Object>(java.util.Map.of("topic","步骤","minDurationMs",8000,"maxDurationMs",12000,"profile","LANDSCAPE_720"));
        JsonNode accepted=json(owner.post("/conversations/"+first+"/turns",new ObjectMapper().writeValueAsString(java.util.Map.of("content","生成方案","context",java.util.Map.of("editing",editing))),"lock-first")).path("data");
        String plan=accepted.path("planId").asText(),task=accepted.path("taskId").asText();
        editing.put("planId",plan);
        String competing=new ObjectMapper().writeValueAsString(java.util.Map.of("content","重新生成同一方案","context",java.util.Map.of("editing",editing)));
        assertEquals(409,owner.post("/conversations/"+second+"/turns",competing,"lock-second").statusCode());
        assertEquals(0,json(owner.get("/conversations/"+second+"/messages")).path("data").path("items").size());
        assertEquals(202,owner.post("/tasks/"+task+"/cancel","{}").statusCode());
        org.junit.jupiter.api.Assertions.assertTrue(json(owner.get("/projects/"+project+"/plans")).path("data").path("items").get(0).path("editingTaskId").isNull());
        var resumed=owner.post("/conversations/"+second+"/turns",competing,"lock-second");
        assertEquals(202,resumed.statusCode(),resumed.body());
        assertEquals(plan,json(resumed).path("data").path("planId").asText());
        String resumedTask=json(resumed).path("data").path("taskId").asText();
        JsonNode claim=json(internal(resumedTask,"claim",java.util.Map.of("executorId","lock-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var failure=java.util.Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","model-failed","code","CONVERSATION_UNVERIFIED","message","真实模型调用未确认");
        assertEquals(200,internal(resumedTask,"fail",failure).statusCode());
        org.junit.jupiter.api.Assertions.assertTrue(json(owner.get("/projects/"+project+"/plans")).path("data").path("items").get(0).path("editingTaskId").isNull());
    }

    @Test
    void viewingHistoryNeverChangesAcceptedBaseOrForcesTheNewVersionIntoView() throws Exception {
        BrowserApi owner=signedIn("history-owner");
        String project=json(owner.post("/projects","{\"name\":\"查看与编辑分开\"}")).path("data").path("id").asText();
        Speech speech=speechFixture(project);
        String session=json(owner.post("/projects/"+project+"/conversations",new ObjectMapper().writeValueAsString(java.util.Map.of("title","保留查看状态","assetIds",java.util.List.of(speech.asset()))))).path("data").path("id").asText();
        var editing=new java.util.HashMap<String,Object>(java.util.Map.of("topic","步骤","minDurationMs",8000,"maxDurationMs",12000,"profile","LANDSCAPE_720"));
        JsonNode first=acceptPlan(owner,session,editing,"history-first");
        String plan=first.path("planId").asText(),v1=publishPlan(first,speech).path("revisionId").asText();
        var selected=owner.patch("/conversations/"+session+"/view",new ObjectMapper().writeValueAsString(java.util.Map.of("planId",plan,"revisionId",v1,"expectedRowVersion",0)));
        assertEquals(200,selected.statusCode(),selected.body());
        editing.put("planId",plan);editing.put("baseRevisionId",v1);
        JsonNode second=acceptPlan(owner,session,editing,"history-second");
        String v2=publishPlan(second,speech).path("revisionId").asText();
        assertEquals(v1,json(owner.get("/conversations/"+session+"/messages")).path("data").path("view").path("revisionId").asText());
        JsonNode third=acceptPlan(owner,session,editing,"history-third");
        assertEquals(200,owner.patch("/conversations/"+session+"/view",new ObjectMapper().writeValueAsString(java.util.Map.of("planId",plan,"revisionId",v2,"expectedRowVersion",1))).statusCode());
        String v3=publishPlan(third,speech).path("revisionId").asText();
        JsonNode stored=json(owner.get("/revisions/"+v3)).path("data");
        assertEquals(3,stored.path("revisionNumber").asInt());assertEquals(v1,stored.path("parentRevisionId").asText());
        assertEquals(v2,json(owner.get("/conversations/"+session+"/messages")).path("data").path("view").path("revisionId").asText());
        assertEquals(3,json(owner.get("/plans/"+plan+"/revisions")).path("data").path("items").size());
    }

    @Test
    void aQueryExecutorCannotInventAnUnpublishedPlanResultCard() throws Exception {
        BrowserApi owner=signedIn("fake-plan-card-owner");
        String project=json(owner.post("/projects","{\"name\":\"结果身份校验\"}")).path("data").path("id").asText();
        String session=json(owner.post("/projects/"+project+"/conversations","{\"title\":\"只查询\"}")).path("data").path("id").asText();
        String task=json(owner.post("/conversations/"+session+"/turns","{\"content\":\"查找素材\"}","query-only")).path("data").path("taskId").asText();
        JsonNode claim=json(internal(task,"claim",java.util.Map.of("executorId","fake-card-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var request=java.util.Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","invented-card","resultType","CONVERSATION_ANSWERED","result",java.util.Map.of("content","并不存在的方案已就绪","evidenceIds",java.util.List.of(),"planRevision",java.util.Map.of("planId",java.util.UUID.randomUUID(),"revisionId",java.util.UUID.randomUUID(),"revisionNumber",1)));
        assertEquals(409,internal(task,"complete",request).statusCode());
        assertEquals(1,json(owner.get("/conversations/"+session+"/messages")).path("data").path("items").size());
        assertEquals(0,json(owner.get("/projects/"+project+"/plans")).path("data").path("items").size());
    }

    @Test
    void anIndependentCompleteViewpointCanUseWordBoundariesInsideAMergedAsrSegment() throws Exception {
        BrowserApi owner=signedIn("word-boundary-owner");
        String project=json(owner.post("/projects","{\"name\":\"合并语音段\"}")).path("data").path("id").asText();Speech speech=speechFixture(project);
        fixtures.update("UPDATE evidence_segments SET text=?,observation_json=?::jsonb WHERE id=?", "第一步，上传素材。接下来等待分析。",
                "{\"words\":[{\"text\":\"第一步，上传素材\",\"punctuation\":\"。\",\"begin_time\":2000,\"end_time\":3500},{\"text\":\"接下来等待分析\",\"punctuation\":\"。\",\"begin_time\":3700,\"end_time\":5000}]}",speech.first());
        String session=json(owner.post("/projects/"+project+"/conversations",new ObjectMapper().writeValueAsString(java.util.Map.of("title","只选上传观点","assetIds",java.util.List.of(speech.asset()))))).path("data").path("id").asText();
        JsonNode accepted=acceptPlan(owner,session,java.util.Map.of("topic","上传素材","minDurationMs",1500,"maxDurationMs",1800,"profile","LANDSCAPE_720"),"word-proposal");
        String task=accepted.path("taskId").asText();JsonNode claim=json(internal(task,"claim",java.util.Map.of("executorId","word-boundary-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var credentials=new java.util.HashMap<String,Object>();credentials.put("attemptId",claim.path("attemptId").asText());credentials.put("leaseToken",claim.path("leaseToken").asText());
        var read=new java.util.HashMap<String,Object>(credentials);read.put("producerKey","word-context");read.put("name","read_evidence_context");read.put("arguments",java.util.Map.of("evidenceId",speech.first()));
        JsonNode context=json(internal(task,"tool",read)).path("data");
        assertEquals(3500,context.path("observations").get(0).path("words").get(0).path("endMs").asLong());
        var candidate=new java.util.HashMap<String,Object>(credentials);candidate.put("producerKey","word-candidate");
        var proposal=java.util.Map.of("title","完整上传步骤","clips",java.util.List.of(java.util.Map.of("assetId",speech.asset(),"sourceInMs",2000,"sourceOutMs",3500,"evidenceIds",java.util.List.of(speech.first()),"reason","保留独立完整的上传步骤，省略下一句")));
        candidate.put("proposal",java.util.Map.of("title","词中截断","clips",java.util.List.of(java.util.Map.of("assetId",speech.asset(),"sourceInMs",1900,"sourceOutMs",3400,"evidenceIds",java.util.List.of(speech.first()),"reason","错误截断"))));
        JsonNode cut=json(internal(task,"validate-edit",candidate)).path("data");assertEquals(false,cut.path("valid").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(cut.path("errors").get(0).path("message").asText().contains("截断原文中的词"));
        candidate.put("producerKey","whole-viewpoint");
        candidate.put("proposal",proposal);var validation=internal(task,"validate-edit",candidate);
        assertEquals(true,json(validation).path("data").path("valid").asBoolean(),validation.body());
        var publication=new java.util.HashMap<String,Object>(credentials);publication.put("producerKey","word-publish");publication.put("resultType","EDIT_PROPOSAL");publication.put("result",java.util.Map.of("content","保留上传素材这一完整观点。","proposal",proposal));
        var completed=internal(task,"complete",publication);assertEquals(200,completed.statusCode(),completed.body());
        JsonNode output=json(owner.get("/revisions/"+json(completed).path("data").path("revisionId").asText())).path("data").path("content").path("outputs").get(0);
        assertEquals(1500,output.path("durationMs").asLong());JsonNode cues=output.path("subtitles").path("cues");
        assertEquals(1,cues.size());assertEquals("第一步，上传素材。",cues.get(0).path("text").asText());assertEquals(0,cues.get(0).path("startMs").asLong());assertEquals(1500,cues.get(0).path("endMs").asLong());
    }

    @Test
    void contextOffersACompleteSentenceCandidateInsideTheFixedHardDurationWithoutPublishing() throws Exception {
        BrowserApi owner=signedIn("candidate-window-owner");
        String project=json(owner.post("/projects","{\"name\":\"候选区间\"}")).path("data").path("id").asText();Speech speech=speechFixture(project);
        String session=json(owner.post("/projects/"+project+"/conversations",new ObjectMapper().writeValueAsString(java.util.Map.of("title","硬时长候选","assetIds",java.util.List.of(speech.asset()))))).path("data").path("id").asText();
        JsonNode accepted=acceptPlan(owner,session,java.util.Map.of("topic","步骤","minDurationMs",8000,"maxDurationMs",12000,"profile","LANDSCAPE_720"),"window-proposal");
        String task=accepted.path("taskId").asText();JsonNode claim=json(internal(task,"claim",java.util.Map.of("executorId","window-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var request=java.util.Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","window-context","name","read_evidence_context","arguments",java.util.Map.of("evidenceId",speech.first()));
        var response=internal(task,"tool",request);assertEquals(200,response.statusCode(),response.body());
        JsonNode window=json(response).path("data").path("candidateWindow");
        assertEquals(2000,window.path("sourceInMs").asLong());assertEquals(10000,window.path("sourceOutMs").asLong());assertEquals(8000,window.path("durationMs").asLong());
        assertEquals(0,json(owner.get("/plans/"+accepted.path("planId").asText()+"/revisions")).path("data").path("items").size());
    }

    private JsonNode acceptPlan(BrowserApi owner,String session,Object editing,String key) throws Exception {
        var response=owner.post("/conversations/"+session+"/turns",new ObjectMapper().writeValueAsString(java.util.Map.of("content","生成完整观点方案","context",java.util.Map.of("editing",editing))),key);
        assertEquals(202,response.statusCode(),response.body());return json(response).path("data");
    }
    private JsonNode publishPlan(JsonNode accepted,Speech speech) throws Exception {
        String task=accepted.path("taskId").asText();
        JsonNode claim=json(internal(task,"claim",java.util.Map.of("executorId","history-test","claimRequestId",java.util.UUID.randomUUID()))).path("data");
        var request=new java.util.HashMap<String,Object>();request.put("attemptId",claim.path("attemptId").asText());request.put("leaseToken",claim.path("leaseToken").asText());
        var read=new java.util.HashMap<String,Object>(request);read.put("producerKey","read-context");read.put("name","read_evidence_context");read.put("arguments",java.util.Map.of("evidenceId",speech.first()));assertEquals(200,internal(task,"tool",read).statusCode());
        request.put("producerKey","publish-plan");request.put("resultType","EDIT_PROPOSAL");request.put("result",java.util.Map.of("content","完整步骤方案已就绪。","proposal",java.util.Map.of("title","完整步骤","clips",java.util.List.of(java.util.Map.of("assetId",speech.asset(),"sourceInMs",2000,"sourceOutMs",10000,"evidenceIds",java.util.List.of(speech.first(),speech.second()),"reason","保留两个完整观点")))));
        var candidate = new java.util.HashMap<String,Object>();candidate.put("attemptId",claim.path("attemptId").asText());candidate.put("leaseToken",claim.path("leaseToken").asText());candidate.put("producerKey","validated-proposal");candidate.put("proposal",((java.util.Map<?,?>)request.get("result")).get("proposal"));
        assertEquals(true,json(internal(task,"validate-edit",candidate)).path("data").path("valid").asBoolean());
        var response=internal(task,"complete",request);assertEquals(200,response.statusCode(),response.body());return json(response).path("data");
    }

    private record Speech(java.util.UUID asset, java.util.UUID first, java.util.UUID second) {}
    private Speech speechFixture(String project) {
        var projectId=java.util.UUID.fromString(project);
        var asset=java.util.UUID.randomUUID(); var upload=java.util.UUID.randomUUID();
        var analysis=java.util.UUID.randomUUID(); var model=java.util.UUID.randomUUID(); var task=java.util.UUID.randomUUID();
        var first=java.util.UUID.randomUUID(); var second=java.util.UUID.randomUUID(); String hash="b".repeat(64);
        // Seed a previously completed ASR; all assertions observe production HTTP behavior.
        // Separate browser verification uses actual uploaded media and real model evidence.
        fixtures.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'speech.mp4',1,?,1,1,?,? FROM projects WHERE id=?", upload,upload.toString(),hash,upload.toString(),java.util.UUID.randomUUID(),projectId);
        fixtures.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status,duration_ms,media_info_json,time_mapping_json) VALUES (?,?,?,?,'speech.mp4',1,?,'VALID',20000,'{\"streams\":[{\"codec_type\":\"video\"},{\"codec_type\":\"audio\"}]}','{\"hasAudio\":true}')",asset,projectId,upload,asset.toString(),hash);
        fixtures.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'speech-v1')",analysis,projectId,asset,hash);
        fixtures.update("INSERT INTO asset_analysis_heads(asset_id,analysis_id) VALUES (?,?)",asset,analysis);
        fixtures.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'CONTENT_ANALYZE','SUCCEEDED','{}')",task,projectId);
        fixtures.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status) VALUES (?,?,?,'fixture','{}','SUCCEEDED')",model,analysis,task);
        for(int i=0;i<2;i++) fixtures.update("INSERT INTO evidence_segments(id,project_id,asset_id,analysis_id,source_kind,ordinal,text,start_ms,end_ms,audio_start_ms,audio_end_ms,observation_json,model_call_id) VALUES (?,?,?,?,'SPEECH',?,?,?,?,?,?, '{}',?)",i==0?first:second,projectId,asset,analysis,i,i==0?"第一步，上传素材。":"第二步，核查原文。",i==0?2000:5000,i==0?5000:10000,i==0?2000:5000,i==0?5000:10000,model);
        return new Speech(asset,first,second);
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
        HttpResponse<String> patch(String path,String body) throws Exception {
            String token=json(get("/auth/csrf")).path("data").path("csrfToken").asText();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",token).method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
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

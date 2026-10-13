package com.agenticvideo.business.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="app.auth.max-attempts=100")
class ExportApiIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @Container static final org.testcontainers.containers.GenericContainer<?> SILO=new org.testcontainers.containers.GenericContainer<>(
            "pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46")
            .withEnv("MINIO_ROOT_USER","export-integration").withEnv("MINIO_ROOT_PASSWORD","export-integration-password")
            .withCommand("server","/data").withExposedPorts(9000).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("internal.worker-token",()->"export-integration-worker-token");
        p.add("storage.endpoint",()->"http://"+SILO.getHost()+":"+SILO.getMappedPort(9000));p.add("storage.public-endpoint",()->"http://"+SILO.getHost()+":"+SILO.getMappedPort(9000));
        p.add("storage.access-key",()->"export-integration");p.add("storage.secret-key",()->"export-integration-password");p.add("storage.bucket",()->"export-tests");
    }
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate fixtures;
    private final ObjectMapper json=new ObjectMapper();

    private final java.util.List<BrowserApi> owners=new java.util.ArrayList<>();
    @org.junit.jupiter.api.AfterEach void releaseQueuedTestBudgets() throws Exception {
        for(var owner:owners)for(var task:new java.util.ArrayList<>(owner.tasks))owner.post("/tasks/"+task+"/cancel",Map.of());
    }
    @Test void aConfirmationBelongsToOneVersionAndOnlyExplicitAuthorizedExportStartsRendering() throws Exception {
        BrowserApi owner=signedIn("export-owner"),other=signedIn("export-other");
        UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","确认与明确导出"))).path("id").asText());Plan plan=planFixture(project);
        var unconfirmed=Map.of("outputIds",List.of(plan.output()),"confirmationId",UUID.randomUUID(),"exportProfile","HD_720","explicitIntent","EXPORT");
        assertEquals(409,owner.post("/revisions/"+plan.first()+"/export-requests",unconfirmed).statusCode());
        assertEquals(0,data(owner.get("/revisions/"+plan.first()+"/confirmations")).path("items").size());
        assertEquals(400,owner.post("/revisions/"+plan.first()+"/confirmations",Map.of("explicitIntent","SATISFIED")).statusCode());
        String key=UUID.randomUUID().toString();var confirmationBody=Map.of("explicitIntent","CONFIRM");
        var accepted=owner.post("/revisions/"+plan.first()+"/confirmations",confirmationBody,key);assertEquals(201,accepted.statusCode(),accepted.body());
        JsonNode confirmation=data(accepted);assertEquals(plan.first().toString(),confirmation.path("revisionId").asText());
        assertEquals(confirmation,data(owner.post("/revisions/"+plan.first()+"/confirmations",confirmationBody,key)));
        assertEquals(1,data(owner.get("/revisions/"+plan.first()+"/confirmations")).path("items").size());
        assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/confirmations")).path("items").size());
        assertEquals(404,other.post("/revisions/"+plan.first()+"/confirmations",confirmationBody).statusCode());
        assertEquals(404,other.get("/revisions/"+plan.first()+"/confirmations").statusCode());
        String id=confirmation.path("confirmationId").asText();
        var exportBody=Map.of("outputIds",List.of(plan.output()),"confirmationId",id,"exportProfile","HD_720","explicitIntent","EXPORT");
        assertEquals(409,owner.post("/revisions/"+plan.second()+"/export-requests",exportBody).statusCode());
        assertEquals(400,owner.post("/revisions/"+plan.first()+"/export-requests",Map.of("outputIds",List.of(plan.output()),"confirmationId",id,"exportProfile","HD_720")).statusCode());
        assertEquals(404,other.post("/revisions/"+plan.first()+"/export-requests",exportBody).statusCode());
        String exportKey=UUID.randomUUID().toString();var exported=owner.post("/revisions/"+plan.first()+"/export-requests",exportBody,exportKey);
        assertEquals(202,exported.statusCode(),exported.body());JsonNode render=data(exported);
        assertEquals(plan.first().toString(),render.path("revisionId").asText());assertEquals(id,render.path("confirmationId").asText());assertEquals("EXPORT",render.path("kind").asText());
        assertEquals(render,data(owner.post("/revisions/"+plan.first()+"/export-requests",exportBody,exportKey)));
        assertEquals("EXPORT_RENDER",data(owner.get("/tasks/"+render.path("taskId").asText())).path("type").asText());
        assertEquals(1280,render.path("configuration").path("width").asInt());assertEquals(720,render.path("configuration").path("height").asInt());
        assertEquals(202,owner.post("/tasks/"+render.path("taskId").asText()+"/cancel",Map.of()).statusCode());
        JsonNode another=data(owner.post("/revisions/"+plan.first()+"/export-requests",exportBody));
        assertNotEquals(render.path("requestId").asText(),another.path("requestId").asText());assertNotEquals(render.path("taskId").asText(),another.path("taskId").asText());
        var history=owner.get("/revisions/"+plan.first()+"/export-requests");assertEquals(200,history.statusCode(),history.body());
        assertEquals(2,data(history).path("items").size());assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/export-requests")).path("items").size());
        assertEquals(404,other.get("/revisions/"+plan.first()+"/export-requests").statusCode());
    }
    @Test void anAttemptPublishesAnExportThatCanBeDownloadedAndOnlyExplicitDeletionRemovesIt() throws Exception {
        BrowserApi owner=signedIn("export-artifact-owner"),other=signedIn("export-artifact-other");
        UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","正式成片下载与删除"))).path("id").asText());Plan plan=planFixture(project);
        String confirmation=data(owner.post("/revisions/"+plan.first()+"/confirmations",Map.of("explicitIntent","CONFIRM"))).path("confirmationId").asText();
        JsonNode requested=data(owner.post("/revisions/"+plan.first()+"/export-requests",Map.of("outputIds",List.of(plan.output()),"confirmationId",confirmation,"exportProfile","HD_720","explicitIntent","EXPORT")));
        String task=requested.path("taskId").asText();
        var claimed=internal(task,"claim",Map.of("executorId","export-artifact-test","claimRequestId",UUID.randomUUID()));assertEquals(200,claimed.statusCode(),claimed.body());JsonNode claim=data(claimed);
        String key=claim.path("input").path("artifactObjectKey").asText(),artifact=claim.path("input").path("artifactId").asText();assertTrue(key.startsWith("exports/"));
        byte[] bytes="verified-export-worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var store=storage()){store.putObject(b->b.bucket("export-tests").key(key).contentType("video/mp4"),software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));}
        var result=Map.of("artifactId",artifact,"objectKey",key,"byteSize",bytes.length,"sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),"durationMs",2000,
                "mediaInfo",Map.of("streams",List.of(Map.of("codec_type","video","codec_name","h264","width",1280,"height",720),Map.of("codec_type","audio","codec_name","aac","channels",2,"sample_rate","48000"))));
        var completedBody=Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","export-rendered","resultType","EXPORT_RENDERED","result",result);
        var completed=internal(task,"complete",completedBody);assertEquals(200,completed.statusCode(),completed.body());assertEquals(data(completed),data(internal(task,"complete",completedBody)));
        assertEquals("SUCCEEDED",data(owner.get("/render-requests/"+requested.path("requestId").asText())).path("status").asText());
        assertEquals(404,other.post("/artifacts/"+artifact+"/download-access",Map.of()).statusCode());
        var access=owner.post("/artifacts/"+artifact+"/download-access",Map.of());assertEquals(200,access.statusCode(),access.body());
        var downloaded=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(data(access).path("url").asText())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,downloaded.statusCode());assertArrayEquals(bytes,downloaded.body());assertTrue(downloaded.headers().firstValue("Content-Disposition").orElse("").contains("attachment"));
        assertEquals(bytes.length,data(owner.get("/artifacts/"+artifact)).path("storedBytes").asLong());
        assertEquals(400,owner.delete("/artifacts/"+artifact,Map.of()).statusCode());
        assertEquals(404,other.delete("/artifacts/"+artifact,Map.of("explicitIntent","DELETE")).statusCode());
        var removed=owner.delete("/artifacts/"+artifact,Map.of("explicitIntent","DELETE"));assertEquals(200,removed.statusCode(),removed.body());
        assertEquals("REMOVED",data(removed).path("state").asText());assertEquals(bytes.length,data(removed).path("removedBytes").asLong());
        assertEquals(0,data(owner.get("/artifacts/"+artifact)).path("storedBytes").asLong());assertEquals(410,owner.post("/artifacts/"+artifact+"/download-access",Map.of()).statusCode());
        JsonNode source=data(owner.post("/assets/"+plan.asset()+"/playback-access",Map.of()));
        var original=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(source.path("url").asText())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals("original".getBytes(java.nio.charset.StandardCharsets.UTF_8),original.body());
        JsonNode missing=data(owner.post("/revisions/"+plan.first()+"/export-requests",Map.of("outputIds",List.of(plan.output()),"confirmationId",confirmation,"exportProfile","HD_720","explicitIntent","EXPORT")));
        String missingTask=missing.path("taskId").asText();JsonNode missingClaim=data(internal(missingTask,"claim",Map.of("executorId","export-artifact-test","claimRequestId",UUID.randomUUID())));
        String missingKey=missingClaim.path("input").path("artifactObjectKey").asText(),missingArtifact=missingClaim.path("input").path("artifactId").asText();
        try(var store=storage()){store.putObject(b->b.bucket("export-tests").key(missingKey).contentType("video/mp4"),software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));}
        var missingResult=new HashMap<String,Object>(result);missingResult.put("artifactId",missingArtifact);missingResult.put("objectKey",missingKey);
        assertEquals(200,internal(missingTask,"complete",Map.of("attemptId",missingClaim.path("attemptId").asText(),"leaseToken",missingClaim.path("leaseToken").asText(),"producerKey","missing-export","resultType","EXPORT_RENDERED","result",missingResult)).statusCode());
        // Real external object loss must not be reported as newly released storage.
        try(var store=storage()){store.deleteObject(b->b.bucket("export-tests").key(missingKey));}
        assertEquals(0,data(owner.get("/artifacts/"+missingArtifact)).path("storedBytes").asLong());
        var missingRemoved=owner.delete("/artifacts/"+missingArtifact,Map.of("explicitIntent","DELETE"));assertEquals(200,missingRemoved.statusCode());
        assertEquals(0,data(missingRemoved).path("removedBytes").asLong());
    }
    @Test void naturalPreviewFreezesTheSentViewAndExplicitReferencesTakePriority() throws Exception {
        BrowserApi owner=signedIn("preview-chat-freeze");
        UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","聊天固定版本"))).path("id").asText());Plan plan=planFixture(project);
        String session=data(owner.post("/projects/"+project+"/conversations",Map.of())).path("id").asText();
        String planId=data(owner.get("/revisions/"+plan.first())).path("planId").asText();
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.first(),"expectedRowVersion",0)).statusCode());
        var body=Map.of("content","请帮我看看这版做出来的效果","context",Map.of("naturalLanguage",true));
        JsonNode turn=data(owner.post("/conversations/"+session+"/turns",body,"fixed-preview"));String task=turn.path("taskId").asText();
        JsonNode claim=data(internal(task,"claim",Map.of("executorId","preview-chat","claimRequestId",UUID.randomUUID())));
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.second(),"expectedRowVersion",1)).statusCode());
        var forged=commandTool(task,claim,"invented-version","submit_production_request",Map.of("intent","PREVIEW","newRequirement",false,"fields",Map.of(),"target",Map.of("revisionNumber",2)));assertEquals(400,forged.statusCode(),forged.body());
        var admission=Map.of("intent","PREVIEW","newRequirement",false,"fields",Map.of());
        var response=commandTool(task,claim,"preview-admit","submit_production_request",admission);assertEquals(200,response.statusCode(),response.body());
        JsonNode fixed=data(response);assertEquals(plan.first().toString(),fixed.path("productionTarget").path("revisionId").asText());
        assertEquals(0,data(owner.get("/revisions/"+plan.first()+"/previews")).path("items").size());
        response=commandTool(task,claim,"preview-execute","execute_production_command",Map.of());assertEquals(200,response.statusCode(),response.body());JsonNode result=data(response);
        assertEquals(plan.first().toString(),result.path("references").get(0).path("revisionId").asText());
        assertEquals(result,data(commandTool(task,claim,"preview-execute","execute_production_command",Map.of())));
        assertEquals(1,data(owner.get("/revisions/"+plan.first()+"/previews")).path("items").size());
        assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/previews")).path("items").size());
        assertEquals(200,internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","preview-answer","resultType","CONVERSATION_ANSWERED","result",Map.of("content",result.path("content").asText(),"evidenceIds",List.of()))).statusCode());
        JsonNode history=data(owner.get("/conversations/"+session+"/snapshot"));assertEquals("PREVIEW_REQUEST",history.path("items").get(history.path("items").size()-1).path("references").get(0).path("type").asText());
        var explicit=Map.of("content","请预览我明确引用的这个版本","references",List.of(Map.of("type","PLAN_REVISION","id",plan.first())),"context",Map.of("naturalLanguage",true));
        String explicitTask=data(owner.post("/conversations/"+session+"/turns",explicit)).path("taskId").asText();JsonNode explicitClaim=data(internal(explicitTask,"claim",Map.of("executorId","preview-chat","claimRequestId",UUID.randomUUID())));
        response=commandTool(explicitTask,explicitClaim,"explicit-admit","submit_production_request",admission);assertEquals(200,response.statusCode(),response.body());assertEquals(plan.first().toString(),data(response).path("productionTarget").path("revisionId").asText());
    }
    @Test void aVersionNumberCannotBeMatchedInsideALargerNumber() throws Exception {
        BrowserApi owner=signedIn("exact-production-number");UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","版本号完整匹配"))).path("id").asText());Plan plan=planFixture(project);
        for(String content:List.of("请预览第12版","请预览第十二版")) {
            String session=data(owner.post("/projects/"+project+"/conversations",Map.of())).path("id").asText(),planId=data(owner.get("/revisions/"+plan.first())).path("planId").asText();
            assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.second(),"expectedRowVersion",0)).statusCode());
            String task=data(owner.post("/conversations/"+session+"/turns",Map.of("content",content,"context",Map.of("naturalLanguage",true)))).path("taskId").asText();
            JsonNode claim=data(internal(task,"claim",Map.of("executorId","exact-number","claimRequestId",UUID.randomUUID())));
            var admission=commandTool(task,claim,"partial-number","submit_production_request",Map.of("intent","PREVIEW","newRequirement",false,"fields",Map.of(),"target",Map.of("revisionNumber",2)));
            assertEquals(400,admission.statusCode(),admission.body());assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/previews")).path("items").size());
        }
    }
    @Test void longVersionHistoryKeepsChatBoundedAndResolvesOldTargetsAtTheSentSnapshot() throws Exception {
        BrowserApi owner=signedIn("bounded-production-history");UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","长会话版本定位"))).path("id").asText());Plan plan=planFixture(project);
        String session=data(owner.post("/projects/"+project+"/conversations",Map.of())).path("id").asText(),planId=data(owner.get("/revisions/"+plan.first())).path("planId").asText();
        for(int number=3;number<=42;number++)addRevisionFixture(project,planId,plan.first(),number);
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.second(),"expectedRowVersion",0)).statusCode());
        String task=data(owner.post("/conversations/"+session+"/turns",Map.of("content","请预览第一版","context",Map.of("naturalLanguage",true)))).path("taskId").asText();
        JsonNode claim=data(internal(task,"claim",Map.of("executorId","bounded-chat","claimRequestId",UUID.randomUUID())));
        assertTrue(claim.path("input").path("production").path("candidates").size()<=12,"长会话不能无限携带版本目录");
        var admitted=commandTool(task,claim,"old-version","submit_production_request",Map.of("intent","PREVIEW","newRequirement",false,"fields",Map.of(),"target",Map.of("revisionNumber",1)));
        assertEquals(200,admitted.statusCode(),admitted.body());assertEquals(plan.first().toString(),data(admitted).path("productionTarget").path("revisionId").asText());
        finishCommand(task,claim,data(commandTool(task,claim,"old-preview","execute_production_command",Map.of())));
        try(var publishing=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            publishing.setAutoCommit(false);
            var concurrentFixture=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(publishing,true));
            addRevisionFixture(concurrentFixture,project,plan.first(),43);
            task=data(owner.post("/conversations/"+session+"/turns",Map.of("content","请预览第43版","context",Map.of("naturalLanguage",true)))).path("taskId").asText();
            claim=data(internal(task,"claim",Map.of("executorId","bounded-chat","claimRequestId",UUID.randomUUID())));
            publishing.commit();
        }
        admitted=commandTool(task,claim,"future-version","submit_production_request",Map.of("intent","PREVIEW","newRequirement",false,"fields",Map.of(),"target",Map.of("revisionNumber",43)));
        assertEquals(200,admitted.statusCode(),admitted.body());assertEquals("CLARIFY",data(admitted).path("intent").asText());assertFalse(data(admitted).has("productionTarget"));
    }
    private void addRevisionFixture(UUID project,String planId,UUID source,int number) {
        addRevisionFixture(fixtures,project,source,number);
    }
    private void addRevisionFixture(org.springframework.jdbc.core.JdbcTemplate fixture,UUID project,UUID source,int number) {
        UUID task=UUID.randomUUID();fixture.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'AGENT_TURN','SUCCEEDED','{}')",task,project);
        fixture.update("INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,parent_revision_id,source_task_id,content_json) SELECT ?,project_id,plan_id,?,id,?,content_json FROM plan_revisions WHERE id=?",UUID.randomUUID(),number,task,source);
    }
    @Test void naturalConfirmationAndExportRequireSeparateMessagesAndAnExistingConfirmedVersion() throws Exception {
        BrowserApi owner=signedIn("production-chat-authority");UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","聊天授权边界"))).path("id").asText());Plan plan=planFixture(project);
        String session=data(owner.post("/projects/"+project+"/conversations",Map.of())).path("id").asText(),planId=data(owner.get("/revisions/"+plan.first())).path("planId").asText();
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.first(),"expectedRowVersion",0)).statusCode());
        String path="/conversations/"+session+"/turns";
        String task=data(owner.post(path,Map.of("content","这版没有问题，请确认它。","context",Map.of("naturalLanguage",true)))).path("taskId").asText();JsonNode claim=data(internal(task,"claim",Map.of("executorId","production-chat","claimRequestId",UUID.randomUUID())));
        var response=commandTool(task,claim,"confirm-admit","submit_production_request",Map.of("intent","CONFIRM","newRequirement",false,"fields",Map.of()));assertEquals(200,response.statusCode(),response.body());
        String requirement=data(response).path("requirementId").asText();assertFalse(requirement.isBlank());
        JsonNode result=data(commandTool(task,claim,"confirm-execute","execute_production_command",Map.of()));assertEquals("CONFIRMATION",result.path("references").get(0).path("type").asText());
        assertEquals(1,data(owner.get("/revisions/"+plan.first()+"/confirmations")).path("items").size());assertEquals(0,data(owner.get("/revisions/"+plan.first()+"/export-requests")).path("items").size());
        finishCommand(task,claim,result);
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.second(),"expectedRowVersion",1)).statusCode());
        task=data(owner.post(path,Map.of("content","请将 V1 导出为720p的 MP4。","context",Map.of("naturalLanguage",true)))).path("taskId").asText();claim=data(internal(task,"claim",Map.of("executorId","production-chat","claimRequestId",UUID.randomUUID())));
        response=commandTool(task,claim,"export-admit","submit_production_request",Map.of("intent","EXPORT","newRequirement",false,"fields",Map.of(),"target",Map.of("revisionNumber",1,"profile","HD_720")));assertEquals(200,response.statusCode(),response.body());assertEquals(plan.first().toString(),data(response).path("productionTarget").path("revisionId").asText());assertEquals(requirement,data(response).path("requirementId").asText());
        response=commandTool(task,claim,"export-execute","execute_production_command",Map.of());assertEquals(200,response.statusCode(),response.body());result=data(response);assertEquals("EXPORT_REQUEST",result.path("references").get(0).path("type").asText());assertEquals(result,data(commandTool(task,claim,"export-execute","execute_production_command",Map.of())));
        assertEquals(1,data(owner.get("/revisions/"+plan.first()+"/export-requests")).path("items").size());assertEquals("HD_720",data(owner.get("/render-requests/"+result.path("references").get(0).path("id").asText())).path("configuration").path("profile").asText());
        assertEquals(200,internal(task,"fail",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","reply-lost","code","CONVERSATION_UNVERIFIED","message","导出已接纳，但回复丢失")).statusCode());
        try(var store=storage()){store.deleteObject(b->b.bucket("export-tests").key("originals/"+plan.asset()));}
        String retry=data(owner.post("/conversations/"+session+"/tasks/"+task+"/retry",Map.of())).path("taskId").asText();JsonNode retryClaim=data(internal(retry,"claim",Map.of("executorId","production-chat","claimRequestId",UUID.randomUUID())));
        assertEquals(plan.first().toString(),retryClaim.path("input").path("productionTarget").path("revisionId").asText());
        var recovered=commandTool(retry,retryClaim,"export-recovered","execute_production_command",Map.of());assertEquals(200,recovered.statusCode(),recovered.body());assertEquals(result,data(recovered));finishCommand(retry,retryClaim,data(recovered));
        assertEquals(1,data(owner.get("/revisions/"+plan.first()+"/export-requests")).path("items").size());
        task=data(owner.post(path,Map.of("content","请把现在这版导出成 MP4。","context",Map.of("naturalLanguage",true)))).path("taskId").asText();claim=data(internal(task,"claim",Map.of("executorId","production-chat","claimRequestId",UUID.randomUUID())));
        assertEquals(200,commandTool(task,claim,"unconfirmed-admit","submit_production_request",Map.of("intent","EXPORT","newRequirement",false,"fields",Map.of())).statusCode());
        result=data(commandTool(task,claim,"unconfirmed-execute","execute_production_command",Map.of()));assertTrue(result.path("content").asText().contains("确认"));assertTrue(result.path("references").isEmpty());assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/confirmations")).path("items").size());assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/export-requests")).path("items").size());finishCommand(task,claim,result);
        for(String content:List.of("我觉得不错。","查询原句“确认当前版本”是否出现。","做完就导出。","请确认这版然后马上导出。")){
            task=data(owner.post(path,Map.of("content",content,"context",Map.of("naturalLanguage",true)))).path("taskId").asText();claim=data(internal(task,"claim",Map.of("executorId","production-chat","claimRequestId",UUID.randomUUID())));
            response=commandTool(task,claim,"forged-authority","submit_production_request",Map.of("intent",content.contains("导出")?"EXPORT":"CONFIRM","newRequirement",false,"fields",Map.of()));assertEquals(400,response.statusCode(),response.body());assertEquals(202,owner.post("/tasks/"+task+"/cancel",Map.of()).statusCode());
            assertEquals(200,internal(task,"cancelled",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"processExited",true)).statusCode());
        }
        assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/confirmations")).path("items").size());
    }
    @Test void conflictingTargetsWaitAndANaturalAnswerResolvesTheOriginalOperation() throws Exception {
        BrowserApi owner=signedIn("production-chat-targets");UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","聊天对象澄清"))).path("id").asText());Plan plan=planFixture(project);
        String session=data(owner.post("/projects/"+project+"/conversations",Map.of())).path("id").asText(),planId=data(owner.get("/revisions/"+plan.first())).path("planId").asText();
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.first(),"expectedRowVersion",0)).statusCode());
        String path="/conversations/"+session+"/turns";var body=Map.of("content","请预览这两个引用中的一个版本。","references",List.of(Map.of("type","PLAN_REVISION","id",plan.first()),Map.of("type","PLAN_REVISION","id",plan.second())),"context",Map.of("naturalLanguage",true));
        JsonNode turn=data(owner.post(path,body,"ambiguous-preview"));String task=turn.path("taskId").asText();JsonNode claim=data(internal(task,"claim",Map.of("executorId","production-targets","claimRequestId",UUID.randomUUID())));
        var admitted=commandTool(task,claim,"ambiguous-admit","submit_production_request",Map.of("intent","PREVIEW","newRequirement",false,"fields",Map.of()));assertEquals(200,admitted.statusCode(),admitted.body());JsonNode fixed=data(admitted);assertEquals("CLARIFY",fixed.path("intent").asText());assertFalse(fixed.has("productionTarget"));
        assertEquals(200,internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","target-question","resultType","CONVERSATION_WAITING","result",Map.of("question",fixed.path("productionQuestion")))).statusCode());
        assertEquals(0,data(owner.get("/revisions/"+plan.first()+"/previews")).path("items").size());
        String unresolved=data(owner.post(path,Map.of("content","随便吧。","context",Map.of("naturalLanguage",true)))).path("taskId").asText();JsonNode unresolvedClaim=data(internal(unresolved,"claim",Map.of("executorId","production-targets","claimRequestId",UUID.randomUUID())));
        var unresolvedResult=commandTool(unresolved,unresolvedClaim,"empty-target-answer","submit_production_request",Map.of("intent","PREVIEW","newRequirement",false,"questionAction","ANSWER","fields",Map.of()));assertEquals(400,unresolvedResult.statusCode(),unresolvedResult.body());
        assertEquals(202,owner.post("/tasks/"+unresolved+"/cancel",Map.of()).statusCode());assertEquals(200,internal(unresolved,"cancelled",Map.of("attemptId",unresolvedClaim.path("attemptId").asText(),"leaseToken",unresolvedClaim.path("leaseToken").asText(),"processExited",true)).statusCode());
        String unknown=data(owner.post(path,Map.of("content","选择第99版。","context",Map.of("naturalLanguage",true)))).path("taskId").asText();JsonNode unknownClaim=data(internal(unknown,"claim",Map.of("executorId","production-targets","claimRequestId",UUID.randomUUID())));
        var unknownResult=commandTool(unknown,unknownClaim,"unknown-target-answer","submit_production_request",Map.of("intent","PREVIEW","newRequirement",false,"questionAction","ANSWER","target",Map.of("revisionNumber",99),"fields",Map.of()));assertEquals(200,unknownResult.statusCode(),unknownResult.body());assertEquals("DISCUSS",data(unknownResult).path("intent").asText());assertEquals("AMBIGUOUS",data(unknownResult).path("questionAction").asText());
        finishCommand(unknown,unknownClaim,data(unknownResult).path("productionReply"));assertEquals(1,data(owner.get("/conversations/"+session+"/snapshot")).path("questions").size());
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.second(),"expectedRowVersion",1)).statusCode());
        String replyTask=data(owner.post(path,Map.of("content","选择第一版。","context",Map.of("naturalLanguage",true)))).path("taskId").asText();JsonNode replyClaim=data(internal(replyTask,"claim",Map.of("executorId","production-targets","claimRequestId",UUID.randomUUID())));
        admitted=commandTool(replyTask,replyClaim,"target-answer","submit_production_request",Map.of("intent","PREVIEW","newRequirement",false,"questionAction","ANSWER","target",Map.of("revisionNumber",1),"fields",Map.of()));assertEquals(200,admitted.statusCode(),admitted.body());assertEquals(plan.first().toString(),data(admitted).path("productionTarget").path("revisionId").asText());
        JsonNode result=data(commandTool(replyTask,replyClaim,"answered-preview","execute_production_command",Map.of()));finishCommand(replyTask,replyClaim,result);
        JsonNode snapshot=data(owner.get("/conversations/"+session+"/snapshot"));assertEquals("ANSWERED",snapshot.path("questions").get(0).path("status").asText());assertEquals("SUCCEEDED",data(owner.get("/tasks/"+task)).path("status").asText());
        BrowserApi other=signedIn("production-target-foreign");UUID foreign=UUID.fromString(data(other.post("/projects",Map.of("name","另一个项目"))).path("id").asText());Plan foreignPlan=planFixture(foreign);
        assertEquals(404,owner.post(path,Map.of("content","预览这版","references",List.of(Map.of("type","PLAN_REVISION","id",foreignPlan.first())),"context",Map.of("naturalLanguage",true))).statusCode());
    }

    private void finishCommand(String task,JsonNode claim,JsonNode result) throws Exception {
        assertEquals(200,internal(task,"complete",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","command-answer","resultType","CONVERSATION_ANSWERED","result",Map.of("content",result.path("content").asText(),"evidenceIds",List.of()))).statusCode());
    }

    private HttpResponse<String> commandTool(String task,JsonNode claim,String key,String name,Object arguments) throws Exception {
        return internal(task,"tool",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey",key,"name",name,"arguments",arguments));
    }

    @Test void directNaturalLanguageUsesTheViewedVersionAndAmbiguityNeverStartsExport() throws Exception {
        BrowserApi owner=signedIn("export-natural-owner");
        UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","自然语言制作路由"))).path("id").asText());Plan plan=planFixture(project);
        String session=data(owner.post("/projects/"+project+"/conversations",Map.of("title","明确确认与导出","assetIds",List.of(plan.asset())))).path("id").asText();
        String planId=data(owner.get("/revisions/"+plan.first())).path("planId").asText();
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.first(),"expectedRowVersion",0)).statusCode());
        String path="/conversations/"+session+"/turns";
        var ambiguous=owner.post(path,Map.of("content","我很满意，能导出吗？"));assertEquals(202,ambiguous.statusCode());
        JsonNode history=data(owner.get("/conversations/"+session+"/messages"));assertEquals("SUCCEEDED",history.path("latestTask").path("status").asText());
        assertTrue(history.path("items").get(1).path("content").asText().contains("明确"));
        assertEquals(0,data(owner.get("/revisions/"+plan.first()+"/confirmations")).path("items").size());
        var confirm=owner.post(path,Map.of("content","确认当前版本"),"natural-confirm");assertEquals(202,confirm.statusCode(),confirm.body());
        assertEquals(data(confirm),data(owner.post(path,Map.of("content","确认当前版本"),"natural-confirm")));
        assertEquals(1,data(owner.get("/revisions/"+plan.first()+"/confirmations")).path("items").size());
        assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/confirmations")).path("items").size());
        var export=owner.post(path,Map.of("content","导出当前版本 720p"));assertEquals(202,export.statusCode(),export.body());
        history=data(owner.get("/conversations/"+session+"/messages"));
        JsonNode reference=history.path("items").get(history.path("items").size()-1).path("references").get(0);
        assertEquals("EXPORT_REQUEST",reference.path("type").asText());
        JsonNode accepted=data(owner.get("/render-requests/"+reference.path("id").asText()));
        assertEquals(plan.first().toString(),accepted.path("revisionId").asText());assertEquals("EXPORT",accepted.path("kind").asText());
        assertEquals("HD_720",accepted.path("configuration").path("profile").asText());
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.second(),"expectedRowVersion",1)).statusCode());
        var rejected=owner.post(path,Map.of("content","导出当前版本 1080p"));assertEquals(202,rejected.statusCode(),rejected.body());
        history=data(owner.get("/conversations/"+session+"/messages"));
        assertTrue(history.path("items").get(history.path("items").size()-1).path("content").asText().contains("确认"));
        var query=owner.post(path,Map.of("content","查询原句“确认当前版本”是否出现"));assertEquals(202,query.statusCode(),query.body());
        assertEquals("AGENT_TURN",data(owner.get("/tasks/"+data(query).path("taskId").asText())).path("type").asText());
        assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/confirmations")).path("items").size());
    }
    @Test void stalledStorageDoesNotLockTheProjectOrConversationAndAChangedViewIsClarified() throws Exception {
        BrowserApi owner=signedIn("export-storage-stall");
        UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","存储阻塞与短事务"))).path("id").asText());Plan plan=planFixture(project);
        String confirmation=data(owner.post("/revisions/"+plan.first()+"/confirmations",Map.of("explicitIntent","CONFIRM"))).path("confirmationId").asText();
        String session=data(owner.post("/projects/"+project+"/conversations",Map.of("title","并行核查","assetIds",List.of(plan.asset())))).path("id").asText();
        String planId=data(owner.get("/revisions/"+plan.first())).path("planId").asText();
        assertEquals(200,owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.first(),"expectedRowVersion",0)).statusCode());
        var prior=owner.post("/conversations/"+session+"/turns",Map.of("content","导出当前版本 720p"),"stored-natural-export");assertEquals(202,prior.statusCode());
        java.util.concurrent.CompletableFuture<HttpResponse<String>> direct=null,natural=null;
        SILO.getDockerClient().pauseContainerCmd(SILO.getContainerId()).exec();
        try {
            var repeated=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return owner.post("/conversations/"+session+"/turns",Map.of("content","导出当前版本 720p"),"stored-natural-export");}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}});
            var receipt=assertDoesNotThrow(()->repeated.get(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(202,receipt.statusCode());assertEquals(data(prior),data(receipt));
            assertEquals(409,owner.post("/conversations/"+session+"/turns",Map.of("content","导出当前版本 1080p"),"stored-natural-export").statusCode());
            direct=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return owner.post("/revisions/"+plan.first()+"/export-requests",Map.of("outputIds",List.of(plan.output()),"confirmationId",confirmation,"exportProfile","HD_720","explicitIntent","EXPORT"));}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}});
            natural=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return owner.post("/conversations/"+session+"/turns",Map.of("content","导出当前版本 720p"));}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}});
            Thread.sleep(1000);assertFalse(direct.isDone());assertFalse(natural.isDone());
            var confirmed=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return owner.post("/revisions/"+plan.second()+"/confirmations",Map.of("explicitIntent","CONFIRM"));}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}});
            assertEquals(201,confirmed.get(3,java.util.concurrent.TimeUnit.SECONDS).statusCode());
            var changed=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return owner.patch("/conversations/"+session+"/view",Map.of("planId",planId,"revisionId",plan.second(),"expectedRowVersion",1));}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}});
            assertEquals(200,changed.get(3,java.util.concurrent.TimeUnit.SECONDS).statusCode());
        }finally{SILO.getDockerClient().unpauseContainerCmd(SILO.getContainerId()).exec();}
        assertEquals(202,direct.get(15,java.util.concurrent.TimeUnit.SECONDS).statusCode());assertEquals(202,natural.get(15,java.util.concurrent.TimeUnit.SECONDS).statusCode());
        JsonNode history=data(owner.get("/conversations/"+session+"/messages"));
        assertTrue(history.path("items").get(3).path("content").asText().contains("变化"));assertEquals(0,history.path("items").get(3).path("references").size());
        assertEquals(2,data(owner.get("/revisions/"+plan.first()+"/export-requests")).path("items").size());assertEquals(0,data(owner.get("/revisions/"+plan.second()+"/export-requests")).path("items").size());
    }
    private HttpResponse<String> internal(String task,String action,Object body) throws Exception {return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/tasks/"+task+"/"+action)).header("Authorization","Bearer export-integration-worker-token").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    private software.amazon.awssdk.services.s3.S3Client storage(){return software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create("http://"+SILO.getHost()+":"+SILO.getMappedPort(9000))).region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true).credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("export-integration","export-integration-password"))).build();}
    private record Plan(UUID first,UUID second,UUID output,UUID asset) {}
    private Plan planFixture(UUID project) throws Exception {
        UUID asset=UUID.randomUUID(),upload=UUID.randomUUID(),plan=UUID.randomUUID(),first=UUID.randomUUID(),second=UUID.randomUUID(),output=UUID.randomUUID();
        byte[] bytes="original".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),objectKey="originals/"+asset;
        try(var store=software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create("http://"+SILO.getHost()+":"+SILO.getMappedPort(9000)))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true).credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("export-integration","export-integration-password"))).build()) {
            try{store.createBucket(b->b.bucket("export-tests"));}catch(software.amazon.awssdk.services.s3.model.S3Exception exists){if(exists.statusCode()!=409)throw exists;}
            store.putObject(b->b.bucket("export-tests").key(objectKey),software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
        }
        // Prepare a prior validated asset/version at the public export seam, not a real decoding claim.
        fixtures.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'source.mp4',?,?,8,1,?,? FROM projects WHERE id=?",upload,upload.toString(),bytes.length,hash,objectKey,UUID.randomUUID(),project);
        fixtures.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status,duration_ms,media_info_json,time_mapping_json) VALUES (?,?,?,?,'source.mp4',?,?,'VALID',20000,'{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\"},{\"codec_type\":\"audio\",\"codec_name\":\"aac\"}]}','{\"hasAudio\":true}')",asset,project,upload,objectKey,bytes.length,hash);
        fixtures.update("INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES (?,?,'已有方案',3)",plan,project);
        var content=Map.of("schemaVersion","1.0","outputs",List.of(Map.of("outputId",output,"title","正式导出样本","durationMs",2000,"clips",List.of(Map.of("clipId",UUID.randomUUID(),"assetId",asset,"sourceInMs",2000,"sourceOutMs",4000,"audio","original","evidenceIds",List.of(),"reason","已有版本")),"render",Map.of("profile","LANDSCAPE_720","width",1280,"height",720,"fit","contain"),"subtitles",Map.of("enabled",true,"cues",List.of(Map.of("startMs",0,"endMs",1800,"text","已有原文字幕。"))))));
        for(var revision:List.of(first,second)) {
            UUID task=UUID.randomUUID();fixtures.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'AGENT_TURN','SUCCEEDED','{}')",task,project);
            fixtures.update("INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,parent_revision_id,source_task_id,content_json) VALUES (?,?,?,?,?,?,?::jsonb)",revision,project,plan,revision.equals(first)?1:2,revision.equals(first)?null:first,task,json.writeValueAsString(content));
            fixtures.update("INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES (?,?)",revision,asset);
        }
        return new Plan(first,second,output,asset);
    }
    private JsonNode data(HttpResponse<String> reply) throws Exception {return json.readTree(reply.body()).path("data");}
    private BrowserApi signedIn(String username) throws Exception {BrowserApi browser=new BrowserApi();owners.add(browser);var body=Map.of("username",username,"password","export-browser-password");assertEquals(201,browser.post("/auth/register",body).statusCode());assertEquals(200,browser.post("/auth/login",body).statusCode());return browser;}
    private final class BrowserApi {
        final java.util.Set<String> tasks=new java.util.LinkedHashSet<>();
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> patch(String path,Object body) throws Exception {String csrf=data(get("/auth/csrf")).path("csrfToken").asText();return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).method("PATCH",HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> delete(String path,Object body) throws Exception {String csrf=data(get("/auth/csrf")).path("csrfToken").asText();return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).header("Idempotency-Key",UUID.randomUUID().toString()).method("DELETE",HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> get(String path) throws Exception {return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> post(String path,Object body) throws Exception {return post(path,body,UUID.randomUUID().toString());}
        HttpResponse<String> post(String path,Object body,String key) throws Exception {String csrf=data(get("/auth/csrf")).path("csrfToken").asText();var reply=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());String task=data(reply).path("taskId").asText();if(!task.isBlank())tasks.add(task);return reply;}
    }
}

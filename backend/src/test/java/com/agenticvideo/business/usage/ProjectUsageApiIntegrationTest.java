package com.agenticvideo.business.usage;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@org.springframework.test.context.TestPropertySource(properties={"legacy.worker-enabled=true","legacy.agent-enabled=true","analysis.execution-enabled=true","legacy.inspection-enabled=true"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="app.auth.max-attempts=100")
class ProjectUsageApiIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @Container static final org.testcontainers.containers.GenericContainer<?> SILO=new org.testcontainers.containers.GenericContainer<>(
            "pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46")
            .withEnv("MINIO_ROOT_USER","usage-integration").withEnv("MINIO_ROOT_PASSWORD","usage-integration-password")
            .withCommand("server","/data").withExposedPorts(9000).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("internal.worker-token",()->"usage-integration-worker-token");
        p.add("storage.endpoint",()->"http://"+SILO.getHost()+":"+SILO.getMappedPort(9000));p.add("storage.public-endpoint",()->"http://"+SILO.getHost()+":"+SILO.getMappedPort(9000));
        p.add("storage.access-key",()->"usage-integration");p.add("storage.secret-key",()->"usage-integration-password");p.add("storage.bucket",()->"usage-tests");
    }
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate fixtures;
    private final ObjectMapper json=new ObjectMapper();

    @Test void emptyProjectShowsFourTrueZerosAndOwnedPersistentWarningThresholds() throws Exception {
        var owner=signedIn("usage-empty-owner");var outsider=signedIn("usage-empty-other");
        String project=data(owner.post("/projects",Map.of("name","用量与预警"))).path("id").asText();
        assertEquals(404,outsider.get("/projects/"+project+"/usage").statusCode());
        var response=owner.get("/projects/"+project+"/usage");assertEquals(200,response.statusCode(),response.body());
        var usage=data(response);assertEquals(4,usage.path("storage").size());
        for(String category:List.of("ORIGINAL","DERIVED","PREVIEW","EXPORT")) {
            assertEquals(0,usage.path("storage").path(category).path("storedBytes").asLong(-1));
            assertEquals(0,usage.path("storage").path(category).path("reservedBytes").asLong(-1));
            assertEquals(0,usage.path("storage").path(category).path("pendingCleanupBytes").asLong(-1));
        }
        assertEquals(0,usage.path("models").path("callCount").asInt(-1));
        assertTrue(usage.path("models").path("estimatedCostCny").isNumber());
        assertEquals(0,usage.path("warnings").size());
        var threshold=Map.of("costCny",0.000001,"calls",1,"tokens",1,"audioSeconds",0.1);
        assertEquals(404,outsider.patch("/projects/"+project+"/usage/thresholds",threshold).statusCode());
        assertEquals(200,owner.patch("/projects/"+project+"/usage/thresholds",threshold).statusCode());
        assertEquals(1,data(owner.get("/projects/"+project+"/usage")).path("thresholds").path("calls").asInt());
        assertEquals(400,owner.patch("/projects/"+project+"/usage/thresholds",Map.of("calls",-1)).statusCode());
        assertEquals(200,owner.patch("/projects/"+project+"/usage/thresholds",Map.of()).statusCode());
        assertTrue(data(owner.get("/projects/"+project+"/usage")).path("thresholds").path("calls").isNull());
    }
    @Test void physicalObjectsMultipartReservationsAndPendingCleanupAreSeparatedByCategory() throws Exception {
        var owner=signedIn("usage-storage-owner");UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","物理空间计量"))).path("id").asText());
        Plan plan=planFixture(project);
        String preview=data(owner.post("/revisions/"+plan.first()+"/previews",Map.of("outputId",plan.output(),"previewProfile","LOW_360"))).path("taskId").asText();
        var previewClaim=data(internal(preview,"claim",Map.of("executorId","usage-fixture","claimRequestId",UUID.randomUUID())));
        String confirmation=data(owner.post("/revisions/"+plan.first()+"/confirmations",Map.of("explicitIntent","CONFIRM"))).path("confirmationId").asText();
        String export=data(owner.post("/revisions/"+plan.first()+"/export-requests",Map.of("outputIds",List.of(plan.output()),"confirmationId",confirmation,"exportProfile","HD_720","explicitIntent","EXPORT"))).path("taskId").asText();
        var exportClaim=data(internal(export,"claim",Map.of("executorId","usage-fixture","claimRequestId",UUID.randomUUID())));
        var upload=data(owner.post("/projects/"+project+"/uploads",Map.of("filename","partial.mp4","sizeBytes",100,"sha256","b".repeat(64),"idempotencyKey",UUID.randomUUID().toString(),"writerId",UUID.randomUUID())));
        String originalKey="originals/"+upload.path("uploadId").asText();
        try(var store=storage()) {
            store.putObject(b->b.bucket("usage-tests").key("derived/audio/"+plan.asset()+"/orphan.wav"),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[11]));
            store.putObject(b->b.bucket("usage-tests").key("derived/frames/"+plan.asset()+"/unpublished/frame.jpg"),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[13]));
            store.putObject(b->b.bucket("usage-tests").key(previewClaim.path("input").path("artifactObjectKey").asText()),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[17]));
            store.putObject(b->b.bucket("usage-tests").key(exportClaim.path("input").path("artifactObjectKey").asText()),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[19]));
            var multipart=store.listMultipartUploads(b->b.bucket("usage-tests").prefix(originalKey)).uploads().get(0);
            store.uploadPart(b->b.bucket("usage-tests").key(originalKey).uploadId(multipart.uploadId()).partNumber(1),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[7]));
        }
        var usage=data(owner.get("/projects/"+project+"/usage"));
        assertEquals(15,usage.path("storage").path("ORIGINAL").path("storedBytes").asLong()); // 8 original + 7 incomplete part
        assertEquals(93,usage.path("storage").path("ORIGINAL").path("reservedBytes").asLong());
        assertEquals(24,usage.path("storage").path("DERIVED").path("storedBytes").asLong());
        assertTrue(usage.path("storage").path("DERIVED").path("reservedBytes").asLong()>0);
        assertEquals(17,usage.path("storage").path("PREVIEW").path("storedBytes").asLong());
        assertEquals(19,usage.path("storage").path("EXPORT").path("storedBytes").asLong());
        assertTrue(usage.path("storage").path("PREVIEW").path("reservedBytes").asLong()>0);
        assertTrue(usage.path("storage").path("EXPORT").path("reservedBytes").asLong()>0);
        assertEquals(200,internal(preview,"fail",Map.of("attemptId",previewClaim.path("attemptId").asText(),"leaseToken",previewClaim.path("leaseToken").asText(),"producerKey","usage-staged-failure","code","RENDER_FAILED","message","fixture interrupted after physical write")).statusCode());
        assertEquals(17,data(owner.get("/projects/"+project+"/usage")).path("storage").path("PREVIEW").path("pendingCleanupBytes").asLong());
        try(var store=storage()){store.deleteObject(b->b.bucket("usage-tests").key("originals/"+plan.asset()));}
        assertEquals(7,data(owner.get("/projects/"+project+"/usage")).path("storage").path("ORIGINAL").path("storedBytes").asLong());
    }
    @Test void providerUsageEstimatesUnknownCallsAndWarningsRemainAuditableWithoutCountingCachedBatches() throws Exception {
        var owner=signedIn("usage-model-owner");UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","模型回执"))).path("id").asText());Plan plan=planFixture(project);
        UUID analysis=UUID.randomUUID(),asrTask=UUID.randomUUID(),conversation=UUID.randomUUID(),visionTask=UUID.randomUUID(),indexTask=UUID.randomUUID(),generation=UUID.randomUUID(),attempt=UUID.randomUUID();
        for(UUID task:List.of(asrTask,conversation,visionTask,indexTask))fixtures.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'CONTENT_ANALYZE','SUCCEEDED','{}')",task,project);
        fixtures.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) VALUES (?,?,?,?,'usage-fixture')",analysis,project,plan.asset(),"a".repeat(64));
        fixtures.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status,usage_json,retry_number) VALUES (?,?,?,'qwen3-asr-flash-filetrans','{}','SUCCEEDED','{\"seconds\":100}',0)",UUID.randomUUID(),analysis,asrTask);
        fixtures.update("INSERT INTO model_calls(id,analysis_id,task_id,model,parameters_json,status,retry_number) VALUES (?,?,?,'qwen3-asr-flash-filetrans','{}','UNCERTAIN',1)",UUID.randomUUID(),analysis,asrTask);
        fixtures.update("INSERT INTO task_attempts(id,task_id,attempt_number,executor_id,claim_request_id,lease_token_hash,lease_expires_at) VALUES (?,?,1,'usage-fixture',?,'fixture',now()+interval '1 minute')",attempt,conversation,UUID.randomUUID());
        fixtures.update("INSERT INTO conversation_model_calls(id,task_id,attempt_id,call_number,request_json,status,response_json,usage_json) VALUES (?,?,?,1,'{\"model\":\"deepseek-flash\",\"messages\":[{\"content\":\"private content\"}]}','SUCCEEDED','{\"model\":\"deepseek-flash\"}','{\"prompt_tokens\":1000,\"completion_tokens\":500,\"prompt_cache_hit_tokens\":200,\"prompt_cache_miss_tokens\":800}')",UUID.randomUUID(),conversation,attempt);
        fixtures.update("INSERT INTO vision_model_calls(id,analysis_id,task_id,frame_sha256,model,parameters_json,status,usage_json) VALUES (?,?,?,?,'qwen3-vl-plus','{}','SUCCEEDED','{\"input_tokens\":100,\"output_tokens\":50}')",UUID.randomUUID(),analysis,visionTask,"c".repeat(64));
        fixtures.update("INSERT INTO search_index_generations(id,project_id,asset_id,analysis_id,task_id,model,dimension,profile_json,units_json,status) VALUES (?,?,?,?,?,'text-embedding-v4',1024,'{}','[]','PUBLISHED')",generation,project,plan.asset(),analysis,indexTask);
        fixtures.update("INSERT INTO embedding_batches(generation_id,ordinal,units_json,status,usage_json,owner_attempt_id) VALUES (?,0,'[]','SUCCEEDED','{\"total_tokens\":100}',?)",generation,attempt);
        fixtures.update("INSERT INTO embedding_batches(generation_id,ordinal,units_json,status,usage_json) VALUES (?,1,'[]','SUCCEEDED','{\"reused\":true}')",generation);
        owner.patch("/projects/"+project+"/usage/thresholds",Map.of("costCny",0.000001,"calls",1,"tokens",1,"audioSeconds",0.1));
        var usage=data(owner.get("/projects/"+project+"/usage"));var models=usage.path("models");
        assertEquals(5,models.path("callCount").asInt());assertEquals(1,models.path("reusedBatchCount").asInt());
        assertEquals(1,models.path("uncertainCallCount").asInt());
        assertEquals(new java.math.BigDecimal("0.032658"),models.path("estimatedCostCny").decimalValue().stripTrailingZeros());
        assertEquals(4,usage.path("warnings").size());
        assertEquals(5,models.path("items").size());
        for(var call:models.path("items")) {
            assertFalse(call.path("provider").asText().isBlank());assertFalse(call.path("purpose").asText().isBlank());
            assertFalse(call.path("pricingRef").asText().isBlank());assertFalse(call.has("request_json"));assertFalse(call.has("response_json"));
            if("UNCERTAIN".equals(call.path("status").asText())){assertEquals("ESTIMATED",call.path("usageBasis").asText());assertEquals(20,call.path("usage").path("audioSeconds").asInt());}
        }
        assertEquals(models,data(owner.get("/projects/"+project+"/usage")).path("models"));
    }
    @Test void visualPreparationReservationsStayVisibleUntilBothTasksStop() throws Exception {
        var owner=signedIn("usage-visual-budget-owner");UUID project=UUID.fromString(data(owner.post("/projects",Map.of("name","派生处理预留"))).path("id").asText());Plan plan=planFixture(project);
        UUID analysis=UUID.randomUUID();
        fixtures.update("INSERT INTO asset_analysis_revisions(id,project_id,asset_id,source_sha256,profile_hash) SELECT ?,project_id,id,sha256,'visual-budget-fixture' FROM assets WHERE id=?",analysis,plan.asset());
        fixtures.update("INSERT INTO asset_analysis_heads(asset_id,analysis_id) VALUES (?,?)",plan.asset(),analysis);
        fixtures.update("INSERT INTO analysis_stages(asset_id,analysis_id,stage_key,status) VALUES (?,?,'VISION','NOT_STARTED')",plan.asset(),analysis);
        String vision=data(owner.post("/assets/"+plan.asset()+"/vision-analysis",Map.of())).path("taskId").asText();
        String inspection=data(owner.post("/projects/"+project+"/visual-inspections",Map.of("assetId",plan.asset(),"query","画面中的说明文字","sourceKind","VISIBLE_TEXT"))).path("taskId").asText();
        long budget=2L*(33554432+8);
        assertEquals(budget,data(owner.get("/projects/"+project+"/usage")).path("storage").path("DERIVED").path("reservedBytes").asLong());
        var claim=data(internal(inspection,"claim",Map.of("executorId","usage-inspection","claimRequestId",UUID.randomUUID())));
        assertEquals(budget,data(owner.get("/projects/"+project+"/usage")).path("storage").path("DERIVED").path("reservedBytes").asLong());
        assertEquals(202,owner.post("/tasks/"+inspection+"/cancel",Map.of()).statusCode());
        assertEquals("CANCEL_REQUESTED",data(owner.get("/tasks/"+inspection)).path("status").asText());
        assertEquals(budget,data(owner.get("/projects/"+project+"/usage")).path("storage").path("DERIVED").path("reservedBytes").asLong());
        assertEquals(200,internal(inspection,"cancelled",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"processExited",true)).statusCode());
        assertEquals(budget/2,data(owner.get("/projects/"+project+"/usage")).path("storage").path("DERIVED").path("reservedBytes").asLong());
        owner.post("/tasks/"+vision+"/cancel",Map.of());
        assertEquals(0,data(owner.get("/projects/"+project+"/usage")).path("storage").path("DERIVED").path("reservedBytes").asLong());
    }
    private HttpResponse<String> internal(String task,String action,Object body) throws Exception {return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/tasks/"+task+"/"+action)).header("Authorization","Bearer usage-integration-worker-token").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    private software.amazon.awssdk.services.s3.S3Client storage(){return software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create("http://"+SILO.getHost()+":"+SILO.getMappedPort(9000))).region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true).credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("usage-integration","usage-integration-password"))).build();}
    private record Plan(UUID first,UUID second,UUID output,UUID asset) {}
    private Plan planFixture(UUID project) throws Exception {
        UUID asset=UUID.randomUUID(),upload=UUID.randomUUID(),plan=UUID.randomUUID(),first=UUID.randomUUID(),second=UUID.randomUUID(),output=UUID.randomUUID();
        byte[] bytes="original".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),objectKey="originals/"+asset;
        try(var store=software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create("http://"+SILO.getHost()+":"+SILO.getMappedPort(9000)))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true).credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("usage-integration","usage-integration-password"))).build()) {
            try{store.createBucket(b->b.bucket("usage-tests"));}catch(software.amazon.awssdk.services.s3.model.S3Exception exists){if(exists.statusCode()!=409)throw exists;}
            store.putObject(b->b.bucket("usage-tests").key(objectKey),software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
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
    private BrowserApi signedIn(String username) throws Exception {BrowserApi browser=new BrowserApi();var body=Map.of("username",username,"password","usage-browser-password");assertEquals(201,browser.post("/auth/register",body).statusCode());assertEquals(200,browser.post("/auth/login",body).statusCode());return browser;}
    private final class BrowserApi {
        
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> patch(String path,Object body) throws Exception {String csrf=data(get("/auth/csrf")).path("csrfToken").asText();return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).method("PATCH",HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> delete(String path,Object body) throws Exception {String csrf=data(get("/auth/csrf")).path("csrfToken").asText();return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).header("Idempotency-Key",UUID.randomUUID().toString()).method("DELETE",HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> get(String path) throws Exception {return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> post(String path,Object body) throws Exception {return post(path,body,UUID.randomUUID().toString());}
        HttpResponse<String> post(String path,Object body,String key) throws Exception {String csrf=data(get("/auth/csrf")).path("csrfToken").asText();var reply=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());return reply;}
    }
}

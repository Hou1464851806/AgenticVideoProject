package com.agenticvideo.business.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@org.springframework.test.context.TestPropertySource(properties={"legacy.worker-enabled=true","legacy.agent-enabled=true","analysis.execution-enabled=true","legacy.inspection-enabled=true"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.auth.max-attempts=100")
class PreviewApiIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @Container static final org.testcontainers.containers.GenericContainer<?> SILO=new org.testcontainers.containers.GenericContainer<>(
            "pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46")
            .withEnv("MINIO_ROOT_USER","preview-integration").withEnv("MINIO_ROOT_PASSWORD","preview-integration-password")
            .withCommand("server","/data").withExposedPorts(9000)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("internal.worker-token", () -> "preview-integration-worker-token");
        properties.add("storage.endpoint",() -> "http://"+SILO.getHost()+":"+SILO.getMappedPort(9000));
        properties.add("storage.public-endpoint",() -> "http://"+SILO.getHost()+":"+SILO.getMappedPort(9000));
        properties.add("storage.access-key",() -> "preview-integration");properties.add("storage.secret-key",() -> "preview-integration-password");
        properties.add("storage.bucket",() -> "preview-tests");
    }
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate fixtures;
    private final ObjectMapper json = new ObjectMapper();

    @Test void onlyAnExplicitOwnedVersionRequestStartsOneReusableFixedPreviewTask() throws Exception {
        BrowserApi owner=signedIn("preview-owner"), outsider=signedIn("preview-outsider");
        String project=data(owner.post("/projects",Map.of("name","手动低清预览"))).path("id").asText();
        Plan plan=planFixture(project);
        assertEquals(0,data(owner.get("/revisions/"+plan.revision()+"/previews")).path("items").size());
        var body=Map.of("outputId",plan.output(),"previewProfile","LOW_360");
        var accepted=owner.post("/revisions/"+plan.revision()+"/previews",body);
        assertEquals(202,accepted.statusCode(),accepted.body());
        JsonNode first=data(accepted);String request=first.path("requestId").asText(),task=first.path("taskId").asText();
        org.junit.jupiter.api.Assertions.assertFalse(request.isBlank());org.junit.jupiter.api.Assertions.assertFalse(task.isBlank());
        JsonNode duplicate=data(owner.post("/revisions/"+plan.revision()+"/previews",body));
        assertEquals(request,duplicate.path("requestId").asText());assertEquals(task,duplicate.path("taskId").asText());
        JsonNode status=data(owner.get("/render-requests/"+request));
        assertEquals(plan.revision().toString(),status.path("revisionId").asText());
        assertEquals(plan.output().toString(),status.path("outputId").asText());assertEquals("PENDING",status.path("status").asText());
        assertEquals("PREVIEW_RENDER",data(owner.get("/tasks/"+task)).path("type").asText());
        assertEquals(1,data(owner.get("/revisions/"+plan.revision()+"/previews")).path("items").size());
        assertEquals(404,outsider.post("/revisions/"+plan.revision()+"/previews",body).statusCode());
        assertEquals(404,outsider.get("/render-requests/"+request).statusCode());
        assertEquals(404,owner.post("/revisions/"+plan.revision()+"/previews",Map.of("outputId",UUID.randomUUID(),"previewProfile","LOW_360")).statusCode());
        assertEquals(400,owner.post("/revisions/"+plan.revision()+"/previews",Map.of("outputId",plan.output(),"previewProfile","arbitrary-codec")).statusCode());
    }

    @Test void aCurrentAttemptPublishesOneUploadedArtifactAndRepeatsReuseThatExactResult() throws Exception {
        BrowserApi owner=signedIn("artifact-owner"),outsider=signedIn("artifact-outsider");
        String project=data(owner.post("/projects",Map.of("name","预览结果回执"))).path("id").asText();Plan plan=planFixture(project);
        JsonNode request=data(owner.post("/revisions/"+plan.revision()+"/previews",Map.of("outputId",plan.output(),"previewProfile","LOW_360")));
        String task=request.path("taskId").asText();
        var claimBody=Map.of("executorId","preview-result-test","claimRequestId",UUID.randomUUID());
        var claimed=internal(task,"claim",claimBody);assertEquals(200,claimed.statusCode(),claimed.body());JsonNode claim=data(claimed);
        assertEquals(claim,data(internal(task,"claim",claimBody)));
        assertEquals(plan.revision().toString(),claim.path("input").path("revisionId").asText());
        assertEquals(plan.output().toString(),claim.path("input").path("output").path("outputId").asText());
        assertEquals(640,claim.path("input").path("configuration").path("width").asInt());
        String artifact=claim.path("input").path("artifactId").asText(),key=claim.path("input").path("artifactObjectKey").asText();
        org.junit.jupiter.api.Assertions.assertFalse(artifact.isBlank());
        byte[] bytes="verified-worker-preview".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var storage=storage()) {
            try{storage.createBucket(builder -> builder.bucket("preview-tests"));}
            catch(software.amazon.awssdk.services.s3.model.S3Exception exists){if(exists.statusCode()!=409)throw exists;}
            storage.putObject(builder -> builder.bucket("preview-tests").key(key).contentType("video/mp4"),software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
        }
        var manifest=Map.of("artifactId",artifact,"objectKey",key,"byteSize",bytes.length,"sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),
                "durationMs",2000,"mediaInfo",Map.of("streams",List.of(Map.of("codec_type","video","codec_name","h264","width",640,"height",360),Map.of("codec_type","audio","codec_name","aac","channels",2,"sample_rate","48000"))));
        var completion=Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","preview-rendered","resultType","PREVIEW_RENDERED","result",manifest);
        for(var replacement:List.of(Map.<String,Object>of("artifactId",UUID.randomUUID()),Map.<String,Object>of("objectKey","previews/foreign.mp4"),Map.<String,Object>of("durationMs",5000),
                Map.<String,Object>of("mediaInfo",Map.of("streams",List.of(Map.of("codec_type","video","codec_name","h264","width",640,"height",360)))))) {
            var wrongManifest=new java.util.LinkedHashMap<String,Object>(manifest);wrongManifest.putAll(replacement);
            var wrongReport=new java.util.LinkedHashMap<String,Object>(completion);wrongReport.put("result",wrongManifest);
            assertEquals(409,internal(task,"complete",wrongReport).statusCode());
        }
        try(var store=storage()){store.deleteObject(b->b.bucket("preview-tests").key(key));}
        assertEquals(409,internal(task,"complete",completion).statusCode());
        try(var store=storage()){store.putObject(b->b.bucket("preview-tests").key(key).contentType("video/mp4"),software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));}
        var completed=internal(task,"complete",completion);assertEquals(200,completed.statusCode(),completed.body());
        assertEquals(data(completed),data(internal(task,"complete",completion)));
        assertEquals(artifact,data(completed).path("artifactId").asText());
        assertEquals("SUCCEEDED",data(owner.get("/render-requests/"+request.path("requestId").asText())).path("status").asText());
        JsonNode reused=data(owner.post("/revisions/"+plan.revision()+"/previews",Map.of("outputId",plan.output(),"previewProfile","LOW_360")));
        assertEquals(task,reused.path("taskId").asText());assertEquals(artifact,reused.path("artifactId").asText());
        assertEquals(404,outsider.post("/artifacts/"+artifact+"/playback-access",Map.of()).statusCode());
        assertEquals(404,outsider.post("/artifacts/"+artifact+"/download-access",Map.of()).statusCode());
        var access=owner.post("/artifacts/"+artifact+"/download-access",Map.of());assertEquals(200,access.statusCode(),access.body());
        var downloaded=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(data(access).path("url").asText())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,downloaded.statusCode());org.junit.jupiter.api.Assertions.assertArrayEquals(bytes,downloaded.body());
        var playback=owner.post("/artifacts/"+artifact+"/playback-access",Map.of());assertEquals(200,playback.statusCode(),playback.body());
        var download=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(data(playback).path("url").asText())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,download.statusCode());org.junit.jupiter.api.Assertions.assertArrayEquals(bytes,download.body());
        try(var storage=storage()){storage.deleteObject(builder -> builder.bucket("preview-tests").key(key));}
        assertEquals(410,owner.post("/artifacts/"+artifact+"/playback-access",Map.of()).statusCode());
        assertEquals(410,owner.post("/artifacts/"+artifact+"/download-access",Map.of()).statusCode());
        JsonNode regenerated=data(owner.post("/revisions/"+plan.revision()+"/previews",Map.of("outputId",plan.output(),"previewProfile","LOW_360")));
        org.junit.jupiter.api.Assertions.assertNotEquals(task,regenerated.path("taskId").asText());
        assertEquals("PENDING",regenerated.path("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(regenerated.path("artifactId").isMissingNode());
        assertEquals("SUCCEEDED",data(owner.get("/tasks/"+task)).path("status").asText());
    }

    private software.amazon.awssdk.services.s3.S3Client storage() {
        return software.amazon.awssdk.services.s3.S3Client.builder().endpointOverride(URI.create("http://"+SILO.getHost()+":"+SILO.getMappedPort(9000)))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("preview-integration","preview-integration-password"))).build();
    }

    @Test void aFailedPreviewPreservesTheVersionAndAnExplicitRetryGetsANewTask() throws Exception {
        BrowserApi owner=signedIn("preview-failure-owner");
        String project=data(owner.post("/projects",Map.of("name","空间不足后显式重试"))).path("id").asText();Plan plan=planFixture(project);
        var body=Map.of("outputId",plan.output(),"previewProfile","LOW_360");JsonNode requested=data(owner.post("/revisions/"+plan.revision()+"/previews",body));
        String task=requested.path("taskId").asText();JsonNode claim=data(internal(task,"claim",Map.of("executorId","preview-space-test","claimRequestId",UUID.randomUUID())));
        var failure=Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","preview-failed","code","PREVIEW_SPACE_LOW","message","当前执行空间不足，未开始合成");
        var failed=internal(task,"fail",failure);assertEquals(200,failed.statusCode(),failed.body());
        JsonNode status=data(owner.get("/render-requests/"+requested.path("requestId").asText()));
        assertEquals("FAILED",status.path("status").asText());assertEquals("PREVIEW_SPACE_LOW",status.path("error").path("code").asText());
        JsonNode retry=data(owner.post("/revisions/"+plan.revision()+"/previews",body));
        org.junit.jupiter.api.Assertions.assertNotEquals(task,retry.path("taskId").asText());assertEquals("PENDING",retry.path("status").asText());
        assertEquals(plan.revision().toString(),retry.path("revisionId").asText());
        assertEquals(1,data(owner.get("/plans/"+data(owner.get("/revisions/"+plan.revision())).path("planId").asText()+"/revisions")).path("items").size());
    }
    @Test void cancelledUploadedPreviewIsNeverPublishedAndItsPrivateObjectIsRemovedAfterExit() throws Exception {
        BrowserApi owner=signedIn("preview-cancel-owner");
        String project=data(owner.post("/projects",Map.of("name","取消预览清理"))).path("id").asText();Plan plan=planFixture(project);
        JsonNode requested=data(owner.post("/revisions/"+plan.revision()+"/previews",Map.of("outputId",plan.output(),"previewProfile","LOW_360")));
        String task=requested.path("taskId").asText();JsonNode claim=data(internal(task,"claim",Map.of("executorId","preview-cancel-test","claimRequestId",UUID.randomUUID())));
        String artifact=claim.path("input").path("artifactId").asText(),key=claim.path("input").path("artifactObjectKey").asText();
        try(var store=storage()) {
            try{store.createBucket(b->b.bucket("preview-tests"));}catch(software.amazon.awssdk.services.s3.model.S3Exception exists){if(exists.statusCode()!=409)throw exists;}
            store.putObject(b->b.bucket("preview-tests").key(key),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[]{1,2,3}));
            assertEquals("CANCEL_REQUESTED",data(owner.post("/tasks/"+task+"/cancel",Map.of())).path("status").asText());
            assertEquals(410,owner.post("/artifacts/"+artifact+"/playback-access",Map.of()).statusCode());
        assertEquals(410,owner.post("/artifacts/"+artifact+"/download-access",Map.of()).statusCode());
            var stopped=Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"processExited",false);
            assertEquals(409,internal(task,"cancelled",stopped).statusCode());
            assertEquals(3,store.headObject(b->b.bucket("preview-tests").key(key)).contentLength());
            assertEquals(200,internal(task,"cancelled",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"processExited",true)).statusCode());
            assertEquals("CANCELLED",data(owner.get("/render-requests/"+requested.path("requestId").asText())).path("status").asText());
            org.junit.jupiter.api.Assertions.assertThrows(software.amazon.awssdk.services.s3.model.S3Exception.class,()->store.headObject(b->b.bucket("preview-tests").key(key)));
        }
    }

    @Test void replayingAnIntentAfterFailureNeverStartsAnotherTaskAndChangedInputConflicts() throws Exception {
        BrowserApi owner=signedIn("preview-intent-owner");String project=data(owner.post("/projects",Map.of("name","预览意图重放"))).path("id").asText();Plan plan=planFixture(project);
        var body=Map.of("outputId",plan.output(),"previewProfile","LOW_360");String key=UUID.randomUUID().toString();
        JsonNode first=data(owner.post("/revisions/"+plan.revision()+"/previews",body,key));String task=first.path("taskId").asText();
        assertEquals(first,data(owner.post("/revisions/"+plan.revision()+"/previews",Map.of("outputId",plan.output().toString().toUpperCase(java.util.Locale.ROOT),"previewProfile","LOW_360"),key)));
        JsonNode claim=data(internal(task,"claim",Map.of("executorId","preview-intent-test","claimRequestId",UUID.randomUUID())));
        assertEquals(200,internal(task,"fail",Map.of("attemptId",claim.path("attemptId").asText(),"leaseToken",claim.path("leaseToken").asText(),"producerKey","preview-failed","code","PREVIEW_SPACE_LOW","message","空间不足")).statusCode());
        assertEquals(first,data(owner.post("/revisions/"+plan.revision()+"/previews",body,key)));
        assertEquals(409,owner.post("/revisions/"+plan.revision()+"/previews",Map.of("outputId",UUID.randomUUID(),"previewProfile","LOW_360"),key).statusCode());
        JsonNode retry=data(owner.post("/revisions/"+plan.revision()+"/previews",body));
        org.junit.jupiter.api.Assertions.assertNotEquals(task,retry.path("taskId").asText());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.path("requestId").asText(),retry.path("requestId").asText());
        assertEquals("FAILED",data(owner.get("/render-requests/"+first.path("requestId").asText())).path("status").asText());
    }

    private HttpResponse<String> internal(String task,String action,Object body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/tasks/"+task+"/"+action))
                .header("Authorization","Bearer preview-integration-worker-token").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }

    private record Plan(UUID revision,UUID output,UUID asset) {}
    private Plan planFixture(String project) throws Exception {
        UUID projectId=UUID.fromString(project),asset=UUID.randomUUID(),upload=UUID.randomUUID(),plan=UUID.randomUUID(),revision=UUID.randomUUID(),task=UUID.randomUUID(),output=UUID.randomUUID();
        // Seed a previously committed version; actual FFmpeg composition is separately checked through the real browser/worker flow.
        fixtures.update("INSERT INTO upload_sessions(id,project_id,actor_user_id,idempotency_key,filename,declared_bytes,declared_sha256,part_size_bytes,expected_parts,object_key,writer_token) SELECT ?,id,owner_user_id,?,'source.mp4',1,?,1,1,?,? FROM projects WHERE id=?",upload,upload.toString(),"b".repeat(64),upload.toString(),UUID.randomUUID(),projectId);
        fixtures.update("INSERT INTO assets(id,project_id,upload_id,object_key,filename,byte_size,sha256,validation_status,duration_ms,media_info_json,time_mapping_json) VALUES (?,?,?,?,'source.mp4',1,?,'VALID',20000,'{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\"},{\"codec_type\":\"audio\",\"codec_name\":\"aac\"}]}','{\"hasAudio\":true}')",asset,projectId,upload,asset.toString(),"b".repeat(64));
        fixtures.update("INSERT INTO tasks(id,project_id,type,status,input_json) VALUES (?,?,'AGENT_TURN','SUCCEEDED','{}')",task,projectId);
        fixtures.update("INSERT INTO edit_plans(id,project_id,title,next_revision_number) VALUES (?,?,'已完成方案',2)",plan,projectId);
        var clip=Map.of("clipId",UUID.randomUUID(),"assetId",asset,"sourceInMs",2000,"sourceOutMs",4000,"audio","original","evidenceIds",List.of(),"reason","已有完整片段");
        var definition=Map.of("outputId",output,"title","中文字幕与原声","durationMs",2000,"clips",List.of(clip),"render",Map.of("profile","LANDSCAPE_720","width",1280,"height",720,"fit","contain","framesPerSecond",30,"container","mp4","videoCodec","h264","audioCodec","aac","configVersion","speech-cut-v1"),"subtitles",Map.of("enabled",true,"cues",List.of(Map.of("startMs",0,"endMs",1800,"text","中文字幕，核查原声。"))));
        fixtures.update("INSERT INTO plan_revisions(id,project_id,plan_id,revision_number,source_task_id,content_json) VALUES (?,?,?,1,?,?::jsonb)",revision,projectId,plan,task,json.writeValueAsString(Map.of("schemaVersion","1.0","outputs",List.of(definition))));
        fixtures.update("INSERT INTO revision_asset_refs(revision_id,asset_id) VALUES (?,?)",revision,asset);
        return new Plan(revision,output,asset);
    }
    private BrowserApi signedIn(String username) throws Exception {
        BrowserApi browser=new BrowserApi();var credentials=Map.of("username",username,"password","preview-browser-password");
        assertEquals(201,browser.post("/auth/register",credentials).statusCode());assertEquals(200,browser.post("/auth/login",credentials).statusCode());return browser;
    }
    private JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }
    private final class BrowserApi {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path,Object body) throws Exception {return post(path,body,UUID.randomUUID().toString());}
        HttpResponse<String> post(String path,Object body,String key) throws Exception {
            String csrf=data(get("/auth/csrf")).path("csrfToken").asText();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf).header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}

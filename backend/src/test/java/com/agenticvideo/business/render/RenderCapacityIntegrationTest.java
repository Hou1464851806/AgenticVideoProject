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
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestPropertySource(properties={"legacy.worker-enabled=true","legacy.agent-enabled=true","analysis.execution-enabled=true","legacy.inspection-enabled=true"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"app.auth.max-attempts=100", "capacity.max-reserved-bytes=157286400"})
class RenderCapacityIntegrationTest {
    private static final java.nio.file.Path VOLUME = createVolume();
    private static java.nio.file.Path createVolume() {
        try { return java.nio.file.Files.createTempDirectory("render-capacity-volume-"); }
        catch(java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
    }
    @org.junit.jupiter.api.AfterAll static void removeVolume() throws Exception { java.nio.file.Files.deleteIfExists(VOLUME); }
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @Container static final org.testcontainers.containers.GenericContainer<?> SILO=new org.testcontainers.containers.GenericContainer<>(
            "pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46")
            .withEnv("MINIO_ROOT_USER","preview-integration").withEnv("MINIO_ROOT_PASSWORD","preview-integration-password")
            .withCommand("server","/data").withExposedPorts(9000)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("capacity.paths", VOLUME::toString);
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

    @Test void pendingRendersReserveSharedSpaceAndCancellingAnUnstartedRenderReleasesItsBudget() throws Exception {
        var owner = signedIn("render-capacity-owner");
        String firstProject = data(owner.post("/projects", Map.of("name", "预览容量一"))).path("id").asText();
        String secondProject = data(owner.post("/projects", Map.of("name", "预览容量二"))).path("id").asText();
        Plan firstPlan = planFixture(firstProject), secondPlan = planFixture(secondProject);
        var first = owner.post("/revisions/" + firstPlan.revision() + "/previews", Map.of("outputId", firstPlan.output(), "previewProfile", "LOW_360"));
        assertEquals(202, first.statusCode(), first.body());
        var secondBody = Map.of("outputId", secondPlan.output(), "previewProfile", "LOW_360");
        var rejected = owner.post("/revisions/" + secondPlan.revision() + "/previews", secondBody);
        assertEquals(409, rejected.statusCode(), rejected.body());
        org.junit.jupiter.api.Assertions.assertTrue(json.readTree(rejected.body()).path("error").path("message").asText().contains("空间"), rejected.body());
        assertEquals(0, data(owner.get("/revisions/" + secondPlan.revision() + "/previews")).path("items").size());
        assertEquals(202, owner.post("/tasks/" + data(first).path("taskId").asText() + "/cancel", Map.of()).statusCode());
        var accepted = owner.post("/revisions/" + secondPlan.revision() + "/previews", secondBody);
        assertEquals(202, accepted.statusCode(), accepted.body());
        assertEquals(202, owner.post("/tasks/" + data(accepted).path("taskId").asText() + "/cancel", Map.of()).statusCode());
    }

    @Test void onlyTheLiveAttemptCanRecheckMeasuredCapacityBeforeExecution() throws Exception {
        var owner = signedIn("render-space-attempt-owner");
        String project = data(owner.post("/projects", Map.of("name", "执行前空间复查"))).path("id").asText();
        Plan plan = planFixture(project);
        var request = owner.post("/revisions/" + plan.revision() + "/previews", Map.of("outputId", plan.output(), "previewProfile", "LOW_360"));
        assertEquals(202, request.statusCode(), request.body());
        String task = data(request).path("taskId").asText();
        var claim = data(internal(task, "claim", Map.of("executorId", "space-check-worker", "claimRequestId", UUID.randomUUID())));
        var credential = Map.of("attemptId", claim.path("attemptId").asText(), "leaseToken", claim.path("leaseToken").asText());
        var checked = internal(task, "space-check", credential);
        assertEquals(200, checked.statusCode(), checked.body());
        org.junit.jupiter.api.Assertions.assertTrue(data(checked).path("requiredBytes").asLong() > 100000000, checked.body());
        assertEquals(409, internal(task, "space-check", Map.of("attemptId", claim.path("attemptId").asText(), "leaseToken", "stale-token")).statusCode());
        java.nio.file.Files.delete(VOLUME);
        try {
            var rejected = internal(task, "space-check", credential);
            assertEquals(409, rejected.statusCode(), rejected.body());
            assertEquals("STORAGE_SPACE_LOW",json.readTree(rejected.body()).path("error").path("code").asText());
            org.junit.jupiter.api.Assertions.assertTrue(json.readTree(rejected.body()).path("error").path("message").asText().contains("存储卷"), rejected.body());
        } finally { java.nio.file.Files.createDirectories(VOLUME); }
        assertEquals(202, owner.post("/tasks/" + task + "/cancel", Map.of()).statusCode());
        assertEquals(409, internal(task, "space-check", credential).statusCode());
        assertEquals(200, internal(task, "cancelled", Map.of("attemptId", claim.path("attemptId").asText(), "leaseToken", claim.path("leaseToken").asText(), "processExited", true)).statusCode());
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

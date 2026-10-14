package com.agenticvideo.business.task;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AnalysisIntegrationSupport {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Container
    static final org.testcontainers.containers.GenericContainer<?> SILO = new org.testcontainers.containers.GenericContainer<>(
            "pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46")
            .withEnv("MINIO_ROOT_USER", "integration-user")
            .withEnv("MINIO_ROOT_PASSWORD", "integration-password-strong")
            .withEnv("MINIO_API_MULTIPART_LISTING", "strict")
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/minio/health/ready").forPort(9000));
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("storage.endpoint", () -> "http://localhost:" + SILO.getMappedPort(9000));
        properties.add("storage.public-endpoint", () -> "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
        properties.add("internal.worker-token", () -> "internal-integration-worker-token");
        properties.add("storage.access-key", () -> "integration-user");
        properties.add("storage.secret-key", () -> "integration-password-strong");
    }
    @Container
    static final org.testcontainers.containers.GenericContainer<?> BROKER = new org.testcontainers.containers.GenericContainer<>(
            "rabbitmq:4.3.6-management@sha256:8dd6e3570ddaa2ef82a6c3a8950e79c1f73893adfbdddbc374fc1f1ac8a0f5dd")
            .withEnv("RABBITMQ_DEFAULT_USER", "integration-worker")
            .withEnv("RABBITMQ_DEFAULT_PASS", "integration-broker-password")
            .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "+S 2:2")
            .withExposedPorts(5672).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*", 1));
    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry properties) {
        properties.add("spring.rabbitmq.host", BROKER::getHost);
        properties.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        properties.add("spring.rabbitmq.username", () -> "integration-worker");
        properties.add("spring.rabbitmq.password", () -> "integration-broker-password");
        properties.add("tasks.dispatch-enabled", () -> true);
        properties.add("tasks.consume-enabled", () -> true);
        properties.add("media.work-directory", () -> System.getProperty("java.io.tmpdir")+"/agentic-video-test-"+java.util.UUID.randomUUID());
        properties.add("tasks.pending-renotify-seconds", () -> 2);
    }
    @org.springframework.beans.factory.annotation.Autowired org.springframework.amqp.rabbit.core.RabbitTemplate broker;
    @LocalServerPort int port;

    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @org.springframework.beans.factory.annotation.Autowired TaskExecution tasks;
    @org.springframework.beans.factory.annotation.Autowired LocalTaskRunner runtime;
    @org.springframework.beans.factory.annotation.Autowired com.agenticvideo.business.analysis.SpeechAnalysis speech;
    @org.springframework.beans.factory.annotation.Autowired com.agenticvideo.business.search.SearchIndexes indexes;
    @org.springframework.beans.factory.annotation.Autowired com.agenticvideo.business.search.SearchVectors vectors;
    @Container static final org.testcontainers.containers.GenericContainer<?> QDRANT = new org.testcontainers.containers.GenericContainer<>("qdrant/qdrant:v1.19.2@sha256:b7b0444c4c351c970b98e90a6f89c2ee4287c65b44e52b4cb503fa5b2aa927ad")
        .withExposedPorts(6333).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/readyz").forPort(6333));
    @DynamicPropertySource static void analysis(DynamicPropertyRegistry properties) {
        properties.add("analysis.execution-enabled",()->true);
        properties.add("analysis.asr-query-seconds",()->1);
        properties.add("QDRANT_URL",()->"http://localhost:"+QDRANT.getMappedPort(6333));
    }
    record Uploaded(BrowserApi browser,java.util.UUID project,java.util.UUID asset,java.util.UUID mediaTask) {}
    Uploaded upload(String filename) throws Exception {
        BrowserApi browser=signedIn("analysis-"+java.util.UUID.randomUUID());
        var project=java.util.UUID.fromString(json(browser.post("/projects","{\"name\":\"Java 分析验收\"}")).path("data").path("id").asText());
        byte[] bytes=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../frontend/e2e/fixtures/"+filename));
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var upload=json(browser.post("/projects/"+project+"/uploads",new ObjectMapper().writeValueAsString(java.util.Map.of("filename",filename,"sizeBytes",bytes.length,"sha256",hash,"idempotencyKey",java.util.UUID.randomUUID(),"writerId",browser.writerId)))).path("data");
        String id=upload.path("uploadId").asText(),writer=upload.path("writerToken").asText();
        var urls=json(browser.post("/uploads/"+id+"/part-urls","{\"partNumbers\":[1],\"writerToken\":\""+writer+"\"}")).path("data");
        assertEquals(200,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(urls.path("parts").get(0).path("url").asText())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var completed=json(browser.post("/uploads/"+id+"/complete","{\"writerToken\":\""+writer+"\"}")).path("data");
        return new Uploaded(browser,project,java.util.UUID.fromString(completed.path("assetId").asText()),java.util.UUID.fromString(completed.path("validationTaskId").asText()));
    }
    void awaitAnalysis(Uploaded uploaded) throws Exception {
        awaitState(uploaded.mediaTask(),"SUCCEEDED");
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(240).toNanos();
        while(System.nanoTime()<deadline) {
            var stages=json(uploaded.browser().get("/assets/"+uploaded.asset())).path("data").path("analysis").path("stages");
            if("SUCCEEDED".equals(stages.path("VISION").asText()) && java.util.Set.of("SUCCEEDED","NOT_APPLICABLE").contains(stages.path("ASR").asText())) {
                var status=new ObjectMapper().valueToTree(indexes.status(uploaded.asset()));
                if(status.path("publishedGeneration").isObject() && db.queryForObject("SELECT count(*) FROM tasks WHERE input_json->>'assetId'=? AND status IN ('PENDING','RUNNING')",Integer.class,uploaded.asset().toString())==0)return;
            }
            if(stages.toString().contains("FAILED"))org.junit.jupiter.api.Assertions.fail(stages+" "+db.queryForList("SELECT type,result_json FROM tasks WHERE input_json->>'assetId'=?",uploaded.asset().toString()));
            Thread.sleep(200);
        }
        org.junit.jupiter.api.Assertions.fail("Analysis timeout: "+new ObjectMapper().valueToTree(speech.stages(uploaded.asset())));
    }


    void enqueue(java.util.UUID task,String type) {
        java.util.UUID event=java.util.UUID.randomUUID();
        db.update("INSERT INTO outbox_events(id,task_id,type,payload_json) VALUES (?,?,?,jsonb_build_object('schemaVersion','1.0','eventId',?::text,'taskId',?::text,'taskType',?::text))",event,task,type,event,task,type);
    }
    void awaitState(java.util.UUID task,String state) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(45).toNanos();
        while(System.nanoTime()<deadline) {
            String current=db.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,task);
            if(state.equals(current))return;
            if("FAILED".equals(current))org.junit.jupiter.api.Assertions.fail(db.queryForObject("SELECT result_json::text FROM tasks WHERE id=?",String.class,task));
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.fail("Task did not reach "+state);
    }
    BrowserApi signedIn(String username) throws Exception {
        BrowserApi browser = new BrowserApi();
        String credentials = new ObjectMapper().writeValueAsString(java.util.Map.of("username", username, "password", "project-strong-password"));
        assertEquals(201, browser.post("/auth/register", credentials).statusCode());
        assertEquals(200, browser.post("/auth/login", credentials).statusCode());
        return browser;
    }

    JsonNode json(HttpResponse<String> response) throws Exception {
        return new ObjectMapper().readTree(response.body());
    }

    final class BrowserApi {
        final java.util.UUID writerId = java.util.UUID.randomUUID();
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body) throws Exception {
            String token = json(get("/auth/csrf")).path("data").path("csrfToken").asText();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token).header("Idempotency-Key",java.util.UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}

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
class TaskApiIntegrationTest {
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
        properties.add("tasks.pending-renotify-seconds", () -> 2);
    }
    @org.springframework.beans.factory.annotation.Autowired org.springframework.amqp.rabbit.core.RabbitTemplate broker;
    @LocalServerPort int port;

    @Test
    void taskClaimIsAuthenticatedAtomicAndIdempotentForOneExecutorStart() throws Exception {
        BrowserApi browser = signedIn("task-owner");
        String projectId = json(browser.post("/projects", "{\"name\":\"任务执行\"}")).path("data").path("id").asText();
        byte[] bytes = "task-claim-file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var upload = json(browser.post("/projects/" + projectId + "/uploads", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename", "claim.mp4", "sizeBytes", bytes.length, "sha256", hash, "idempotencyKey", "claim-upload")))).path("data");
        String id = upload.path("uploadId").asText();
        String writer = upload.path("writerToken").asText();
        var urls = json(browser.post("/uploads/" + id + "/part-urls", "{\"partNumbers\":[1],\"writerToken\":\"" + writer + "\"}")).path("data");
        var put = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(urls.path("parts").get(0).path("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, put.statusCode());
        var completed = json(browser.post("/uploads/" + id + "/complete", "{\"writerToken\":\"" + writer + "\"}")).path("data");
        String taskId = completed.path("validationTaskId").asText();
        org.springframework.amqp.core.Message notification = null;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && notification == null) {
            notification = broker.receive("media.v1", 500);
            if(notification!=null && !new ObjectMapper().readTree(notification.getBody()).path("taskId").asText().equals(taskId)) notification=null;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(notification, "The persisted outbox must reach real RabbitMQ");
        var event = new ObjectMapper().readTree(notification.getBody());
        assertEquals(taskId, event.path("taskId").asText());
        assertEquals("MEDIA_PREPARE", event.path("taskType").asText());
        assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT, notification.getMessageProperties().getReceivedDeliveryMode());

        // The consumer lost the notification before claiming. RabbitMQ has acknowledged it,
        // so the persisted PENDING task must produce another notification without a new attempt.
        org.springframework.amqp.core.Message recoveredNotification = null;
        deadline = System.nanoTime() + java.time.Duration.ofSeconds(7).toNanos();
        while (System.nanoTime() < deadline && recoveredNotification == null) {
            var candidate = broker.receive("media.v1", 500);
            if(candidate != null && new ObjectMapper().readTree(candidate.getBody()).path("taskId").asText().equals(taskId))
                recoveredNotification = candidate;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(recoveredNotification, "An unclaimed task must survive a lost notification");
        assertEquals(0, json(browser.get("/tasks/" + taskId)).path("data").path("attemptCount").asInt());

        String requestId = java.util.UUID.randomUUID().toString();
        String claimBody = "{\"executorId\":\"worker-start-1\",\"claimRequestId\":\"" + requestId + "\"}";
        assertEquals(401, internal(taskId, "claim", claimBody, false).statusCode());
        var claimed = internal(taskId, "claim", claimBody, true);
        assertEquals(200, claimed.statusCode(), claimed.body());
        var data = json(claimed).path("data");
        assertEquals("RUNNING", data.path("status").asText());
        org.junit.jupiter.api.Assertions.assertFalse(data.path("attemptId").asText().isBlank());
        org.junit.jupiter.api.Assertions.assertFalse(data.path("leaseToken").asText().isBlank());
        var repeated = internal(taskId, "claim", claimBody, true);
        assertEquals(200, repeated.statusCode());
        assertEquals(data.path("attemptId").asText(), json(repeated).path("data").path("attemptId").asText());
        assertEquals(data.path("leaseToken").asText(), json(repeated).path("data").path("leaseToken").asText());
        assertEquals(409, internal(taskId, "claim", claimBody.replace(requestId, java.util.UUID.randomUUID().toString()), true).statusCode());
        assertEquals("RUNNING", json(browser.get("/tasks/" + taskId)).path("data").path("status").asText());
        String execution = new ObjectMapper().writeValueAsString(java.util.Map.of("attemptId", data.path("attemptId").asText(), "leaseToken", data.path("leaseToken").asText()));
        var heartbeat = internal(taskId, "heartbeat", execution, true);
        assertEquals(200, heartbeat.statusCode());
        assertTrue(java.time.Instant.parse(json(heartbeat).path("data").path("leaseExpiresAt").asText())
                .isAfter(java.time.Instant.now().plusSeconds(50)));
        assertEquals(409, internal(taskId, "heartbeat", execution.replace(data.path("leaseToken").asText(), "invalid-token"), true).statusCode());
        var cancelled = browser.post("/tasks/" + taskId + "/cancel", "{}");
        assertEquals(202, cancelled.statusCode(), cancelled.body());
        assertEquals("CANCEL_REQUESTED", json(cancelled).path("data").path("status").asText());
        assertEquals("STOP", json(internal(taskId, "heartbeat", execution, true)).path("data").path("action").asText());
        String stopped = execution.substring(0, execution.length()-1) + ",\"processExited\":true}";
        String stillRunning = execution.substring(0, execution.length()-1) + ",\"processExited\":false}";
        assertEquals(409, internal(taskId, "cancelled", stillRunning, true).statusCode());
        assertEquals(200, internal(taskId, "cancelled", stopped, true).statusCode());
        assertEquals("CANCELLED", json(browser.get("/tasks/" + taskId)).path("data").path("status").asText());
        assertEquals(409, internal(taskId, "heartbeat", execution, true).statusCode());
        String late = new ObjectMapper().writeValueAsString(java.util.Map.of("attemptId",data.path("attemptId").asText(),"leaseToken",data.path("leaseToken").asText(),"producerKey","late","resultType","MEDIA_PREPARED","result",java.util.Map.of("byteSize",bytes.length,"sha256",hash,"durationMs",1000,"mediaInfo",java.util.Map.of(),"timeMapping",java.util.Map.of())));
        assertEquals(409,internal(taskId,"complete",late,true).statusCode());
        assertEquals(0,json(browser.get("/projects/"+projectId+"/assets")).path("data").path("items").size());
    }
    @Test
    void completedReportIsIdempotentAndDifferentOrNewLateReportsCannotRepublish() throws Exception {
        BrowserApi browser=signedIn("task-result-owner");
        String projectId=json(browser.post("/projects","{\"name\":\"结果发布\"}")).path("data").path("id").asText();
        byte[] bytes=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../frontend/e2e/fixtures/short-video.mp4"));
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var upload=json(browser.post("/projects/"+projectId+"/uploads",new ObjectMapper().writeValueAsString(java.util.Map.of("filename","valid.mp4","sizeBytes",bytes.length,"sha256",hash,"idempotencyKey","publish-upload")))).path("data");
        String id=upload.path("uploadId").asText();
        String writer=upload.path("writerToken").asText();
        var urls=json(browser.post("/uploads/"+id+"/part-urls","{\"partNumbers\":[1],\"writerToken\":\""+writer+"\"}")).path("data");
        assertEquals(200,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(urls.path("parts").get(0).path("url").asText())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        String taskId=json(browser.post("/uploads/"+id+"/complete","{\"writerToken\":\""+writer+"\"}")).path("data").path("validationTaskId").asText();
        var claimed=internal(taskId,"claim",new ObjectMapper().writeValueAsString(java.util.Map.of("executorId","result-worker","claimRequestId",java.util.UUID.randomUUID())),true);
        assertEquals(200,claimed.statusCode(),claimed.body());
        var credential=json(claimed).path("data");
        java.util.Map<String,Object> result=java.util.Map.of("byteSize",bytes.length,"sha256",hash,"durationMs",1000,"mediaInfo",java.util.Map.of("streams",java.util.List.of(java.util.Map.of("codec_type","video","codec_name","h264","width",160,"height",90))),"timeMapping",java.util.Map.of("hasAudio",true,"formatStartMs",0,"videoStartMs",0,"audioOffsetFromVideoMs",0));
        String body=new ObjectMapper().writeValueAsString(java.util.Map.of("attemptId",credential.path("attemptId").asText(),"leaseToken",credential.path("leaseToken").asText(),"producerKey","result-one","resultType","MEDIA_PREPARED","result",result));
        var completed=internal(taskId,"complete",body,true);
        assertEquals(200,completed.statusCode(),completed.body());
        var duplicate=internal(taskId,"complete",body,true);
        assertEquals(200,duplicate.statusCode(),duplicate.body());
        String assetId=json(completed).path("data").path("assetId").asText();
        assertEquals(assetId,json(duplicate).path("data").path("assetId").asText());
        var changed=new ObjectMapper().readTree(body);
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("result")).put("durationMs",999);
        assertEquals(409,internal(taskId,"complete",changed.toString(),true).statusCode());
        assertEquals(409,internal(taskId,"complete",body.replace("result-one","result-two"),true).statusCode());
        assertEquals(1,json(browser.get("/projects/"+projectId+"/assets")).path("data").path("items").size());
        assertEquals("VALID",json(browser.get("/assets/"+assetId)).path("data").path("validationStatus").asText());
        assertEquals(1000,json(browser.get("/assets/"+assetId)).path("data").path("durationMs").asLong());
    }
    private HttpResponse<String> internal(String taskId, String action, String body, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/tasks/" + taskId + "/" + action))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) request.header("Authorization", "Bearer internal-integration-worker-token");
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
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
        HttpResponse<String> post(String path, String body) throws Exception {
            String token = json(get("/auth/csrf")).path("data").path("csrfToken").asText();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}

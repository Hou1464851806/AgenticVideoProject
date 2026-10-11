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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"conversation.model-record.request-max-bytes=512", "conversation.model-record.response-max-bytes=256"})
class ModelRecordLimitApiIntegrationTest {
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
    void configuredLimitsCountUtf8BytesAndRejectedRecordsDoNotConsumeSubmissionIdentity() throws Exception {
        BrowserApi owner = signedIn("utf8-record-owner");
        String project = json(owner.post("/projects", "{\"name\":\"UTF8边界\"}")).path("data").path("id").asText();
        String session = json(owner.post("/projects/" + project + "/conversations", "{\"title\":\"配置边界\"}")).path("data").path("id").asText();
        String task = json(owner.post("/conversations/" + session + "/turns", "{\"content\":\"核查边界\"}", "utf8-record")).path("data").path("taskId").asText();
        JsonNode claim = json(internal(task, "claim", Map.of("executorId", "utf8-record", "claimRequestId", java.util.UUID.randomUUID()))).path("data");
        var request = new java.util.HashMap<String,Object>();
        request.put("attemptId", claim.path("attemptId").asText()); request.put("leaseToken", claim.path("leaseToken").asText());
        request.put("action", "PREPARE"); request.put("data", Map.of("callNumber", 1, "request", Map.of("content", "中".repeat(200))));
        var rejected = internal(task, "provider-call", request);
        assertEquals(413, rejected.statusCode(), rejected.body());
        assertEquals("MODEL_RECORD_TOO_LARGE", json(rejected).path("error").path("code").asText());
        assertEquals(false, json(rejected).path("error").path("retryable").asBoolean());
        assertEquals(614, json(rejected).path("error").path("details").path("actualBytes").asLong());
        assertEquals(512, json(rejected).path("error").path("details").path("limitBytes").asLong());
        assertEquals("REQUEST", json(rejected).path("error").path("details").path("recordKind").asText());
        Object payload = Map.of("content", "a".repeat(498)); // Exactly 512 UTF-8 bytes with JSON syntax.
        request.put("data", Map.of("callNumber", 1, "request", payload));
        assertEquals(true, json(internal(task, "provider-call", request)).path("data").path("canSubmit").asBoolean());
        request.put("action", "SUCCEEDED"); request.put("data", Map.of("callNumber", 1, "response", Map.of("content", "中".repeat(100))));
        var oversizedResponse = internal(task, "provider-call", request);
        assertEquals(413, oversizedResponse.statusCode(), oversizedResponse.body());
        assertEquals(314, json(oversizedResponse).path("error").path("details").path("actualBytes").asLong());
        assertEquals(256, json(oversizedResponse).path("error").path("details").path("limitBytes").asLong());
        assertEquals("RESPONSE", json(oversizedResponse).path("error").path("details").path("recordKind").asText());
        request.put("action", "PREPARE"); request.put("data", Map.of("callNumber", 1, "request", payload));
        var uncertain = json(internal(task, "provider-call", request)).path("data");
        assertEquals(false, uncertain.path("canSubmit").asBoolean());
        assertEquals("UNCERTAIN", uncertain.path("status").asText());
        request.put("action", "SUCCEEDED");
        Object response = Map.of("content", "b".repeat(231), "usage", Map.of()); // Exactly 256 bytes.
        request.put("data", Map.of("callNumber", 1, "response", response));
        assertEquals(200, internal(task, "provider-call", request).statusCode());
        request.put("action", "PREPARE"); request.put("data", Map.of("callNumber", 1, "request", payload));
        assertEquals(new ObjectMapper().valueToTree(response), json(internal(task, "provider-call", request)).path("data").path("response"));
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

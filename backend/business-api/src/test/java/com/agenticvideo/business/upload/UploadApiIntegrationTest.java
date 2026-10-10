package com.agenticvideo.business.upload;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadApiIntegrationTest {
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
    static final SiloFaultProxy PROXY = new SiloFaultProxy(() -> SILO.getMappedPort(9000));
    @org.junit.jupiter.api.AfterAll static void closeProxy() throws Exception { PROXY.close(); }
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("storage.endpoint", PROXY::endpoint);
        properties.add("storage.public-endpoint", () -> "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
        properties.add("storage.list-page-size", () -> 1);
        properties.add("storage.access-key", () -> "integration-user");
        properties.add("storage.secret-key", () -> "integration-password-strong");
    }
    @LocalServerPort int port;

    @Test
    void createUploadPersistsOneIdentityAndReservationAcrossDuplicateRequests() throws Exception {
        BrowserApi browser = signedIn("upload-owner");
        String projectId = json(browser.post("/projects", "{\"name\":\"视频上传\"}")).path("data").path("id").asText();
        String body = "{\"filename\":\"talk.mp4\",\"sizeBytes\":1024,\"sha256\":\"" + "a".repeat(64) + "\",\"idempotencyKey\":\"upload-create-1\"}";
        var created = browser.post("/projects/" + projectId + "/uploads", body);
        assertEquals(201, created.statusCode(), created.body());
        String id = json(created).path("data").path("uploadId").asText();
        var duplicate = browser.post("/projects/" + projectId + "/uploads", body);
        assertEquals(201, duplicate.statusCode(), duplicate.body());
        assertEquals(id, json(duplicate).path("data").path("uploadId").asText());
        assertEquals(409, browser.post("/projects/" + projectId + "/uploads", body.replace("talk.mp4", "other.mp4")).statusCode());
        var status = browser.get("/uploads/" + id);
        assertEquals(200, status.statusCode());
        assertEquals("UPLOADING", json(status).path("data").path("status").asText());
        assertEquals(1024, json(status).path("data").path("reservedBytes").asLong());
        assertEquals(1, json(browser.get("/projects/" + projectId + "/uploads")).path("data").path("items").size());
    }

    @Test
    void directMultipartCompletionPublishesOnlyOnePendingAssetAndTask() throws Exception {
        BrowserApi browser = signedIn("upload-complete-owner");
        String projectId = json(browser.post("/projects", "{\"name\":\"分片上传\"}")).path("data").path("id").asText();
        byte[] bytes = "real-multipart-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        PROXY.fault.set(SiloFaultProxy.Fault.DROP_CREATE_RESPONSE);
        var created = browser.post("/projects/" + projectId + "/uploads", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename", "talk.mp4", "sizeBytes", bytes.length, "sha256", hash, "idempotencyKey", "multipart-one")));
        assertEquals(201, created.statusCode(), created.body());
        var data = json(created).path("data");
        String id = data.path("uploadId").asText();
        String writer = data.path("writerToken").asText();
        try (var storageClient = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + SILO.getMappedPort(9000)))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("integration-user", "integration-password-strong"))).build()) {
            assertEquals(1, storageClient.listMultipartUploads(b -> b.bucket("originals").prefix("originals/" + id)).uploads().size());
        }
        var authorization = browser.post("/uploads/" + id + "/part-urls", "{\"partNumbers\":[1],\"writerToken\":\"" + writer + "\"}");
        assertEquals(200, authorization.statusCode(), authorization.body());
        String url = json(authorization).path("data").path("parts").get(0).path("url").asText();
        var uploaded = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        org.junit.jupiter.api.Assertions.assertTrue(uploaded.headers().firstValue("ETag").isPresent());
        String input = "{\"writerToken\":\"" + writer + "\"}";
        PROXY.fault.set(SiloFaultProxy.Fault.COMPLETE_EMBEDDED_ERROR);
        var embeddedError = browser.post("/uploads/" + id + "/complete", input);
        assertEquals(503, embeddedError.statusCode());
        assertEquals("STORAGE_UNAVAILABLE", json(embeddedError).path("error").path("code").asText());
        org.junit.jupiter.api.Assertions.assertTrue(json(embeddedError).path("error").path("retryable").asBoolean());
        assertEquals(0, json(browser.get("/projects/" + projectId + "/assets?includePending=true")).path("data").path("items").size());
        PROXY.fault.set(SiloFaultProxy.Fault.DROP_COMPLETE_RESPONSE);
        var completed = browser.post("/uploads/" + id + "/complete", input);
        assertEquals(202, completed.statusCode(), completed.body());
        var duplicate = browser.post("/uploads/" + id + "/complete", input);
        assertEquals(202, duplicate.statusCode(), duplicate.body());
        assertEquals(json(completed).path("data").path("assetId").asText(), json(duplicate).path("data").path("assetId").asText());
        assertEquals(json(completed).path("data").path("validationTaskId").asText(), json(duplicate).path("data").path("validationTaskId").asText());
        assertEquals("VALIDATING", json(browser.get("/uploads/" + id)).path("data").path("validationStatus").asText());
        var assets = browser.get("/projects/" + projectId + "/assets?includePending=true");
        assertEquals(200, assets.statusCode(), assets.body());
        assertEquals(1, json(assets).path("data").path("items").size());
    }

    @Test
    void anotherAccountCannotSignRecordOrCompleteUploadAndIncompleteFileStaysUnpublished() throws Exception {
        BrowserApi owner = signedIn("upload-private-owner");
        BrowserApi outsider = signedIn("upload-private-outsider");
        String projectId = json(owner.post("/projects", "{\"name\":\"私有上传\"}")).path("data").path("id").asText();
        String body = "{\"filename\":\"private.mp4\",\"sizeBytes\":1024,\"sha256\":\"" + "b".repeat(64) + "\",\"idempotencyKey\":\"private-one\"}";
        var data = json(owner.post("/projects/" + projectId + "/uploads", body)).path("data");
        String id = data.path("uploadId").asText();
        String writer = data.path("writerToken").asText();
        assertEquals(404, outsider.post("/projects/" + projectId + "/uploads", body).statusCode());
        assertEquals(404, outsider.get("/uploads/" + id).statusCode());
        assertEquals(404, outsider.post("/uploads/" + id + "/part-urls", "{\"partNumbers\":[1],\"writerToken\":\"" + writer + "\"}").statusCode());
        assertEquals(404, outsider.post("/uploads/" + id + "/complete", "{\"writerToken\":\"" + writer + "\"}").statusCode());
        assertEquals(409, owner.post("/uploads/" + id + "/complete", "{\"writerToken\":\"" + writer + "\"}").statusCode());
        assertEquals(0, json(owner.get("/projects/" + projectId + "/assets?includePending=true")).path("data").path("items").size());
        assertEquals("UPLOADING", json(owner.get("/uploads/" + id)).path("data").path("status").asText());
    }

    @Test
    void completingTwoPartsReadsBothSiloPagesBeforePublishing() throws Exception {
        BrowserApi browser = signedIn("upload-paged-owner");
        String projectId = json(browser.post("/projects", "{\"name\":\"分页列片\"}")).path("data").path("id").asText();
        byte[] bytes = new byte[8 * 1024 * 1024 + 19];
        java.util.Arrays.fill(bytes, (byte) 37);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var data = json(browser.post("/projects/" + projectId + "/uploads", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "filename", "paged.mp4", "sizeBytes", bytes.length, "sha256", hash, "idempotencyKey", "paged-upload")))).path("data");
        String id = data.path("uploadId").asText();
        String writer = data.path("writerToken").asText();
        var authorization = browser.post("/uploads/" + id + "/part-urls", "{\"partNumbers\":[1,2],\"writerToken\":\"" + writer + "\"}");
        assertEquals(200, authorization.statusCode(), authorization.body());
        var parts = json(authorization).path("data").path("parts");
        for (int i=0; i<2; i++) {
            int offset = i * 8 * 1024 * 1024;
            byte[] part = java.util.Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 8 * 1024 * 1024));
            var uploaded = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(parts.get(i).path("url").asText()))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(part)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, uploaded.statusCode(), uploaded.body());
        }
        PROXY.partPages.set(0);
        var completed = browser.post("/uploads/" + id + "/complete", "{\"writerToken\":\"" + writer + "\"}");
        assertEquals(202, completed.statusCode(), completed.body());
        assertEquals(2, PROXY.partPages.get(), "Both pages of actual Silo parts must be read");
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

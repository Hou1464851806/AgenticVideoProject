package com.agenticvideo.business.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestPropertySource(properties={"legacy.worker-enabled=true","legacy.agent-enabled=true","analysis.execution-enabled=true","legacy.inspection-enabled=true"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadSpaceRecheckIntegrationTest {
    private static final java.nio.file.Path VOLUME = createVolume();
    private static java.nio.file.Path createVolume() {
        try { return java.nio.file.Files.createTempDirectory("upload-capacity-volume-"); }
        catch(java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
    }
    @org.junit.jupiter.api.AfterAll static void removeVolume() throws Exception { java.nio.file.Files.deleteIfExists(VOLUME); }
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("app.auth.max-attempts", () -> 200);
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
        properties.add("storage.endpoint", () -> "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
        properties.add("storage.public-endpoint", () -> "http://" + SILO.getHost() + ":" + SILO.getMappedPort(9000));
        properties.add("storage.list-page-size", () -> 1);
        properties.add("upload.cleanup-settle-seconds", () -> 1);
        properties.add("upload.cleanup-interval-ms", () -> 100);
        properties.add("capacity.paths", VOLUME::toString);
        properties.add("upload.max-file-bytes", () -> 1024);
        properties.add("upload.max-project-bytes", () -> 1536);
        properties.add("upload.max-project-assets", () -> 2);
        properties.add("storage.part-url-seconds", () -> 3);
        properties.add("storage.access-key", () -> "integration-user");
        properties.add("storage.secret-key", () -> "integration-password-strong");
    }
    @LocalServerPort int port;

    @Test
    void authorizationAndCompletionRecheckTheActualVolumeAfterUploadAdmission() throws Exception {
        var owner = signedIn("space-recheck-owner");
        String project = json(owner.post("/projects", "{\"name\":\"重新核对空间\"}")).path("data").path("id").asText();
        var upload = json(owner.post("/projects/" + project + "/uploads", uploadBody(owner, 19, "space-recheck"))).path("data");
        String id = upload.path("uploadId").asText(), writer = upload.path("writerToken").asText();
        String authorization = new ObjectMapper().writeValueAsString(java.util.Map.of("partNumbers", java.util.List.of(1), "writerToken", writer));
        java.nio.file.Files.delete(VOLUME);
        try {
            var rejected = owner.post("/uploads/" + id + "/part-urls", authorization);
            assertEquals(409, rejected.statusCode(), rejected.body());
            org.junit.jupiter.api.Assertions.assertTrue(json(rejected).path("error").path("message").asText().contains("存储卷"), rejected.body());
        } finally { java.nio.file.Files.createDirectories(VOLUME); }
        String url = json(owner.post("/uploads/" + id + "/part-urls", authorization)).path("data").path("parts").get(0).path("url").asText();
        assertEquals(200, HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[19])).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        java.nio.file.Files.delete(VOLUME);
        try {
            var rejected = owner.post("/uploads/" + id + "/complete", new ObjectMapper().writeValueAsString(java.util.Map.of("writerToken", writer)));
            assertEquals(409, rejected.statusCode(), rejected.body());
            assertEquals(0, json(owner.get("/projects/" + project + "/assets?includePending=true")).path("data").path("items").size());
        } finally { java.nio.file.Files.createDirectories(VOLUME); }
    }

    private String uploadBody(UploadTestApi owner, long bytes, String key) throws Exception {
        return new ObjectMapper().writeValueAsString(java.util.Map.of("filename", "quota.mp4", "sizeBytes", bytes,
                "sha256", "a".repeat(64), "idempotencyKey", key, "writerId", owner.writerId));
    }

    private UploadTestApi signedIn(String username) throws Exception {
        return UploadTestApi.signedIn(port,username);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return new ObjectMapper().readTree(response.body());
    }

}

package com.agenticvideo.business.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class UploadLowSpaceIntegrationTest {
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
        properties.add("capacity.min-free-bytes", () -> Long.MAX_VALUE);
        properties.add("upload.max-file-bytes", () -> 1024);
        properties.add("upload.max-project-bytes", () -> 1536);
        properties.add("upload.max-project-assets", () -> 2);
        properties.add("storage.part-url-seconds", () -> 3);
        properties.add("storage.access-key", () -> "integration-user");
        properties.add("storage.secret-key", () -> "integration-password-strong");
    }
    @LocalServerPort int port;

    @Test
    void measuredFreeSpaceBelowSafetyMarginRejectsUploadWithoutCreatingAReservation() throws Exception {
        var owner = signedIn("low-space-owner");
        String project = json(owner.post("/projects", "{\"name\":\"低空间\"}")).path("data").path("id").asText();
        var rejected = owner.post("/projects/" + project + "/uploads", uploadBody(owner, 1, "low-space"));
        assertEquals(409, rejected.statusCode(), rejected.body());
        String message = json(rejected).path("error").path("message").asText();
        org.junit.jupiter.api.Assertions.assertTrue(message.contains("空间") && message.contains("清理"), message);
        assertEquals(0, json(owner.get("/projects/" + project + "/uploads")).path("data").path("items").size());
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

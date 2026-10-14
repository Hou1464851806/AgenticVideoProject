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
class UploadGlobalCapacityIntegrationTest {
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
        properties.add("capacity.max-reserved-bytes", () -> 419430400);
        properties.add("upload.max-file-bytes", () -> 1024);
        properties.add("upload.max-project-bytes", () -> 1536);
        properties.add("upload.max-project-assets", () -> 2);
        properties.add("storage.part-url-seconds", () -> 3);
        properties.add("storage.access-key", () -> "integration-user");
        properties.add("storage.secret-key", () -> "integration-password-strong");
    }
    @LocalServerPort int port;

    @Test
    void projectsCompeteForOneDurableBudgetIncludingFutureMediaWorkspace() throws Exception {
        var firstOwner = signedIn("capacity-first-owner");
        var secondOwner = signedIn("capacity-second-owner");
        String firstProject = json(firstOwner.post("/projects", "{\"name\":\"全局容量一\"}")).path("data").path("id").asText();
        String secondProject = json(secondOwner.post("/projects", "{\"name\":\"全局容量二\"}")).path("data").path("id").asText();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> firstOwner.post("/projects/" + firstProject + "/uploads", uploadBody(firstOwner, 1, "global-one")));
            var second = executor.submit(() -> secondOwner.post("/projects/" + secondProject + "/uploads", uploadBody(secondOwner, 1, "global-two")));
            var replies = java.util.List.of(first.get(), second.get());
            assertEquals(java.util.List.of(201, 409), replies.stream().map(HttpResponse::statusCode).sorted().toList());
            String acceptedProject = replies.get(0).statusCode() == 201 ? firstProject : secondProject;
            var acceptedOwner = replies.get(0).statusCode() == 201 ? firstOwner : secondOwner;
            assertEquals(1, json(acceptedOwner.get("/projects/" + acceptedProject + "/uploads")).path("data").path("items").size());
            var rejected = replies.stream().filter(reply -> reply.statusCode() == 409).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertTrue(json(rejected).path("error").path("message").asText().contains("空间"), rejected.body());
        } finally { executor.shutdownNow(); }
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

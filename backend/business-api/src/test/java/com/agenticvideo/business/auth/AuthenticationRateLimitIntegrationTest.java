package com.agenticvideo.business.auth;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.auth.max-attempts=3")
class AuthenticationRateLimitIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @LocalServerPort int port;

    @Test
    void repeatedRegistrationRequestsAreBoundedAndExplainWhenToRetry() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String base = "http://localhost:" + port;
        var csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String token = new ObjectMapper().readTree(csrf.body()).path("data").path("csrfToken").asText();
        HttpRequest invalidRegistration = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/register"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"\",\"password\":\"\"}")).build();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(400, client.send(invalidRegistration, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        var limited = client.send(invalidRegistration, HttpResponse.BodyHandlers.ofString());
        assertEquals(429, limited.statusCode());
        assertEquals("RATE_LIMITED", new ObjectMapper().readTree(limited.body()).path("error").path("code").asText());
        assertTrue(new ObjectMapper().readTree(limited.body()).path("error").path("retryable").asBoolean());
        assertEquals("60", limited.headers().firstValue("Retry-After").orElseThrow());
    }
}

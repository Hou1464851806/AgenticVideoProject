package com.agenticvideo.business.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationApiIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-bookworm")
            .withDatabaseName("agentic_video")
            .withUsername("agentic_video")
            .withPassword("integration-test-only");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userCanRegisterAndDuplicateNamesAreRejectedAfterNormalization() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String baseUrl = "http://localhost:" + port;
        String csrfToken = getCsrfToken(client, baseUrl);

        HttpResponse<String> registration = postJson(
                client,
                baseUrl + "/api/v1/auth/register",
                "{\"username\":\"Alice\",\"password\":\"a-strong-password-123\"}",
                csrfToken);

        assertEquals(201, registration.statusCode(), registration.body());
        JsonNode createdUser = objectMapper.readTree(registration.body()).path("data").path("user");
        assertEquals("alice", createdUser.path("username").asText());
        assertFalse(createdUser.has("password"));

        HttpResponse<String> duplicate = postJson(
                client,
                baseUrl + "/api/v1/auth/register",
                "{\"username\":\"ALICE\",\"password\":\"another-strong-password\"}",
                csrfToken);

        assertEquals(409, duplicate.statusCode(), duplicate.body());
        assertEquals("USERNAME_TAKEN", objectMapper.readTree(duplicate.body()).path("error").path("code").asText());
    }

    private String getCsrfToken(HttpClient client, String baseUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/csrf")).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body()).path("data").path("csrfToken").asText();
    }

    @Test
    void oversizedUtf8PasswordIsRejectedWithAUsefulValidationError() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String base = "http://localhost:" + port;
        HttpResponse<String> response = postJson(client, base + "/api/v1/auth/register",
                objectMapper.writeValueAsString(java.util.Map.of("username", "unicode", "password", "密".repeat(40))),
                getCsrfToken(client, base));
        assertEquals(400, response.statusCode());
        assertEquals("INVALID_REQUEST", objectMapper.readTree(response.body()).path("error").path("code").asText());
        HttpResponse<String> login = postJson(client, base + "/api/v1/auth/login",
                objectMapper.writeValueAsString(java.util.Map.of("username", "unicode", "password", "密".repeat(40))),
                getCsrfToken(client, base));
        assertEquals(400, login.statusCode());
    }

    @Test
    void registrationRejectsMissingCsrfAndForeignOrigins() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String base = "http://localhost:" + port;
        String body = "{\"username\":\"Mallory\",\"password\":\"a-strong-password\"}";
        HttpResponse<String> missing = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/register"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, missing.statusCode());
        HttpResponse<String> foreign = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/register"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", getCsrfToken(client, base))
                .header("Origin", "https://untrusted.example").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, foreign.statusCode());
        assertEquals("CSRF_REJECTED", objectMapper.readTree(foreign.body()).path("error").path("code").asText());
    }

    @Test
    void userCanLoginAndLogoutRevokesTheOriginalSession() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String baseUrl = "http://localhost:" + port;
        String token = getCsrfToken(client, baseUrl);
        String credentials = "{\"username\":\"Bob\",\"password\":\"bobs-strong-password\"}";
        assertEquals(201, postJson(client, baseUrl + "/api/v1/auth/register", credentials, token).statusCode());
        HttpResponse<String> anonymous = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/me"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
        assertEquals(401, postJson(client, baseUrl + "/api/v1/auth/login",
                "{\"username\":\"Bob\",\"password\":\"wrong-password\"}", token).statusCode());
        HttpResponse<String> login = postJson(client, baseUrl + "/api/v1/auth/login", credentials, token);
        assertEquals(200, login.statusCode(), login.body());
        String sessionCookie = login.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertTrue(sessionCookie.contains("HttpOnly"));
        assertTrue(sessionCookie.contains("SameSite=Lax"));
        HttpResponse<String> current = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/me"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("bob", objectMapper.readTree(current.body()).path("data").path("user").path("username").asText());
        assertEquals(200, postJson(client, baseUrl + "/api/v1/auth/logout", "{}", getCsrfToken(client, baseUrl)).statusCode());
        HttpResponse<String> revoked = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/me"))
                .header("Cookie", sessionCookie.split(";", 2)[0]).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, revoked.statusCode());
    }

    private HttpResponse<String> postJson(HttpClient client, String url, String body, String csrfToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

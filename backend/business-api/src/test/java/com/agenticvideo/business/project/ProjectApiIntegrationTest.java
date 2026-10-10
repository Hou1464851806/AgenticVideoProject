package com.agenticvideo.business.project;

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
class ProjectApiIntegrationTest {
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
    void signedInUserCanCreateListAndOpenTheirProject() throws Exception {
        BrowserApi browser = signedIn("project-owner");
        var created = browser.post("/projects", "{\"name\":\"演讲剪辑\"}");
        assertEquals(201, created.statusCode(), created.body());
        String id = json(created).path("data").path("id").asText();
        var listed = browser.get("/projects");
        assertEquals(200, listed.statusCode());
        assertEquals(id, json(listed).path("data").path("items").get(0).path("id").asText());
        var opened = browser.get("/projects/" + id);
        assertEquals(200, opened.statusCode());
        assertEquals("演讲剪辑", json(opened).path("data").path("name").asText());
    }

    private BrowserApi signedIn(String username) throws Exception {
        BrowserApi browser = new BrowserApi();
        String credentials = new ObjectMapper().writeValueAsString(java.util.Map.of("username", username, "password", "project-strong-password"));
        assertEquals(201, browser.post("/auth/register", credentials).statusCode());
        assertEquals(200, browser.post("/auth/login", credentials).statusCode());
        return browser;
    }

    @Test
    void invalidProjectNameReturnsAnErrorAboutTheProjectField() throws Exception {
        var response = signedIn("project-validation").post("/projects", "{\"name\":\"   \"}");
        assertEquals(400, response.statusCode());
        assertEquals("INVALID_REQUEST", json(response).path("error").path("code").asText());
        org.junit.jupiter.api.Assertions.assertTrue(json(response).path("error").path("message").asText().contains("项目名称"));
    }

    @Test
    void twoAccountsCannotListOrOpenEachOthersProjectsEvenWithForgedOwnership() throws Exception {
        BrowserApi alice = signedIn("project-alice");
        BrowserApi bob = signedIn("project-bob");
        String bobId = json(bob.get("/auth/me")).path("data").path("user").path("id").asText();
        var created = alice.post("/projects", "{\"name\":\"Alice 私有项目\",\"ownerUserId\":\"" + bobId + "\"}");
        assertEquals(201, created.statusCode());
        String id = json(created).path("data").path("id").asText();
        assertEquals(1, json(alice.get("/projects")).path("data").path("items").size());
        assertEquals(0, json(bob.get("/projects")).path("data").path("items").size());
        assertEquals(404, bob.get("/projects/" + id).statusCode());
        assertEquals("RESOURCE_NOT_FOUND", json(bob.get("/projects/" + id)).path("error").path("code").asText());
        assertEquals(404, bob.get("/projects/00000000-0000-0000-0000-000000000001").statusCode());
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/projects")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
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

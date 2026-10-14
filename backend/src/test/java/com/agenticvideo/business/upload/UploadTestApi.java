package com.agenticvideo.business.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Browser authentication and CSRF at the same public HTTP boundary as the upload assertions. */
final class UploadTestApi {
    final UUID writerId=UUID.randomUUID();
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    private final ObjectMapper json=new ObjectMapper();
    private final String base;
    private UploadTestApi(int port) { base="http://localhost:"+port+"/api/v1"; }
    static UploadTestApi signedIn(int port,String username) throws Exception {
        var browser=new UploadTestApi(port);
        String credentials=browser.json.writeValueAsString(Map.of("username",username,"password","project-strong-password"));
        assertEquals(201,browser.post("/auth/register",credentials).statusCode());
        assertEquals(200,browser.post("/auth/login",credentials).statusCode());
        return browser;
    }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path,String body) throws Exception {
        JsonNode csrf=json.readTree(get("/auth/csrf").body()).path("data");
        return client.send(HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json")
                .header("X-XSRF-TOKEN",csrf.path("csrfToken").asText()).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}

package com.agenticvideo.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BusinessClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String url;
    private final String token;
    BusinessClient(ObjectMapper json, @Value("${worker.business-url}") String url, @Value("${worker.token}") String token) {
        this.json = json; this.url = url; this.token = token;
    }
    public Reply post(String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body()).path("data"));
    }
    public record Reply(int status, JsonNode data) {}
}

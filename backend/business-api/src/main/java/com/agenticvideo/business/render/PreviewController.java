package com.agenticvideo.business.render;

import com.agenticvideo.business.project.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PreviewController {
    private final PreviewRequests previews;
    private final ProjectAccess access;
    private final RenderArtifacts artifacts;
    public PreviewController(PreviewRequests previews,ProjectAccess access,RenderArtifacts artifacts) {this.previews=previews;this.access=access;this.artifacts=artifacts;}
    @PostMapping("/revisions/{revisionId}/previews")
    public Object request(@PathVariable UUID revisionId,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,Authentication account) {
        access.requireOwned((UUID)previews.revision(revisionId).get("project_id"),account);
        return ResponseEntity.accepted().body(envelope(previews.request(revisionId,body,key)));
    }
    @GetMapping("/revisions/{revisionId}/previews")
    public Object list(@PathVariable UUID revisionId,Authentication account) {
        access.requireOwned((UUID)previews.revision(revisionId).get("project_id"),account);
        return envelope(previews.list(revisionId));
    }
    @GetMapping("/render-requests/{requestId}")
    public Object read(@PathVariable UUID requestId,Authentication account) {
        access.requireOwned((UUID)previews.requestRow(requestId).get("project_id"),account);
        return envelope(previews.read(requestId));
    }
    private Object envelope(Object data) {return Map.of("data",data,"requestId",UUID.randomUUID());}
    @PostMapping("/artifacts/{artifactId}/playback-access")
    public Object playback(@PathVariable UUID artifactId,Authentication account) {
        access.requireOwned((UUID)artifacts.row(artifactId).get("project_id"),account);
        return envelope(artifacts.playback(artifactId));
    }
}

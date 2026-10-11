package com.agenticvideo.business.render;

import com.agenticvideo.business.project.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/revisions/{revisionId}")
public class ExportController {
    private final PreviewRequests revisions;
    private final ExportRequests exports;
    private final ProjectAccess access;
    public ExportController(PreviewRequests revisions,ExportRequests exports,ProjectAccess access){this.revisions=revisions;this.exports=exports;this.access=access;}
    @PostMapping("/confirmations")
    public Object confirm(@PathVariable UUID revisionId,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,Authentication account) {
        access.requireOwned((UUID)revisions.revision(revisionId).get("project_id"),account);
        return ResponseEntity.status(201).body(envelope(exports.confirm(revisionId,access.currentUserId(account),key,body)));
    }
    @GetMapping("/confirmations")
    public Object confirmations(@PathVariable UUID revisionId,Authentication account) {
        access.requireOwned((UUID)revisions.revision(revisionId).get("project_id"),account);
        return envelope(exports.confirmations(revisionId));
    }
    @GetMapping("/export-requests")
    public Object history(@PathVariable UUID revisionId,Authentication account) {
        access.requireOwned((UUID)revisions.revision(revisionId).get("project_id"),account);
        return envelope(exports.list(revisionId));
    }
    @PostMapping("/export-requests")
    public Object export(@PathVariable UUID revisionId,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,Authentication account) {
        access.requireOwned((UUID)revisions.revision(revisionId).get("project_id"),account);
        return ResponseEntity.accepted().body(envelope(exports.request(revisionId,access.currentUserId(account),key,body)));
    }
    private Object envelope(Object data){return Map.of("data",data,"requestId",UUID.randomUUID());}
}

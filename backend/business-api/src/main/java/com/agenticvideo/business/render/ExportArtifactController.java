package com.agenticvideo.business.render;

import com.agenticvideo.business.project.ProjectAccess;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/artifacts/{artifactId}")
public class ExportArtifactController {
    private final ProjectAccess access;
    private final RenderArtifacts artifacts;
    private final ExportArtifacts exports;
    public ExportArtifactController(ProjectAccess access,RenderArtifacts artifacts,ExportArtifacts exports) {this.access=access;this.artifacts=artifacts;this.exports=exports;}
    private void owned(UUID id, Authentication account) {access.requireOwned((UUID)artifacts.row(id).get("project_id"),account);}
    private Object envelope(Object value) {return Map.of("data",value,"requestId",UUID.randomUUID());}
    @GetMapping public Object read(@PathVariable UUID artifactId,Authentication account) {owned(artifactId,account);return envelope(exports.read(artifactId));}
    @PostMapping("/download-access") public Object download(@PathVariable UUID artifactId,Authentication account) {owned(artifactId,account);return envelope(exports.download(artifactId));}
    @DeleteMapping public Object delete(@PathVariable UUID artifactId,@RequestBody JsonNode body,Authentication account) {owned(artifactId,account);return envelope(exports.delete(artifactId,access.currentUserId(account),body));}
}

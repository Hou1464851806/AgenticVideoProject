package com.agenticvideo.business.upload;

import com.agenticvideo.business.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UploadController {
    private final ProjectAccess access;
    private final UploadService uploads;
    private final UploadCleanup cleanup;
    public UploadController(ProjectAccess access, UploadService uploads,UploadCleanup cleanup) { this.access = access; this.uploads = uploads;this.cleanup=cleanup; }

    @PostMapping("/uploads/{id}/cancel")
    Map<String,Object> cancel(@PathVariable UUID id,Authentication account) {
        var upload=uploads.find(id);access.requireOwned(upload.projectId(),account);
        cleanup.cancel(id);
        return envelope(uploads.view(uploads.find(id)));
    }

    @PostMapping("/projects/{projectId}/uploads")
    ResponseEntity<?> create(@PathVariable UUID projectId, @Valid @RequestBody CreateUpload request, Authentication account) {
        access.requireOwned(projectId, account);
        return ResponseEntity.status(201).body(envelope(uploads.create(projectId, access.currentUserId(account), request)));
    }
    @GetMapping("/uploads/{id}")
    Map<String, Object> get(@PathVariable UUID id, Authentication account) {
        var upload = uploads.find(id);
        access.requireOwned(upload.projectId(), account);
        return envelope(uploads.view(upload));
    }
    @GetMapping("/projects/{projectId}/uploads")
    Map<String, Object> list(@PathVariable UUID projectId, @RequestParam(required = false) UUID cursor, Authentication account) {
        access.requireOwned(projectId, account);
        return envelope(uploads.list(projectId, cursor));
    }
    @PostMapping("/uploads/{id}/part-urls")
    Map<String, Object> authorizeParts(@PathVariable UUID id, @Valid @RequestBody PartUrls request, Authentication account) {
        var upload = uploads.find(id);
        access.requireOwned(upload.projectId(), account);
        return envelope(uploads.partUrls(upload, request));
    }
    @PutMapping("/uploads/{id}/parts/{number}")
    Map<String, Object> recordPart(@PathVariable UUID id, @PathVariable int number, @Valid @RequestBody PartReport request, Authentication account) {
        var upload = uploads.find(id);
        access.requireOwned(upload.projectId(), account);
        return envelope(uploads.recordPart(upload, number, request));
    }
    public record PartReport(@NotNull UUID writerToken, @NotBlank String etag, @Positive long sizeBytes) {}
    @PostMapping("/uploads/{id}/resume")
    Map<String, Object> resume(@PathVariable UUID id, @Valid @RequestBody Resume request, Authentication account) {
        var upload = uploads.find(id);
        access.requireOwned(upload.projectId(), account);
        return envelope(uploads.resume(upload, request));
    }
    public record Resume(@Positive long sizeBytes, @NotNull @Pattern(regexp="[a-fA-F0-9]{64}") String sha256,
            @NotNull UUID writerId) {}
    @PostMapping("/uploads/{id}/pause")
    Map<String, Object> pause(@PathVariable UUID id, @Valid @RequestBody Writer request, Authentication account) {
        var upload = uploads.find(id);
        access.requireOwned(upload.projectId(), account);
        return envelope(uploads.pause(upload, request.writerToken()));
    }
    @PostMapping("/uploads/{id}/complete")
    ResponseEntity<?> complete(@PathVariable UUID id, @Valid @RequestBody Writer request, Authentication account) {
        var upload = uploads.find(id);
        access.requireOwned(upload.projectId(), account);
        return ResponseEntity.accepted().body(envelope(uploads.complete(upload, request.writerToken())));
    }
    @GetMapping("/projects/{projectId}/assets")
    Map<String, Object> assets(@PathVariable UUID projectId, @RequestParam(defaultValue="false") boolean includePending, Authentication account) {
        access.requireOwned(projectId, account);
        return envelope(uploads.assets(projectId, includePending));
    }
    public record PartUrls(@NotEmpty @Size(max=3) java.util.List<@Min(1) Integer> partNumbers, @NotNull UUID writerToken) {}
    public record Writer(@NotNull UUID writerToken) {}
    static Map<String, Object> envelope(Object data) { return Map.of("data", data, "requestId", UUID.randomUUID().toString()); }
    public record CreateUpload(@NotBlank @Size(max = 255) String filename, @Positive long sizeBytes,
            @Pattern(regexp = "[a-fA-F0-9]{64}") @NotNull String sha256,
            @NotBlank @Size(max = 120) String idempotencyKey, @NotNull UUID writerId) {}
}

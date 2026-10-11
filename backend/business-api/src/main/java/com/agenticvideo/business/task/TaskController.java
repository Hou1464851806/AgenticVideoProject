package com.agenticvideo.business.task;

import com.agenticvideo.business.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class TaskController {
    private final TaskExecution tasks;
    private final ProjectAccess access;
    TaskController(TaskExecution tasks, ProjectAccess access) { this.tasks = tasks; this.access = access; }
    @PostMapping("/internal/v1/tasks/{id}/claim")
    Map<String, Object> claim(@PathVariable UUID id, @Valid @RequestBody Claim request) {
        return envelope(tasks.claim(id, request));
    }
    @PostMapping("/internal/v1/tasks/{id}/complete")
    Map<String, Object> complete(@PathVariable UUID id, @Valid @RequestBody Completed request) {
        return envelope(tasks.complete(id, request));
    }
    public record Completed(@NotNull UUID attemptId, @NotBlank String leaseToken,
            @NotBlank String resultType, @NotBlank @Size(max=120) String producerKey,
            @NotNull com.fasterxml.jackson.databind.JsonNode result) {}
    @PostMapping("/internal/v1/tasks/{id}/fail")
    Map<String,Object> fail(@PathVariable UUID id, @Valid @RequestBody Failed request) { return envelope(tasks.fail(id,request)); }
    public record Failed(@NotNull UUID attemptId,@NotBlank String leaseToken,@NotBlank @Size(max=120) String producerKey,
            @NotBlank @Size(max=64) String code,@NotBlank @Size(max=240) String message) {}
    @PostMapping("/internal/v1/tasks/{id}/space-check")
    Map<String,Object> spaceCheck(@PathVariable UUID id,@Valid @RequestBody Execution request) {
        return envelope(tasks.spaceCheck(id,request));
    }
    @PostMapping("/internal/v1/tasks/{id}/heartbeat")
    Map<String, Object> heartbeat(@PathVariable UUID id, @Valid @RequestBody Execution request) {
        return envelope(tasks.heartbeat(id, request));
    }
    @PostMapping("/internal/v1/tasks/{id}/cancelled")
    Map<String, Object> cancelled(@PathVariable UUID id, @Valid @RequestBody Stopped request) {
        return envelope(tasks.cancelled(id, request));
    }
    @PostMapping("/api/v1/tasks/{id}/cancel")
    org.springframework.http.ResponseEntity<?> cancel(@PathVariable UUID id, Authentication account) {
        var task = tasks.read(id);
        access.requireOwned((UUID) task.get("project_id"), account);
        return org.springframework.http.ResponseEntity.accepted().body(envelope(tasks.cancel(id)));
    }
    public record Execution(@NotNull UUID attemptId, @NotBlank String leaseToken) {}
    @PostMapping("/internal/v1/tasks/{id}/tool")
    Map<String,Object> tool(@PathVariable UUID id,@Valid @RequestBody Tool request) { return envelope(tasks.tool(id,request)); }
    public record Tool(@NotNull UUID attemptId,@NotBlank String leaseToken,
            @NotBlank @Size(max=120) String producerKey,@NotBlank String name,
            @NotNull com.fasterxml.jackson.databind.JsonNode arguments) {}
    @PostMapping("/internal/v1/tasks/{id}/provider-call")
    Map<String,Object> provider(@PathVariable UUID id,@Valid @RequestBody ProviderCall request) { return envelope(tasks.provider(id,request)); }
    public record ProviderCall(@NotNull UUID attemptId,@NotBlank String leaseToken,@NotBlank String action,@NotNull com.fasterxml.jackson.databind.JsonNode data) {}
    @PostMapping("/internal/v1/tasks/{id}/validate-edit")
    Map<String,Object> validateEdit(@PathVariable UUID id,@Valid @RequestBody Candidate request) { return envelope(tasks.validateEdit(id,request)); }
    public record Candidate(@NotNull UUID attemptId,@NotBlank String leaseToken,
            @NotBlank @Size(max=120) String producerKey,@NotNull com.fasterxml.jackson.databind.JsonNode proposal) {}
    public record Stopped(@NotNull UUID attemptId, @NotBlank String leaseToken, boolean processExited) {}
    @GetMapping("/api/v1/tasks/{id}")
    Map<String, Object> get(@PathVariable UUID id, Authentication account) {
        var task = tasks.read(id);
        access.requireOwned((UUID) task.get("project_id"), account);
        return envelope(Map.of("id", id, "status", task.get("status"), "type", task.get("type"), "attemptCount", tasks.attemptCount(id)));
    }
    static Map<String, Object> envelope(Object data) { return Map.of("data", data, "requestId", UUID.randomUUID()); }
    public record Claim(@NotBlank @Size(max=120) String executorId, @NotNull UUID claimRequestId) {}
}

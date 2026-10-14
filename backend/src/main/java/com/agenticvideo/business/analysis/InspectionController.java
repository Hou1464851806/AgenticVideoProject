package com.agenticvideo.business.analysis;
import com.agenticvideo.business.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static org.springframework.http.HttpStatus.*;
@RestController
class InspectionController {
    @org.springframework.beans.factory.annotation.Value("${legacy.inspection-enabled:false}")
    private boolean inspectionEnabled;
    private void requireEnabled(){if(!inspectionEnabled)throw new org.springframework.web.server.ResponseStatusException(SERVICE_UNAVAILABLE,"M1 尚未接入按需细读能力");}
    private final VisualInspections inspections;private final ProjectAccess access;
    InspectionController(VisualInspections inspections,ProjectAccess access){this.inspections=inspections;this.access=access;}
    @PostMapping("/api/v1/projects/{project}/visual-inspections") @ResponseStatus(ACCEPTED)
    Object create(@PathVariable UUID project,@Valid @RequestBody Query input,@RequestHeader("Idempotency-Key") String key,Authentication account){access.requireOwned(project,account);requireEnabled();if(key.isBlank()||key.length()>100)throw new org.springframework.web.server.ResponseStatusException(BAD_REQUEST,"补查幂等键无效");return Map.of("data",inspections.create(project,input.assetId(),input.query(),input.sourceKind(),input.startMs(),input.endMs(),key),"requestId",UUID.randomUUID());}
    @GetMapping("/api/v1/visual-inspections/{id}")
    Object view(@PathVariable UUID id,Authentication account){access.requireOwned(inspections.project(id),account);return Map.of("data",inspections.view(id),"requestId",UUID.randomUUID());}
    @PostMapping("/api/v1/visual-inspections/{id}/expansion-proposal")
    Object propose(@PathVariable UUID id,Authentication account){access.requireOwned(inspections.project(id),account);return Map.of("data",inspections.propose(id),"requestId",UUID.randomUUID());}
    @PostMapping("/api/v1/visual-inspections/{id}/expansion-decision")
    Object decide(@PathVariable UUID id,@Valid @RequestBody Decision decision,Authentication account){access.requireOwned(inspections.project(id),account);requireEnabled();return Map.of("data",inspections.decide(id,decision.choice(),access.currentUserId(account)),"requestId",UUID.randomUUID());}
    record Decision(@NotNull @Pattern(regexp="DECLINE|APPROVE") String choice){}
    record Query(@NotNull UUID assetId,@NotBlank @Size(max=256) String query,@NotNull @Pattern(regexp="VISUAL|VISIBLE_TEXT") String sourceKind,@Min(0) Long startMs,@Min(1) Long endMs){}
}

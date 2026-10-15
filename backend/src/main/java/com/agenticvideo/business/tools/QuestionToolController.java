package com.agenticvideo.business.tools;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Authenticated local CLI transport. Scope identity never comes from model arguments. */
@RestController
@RequestMapping("/internal/v1/questions")
class QuestionToolController {
    private final QuestionScopes scopes;
    private final ReadTools tools;
    private final com.agenticvideo.business.plan.ProductionDrafts productions;
    QuestionToolController(QuestionScopes scopes,ReadTools tools,com.agenticvideo.business.plan.ProductionDrafts productions){this.scopes=scopes;this.tools=tools;this.productions=productions;}
    record Create(@NotNull UUID project_id,List<@NotNull UUID> asset_ids) {}
    @PostMapping("/scopes") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String,Object> create(@Valid @RequestBody Create input){return view(scopes.create(input.project_id(),input.asset_ids()));}
    @GetMapping("/scopes/{scopeId}")
    Map<String,Object> get(@PathVariable UUID scopeId){var scope=scopes.get(scopeId);scopes.requireDependencies(scope);return view(scope);}
    @PostMapping("/scopes/{scopeId}/tools")
    JsonNode execute(@PathVariable UUID scopeId,@RequestBody JsonNode request){return tools.execute(scopeId,request);}
    record Bind(@NotNull UUID project_id,@NotNull UUID scope_id) {}
    @PutMapping("/productions/{requirementId}")
    Map<String,Object> bind(@PathVariable UUID requirementId,@Valid @RequestBody Bind input){return productions.bind(requirementId,input.project_id(),input.scope_id());}
    @PostMapping("/productions/{requirementId}/tools")
    JsonNode validate(@PathVariable UUID requirementId,@RequestBody JsonNode request){
        var production=productions.get(requirementId);
        return tools.execute((UUID)production.get("scope_id"),requirementId,request);
    }
    private Map<String,Object> view(com.agenticvideo.business.search.QuestionEvidence.Scope scope){return Map.of("scope_id",scope.id(),"project_id",scope.projectId(),"assets",scope.assets(),"mock",false);}
    @ExceptionHandler(ToolFailure.class) @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    Map<String,Object> invalid(ToolFailure failure){return Map.of("error",Map.of("code",failure.code,"category",failure.category,"message",failure.getMessage()));}
}

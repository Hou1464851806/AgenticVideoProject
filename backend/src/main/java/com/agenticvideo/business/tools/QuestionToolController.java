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
    QuestionToolController(QuestionScopes scopes,ReadTools tools){this.scopes=scopes;this.tools=tools;}
    record Create(@NotNull UUID project_id,List<@NotNull UUID> asset_ids) {}
    @PostMapping("/scopes") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String,Object> create(@Valid @RequestBody Create input){return view(scopes.create(input.project_id(),input.asset_ids()));}
    @GetMapping("/scopes/{scopeId}")
    Map<String,Object> get(@PathVariable UUID scopeId){var scope=scopes.get(scopeId);scopes.requireDependencies(scope);return view(scope);}
    @PostMapping("/scopes/{scopeId}/tools")
    JsonNode execute(@PathVariable UUID scopeId,@RequestBody JsonNode request){return tools.execute(scopeId,request);}
    private Map<String,Object> view(com.agenticvideo.business.search.QuestionEvidence.Scope scope){return Map.of("scope_id",scope.id(),"project_id",scope.projectId(),"assets",scope.assets(),"mock",false);}
    @ExceptionHandler(ToolFailure.class) @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    Map<String,Object> invalid(ToolFailure failure){return Map.of("error",Map.of("code",failure.code,"category",failure.category,"message",failure.getMessage()));}
}

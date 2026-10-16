package com.agenticvideo.business.agentrun;

import com.agenticvideo.business.task.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/agent-runs/{task}")
public class AgentRunController {
    private final AgentRuns runs;
    private final AgentRunResults results;
    private final TaskExecution tasks;
    private final AgentRunTools tools;
    public AgentRunController(AgentRuns runs,AgentRunResults results,TaskExecution tasks,AgentRunTools tools){this.runs=runs;this.results=results;this.tasks=tasks;this.tools=tools;}
    @PostMapping("/claim") Object claim(@PathVariable UUID task,@Valid @RequestBody TaskController.Claim request){return envelope(results.claim(task,request));}
    @PostMapping("/parsed") Object parsed(@PathVariable UUID task,@Valid @RequestBody Parsed request){return envelope(runs.bind(task,request.attemptId(),request.leaseToken(),request.parsed()));}
    @PostMapping("/result") Object result(@PathVariable UUID task,@Valid @RequestBody Result request){return envelope(results.publish(task,request.attemptId(),request.leaseToken(),request.result()));}
    @PostMapping("/tools") Object tool(@PathVariable UUID task,@Valid @RequestBody Tool request){return envelope(tools.execute(task,request.attemptId(),request.leaseToken(),request.editGeneration(),request.request()));}
    @PostMapping("/receipts") Object receipts(@PathVariable UUID task,@Valid @RequestBody TaskController.Execution request){return envelope(results.receipts(task,request.attemptId(),request.leaseToken()));}
    @PostMapping("/heartbeat") Object heartbeat(@PathVariable UUID task,@Valid @RequestBody TaskController.Execution request){results.requireRun(task);return envelope(tasks.heartbeat(task,request));}
    @PostMapping("/cancelled") Object cancelled(@PathVariable UUID task,@Valid @RequestBody TaskController.Stopped request){results.requireRun(task);return envelope(tasks.cancelled(task,request));}
    @PostMapping("/events") Object event(@PathVariable UUID task,@Valid @RequestBody TaskController.PublicEvent request){results.requireRun(task);if(!"activity.updated".equals(request.type()))throw AgentRuns.conflict("新版 Agent 只发布公开活动阶段");return envelope(tasks.event(task,request));}
    record Parsed(@NotNull UUID attemptId,@NotBlank String leaseToken,@NotNull JsonNode parsed) {}
    record Result(@NotNull UUID attemptId,@NotBlank String leaseToken,@NotNull JsonNode result) {}
    record Tool(@NotNull UUID attemptId,@NotBlank String leaseToken,@Min(0) Long editGeneration,@NotNull JsonNode request) {}
    private Object envelope(Object value){return Map.of("data",value,"requestId",UUID.randomUUID());}
}

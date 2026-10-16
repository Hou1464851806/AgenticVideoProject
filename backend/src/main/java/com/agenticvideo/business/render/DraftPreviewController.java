package com.agenticvideo.business.render;

import com.agenticvideo.business.agentrun.AgentRuns;
import com.agenticvideo.business.agentrun.AgentRunContracts;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations/{conversation}/drafts/previews")
public class DraftPreviewController {
    private final DraftPreviews previews;
    private final AgentRuns runs;
    public DraftPreviewController(DraftPreviews previews,AgentRuns runs){this.previews=previews;this.runs=runs;}
    @PostMapping public Object request(@PathVariable UUID conversation,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode request,Authentication account) {
        return ResponseEntity.accepted().body(envelope(previews.request(conversation,key,AgentRunContracts.read(request,DraftPreviews.Request.class),account)));
    }
    @GetMapping public Object list(@PathVariable UUID conversation,Authentication account) {
        runs.requireOwned(conversation,account);return envelope(previews.list(conversation));
    }
    @PostMapping("/{request}/retry") public Object retry(@PathVariable UUID conversation,@PathVariable UUID request,Authentication account) {
        return ResponseEntity.accepted().body(envelope(previews.retry(conversation,request,account)));
    }
    private Object envelope(Object data){return Map.of("data",data,"requestId",UUID.randomUUID());}
}

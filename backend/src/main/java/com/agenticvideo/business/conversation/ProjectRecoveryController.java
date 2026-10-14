package com.agenticvideo.business.conversation;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ProjectRecoveryController {
    private final ProjectRecovery recovery;
    private final ProjectEventStream events;
    public ProjectRecoveryController(ProjectRecovery recovery, ProjectEventStream events) { this.recovery = recovery; this.events = events; }
    @GetMapping("/snapshot")
    public Object snapshot(@PathVariable UUID projectId, Authentication account) {
        return Map.of("data", recovery.snapshot(projectId, account), "requestId", UUID.randomUUID());
    }
    @GetMapping(value="/events", produces="text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(@PathVariable UUID projectId,
            @RequestParam(required=false) String after, @RequestHeader(value="Last-Event-ID",required=false) String previous,
            Authentication account, jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return events.subscribe(projectId, previous == null ? after : previous, account, request.getSession(false));
    }
}

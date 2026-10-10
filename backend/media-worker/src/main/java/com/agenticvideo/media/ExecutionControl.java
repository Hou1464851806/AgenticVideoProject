package com.agenticvideo.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
class ExecutionControl {
    private final MediaExecutor executor;
    private final String token;
    ExecutionControl(MediaExecutor executor, @Value("${worker.token}") String token) { this.executor=executor; this.token=token; }
    @GetMapping("/internal/v1/executions/{attempt}")
    Map<String,Object> state(@PathVariable UUID attempt, @RequestHeader(value="Authorization",required=false) String provided) {
        if (token.length()<24 || provided==null || !MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return executor.state(attempt);
    }    @PostMapping("/internal/v1/executions/{attempt}/stop")
    Map<String,Object> stop(@PathVariable UUID attempt,@RequestHeader(value="Authorization",required=false) String provided) {
        state(attempt,provided);
        return executor.stop(attempt);
    }

}

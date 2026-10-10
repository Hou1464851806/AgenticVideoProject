package com.agenticvideo.business.project;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ProjectErrors {
    @ExceptionHandler(ProjectAccess.ProjectNotFound.class)
    ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("requestId", UUID.randomUUID(), "error",
                Map.of("code", "RESOURCE_NOT_FOUND", "message", "项目不存在或无权访问", "retryable", false, "details", Map.of())));
    }
}

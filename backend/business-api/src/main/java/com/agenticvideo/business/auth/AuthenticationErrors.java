package com.agenticvideo.business.auth;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthenticationErrors {
    @ExceptionHandler(AuthenticationController.AuthFailure.class)
    ResponseEntity<?> failure(AuthenticationController.AuthFailure failure) {
        return ResponseEntity.status(failure.status).body(error(failure.code, failure.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation() {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "请检查用户名和密码长度"));
    }

    static Map<String, Object> error(String code, String message) {
        boolean retryable = "RATE_LIMITED".equals(code);
        return Map.of("error", Map.of("code", code, "message", message, "retryable", retryable,
                        "details", retryable ? Map.of("retryAfterMs", 60_000) : Map.of()),
                "requestId", UUID.randomUUID());
    }
}

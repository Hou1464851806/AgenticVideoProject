package com.agenticvideo.business.upload;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;

@RestControllerAdvice
class UploadErrors {
    @ExceptionHandler(SdkException.class)
    ResponseEntity<?> storageUnavailable() {
        return ResponseEntity.status(503).body(error("STORAGE_UNAVAILABLE", "对象存储暂时不可用，请稍后核对并重试", true));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> rejected(ResponseStatusException failure) {
        String code = failure instanceof com.agenticvideo.business.storage.StorageCapacity.SpaceUnavailable ? "STORAGE_SPACE_LOW" : switch (failure.getStatusCode().value()) {
            case 404 -> "RESOURCE_NOT_FOUND";
            case 409 -> "UPLOAD_CONFLICT";
            default -> "INVALID_REQUEST";
        };
        return ResponseEntity.status(failure.getStatusCode()).body(error(code, failure.getReason(), false));
    }
    private Map<String, Object> error(String code, String message, boolean retryable) {
        return Map.of("requestId", UUID.randomUUID(), "error", Map.of("code", code, "message", message,
                "retryable", retryable, "details", Map.of()));
    }
}

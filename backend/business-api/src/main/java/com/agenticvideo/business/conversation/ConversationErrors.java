package com.agenticvideo.business.conversation;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ConversationErrors {
    @ExceptionHandler(ModelRecordTooLarge.class)
    ResponseEntity<?> modelRecordTooLarge(ModelRecordTooLarge failure) {
        return ResponseEntity.status(413).body(Map.of("requestId", UUID.randomUUID(), "error",
                Map.of("code", "MODEL_RECORD_TOO_LARGE", "message", "模型调用记录超过配置的字节上限",
                        "retryable", false, "details", Map.of("recordKind", failure.kind,
                                "actualBytes", failure.actualBytes, "limitBytes", failure.limitBytes))));
    }
}

package com.agenticvideo.business.upload;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class UploadAdmission {
    private static final Logger LOG = LoggerFactory.getLogger(UploadAdmission.class);
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("upload-admission.lua"));
        SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final int capacity;
    private final long periodMs;

    public UploadAdmission(StringRedisTemplate redis,
            @Value("${upload.admission.capacity:3}") int capacity,
            @Value("${upload.admission.period-ms:60000}") long periodMs) {
        if (capacity < 1 || periodMs < 1 || periodMs > Long.MAX_VALUE / capacity)
            throw new IllegalArgumentException("Invalid upload admission capacity or period");
        this.redis = redis;
        this.capacity = capacity;
        this.periodMs = periodMs;
        LOG.info("Upload admission capacity={}, periodMs={}", capacity, periodMs);
    }

    public void require(UUID userId) {
        long wait;
        try {
            // No application retry: a lost response may already have consumed a token.
            wait = redis.execute(SCRIPT, List.of("upload:admission:" + userId),
                    Integer.toString(capacity), Long.toString(periodMs));
        } catch (RedisConnectionFailureException | QueryTimeoutException failure) {
            throw unavailable(failure);
        } catch (RedisSystemException failure) {
            // noeviction memory exhaustion is infrastructure failure; script bugs remain visible.
            if (failure.getRootCause() instanceof io.lettuce.core.RedisCommandExecutionException command
                    && command.getMessage().startsWith("OOM ")) throw unavailable(failure);
            throw failure;
        }
        if (wait > 0) throw new RateLimited(wait);
    }

    private Unavailable unavailable(RuntimeException failure) {
        LOG.warn("Upload admission Redis unavailable", failure);
        return new Unavailable(failure);
    }

    static final class RateLimited extends RuntimeException {
        final long retryAfterMs;
        RateLimited(long retryAfterMs) {
            super("上传过于频繁，请约 " + ((retryAfterMs + 999) / 1000) + " 秒后重试");
            this.retryAfterMs = retryAfterMs;
        }
    }
    static final class Unavailable extends RuntimeException {
        Unavailable(Throwable cause) { super("上传准入服务暂时不可用，请稍后重试", cause); }
    }
}

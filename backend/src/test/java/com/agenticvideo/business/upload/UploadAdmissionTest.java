package com.agenticvideo.business.upload;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class UploadAdmissionTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(
            "redis:7.4.6-alpine@sha256:3b73847e72874be07e6657b129a94761662b79bc0f679273757d4218573b2a98")
            .withExposedPorts(6379);

    @Test void initiallyAllowsThreeThenRejectsWithoutAffectingAnotherUser() {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        try {
            var admission = new UploadAdmission(new StringRedisTemplate(factory), 3, 60000);
            var user = UUID.randomUUID();
            for (int i=0;i<3;i++) admission.require(user);
            var denied = assertThrows(UploadAdmission.RateLimited.class, () -> admission.require(user));
            assertTrue(denied.retryAfterMs > 0 && denied.retryAfterMs <= 60000);
            admission.require(UUID.randomUUID());
        } finally { factory.destroy(); }
    }

    @Test void concurrentIndependentComponentsShareExactlyThreePermits() throws Exception {
        var first = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        var second = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        first.afterPropertiesSet(); second.afterPropertiesSet();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(12);
        try {
            var a = new UploadAdmission(new StringRedisTemplate(first), 3, 60000);
            var b = new UploadAdmission(new StringRedisTemplate(second), 3, 60000);
            var user = UUID.randomUUID();
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i=0;i<24;i++) {
                var component = i % 2 == 0 ? a : b;
                futures.add(executor.submit(() -> {
                    start.await();
                    try { component.require(user); return true; }
                    catch (UploadAdmission.RateLimited denied) { return false; }
                }));
            }
            start.countDown();
            int allowed = 0;
            for (var future : futures) if (future.get()) allowed++;
            assertEquals(3, allowed);
        } finally { executor.shutdownNow(); first.destroy(); second.destroy(); }
    }

    @Test void partialRefillSurvivesRepeatedRejectionAndIdleExpiryRestoresOnlyCapacity() throws Exception {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        try {
            var redis = new StringRedisTemplate(factory);
            var admission = new UploadAdmission(redis, 3, 1000);
            var user = UUID.randomUUID();
            for (int i=0;i<3;i++) admission.require(user);
            Thread.sleep(350);
            long first = assertThrows(UploadAdmission.RateLimited.class, () -> admission.require(user)).retryAfterMs;
            assertTrue(first < 800);
            Thread.sleep(350);
            long second = assertThrows(UploadAdmission.RateLimited.class, () -> admission.require(user)).retryAfterMs;
            assertTrue(second < first - 200);
            assertTrue(redis.getExpire("upload:admission:" + user, java.util.concurrent.TimeUnit.MILLISECONDS) >= 2800);
            Thread.sleep(second + 30);
            admission.require(user);
            assertThrows(UploadAdmission.RateLimited.class, () -> admission.require(user));
            Thread.sleep(3100);
            assertFalse(redis.hasKey("upload:admission:" + user));
            for (int i=0;i<3;i++) admission.require(user);
            assertThrows(UploadAdmission.RateLimited.class, () -> admission.require(user));
            redis.delete("upload:admission:" + user);
            for (int i=0;i<3;i++) admission.require(user);
        } finally { factory.destroy(); }
    }

    @Test void boundedTimeoutAndConnectionRefusalFailClosedThenRecover() throws Exception {
        var properties = new org.springframework.boot.autoconfigure.data.redis.RedisProperties();
        properties.setHost(REDIS.getHost()); properties.setPort(REDIS.getMappedPort(6379));
        properties.setTimeout(java.time.Duration.ofMillis(200));
        properties.setConnectTimeout(java.time.Duration.ofMillis(200));
        var factory = new UploadAdmissionRedisConfiguration().redisConnectionFactory(properties);
        factory.afterPropertiesSet();
        try {
            var admission = new UploadAdmission(new StringRedisTemplate(factory), 3, 60000);
            admission.require(UUID.randomUUID());
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                assertTimeout(java.time.Duration.ofSeconds(3), () ->
                        assertThrows(UploadAdmission.Unavailable.class, () -> admission.require(UUID.randomUUID())));
            } finally { REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
            admission.require(UUID.randomUUID());
        } finally { factory.destroy(); }
        try (var socket = new java.net.ServerSocket(0)) {
            properties.setPort(socket.getLocalPort());
        }
        var refused = new UploadAdmissionRedisConfiguration().redisConnectionFactory(properties);
        refused.afterPropertiesSet();
        try {
            assertThrows(UploadAdmission.Unavailable.class, () ->
                    new UploadAdmission(new StringRedisTemplate(refused), 3, 60000).require(UUID.randomUUID()));
        } finally { refused.destroy(); }
    }

    @Test void noevictionMemoryFailureIsUnavailableButCorruptStateIsNotHidden() throws Exception {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        try {
            var redis = new StringRedisTemplate(factory);
            var admission = new UploadAdmission(redis, 3, 60000);
            var user = UUID.randomUUID();
            redis.opsForValue().set("upload:admission:" + user, "wrong-type");
            assertThrows(org.springframework.data.redis.RedisSystemException.class, () -> admission.require(user));
            REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "1");
            try { assertThrows(UploadAdmission.Unavailable.class, () -> admission.require(UUID.randomUUID())); }
            finally { REDIS.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "0"); }
            admission.require(UUID.randomUUID());
        } finally { factory.destroy(); }
    }

    @Test void invalidExternalConfigurationFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new UploadAdmission(null, 0, 60000));
        assertThrows(IllegalArgumentException.class, () -> new UploadAdmission(null, 3, 0));
    }
}

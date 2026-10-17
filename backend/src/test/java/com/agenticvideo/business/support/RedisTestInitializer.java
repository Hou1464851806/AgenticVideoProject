package com.agenticvideo.business.support;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.GenericContainer;

/** Real shared Redis; non-admission tests explicitly use a generous capacity. */
public class RedisTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    public static final GenericContainer<?> REDIS = new GenericContainer<>(
            "redis:7.4.6-alpine@sha256:3b73847e72874be07e6657b129a94761662b79bc0f679273757d4218573b2a98")
            .withExposedPorts(6379);
    static { REDIS.start(); }
    @Override public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testRedis", Map.of(
                "spring.data.redis.host", REDIS.getHost(), "spring.data.redis.port", REDIS.getMappedPort(6379),
                "upload.admission.capacity", context.getEnvironment().getProperty("upload.admission.test-capacity", Integer.class, 10000))));
    }
}

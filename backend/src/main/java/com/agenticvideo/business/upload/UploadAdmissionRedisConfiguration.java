package com.agenticvideo.business.upload;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration(proxyBeanMethods = false)
class UploadAdmissionRedisConfiguration {
    @Bean
    LettuceConnectionFactory redisConnectionFactory(RedisProperties properties) {
        if (properties.getConnectTimeout().isNegative() || properties.getConnectTimeout().isZero()
                || properties.getTimeout().isNegative() || properties.getTimeout().isZero())
            throw new IllegalArgumentException("Redis timeouts must be positive");
        var server = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        server.setDatabase(properties.getDatabase());
        server.setUsername(properties.getUsername());
        server.setPassword(properties.getPassword());
        var client = LettuceClientConfiguration.builder().commandTimeout(properties.getTimeout())
                .clientOptions(ClientOptions.builder().autoReconnect(false)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .socketOptions(SocketOptions.builder().connectTimeout(properties.getConnectTimeout()).build())
                        .build()).build();
        var factory = new LettuceConnectionFactory(server, client);
        // Each admission owns a fresh connection: no disconnected queue or replay, and next call can recover.
        factory.setShareNativeConnection(false);
        return factory;
    }
}

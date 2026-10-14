package com.agenticvideo.business.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
class WorkerSecurity {
    @Bean @Order(1)
    SecurityFilterChain workerFilterChain(HttpSecurity http, ObjectMapper json,
            @Value("${internal.worker-token:}") String token,
            @Value("${legacy.worker-enabled:false}") boolean legacyWorker) throws Exception {
        return http.securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("WORKER"))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws IOException, ServletException {
                        if(!legacyWorker && request.getRequestURI().startsWith("/internal/v1/tasks/")) {
                            response.setStatus(410); response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(),Map.of("error",Map.of("code","WORKER_PROTOCOL_RETIRED","message","本机任务通过 MQ 执行，旧 Worker 协议已停用")));
                            return;
                        }
                        String provided = request.getHeader("Authorization");
                        if (token.length() < 24 || provided == null || !MessageDigest.isEqual(
                                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(), Map.of("error", Map.of("code", "EXECUTOR_AUTH_REQUIRED", "message", "执行服务认证失败")));
                            return;
                        }
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                                "media-worker", null, List.of(new SimpleGrantedAuthority("WORKER"))));
                        chain.doFilter(request, response);
                    }
                }, AnonymousAuthenticationFilter.class).build();
    }
}

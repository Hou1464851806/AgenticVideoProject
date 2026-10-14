package com.agenticvideo.business.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

final class BrowserRequestProtection extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final String allowedOrigin;
    private final int maximumAttempts;
    private final Map<String, AttemptWindow> attempts = new HashMap<>();

    BrowserRequestProtection(ObjectMapper json, String allowedOrigin, int maximumAttempts) {
        if (maximumAttempts < 1) throw new IllegalArgumentException("认证请求限额必须为正数");
        this.json = json;
        this.allowedOrigin = allowedOrigin;
        this.maximumAttempts = maximumAttempts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
            String origin = request.getHeader("Origin");
            if (origin != null && !allowedOrigin.equals(origin)) {
                reject(response, 403, "CSRF_REJECTED", "请求来源不受信任");
                return;
            }
            if (Set.of("/api/v1/auth/login", "/api/v1/auth/register").contains(request.getRequestURI())
                    && !allowAttempt(request.getRemoteAddr())) {
                response.setHeader("Retry-After", "60");
                reject(response, 429, "RATE_LIMITED", "认证请求过于频繁，请一分钟后再试");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allowAttempt(String address) {
        long now = System.currentTimeMillis();
        attempts.values().removeIf(window -> window.expiresAt <= now);
        AttemptWindow window = attempts.get(address);
        if (window == null) {
            if (attempts.size() >= 4096) return false;
            window = new AttemptWindow(now + 60_000);
            attempts.put(address, window);
        }
        return ++window.count <= maximumAttempts;
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), AuthenticationErrors.error(code, message));
    }

    private static final class AttemptWindow {
        final long expiresAt;
        int count;
        AttemptWindow(long expiresAt) { this.expiresAt = expiresAt; }
    }
}

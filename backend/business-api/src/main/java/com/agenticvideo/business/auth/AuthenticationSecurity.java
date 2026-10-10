package com.agenticvideo.business.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
public class AuthenticationSecurity {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    UserDetailsService accountDetails(JdbcTemplate database) {
        return username -> database.query("SELECT normalized_username, password_hash, status FROM users WHERE normalized_username = ?",
                (row, index) -> User.withUsername(row.getString(1)).password(row.getString(2))
                        .disabled(!"ACTIVE".equals(row.getString(3))).authorities("USER").build(),
                AuthenticationController.normalize(username)).stream().findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }

    @Bean
    SecurityFilterChain browserSecurity(HttpSecurity http, ObjectMapper json,
                                        @Value("${app.allowed-origin}") String allowedOrigin,
                                        @Value("${app.auth.max-attempts:20}") int maximumAttempts) throws Exception {
        return http
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/register", "/api/v1/auth/login", "/", "/index.html", "/assets/**").permitAll()
                        .anyRequest().authenticated())
                .logout(logout -> logout.disable())
                .addFilterBefore(new BrowserRequestProtection(json, allowedOrigin, maximumAttempts), CsrfFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(), AuthenticationErrors.error("AUTH_REQUIRED", "请先登录"));
                        })
                        .accessDeniedHandler((request, response, failure) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(), AuthenticationErrors.error("CSRF_REJECTED", "请求校验失败，请刷新后重试"));
                        }))
                .build();
    }
}

package com.agenticvideo.business.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
    private final JdbcTemplate database;
    private final PasswordEncoder passwords;
    private final AuthenticationManager authentication;

    public AuthenticationController(JdbcTemplate database, PasswordEncoder passwords, AuthenticationManager authentication) {
        this.database = database;
        this.passwords = passwords;
        this.authentication = authentication;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody Credentials credentials,
                                     HttpServletRequest request, HttpServletResponse response) {
        validatePassword(credentials.password());
        Authentication account;
        try {
            account = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                    normalize(credentials.username()), credentials.password()));
        } catch (AuthenticationException invalid) {
            throw new AuthFailure(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        if (request.getSession(false) != null) request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(account);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        return me(account);
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication account) {
        var user = database.queryForMap("SELECT id, normalized_username FROM users WHERE normalized_username = ?", account.getName());
        return response(Map.of("user", Map.of("id", user.get("id"), "username", user.get("normalized_username"))));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response, Authentication account) {
        new SecurityContextLogoutHandler().logout(request, response, account);
        return response(Map.of("loggedOut", true));
    }

    @GetMapping("/csrf")
    public Map<String, Object> csrf(CsrfToken token) {
        return response(Map.of("csrfToken", token.getToken()));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody Credentials credentials) {
        validatePassword(credentials.password());
        String username = normalize(credentials.username());
        if (username.isBlank() || username.length() > 64) {
            throw new AuthFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "用户名长度必须为 1 到 64 个字符");
        }
        UUID id = UUID.randomUUID();
        try {
            database.update("INSERT INTO users(id, normalized_username, password_hash) VALUES (?, ?, ?)",
                    id, username, passwords.encode(credentials.password()));
        } catch (DuplicateKeyException duplicate) {
            throw new AuthFailure(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已被使用");
        }
        return response(Map.of("user", Map.of("id", id, "username", username)));
    }

    static String normalize(String username) {
        return Normalizer.normalize(username.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AuthFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "密码编码后不能超过 72 字节");
        }
    }

    static Map<String, Object> response(Object data) {
        return Map.of("data", data, "requestId", UUID.randomUUID());
    }

    public record Credentials(@NotBlank @Size(max = 64) String username,
                              @NotBlank @Size(min = 8, max = 72) String password) {}

    public static class AuthFailure extends RuntimeException {
        final HttpStatus status;
        final String code;
        AuthFailure(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}

package com.fixloop.auth;

import java.time.Duration;
import java.util.UUID;

import com.fixloop.auth.dto.TokenResponse;
import com.fixloop.auth.dto.UserResponse;
import com.fixloop.common.RequestRateLimiter;
import com.fixloop.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity endpoints (bible 13.2). Thin by design: validate, rate limit, call
 * one service method, return a response object.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final int LOGIN_LIMIT = 10;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(5);
    private static final int REGISTER_LIMIT = 5;
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);
    private static final int REFRESH_LIMIT = 60;
    private static final Duration REFRESH_WINDOW = Duration.ofMinutes(5);
    private static final int LOGOUT_LIMIT = 60;
    private static final Duration LOGOUT_WINDOW = Duration.ofMinutes(5);
    private static final int PASSWORD_LIMIT = 10;
    private static final Duration PASSWORD_WINDOW = Duration.ofHours(1);

    private final AuthService authService;
    private final RequestRateLimiter rateLimiter;
    private final AuthProperties properties;

    public AuthController(AuthService authService, RequestRateLimiter rateLimiter, AuthProperties properties) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        limit("register:" + clientIp(http), REGISTER_LIMIT, REGISTER_WINDOW);
        return authService.register(request.email(), request.password(), request.fullName());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        limit("login:" + clientIp(http), LOGIN_LIMIT, LOGIN_WINDOW);
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        limit("refresh:" + clientIp(http), REFRESH_LIMIT, REFRESH_WINDOW);
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        limit("logout:" + clientIp(http), LOGOUT_LIMIT, LOGOUT_WINDOW);
        authService.logout(request.refreshToken());
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody ChangePasswordRequest request) {
        String userId = jwt.getSubject();
        limit("password:" + userId, PASSWORD_LIMIT, PASSWORD_WINDOW);
        authService.changePassword(toUserId(userId), request.currentPassword(), request.newPassword());
    }

    private void limit(String key, int limit, Duration window) {
        if (properties.rateLimitEnabled()) {
            rateLimiter.check(key, limit, window);
        }
    }

    /**
     * The client address for rate limiting. Behind a trusted proxy set
     * {@code TRUST_FORWARDED_FOR=true}; never trust the header by default,
     * because a client can send it itself.
     */
    private String clientIp(HttpServletRequest request) {
        if (properties.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static UUID toUserId(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw com.fixloop.common.ApiException.unauthenticated(
                "Your session is no longer valid. Please sign in again.");
        }
    }

    public record RegisterRequest(
            @NotBlank(message = "is required")
            @Email(message = "must be a valid email address")
            @Size(max = 254, message = "must be at most 254 characters")
            String email,
            @NotBlank(message = "is required")
            @Size(min = PasswordPolicy.MINIMUM_LENGTH, max = 200,
                message = "must be at least " + PasswordPolicy.MINIMUM_LENGTH + " characters")
            String password,
            @NotBlank(message = "is required")
            @Size(min = 2, max = 120, message = "must be between 2 and 120 characters")
            String fullName) {
    }

    public record LoginRequest(
            @NotBlank(message = "is required")
            @Size(max = 254, message = "must be at most 254 characters")
            String email,
            @NotBlank(message = "is required")
            @Size(max = 200, message = "must be at most 200 characters")
            String password) {
    }

    public record RefreshRequest(
            @NotBlank(message = "is required")
            @Size(max = 200, message = "is not valid")
            String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "is required")
            @Size(max = 200, message = "must be at most 200 characters")
            String currentPassword,
            @NotBlank(message = "is required")
            @Size(min = PasswordPolicy.MINIMUM_LENGTH, max = 200,
                message = "must be at least " + PasswordPolicy.MINIMUM_LENGTH + " characters")
            String newPassword) {
    }
}

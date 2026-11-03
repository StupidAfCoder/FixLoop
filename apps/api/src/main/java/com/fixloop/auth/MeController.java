package com.fixloop.auth;

import java.util.UUID;

import com.fixloop.auth.dto.UserResponse;
import com.fixloop.common.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own account, role and scope (bible 13.2, {@code GET /me}). */
@RestController
public class MeController {
    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/api/v1/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        try {
            return authService.me(UUID.fromString(jwt.getSubject()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ApiException.unauthenticated("Your session is no longer valid. Please sign in again.");
        }
    }
}

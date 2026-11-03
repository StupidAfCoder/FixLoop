package com.fixloop.auth.dto;

/**
 * The result of a successful login or refresh. The access token is short-lived;
 * the refresh token is single-use and rotated on every refresh.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String refreshToken) {

    public static TokenResponse bearer(String accessToken, long expiresInSeconds, String refreshToken) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds, refreshToken);
    }
}

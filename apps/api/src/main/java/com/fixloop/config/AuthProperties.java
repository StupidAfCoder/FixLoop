package com.fixloop.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the project's own authentication (bible 7.1: own JWT, with
 * no third-party auth dependency to fail on demo day).
 *
 * <p>The signing secret comes from {@code JWT_SECRET} and must never be
 * committed. When it is absent the application starts with an ephemeral key:
 * convenient for local work and tests, wrong for anything deployed, so the
 * startup log says so.
 *
 * <p>The three {@code Boolean} flags are nullable so that a missing property
 * falls back to the safe default instead of becoming {@code false}.
 */
@ConfigurationProperties("fixloop.auth")
public record AuthProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String jwtSecret,
        List<String> allowedEmailDomains,
        Boolean registrationEnabled,
        Boolean rateLimitEnabled,
        Boolean trustForwardedFor) {

    public AuthProperties {
        // A URI, not a bare word: Spring parses the `iss` claim as a URL, so a
        // non-URI issuer breaks typed accessors such as Jwt#getIssuer().
        issuer = isBlank(issuer) ? "https://fixloop.local" : issuer;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(14) : refreshTokenTtl;
        allowedEmailDomains = allowedEmailDomains == null ? List.of() : List.copyOf(allowedEmailDomains);
        registrationEnabled = registrationEnabled == null ? Boolean.TRUE : registrationEnabled;
        rateLimitEnabled = rateLimitEnabled == null ? Boolean.TRUE : rateLimitEnabled;
        trustForwardedFor = trustForwardedFor == null ? Boolean.FALSE : trustForwardedFor;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

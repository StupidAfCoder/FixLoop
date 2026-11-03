package com.fixloop.auth;

import java.time.Clock;
import java.time.Instant;

import com.fixloop.config.AuthConfiguration;
import com.fixloop.config.AuthProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues short-lived access tokens (bible 14.2). The token carries only what
 * authorisation needs: the user id, the role and the user's scope. It never
 * carries the email address or the reporter's identity.
 */
@Component
public class JwtService {
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAccessToken issue(UserAccount user) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(user.id().toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim(AuthConfiguration.ROLE_CLAIM, user.role().name());
        if (user.departmentId() != null) {
            claims.claim("dept", user.departmentId().toString());
        }
        if (user.scopeLocationId() != null) {
            claims.claim("scope", user.scopeLocationId().toString());
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedAccessToken(value, expiresAt);
    }

    public record IssuedAccessToken(String value, Instant expiresAt) {
    }
}

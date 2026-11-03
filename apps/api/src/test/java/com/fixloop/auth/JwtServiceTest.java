package com.fixloop.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.fixloop.config.AuthProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String SECRET = "a-test-only-signing-secret-of-at-least-32-bytes";

    private static AuthProperties properties(String issuer) {
        return new AuthProperties(issuer, Duration.ofMinutes(15), Duration.ofDays(1),
            SECRET, List.of("bmu.edu.in"), true, true, false);
    }

    private static SecretKey key() {
        return new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static JwtService service(AuthProperties properties) {
        return new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(key())),
            properties, java.time.Clock.systemUTC());
    }

    private static JwtDecoder decoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key())
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static UserAccount staffUser() {
        return new UserAccount(UUID.randomUUID(), "electrician@bmu.edu.in", "hash", "Staff Member",
            Role.STAFF, UUID.randomUUID(), null, true, false, true);
    }

    @Test
    void accessTokenCarriesIdentityRoleAndScopeButNotTheEmail() {
        AuthProperties properties = properties("fixloop-test");
        UserAccount user = staffUser();

        JwtService.IssuedAccessToken issued = service(properties).issue(user);
        Jwt decoded = decoder(properties).decode(issued.value());

        assertThat(decoded.getSubject()).isEqualTo(user.id().toString());
        assertThat(decoded.getClaimAsString("role")).isEqualTo("STAFF");
        assertThat(decoded.getClaimAsString("dept")).isEqualTo(user.departmentId().toString());
        assertThat(decoded.getClaimAsString("email")).isNull();
        assertThat(decoded.hasClaim("email")).isFalse();
        // Read the raw claim: Jwt#getIssuer() insists on a URL, and a deployment
        // may well configure a different issuer.
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("fixloop-test");
        assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
            .isEqualTo(Duration.ofMinutes(15));
        assertThat(issued.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void anAccessTokenWithAStudentRoleHasNoDepartmentClaim() {
        AuthProperties properties = properties("fixloop-test");
        UserAccount student = new UserAccount(UUID.randomUUID(), "student@bmu.edu.in", "hash",
            "Student", Role.STUDENT, null, null, false, false, true);

        Jwt decoded = decoder(properties).decode(service(properties).issue(student).value());

        assertThat(decoded.hasClaim("dept")).isFalse();
        assertThat(decoded.getClaimAsString("role")).isEqualTo("STUDENT");
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() {
        JwtService foreign = service(properties("some-other-issuer"));
        JwtService.IssuedAccessToken issued = foreign.issue(staffUser());

        assertThatThrownBy(() -> decoder(properties("fixloop-test")).decode(issued.value()))
            .isInstanceOf(JwtException.class);
    }
}

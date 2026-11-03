package com.fixloop.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Password hashing, clocks and the project's own HS256 JWT machinery. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {
    private static final Logger log = LoggerFactory.getLogger(AuthConfiguration.class);
    private static final int MINIMUM_SECRET_BYTES = 32;
    /** The single claim that carries authorisation. */
    public static final String ROLE_CLAIM = "role";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Strength 12 is deliberately slow; login is rate limited as well.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        String configured = properties.jwtSecret();
        if (configured == null || configured.isBlank()) {
            byte[] ephemeral = new byte[64];
            new SecureRandom().nextBytes(ephemeral);
            log.warn("JWT_SECRET is not set. Using an ephemeral signing key: every restart logs "
                + "all users out. Set JWT_SECRET (at least {} bytes) outside local development.",
                MINIMUM_SECRET_BYTES);
            return new SecretKeySpec(ephemeral, "HmacSHA256");
        }
        byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
        if (secret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT_SECRET must be at least " + MINIMUM_SECRET_BYTES + " bytes long.");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey signingKey) {
        JWKSource<SecurityContext> jwks = new ImmutableSecret<>(signingKey);
        return new NimbusJwtEncoder(jwks);
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey signingKey, AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /** Maps the {@code role} claim to a Spring Security authority, nothing more. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(ROLE_CLAIM);
            if (role == null || role.isBlank()) {
                return List.of();
            }
            return List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }
}

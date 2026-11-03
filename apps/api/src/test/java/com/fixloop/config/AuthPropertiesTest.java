package com.fixloop.config;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A missing property must never silently disable registration or rate limiting. */
class AuthPropertiesTest {

    @Test
    void fillsSafeDefaultsWhenNothingIsConfigured() {
        AuthProperties properties = new AuthProperties(null, null, null, null, null, null, null, null);

        assertThat(properties.issuer()).isEqualTo("https://fixloop.local");
        assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
        assertThat(properties.allowedEmailDomains()).isEqualTo(List.of());
        assertThat(properties.registrationEnabled()).isTrue();
        assertThat(properties.rateLimitEnabled()).isTrue();
        assertThat(properties.trustForwardedFor()).isFalse();
    }

    @Test
    void keepsConfiguredValues() {
        AuthProperties properties = new AuthProperties("fixloop-prod", Duration.ofMinutes(5),
            Duration.ofDays(7), "secret", List.of("bmu.edu.in"), false, false, true);

        assertThat(properties.issuer()).isEqualTo("fixloop-prod");
        assertThat(properties.registrationEnabled()).isFalse();
        assertThat(properties.trustForwardedFor()).isTrue();
        assertThat(properties.allowedEmailDomains()).containsExactly("bmu.edu.in");
    }
}

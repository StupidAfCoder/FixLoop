package com.fixloop.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestRateLimiterTest {

    /** A clock the test can move forward without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-11-01T10:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final RequestRateLimiter limiter = new RequestRateLimiter(clock);

    @Test
    void allowsUpToTheLimitThenRefuses() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(limiter.tryAcquire("login:1.2.3.4", 3, Duration.ofMinutes(5))).isTrue();
        }
        assertThat(limiter.tryAcquire("login:1.2.3.4", 3, Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void countsEachKeySeparately() {
        assertThat(limiter.tryAcquire("login:1.2.3.4", 1, Duration.ofMinutes(5))).isTrue();
        assertThat(limiter.tryAcquire("login:1.2.3.4", 1, Duration.ofMinutes(5))).isFalse();
        assertThat(limiter.tryAcquire("login:5.6.7.8", 1, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void startsAFreshWindowOnceTheOldOneExpires() {
        assertThat(limiter.tryAcquire("register:1.2.3.4", 1, Duration.ofHours(1))).isTrue();
        assertThat(limiter.tryAcquire("register:1.2.3.4", 1, Duration.ofHours(1))).isFalse();

        clock.advance(Duration.ofMinutes(61));

        assertThat(limiter.tryAcquire("register:1.2.3.4", 1, Duration.ofHours(1))).isTrue();
    }

    @Test
    void checkThrowsTheSharedErrorShape() {
        limiter.check("login:9.9.9.9", 1, Duration.ofMinutes(5));

        assertThatThrownBy(() -> limiter.check("login:9.9.9.9", 1, Duration.ofMinutes(5)))
            .isInstanceOf(ApiException.class)
            .satisfies(exception -> {
                ApiException api = (ApiException) exception;
                assertThat(api.status().value()).isEqualTo(429);
                assertThat(api.code()).isEqualTo("RATE_LIMITED");
            });
    }
}

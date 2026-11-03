package com.fixloop.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * A small fixed-window rate limiter for one application instance (bible 7.6:
 * "an in-memory bucket per user and IP, adequate for one instance").
 *
 * <p>It protects login, registration and complaint creation from casual abuse.
 * It is deliberately not a distributed limiter: if the API is ever scaled to
 * more than one instance, this must move to a shared store.
 */
@Component
public class RequestRateLimiter {
    /** Above this many tracked keys, expired windows are swept away. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RequestRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Throws 429 when the caller has exceeded {@code limit} calls in {@code window}. */
    public void check(String key, int limit, Duration window) {
        if (!tryAcquire(key, limit, window)) {
            throw ApiException.tooManyRequests("Too many attempts. Please wait a few minutes and try again.");
        }
    }

    /**
     * Records one attempt and reports whether it is within the limit.
     * Visible for tests.
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        Window updated = windows.compute(key, (ignored, existing) -> {
            if (existing == null || !now.isBefore(existing.start().plus(window))) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        if (windows.size() > SWEEP_THRESHOLD) {
            sweep(now, window);
        }
        return updated.count().get() <= limit;
    }

    private void sweep(Instant now, Duration window) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window candidate = iterator.next().getValue();
            if (!now.isBefore(candidate.start().plus(window))) {
                iterator.remove();
            }
        }
    }

    private record Window(Instant start, AtomicInteger count) {
    }
}

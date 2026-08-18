package com.supply.demandforecast.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * An in-memory token bucket, one per caller key.
 *
 * <p>Each key starts with a full bucket and refills continuously at {@code refillPerMinute}. A
 * request costs one token; a caller may burst up to {@code capacity} before it has to wait. This
 * is process-local and unsynchronised across instances — deliberately: it is a runaway-loop and
 * crude-flood guard, not a distributed quota, and pulling in Redis or a bucket library to make it
 * cluster-wide would buy precision this use does not need.
 *
 * <p>The clock is injectable so a test can observe refill without sleeping. Time is read from
 * {@link System#nanoTime()} rather than the wall clock, so a clock adjustment cannot make a bucket
 * refund tokens it should not.
 */
@Slf4j
@Component
public class TokenBucketRateLimiter {

    /**
     * Ceiling on tracked keys, a backstop against unbounded growth from a flood of distinct client
     * IPs. Every real principal and genuine client would fit many times over, so reaching it is
     * itself a sign of abuse. Idle (full) buckets are dropped first; only if none are idle is the
     * table cleared wholesale, which briefly hands active callers a fresh allowance rather than
     * letting the map grow without bound.
     */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final RateLimitProperties properties;
    private final LongSupplier nanoTime;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * The bean constructor, using the system clock.
     *
     * <p>Explicitly {@code @Autowired} because this class has a second, test-only constructor:
     * with more than one candidate and none marked, Spring looks for a no-arg constructor instead
     * and fails to instantiate the bean at all.
     */
    @Autowired
    public TokenBucketRateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    /** Package-private for tests: a controllable clock removes the need to sleep to observe refill. */
    TokenBucketRateLimiter(RateLimitProperties properties, LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    /**
     * Charges one token to {@code key}.
     *
     * @return an allowed decision, or a denial carrying the whole seconds until a token frees up
     */
    public Decision tryAcquire(String key) {
        evictIfNeeded();
        Bucket bucket = buckets.computeIfAbsent(
                key, k -> new Bucket(properties.capacity(), nanoTime.getAsLong()));

        synchronized (bucket) {
            refill(bucket);
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return Decision.allow();
            }
            long retryAfter = (long) Math.ceil((1.0 - bucket.tokens) / refillPerSecond());
            return Decision.deny(Math.max(1, retryAfter));
        }
    }

    private void refill(Bucket bucket) {
        long now = nanoTime.getAsLong();
        double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds <= 0) {
            return;
        }
        bucket.tokens = Math.min(properties.capacity(), bucket.tokens + elapsedSeconds * refillPerSecond());
        bucket.lastRefillNanos = now;
    }

    private double refillPerSecond() {
        return properties.refillPerMinute() / 60.0;
    }

    /**
     * Keeps the key table bounded. Runs only once it is full, and prefers to forget buckets sitting
     * at capacity — a caller not currently being limited loses nothing by being forgotten.
     */
    private void evictIfNeeded() {
        if (buckets.size() < MAX_TRACKED_KEYS) {
            return;
        }
        buckets.values().removeIf(bucket -> {
            synchronized (bucket) {
                refill(bucket);
                return bucket.tokens >= properties.capacity();
            }
        });
        if (buckets.size() >= MAX_TRACKED_KEYS) {
            log.warn("Rate-limit key table still at {} entries after eviction; clearing", buckets.size());
            buckets.clear();
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    /**
     * The outcome of a charge: allowed, or denied with a hint of how long to wait.
     *
     * <p>The factories are {@code allow}/{@code deny} rather than {@code allowed}/{@code denied}:
     * a no-arg method named {@code allowed()} would collide with the accessor the record generates
     * for its own {@code allowed} component, which javac rejects outright.
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {

        static Decision allow() {
            return new Decision(true, 0);
        }

        static Decision deny(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }
}

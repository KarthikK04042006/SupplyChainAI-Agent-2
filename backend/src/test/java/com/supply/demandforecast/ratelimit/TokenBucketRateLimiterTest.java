package com.supply.demandforecast.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TokenBucketRateLimiter}: the burst-then-refill arithmetic, driven by a clock the test
 * advances by hand so refill is observed without sleeping.
 *
 * <p>The properties here use small numbers — a capacity of 3, a token a second — because what is
 * under test is the shape of the behaviour, not the generous production figures.
 */
class TokenBucketRateLimiterTest {

    /** A hand-cranked nanosecond clock, so "a second passes" is an assignment, not a sleep. */
    private final AtomicLong nanos = new AtomicLong(0);

    private TokenBucketRateLimiter limiterWith(int capacity, int refillPerMinute) {
        RateLimitProperties props = new RateLimitProperties(true, capacity, refillPerMinute);
        return new TokenBucketRateLimiter(props, nanos::get);
    }

    private void advanceSeconds(long seconds) {
        nanos.addAndGet(seconds * 1_000_000_000L);
    }

    @Test
    void allowsUpToCapacityThenDenies() {
        TokenBucketRateLimiter limiter = limiterWith(3, 60);

        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();

        TokenBucketRateLimiter.Decision denied = limiter.tryAcquire("k");
        assertThat(denied.allowed()).isFalse();
        // The hint is a whole number of seconds, and never zero — a client that waits it out finds
        // at least one token has accrued.
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void refillsAsTimePasses() {
        TokenBucketRateLimiter limiter = limiterWith(3, 60); // one token per second

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k");
        }
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();

        advanceSeconds(1);

        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        // ...and only the one token accrued, so the very next call is refused again.
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();
    }

    @Test
    void refillIsCappedAtCapacity() {
        TokenBucketRateLimiter limiter = limiterWith(3, 60);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k");
        }
        // Idle far longer than it takes to refill the whole bucket: the burst is 3, not 3 + a
        // hundred, so a caller cannot bank credit by staying quiet.
        advanceSeconds(100);

        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();
    }

    @Test
    void keysAreLimitedIndependently() {
        TokenBucketRateLimiter limiter = limiterWith(1, 60);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        // A different caller has its own bucket and is unaffected by the first exhausting theirs.
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void aSlowerRefillMeansALongerWait() {
        TokenBucketRateLimiter limiter = limiterWith(1, 30); // a token every two seconds

        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        TokenBucketRateLimiter.Decision denied = limiter.tryAcquire("k");
        assertThat(denied.retryAfterSeconds()).isEqualTo(2);

        advanceSeconds(1);
        assertThat(limiter.tryAcquire("k").allowed()).isFalse(); // half a token is not enough
        advanceSeconds(1);
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
    }
}

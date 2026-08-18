package com.supply.demandforecast.ratelimit;

/**
 * Raised when a caller exceeds the request budget for a rate-limited endpoint.
 *
 * <p>Thrown from {@link RateLimitInterceptor#preHandle} — inside the {@code DispatcherServlet}, so
 * {@code GlobalExceptionHandler} shapes it into a 429 with a {@code Retry-After}. The whole-seconds
 * hint travels on the exception so the handler does not have to recompute it.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Rate limit exceeded; retry in %d second(s)".formatted(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

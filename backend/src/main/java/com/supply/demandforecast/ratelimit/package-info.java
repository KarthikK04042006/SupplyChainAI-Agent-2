/**
 * Abuse and input-size protection for the few endpoints that do real write or compute work per
 * call: the three CSV uploads and the on-demand inventory scan. A dependency-free in-memory
 * {@link com.supply.demandforecast.ratelimit.TokenBucketRateLimiter token bucket}, applied by a
 * {@link com.supply.demandforecast.ratelimit.RateLimitInterceptor handler interceptor} so a refused
 * request still flows through {@code GlobalExceptionHandler} as an RFC-7807 429 — a servlet filter
 * throwing would bypass that advice entirely.
 */
package com.supply.demandforecast.ratelimit;

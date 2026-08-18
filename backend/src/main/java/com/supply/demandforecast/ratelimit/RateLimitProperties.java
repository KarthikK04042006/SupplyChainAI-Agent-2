package com.supply.demandforecast.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token-bucket settings, bound from the {@code ratelimit} prefix.
 *
 * <p>Defaults are applied in the constructor rather than only in {@code application.yml}, so a
 * trimmed config or a test context still limits sensibly. The defaults are deliberately generous:
 * this guards against a runaway loop or a crude flood, not an operator clicking a button.
 *
 * @param enabled         whether the limiter runs at all. Off in the shared test context, where a
 *                        singleton limiter's buckets would otherwise carry counts across methods
 * @param capacity        bucket size, i.e. the largest burst a single caller may make before it
 *                        must wait for a refill
 * @param refillPerMinute tokens added per minute, i.e. the sustained rate once the burst is spent
 */
@ConfigurationProperties(prefix = "ratelimit")
public record RateLimitProperties(
        Boolean enabled,
        int capacity,
        int refillPerMinute
) {

    public RateLimitProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (capacity <= 0) {
            capacity = 20;
        }
        if (refillPerMinute <= 0) {
            refillPerMinute = 20;
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}

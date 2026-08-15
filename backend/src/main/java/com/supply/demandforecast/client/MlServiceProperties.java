package com.supply.demandforecast.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection and retry settings for the ML service.
 *
 * <p>Bound from the {@code ml.service} prefix. Defaults are set here rather than only in
 * {@code application.yml} so a slimmed-down config or a test context still produces a
 * working client.
 *
 * @param url            base URL of the ML service
 * @param connectTimeout milliseconds to establish a connection
 * @param readTimeout    milliseconds to wait for a response; model selection runs
 *                       walk-forward validation over several candidates, so this is
 *                       generous by design
 * @param maxAttempts    total attempts including the first, so 1 disables retries
 * @param retryBackoffMs base linear backoff, multiplied by the attempt number
 */
@ConfigurationProperties(prefix = "ml.service")
public record MlServiceProperties(
        String url,
        long connectTimeout,
        long readTimeout,
        int maxAttempts,
        long retryBackoffMs
) {

    public MlServiceProperties {
        if (url == null || url.isBlank()) {
            url = "http://ml-service:8000";
        }
        if (connectTimeout <= 0) {
            connectTimeout = 3_000;
        }
        if (readTimeout <= 0) {
            readTimeout = 60_000;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 3;
        }
        if (retryBackoffMs < 0) {
            retryBackoffMs = 250;
        }
    }
}

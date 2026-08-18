package com.supply.demandforecast.logging;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.IOException;

/**
 * Copies the current request's id onto every outbound ML call as an {@code X-Request-Id} header.
 *
 * <p>{@link RequestIdFilter} has already put the id in the MDC for anything running on a request
 * thread; this carries it across the process boundary so the ML service logs the same id and one
 * call can be followed through both services. Attached to the shared ML transport (see
 * {@link com.supply.demandforecast.config.AppConfig}) rather than to each client method, so the
 * {@code /health} GET and every POST are covered in one place — and because the value is per-call,
 * which a static {@code defaultHeader} cannot express.
 *
 * <p>When nothing is in scope — the scheduled scan runs on a background thread, not inside a
 * request — no header is sent and the ML service mints its own. A blank id is treated as absent, so
 * the downstream never has to tell an empty header from a missing one.
 */
public class RequestIdClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    @NonNull
    public ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
                                        @NonNull ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null && !requestId.isBlank()) {
            request.getHeaders().set(RequestIdFilter.HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}

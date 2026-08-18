package com.supply.demandforecast.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stamps every request with a request-id and puts it where the logging pattern can find it.
 *
 * <p>The id is taken from an inbound {@code X-Request-Id} header when one is present and sane,
 * so a call that has already been traced upstream keeps its identity; otherwise a fresh one is
 * minted. It is placed in the SLF4J {@link MDC} under {@value #MDC_KEY} — {@code logback-spring.xml}
 * prints it on every line — and echoed back on the response so a caller can quote it in a bug
 * report. {@link com.supply.demandforecast.config.AppConfig} reads the same MDC key to forward the
 * id to the ML service, so one request's log lines line up across both processes.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE}, ahead of Spring Security's filter chain
 * (registered at order {@code -100}). That ordering is the point: the authentication filter and the
 * access-denied handler log while they decide who a caller is, and those lines are only traceable
 * if the id is already in the MDC when they run. It also means the id is present for requests
 * Security rejects, which are exactly the ones someone tends to ask about.
 *
 * <p>An inbound id is trusted only if it matches {@link #SAFE_ID} — a bounded, printable token.
 * The value is written verbatim into log lines and a response header, so an unchecked one would let
 * a caller forge log entries with embedded newlines or smuggle a header-splitting sequence into the
 * response. Anything that fails the check is discarded and replaced rather than sanitised, because a
 * client sending a malformed trace id has nothing worth preserving.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter implements Ordered {

    /** Response and (accepted) inbound header. Matched case-insensitively on the way in. */
    public static final String HEADER = "X-Request-Id";

    /** MDC key the logging pattern and the ML transport both read. */
    public static final String MDC_KEY = "requestId";

    /**
     * What an inbound id may contain to be trusted: printable, unpunctuated, and bounded. Covers a
     * UUID and the common "svc-123"/opaque-token shapes without admitting whitespace — a newline in
     * particular would break the one-line-per-event contract the log pattern depends on.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String requestId = resolveRequestId(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled and reused: leaving the id behind would tag the next, unrelated
            // request on this thread with it. Cleared in a finally so a failure downstream cannot
            // skip the cleanup.
            MDC.remove(MDC_KEY);
        }
    }

    /** The inbound id if it is present and safe to echo, otherwise a fresh random one. */
    private static String resolveRequestId(String inbound) {
        if (inbound != null && SAFE_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }
}

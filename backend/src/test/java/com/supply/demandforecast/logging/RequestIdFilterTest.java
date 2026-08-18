package com.supply.demandforecast.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequestIdFilter}: what each request's log lines and its response header are tagged with.
 *
 * <p>The id is only useful if it is in the MDC <em>while</em> the request runs, so the chain here
 * captures {@code MDC.get} at the moment it is called rather than reading it after — the same
 * moment every downstream log line would. The response header is then expected to match what was
 * live in the MDC, which is the contract a caller quoting the header relies on.
 */
class RequestIdFilterTest {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesAndEchoesAnIdWhenNoneIsSent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] duringRequest = new String[1];

        filter.doFilter(request, response, capture(duringRequest));

        assertThat(duringRequest[0]).matches(UUID);
        // What the caller can quote matches what tagged the logs.
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(duringRequest[0]);
    }

    @Test
    void preservesASafeInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "trace-abc_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] duringRequest = new String[1];

        filter.doFilter(request, response, capture(duringRequest));

        assertThat(duringRequest[0]).isEqualTo("trace-abc_123");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("trace-abc_123");
    }

    @Test
    void replacesAnUnsafeInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Spaces make it unsafe to echo into a log line or a header; it must not survive.
        request.addHeader(RequestIdFilter.HEADER, "not a valid id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] duringRequest = new String[1];

        filter.doFilter(request, response, capture(duringRequest));

        assertThat(duringRequest[0]).matches(UUID);
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotEqualTo("not a valid id");
    }

    @Test
    void clearsTheMdcOnceTheRequestIsDone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), capture(new String[1]));

        // A pooled thread must not carry this request's id into the next one it serves.
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void runsAheadOfEverythingElse() {
        // Ordering is the whole point: the id has to be in the MDC before the security filters
        // (registered at -100) log their decisions.
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    /** A chain that records what was live in the MDC at the instant it ran. */
    private static FilterChain capture(String[] sink) {
        return (req, res) -> sink[0] = MDC.get(RequestIdFilter.MDC_KEY);
    }
}

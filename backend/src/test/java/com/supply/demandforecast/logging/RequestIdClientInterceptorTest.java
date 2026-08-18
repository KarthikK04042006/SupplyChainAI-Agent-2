package com.supply.demandforecast.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RequestIdClientInterceptor}: the seam that carries a request's id into the ML service.
 *
 * <p>The interceptor is the whole reason a trace id survives the hop between the two processes, so
 * it is tested directly rather than through a live client. What matters is narrow — is the header
 * set from the MDC, and is it left off when there is nothing to send — so the execution is stubbed
 * and the assertions are on the request it was handed.
 */
class RequestIdClientInterceptorTest {

    private final RequestIdClientInterceptor interceptor = new RequestIdClientInterceptor();
    private final byte[] body = "{}".getBytes();

    @AfterEach
    void clearMdc() {
        // The MDC is thread-local and this thread is reused across tests; a stray id would leak
        // into the next one's assertions.
        MDC.clear();
    }

    @Test
    void forwardsTheMdcRequestIdAsAHeader() throws IOException {
        MDC.put(RequestIdFilter.MDC_KEY, "req-123");
        MockClientHttpRequest request = postRequest();
        ClientHttpRequestExecution execution = stubbedExecution();

        ClientHttpResponse response = interceptor.intercept(request, body, execution);

        assertThat(request.getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo("req-123");
        // The request and body are passed through untouched apart from the added header, and the
        // execution's response is returned as-is.
        verify(execution).execute(same(request), eq(body));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sendsNoHeaderWhenNoRequestIdIsInScope() throws IOException {
        // Nothing in the MDC: a background thread, or a call outside any request.
        MockClientHttpRequest request = postRequest();
        ClientHttpRequestExecution execution = stubbedExecution();

        interceptor.intercept(request, body, execution);

        assertThat(request.getHeaders().getFirst(RequestIdFilter.HEADER)).isNull();
        verify(execution).execute(same(request), eq(body));
    }

    @Test
    void treatsABlankRequestIdAsAbsent() throws IOException {
        MDC.put(RequestIdFilter.MDC_KEY, "   ");
        MockClientHttpRequest request = postRequest();
        ClientHttpRequestExecution execution = stubbedExecution();

        interceptor.intercept(request, body, execution);

        assertThat(request.getHeaders().getFirst(RequestIdFilter.HEADER)).isNull();
    }

    private static MockClientHttpRequest postRequest() {
        return new MockClientHttpRequest(HttpMethod.POST, URI.create("http://ml-service:8000/forecast"));
    }

    private static ClientHttpRequestExecution stubbedExecution() throws IOException {
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any()))
                .thenReturn(new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        return execution;
    }
}

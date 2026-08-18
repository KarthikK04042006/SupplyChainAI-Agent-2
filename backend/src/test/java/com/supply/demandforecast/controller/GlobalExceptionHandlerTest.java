package com.supply.demandforecast.controller;

import com.supply.demandforecast.ratelimit.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two error translations that are awkward to reach through a live request.
 *
 * <p>{@code MaxUploadSizeExceededException} is raised by the servlet container's multipart parsing,
 * which {@code MockMvc} does not exercise, so its 413 mapping is asserted on the handler directly.
 * The 429's {@code Retry-After} wiring is checked here too — the {@code ResponseEntity} it must
 * return to carry a header is easy to get subtly wrong, and the value is worth pinning in isolation
 * from the token-bucket timing that produces it.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsAnOversizeMultipartTo413() {
        ProblemDetail problem = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(5_000_000));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(problem.getTitle()).isEqualTo("Upload too large");
    }

    @Test
    void attachesRetryAfterHeaderAndPropertyToA429() {
        ResponseEntity<ProblemDetail> response =
                handler.handleRateLimitExceeded(new RateLimitExceededException(42));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // The header is the machine-readable half; a client that reads only it still learns the wait.
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Too many requests");
        // ...and the body repeats it, so a JSON-only client need not parse headers.
        assertThat(problem.getProperties()).containsEntry("retryAfterSeconds", 42L);
    }
}

package com.supply.demandforecast.client;

import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.dto.MlForecastRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests {@link MlServiceClient} against stubbed HTTP responses rather than a mocked client.
 *
 * <p>Every other test in this suite replaces {@code MlServiceClient} with a Mockito mock,
 * which is right for testing the code that calls it but means the client's own handling of
 * a response was never exercised. Two defects lived in that gap: an error status served to
 * callers as a valid empty forecast, and a retry branch that could not be reached. Both were
 * invisible to a mock, because a mock returns whatever it was told to and never has a status
 * code at all.
 *
 * <p>{@link MockRestServiceServer} covers the seam these tests care about — status handling,
 * retry decisions, error propagation — but not the transport, since it substitutes the
 * request factory. The HTTP/1.1 pinning in {@code AppConfig} is therefore out of scope here.
 */
class MlServiceClientTest {

    private static final String BASE_URL = "http://ml-service:8000";
    private static final String FORECAST_URL = BASE_URL + "/forecast";

    /** FastAPI's validation error shape: no field in common with a ForecastResponse. */
    private static final String VALIDATION_ERROR = """
            {"detail":[{"type":"missing","loc":["body"],"msg":"Field required","input":null}]}""";

    private static final String VALID_FORECAST = """
            {"sku":"WIDGET-001","selected_model":"sarima","selection_reason":"lowest MAPE",
             "granularity":"daily","observations":180,"seasonality_detected":true,
             "scores":[],"points":[{"date":"2026-08-07","predicted":55.1,"lower":49.7,"upper":60.6}],
             "warnings":[],"anomalies":[]}""";

    private MockRestServiceServer server;
    private MlServiceClient client;

    /** Builds a client whose retries are instant, so a retry test does not sleep. */
    private void givenClient(int maxAttempts) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new MlServiceClient(
                builder.build(),
                new MlServiceProperties(BASE_URL, 1_000, 1_000, maxAttempts, 0));
    }

    private static MlForecastRequest request() {
        return MlForecastRequest.of("WIDGET-001", 14,
                List.of(new MlForecastRequest.DemandPoint(LocalDate.of(2026, 8, 1), 42.0)));
    }

    @Test
    void returnsTheForecastWhenTheServiceAnswersSuccessfully() {
        givenClient(3);
        server.expect(times(1), requestTo(FORECAST_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(VALID_FORECAST, MediaType.APPLICATION_JSON));

        ForecastResponse response = client.forecast(request());

        assertThat(response.sku()).isEqualTo("WIDGET-001");
        assertThat(response.selectedModel()).isEqualTo("sarima");
        assertThat(response.observations()).isEqualTo(180);
        assertThat(response.points()).hasSize(1);
        server.verify();
    }

    /**
     * The defect this class exists for: a 422 must not be served as an empty forecast.
     *
     * <p>Asserting only that it throws would still pass if the body bound to an all-null
     * record and tripped the empty-body check, so the upstream detail has to be carried
     * through — that is what distinguishes a real diagnosis from a generic failure.
     */
    @Test
    void rejectsAnErrorStatusRatherThanBindingTheErrorBodyToAForecast() {
        givenClient(3);
        server.expect(times(1), requestTo(FORECAST_URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(VALIDATION_ERROR));

        assertThatThrownBy(() -> client.forecast(request()))
                .isInstanceOf(MlServiceException.class)
                .hasMessageContaining("WIDGET-001")
                .hasMessageContaining("Field required")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(MlServiceException.class))
                .satisfies(e -> {
                    assertThat(e.getUpstreamStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getUpstreamBody()).contains("Field required");
                    assertThat(e.isUpstreamClientError()).isTrue();
                    assertThat(e.apiStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });

        server.verify();
    }

    /** A 4xx is the service objecting to the request, so replaying it only adds latency. */
    @Test
    void doesNotRetryAClientError() {
        givenClient(3);
        server.expect(times(1), requestTo(FORECAST_URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(VALIDATION_ERROR));

        assertThatThrownBy(() -> client.forecast(request()))
                .isInstanceOf(MlServiceException.class);

        // Fails if a second request was issued: the stub expects exactly one.
        server.verify();
    }

    /** A 5xx may be a restart or a GC pause, so it is worth the configured attempts. */
    @Test
    void retriesAServerErrorUpToTheConfiguredLimitThenReportsIt() {
        givenClient(3);
        server.expect(times(3), requestTo(FORECAST_URL))
                .andRespond(withServerError().body("upstream exploded"));

        assertThatThrownBy(() -> client.forecast(request()))
                .isInstanceOf(MlServiceException.class)
                .satisfies(thrown -> {
                    MlServiceException e = (MlServiceException) thrown;
                    assertThat(e.getUpstreamStatus())
                            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(e.isUpstreamClientError()).isFalse();
                    // A 5xx is the service's fault, not the caller's.
                    assertThat(e.apiStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        server.verify();
    }

    @Test
    void succeedsOnASecondAttemptAfterATransientServerError() {
        givenClient(3);
        server.expect(times(1), requestTo(FORECAST_URL))
                .andRespond(withServerError());
        server.expect(times(1), requestTo(FORECAST_URL))
                .andRespond(withSuccess(VALID_FORECAST, MediaType.APPLICATION_JSON));

        ForecastResponse response = client.forecast(request());

        assertThat(response.selectedModel()).isEqualTo("sarima");
        server.verify();
    }

    /** A 200 carrying nothing is a broken contract, not a forecast of zero days. */
    @Test
    void rejectsAnEmptyBodyOnASuccessfulStatus() {
        givenClient(1);
        server.expect(times(1), requestTo(FORECAST_URL))
                .andRespond(withSuccess().contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.forecast(request()))
                .isInstanceOf(MlServiceException.class)
                .hasMessageContaining("empty body");

        server.verify();
    }

    /** A health probe has a meaningful "no", so it answers false instead of throwing. */
    @Test
    void reportsUnhealthyRatherThanThrowingWhenTheHealthProbeFails() {
        givenClient(1);
        server.expect(times(1), requestTo(BASE_URL + "/health"))
                .andRespond(withServerError());

        assertThat(client.isHealthy()).isFalse();
        server.verify();
    }

    @Test
    void reportsHealthyWhenTheServiceSaysItIsUp() {
        givenClient(1);
        server.expect(times(1), requestTo(BASE_URL + "/health"))
                .andRespond(withSuccess("{\"status\":\"UP\",\"service\":\"ml-service\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.isHealthy()).isTrue();
        server.verify();
    }
}

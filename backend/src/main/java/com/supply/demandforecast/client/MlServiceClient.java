package com.supply.demandforecast.client;

import com.supply.demandforecast.dto.BatchForecastRequest;
import com.supply.demandforecast.dto.BatchForecastResponse;
import com.supply.demandforecast.dto.ExplainRequest;
import com.supply.demandforecast.dto.ExplainResponse;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.dto.MlForecastRequest;
import com.supply.demandforecast.dto.ReorderRecommendationResponse;
import com.supply.demandforecast.dto.ReorderRequest;
import com.supply.demandforecast.dto.RiskScanRequest;
import com.supply.demandforecast.dto.RiskScanResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.function.Supplier;

/**
 * REST client for the Python ML service, covering every forecasting, inventory, and
 * explainability endpoint.
 *
 * <p>The ML service is stateless: this backend passes it whatever data a call needs and
 * gets predictions or scores back. Nothing is cached here, so each call carries the
 * history it wants analysed.
 *
 * <p>Three behaviours are deliberate:
 *
 * <ul>
 *   <li><b>Retries are limited to transient failures.</b> A connect timeout or a 5xx may
 *       succeed on a second attempt; a 422 will not, because the request itself is what
 *       the service objected to. Replaying it would just add latency to a certain
 *       failure.
 *   <li><b>Upstream errors are propagated, not swallowed.</b> The ML service's status and
 *       body travel inside {@link MlServiceException} so the API can report what actually
 *       went wrong instead of a generic failure.
 *   <li><b>Only {@link #isHealthy()} returns a sentinel.</b> A health probe has a
 *       meaningful "no", so it answers false rather than throwing.
 * </ul>
 *
 * <p>The contract is OpenAPI-documented by FastAPI at {@code {base-url}/docs} and pinned
 * by {@code MlContractTest}.
 */
@Slf4j
@Component
public class MlServiceClient {

    private final RestClient restClient;
    private final int maxAttempts;
    private final long retryBackoffMs;

    public MlServiceClient(RestClient mlServiceRestClient, MlServiceProperties properties) {
        this.restClient = mlServiceRestClient;
        this.maxAttempts = properties.maxAttempts();
        this.retryBackoffMs = properties.retryBackoffMs();
    }

    /**
     * Pings the ML service's {@code /health} endpoint.
     *
     * @return {@code true} if the service responded with {@code status: UP}
     */
    public boolean isHealthy() {
        try {
            Map<?, ?> body = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);
            return body != null && "UP".equals(body.get("status"));
        } catch (Exception e) {
            log.warn("ML service health check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Requests a demand forecast for a single SKU. */
    public ForecastResponse forecast(MlForecastRequest request) {
        return post("/forecast", request, ForecastResponse.class, "forecast for SKU " + request.sku());
    }

    /**
     * Forecasts many SKUs in one round trip.
     *
     * <p>Per-SKU failures come back in {@link BatchForecastResponse#failures()} rather
     * than as an exception — one bad product must not sink the whole scan.
     */
    public BatchForecastResponse forecastBatch(BatchForecastRequest request) {
        int count = request.items() == null ? 0 : request.items().size();
        return post("/forecast/batch", request, BatchForecastResponse.class,
                "batch forecast for " + count + " SKU(s)");
    }

    /** Computes safety stock, reorder point, and order quantity for one SKU. */
    public ReorderRecommendationResponse reorderRecommendation(ReorderRequest request) {
        return post("/inventory/reorder-recommendation", request,
                ReorderRecommendationResponse.class,
                "reorder recommendation for SKU " + request.sku());
    }

    /** Classifies a batch of SKUs as OK, STOCKOUT_RISK, or OVERSTOCK. */
    public RiskScanResponse riskScan(RiskScanRequest request) {
        int count = request.items() == null ? 0 : request.items().size();
        return post("/inventory/risk-scan", request, RiskScanResponse.class,
                "risk scan for " + count + " SKU(s)");
    }

    /**
     * Decomposes one predicted day into per-feature SHAP contributions.
     *
     * <p>Only meaningful when XGBoost was the selected model; the ML service answers 422
     * otherwise, which surfaces here as an {@link MlServiceException} carrying its reason.
     */
    public ExplainResponse explain(String productId, ExplainRequest request) {
        return post("/explain/" + productId, request, ExplainResponse.class,
                "explanation for SKU " + productId);
    }

    /**
     * POSTs with bounded retries, translating every failure into
     * {@link MlServiceException}.
     *
     * @param description what was being attempted, for log and error messages
     */
    private <T> T post(String path, Object body, Class<T> responseType, String description) {
        T response = withRetries(() -> restClient.post()
                .uri(path)
                .body(body)
                .retrieve()
                .body(responseType), description);

        if (response == null) {
            // A 200 with no body is a broken contract, not an empty result. Returning
            // null here would push a NullPointerException into unrelated caller code.
            throw new MlServiceException("ML service returned an empty body for " + description);
        }
        return response;
    }

    /**
     * Runs {@code call}, retrying only failures that could plausibly succeed next time.
     *
     * <p>Backoff is linear rather than exponential: the ML service is an internal
     * neighbour on the same network, and these calls sit inside a request the user is
     * waiting on, so the goal is to ride out a restart or a GC pause, not to shed load
     * from a distant dependency.
     */
    private <T> T withRetries(Supplier<T> call, String description) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (HttpStatusCodeException e) {
                HttpStatusCode status = e.getStatusCode();
                String upstreamBody = e.getResponseBodyAsString();

                if (status.is4xxClientError()) {
                    log.warn("ML service rejected {}: {} {}", description, status, upstreamBody);
                    throw new MlServiceException(
                            "ML service rejected the " + description + ": " + describe(upstreamBody),
                            e, status, upstreamBody);
                }

                last = new MlServiceException(
                        "ML service failed the " + description + " with " + status,
                        e, status, upstreamBody);
                logRetry(attempt, description, status + " " + describe(upstreamBody));
            } catch (ResourceAccessException e) {
                // Connect or read timeout: no response at all, so no upstream status.
                last = new MlServiceException(
                        "ML service unreachable for the " + description, e);
                logRetry(attempt, description, e.getMessage());
            } catch (RestClientException e) {
                // Deserialization and other protocol faults. Retrying will not change a
                // malformed payload, so fail immediately.
                log.error("ML service response could not be processed for {}: {}",
                        description, e.getMessage());
                throw new MlServiceException(
                        "ML service response could not be processed for the " + description, e);
            }

            if (attempt < maxAttempts) {
                sleepBeforeRetry(attempt);
            }
        }

        log.error("ML service {} failed after {} attempt(s)", description, maxAttempts);
        throw last;
    }

    private void logRetry(int attempt, String description, String reason) {
        if (attempt < maxAttempts) {
            log.warn("ML service {} failed on attempt {}/{}, retrying: {}",
                    description, attempt, maxAttempts, reason);
        }
    }

    /** Sleeps between attempts, preserving the interrupt rather than discarding it. */
    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryBackoffMs * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MlServiceException("Interrupted while retrying the ML service call",
                    interrupted);
        }
    }

    /** Trims an upstream body to something loggable without dumping a whole payload. */
    private static String describe(String body) {
        if (body == null || body.isBlank()) {
            return "no detail supplied";
        }
        String flattened = body.replaceAll("\\s+", " ").trim();
        return flattened.length() > 500 ? flattened.substring(0, 500) + "..." : flattened;
    }
}

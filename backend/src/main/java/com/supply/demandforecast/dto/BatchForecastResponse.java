package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response from the ML service's {@code POST /forecast/batch}.
 *
 * <p>One SKU failing does not fail the batch — failures are reported alongside the
 * successful forecasts. Callers must therefore read {@link #failures()} rather than
 * assuming every requested SKU appears in {@link #forecasts()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchForecastResponse(
        int requested,
        int succeeded,
        int failed,
        List<ForecastResponse> forecasts,
        List<Failure> failures
) {

    public List<ForecastResponse> forecasts() {
        return forecasts == null ? List.of() : forecasts;
    }

    public List<Failure> failures() {
        return failures == null ? List.of() : failures;
    }

    /** A SKU that could not be forecast, and why. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(String sku, String error) {
    }
}

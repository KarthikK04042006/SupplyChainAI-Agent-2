package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.supply.demandforecast.domain.Severity;

import java.time.LocalDate;
import java.util.List;

/**
 * Forecast returned by the ML service and passed through to API clients.
 *
 * <p>This mirrors the current Pydantic {@code ForecastResponse}. The earlier version of
 * this record described the Part 1 service, which returned a single {@code method}
 * string and an {@code mae}; the service now selects between candidate models by
 * walk-forward validation and reports the whole comparison, so those two fields became
 * {@link #selectedModel()}, {@link #selectionReason()} and {@link #scores()}.
 *
 * <p>{@link JsonIgnoreProperties} is deliberate: the ML service is versioned separately
 * and may add response fields. A new field it starts returning should not break every
 * forecast call in this backend, whereas a *renamed* field still fails loudly in
 * {@code MlContractTest}, which is where that failure belongs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForecastResponse(
        String sku,
        @JsonProperty("selected_model") String selectedModel,
        @JsonProperty("selection_reason") String selectionReason,
        String granularity,
        int observations,
        @JsonProperty("seasonality_detected") boolean seasonalityDetected,
        List<ModelScore> scores,
        List<ForecastPoint> points,
        List<ForecastWarning> warnings,
        List<DemandAnomaly> anomalies
) {

    /** Never null, so callers can iterate without a null guard. */
    public List<ForecastWarning> warnings() {
        return warnings == null ? List.of() : warnings;
    }

    public List<DemandAnomaly> anomalies() {
        return anomalies == null ? List.of() : anomalies;
    }

    public List<ModelScore> scores() {
        return scores == null ? List.of() : scores;
    }

    public List<ForecastPoint> points() {
        return points == null ? List.of() : points;
    }

    /** A single forecast day with its prediction interval. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForecastPoint(
            LocalDate date,
            double predicted,
            double lower,
            double upper
    ) {
    }

    /**
     * How one candidate model scored in walk-forward validation.
     *
     * <p>Every metric is nullable: a model that failed to fit reports {@code error}
     * instead of scores, and the service still lists it so the comparison is auditable.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelScore(
            String model,
            Double mape,
            Double rmse,
            Double mae,
            Integer folds,
            Boolean fitted,
            String error
    ) {
    }

    /** A caveat attached to the forecast, such as a recent spike biasing the inputs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForecastWarning(
            String code,
            String message,
            Severity severity
    ) {
    }

    /** A day in the observed history that departed sharply from its own baseline. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DemandAnomaly(
            LocalDate date,
            String kind,
            double observed,
            double expected,
            double deviation,
            @JsonProperty("z_score") double zScore,
            Severity severity
    ) {
    }
}

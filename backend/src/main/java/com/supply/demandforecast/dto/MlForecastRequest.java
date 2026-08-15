package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * Request body for the ML service's {@code POST /forecast}.
 *
 * <p>The backend owns the sales history, so every call ships the series rather than
 * relying on state in the ML service.
 *
 * <p>{@link JsonInclude.Include#NON_NULL} matters here: the Python side supplies its own
 * defaults for the optional knobs, and sending explicit nulls would override them with
 * nothing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MlForecastRequest(
        String sku,
        @JsonProperty("horizon_days") int horizonDays,
        @JsonProperty("seasonal_periods") Integer seasonalPeriods,
        @JsonProperty("detect_anomalies") Boolean detectAnomalies,
        @JsonProperty("country_holidays") String countryHolidays,
        List<DemandPoint> history
) {

    /** Standard call: a weekly seasonal cycle with anomaly detection on. */
    public static MlForecastRequest of(String sku, int horizonDays, List<DemandPoint> history) {
        return new MlForecastRequest(sku, horizonDays, 7, true, null, history);
    }

    /** One observed day, as the ML service's {@code DemandPoint} expects it. */
    public record DemandPoint(LocalDate date, double quantity) {
    }
}

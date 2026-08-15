package com.supply.demandforecast.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Client-facing request for {@code POST /api/forecast/{sku}}.
 *
 * <p>Only tuning knobs live here — the history itself is loaded from the database
 * by the backend, never supplied by the caller.
 */
public record ForecastRequest(
        @Min(1) @Max(365) Integer horizonDays,
        @Min(2) @Max(365) Integer seasonalPeriods
) {
    public static final int DEFAULT_HORIZON_DAYS = 14;
    public static final int DEFAULT_SEASONAL_PERIODS = 7;

    public int horizonDaysOrDefault() {
        return horizonDays != null ? horizonDays : DEFAULT_HORIZON_DAYS;
    }

    public int seasonalPeriodsOrDefault() {
        return seasonalPeriods != null ? seasonalPeriods : DEFAULT_SEASONAL_PERIODS;
    }
}

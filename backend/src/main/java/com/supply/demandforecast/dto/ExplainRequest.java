package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body for the ML service's {@code POST /explain/{product_id}}.
 *
 * <p>{@code step} selects which forecast day to decompose. The ML service replays the
 * recursive feature walk to reach that day, so step 5 is explained against the features
 * it would actually see rather than against step 1's row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExplainRequest(
        List<MlForecastRequest.DemandPoint> history,
        @JsonProperty("horizon_days") int horizonDays,
        Integer step,
        @JsonProperty("top_n") Integer topN,
        @JsonProperty("seasonal_periods") Integer seasonalPeriods,
        @JsonProperty("country_holidays") String countryHolidays
) {

    /** Explains the first forecast day, returning the ten strongest drivers. */
    public static ExplainRequest of(int horizonDays, List<MlForecastRequest.DemandPoint> history) {
        return new ExplainRequest(history, horizonDays, 1, 10, 7, null);
    }
}

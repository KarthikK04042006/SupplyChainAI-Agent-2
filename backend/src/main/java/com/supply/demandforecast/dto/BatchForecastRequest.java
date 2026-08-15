package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body for the ML service's {@code POST /forecast/batch}.
 *
 * <p>Used by the inventory scan, which needs a forecast per SKU before it can judge
 * stock cover. One batch call keeps that to a single round trip.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchForecastRequest(
        List<Item> items,
        @JsonProperty("horizon_days") int horizonDays,
        @JsonProperty("seasonal_periods") Integer seasonalPeriods,
        String granularity,
        @JsonProperty("country_holidays") String countryHolidays
) {

    public static BatchForecastRequest of(List<Item> items, int horizonDays) {
        return new BatchForecastRequest(items, horizonDays, 7, "daily", null);
    }

    /** One SKU's history within the batch. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String sku,
            List<MlForecastRequest.DemandPoint> history,
            @JsonProperty("horizon_days") Integer horizonDays,
            String granularity,
            @JsonProperty("category_average") Double categoryAverage
    ) {
        public static Item of(String sku, List<MlForecastRequest.DemandPoint> history) {
            return new Item(sku, history, null, null, null);
        }
    }
}

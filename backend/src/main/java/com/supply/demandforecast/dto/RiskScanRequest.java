package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body for the ML service's {@code POST /inventory/risk-scan}.
 *
 * <p>{@code leadTimeDays} here is the batch default; a product with its own supplier
 * lead time overrides it per item. That distinction matters — an imported SKU on 21 days
 * and a local one on 7 are not comparably safe at the same days of cover.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RiskScanRequest(
        List<Item> items,
        @JsonProperty("lead_time_days") double leadTimeDays,
        @JsonProperty("overstock_threshold_days") Double overstockThresholdDays,
        @JsonProperty("review_period_days") Double reviewPeriodDays
) {

    /** One SKU's stock position and forecast. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String sku,
            @JsonProperty("current_stock") double currentStock,
            @JsonProperty("on_order") Double onOrder,
            @JsonProperty("forecast_daily_demand") List<Double> forecastDailyDemand,
            @JsonProperty("lead_time_days") Double leadTimeDays,
            @JsonProperty("safety_stock") Double safetyStock
    ) {
    }
}

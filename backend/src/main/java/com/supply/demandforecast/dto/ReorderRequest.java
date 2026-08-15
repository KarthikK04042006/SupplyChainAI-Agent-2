package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request body for the ML service's {@code POST /inventory/reorder-recommendation}.
 *
 * <p>Optional fields are null by default so the ML service applies its own defaults.
 * Note that the ML service ignores unknown JSON keys, so a misspelled field here would
 * be silently dropped rather than rejected — the field names below are taken from the
 * live schema and are pinned by {@code MlContractTest}.
 *
 * @param costs when supplied, the recommendation is EOQ-based; otherwise the service
 *              falls back to a coverage heuristic and says so in {@code methodNote}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReorderRequest(
        String sku,
        @JsonProperty("current_stock") double currentStock,
        @JsonProperty("on_order") Double onOrder,
        @JsonProperty("lead_time_days") double leadTimeDays,
        @JsonProperty("lead_time_std_days") Double leadTimeStdDays,
        @JsonProperty("service_level") Double serviceLevel,
        @JsonProperty("forecast_daily_demand") List<Double> forecastDailyDemand,
        @JsonProperty("demand_std_override") Double demandStdOverride,
        @JsonProperty("demand_history") List<MlForecastRequest.DemandPoint> demandHistory,
        @JsonProperty("review_period_days") Double reviewPeriodDays,
        Costs costs,
        @JsonProperty("default_coverage_days") Double defaultCoverageDays,
        @JsonProperty("min_order_quantity") Double minOrderQuantity,
        @JsonProperty("order_multiple") Double orderMultiple
) {

    /**
     * Holding and ordering costs.
     *
     * <p>Holding cost can be given directly per unit per year, or derived from
     * {@code unitCost} and {@code holdingCostRate}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Costs(
            @JsonProperty("ordering_cost") Double orderingCost,
            @JsonProperty("holding_cost_per_unit_year") Double holdingCostPerUnitYear,
            @JsonProperty("unit_cost") Double unitCost,
            @JsonProperty("holding_cost_rate") Double holdingCostRate
    ) {
    }
}

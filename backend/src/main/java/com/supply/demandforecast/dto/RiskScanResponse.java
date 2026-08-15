package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.supply.demandforecast.domain.RiskStatus;
import com.supply.demandforecast.domain.Severity;

import java.util.List;

/** Response from the ML service's {@code POST /inventory/risk-scan}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskScanResponse(
        Summary summary,
        List<Assessment> assessments
) {

    public List<Assessment> assessments() {
        return assessments == null ? List.of() : assessments;
    }

    /**
     * One SKU's verdict, ordered most urgent first by the ML service.
     *
     * <p>The nullable fields are status-specific rather than merely optional:
     * {@code excessUnits} is only meaningful for an overstock, {@code
     * projectedShortfallUnits} only for a stockout risk, and {@code daysUntilStockout}
     * is null when forecast demand is zero — there is no date to project, which is a
     * different statement from projecting a stockout today.
     *
     * @param riskScore ranks urgency within a status; higher is more urgent
     * @param reason    plain-language justification, suitable for an alert message
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assessment(
            String sku,
            RiskStatus status,
            Severity severity,
            @JsonProperty("risk_score") double riskScore,
            @JsonProperty("days_of_supply") Double daysOfSupply,
            @JsonProperty("days_until_stockout") Double daysUntilStockout,
            @JsonProperty("excess_days_of_supply") Double excessDaysOfSupply,
            @JsonProperty("excess_units") Double excessUnits,
            @JsonProperty("projected_shortfall_units") Double projectedShortfallUnits,
            @JsonProperty("inventory_position") double inventoryPosition,
            @JsonProperty("average_daily_demand") double averageDailyDemand,
            @JsonProperty("risk_window_days") double riskWindowDays,
            String reason
    ) {
    }

    /** Batch tallies, so a dashboard can show counts without recounting client-side. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(
            int total,
            int ok,
            @JsonProperty("stockout_risk") int stockoutRisk,
            int overstock
    ) {
    }
}

package com.supply.demandforecast.dto;

import java.time.Instant;
import java.util.Map;

/**
 * The four KPI cards on the overview page.
 *
 * <p>Each figure carries the size of the sample behind it. A catalogue where only a third of the
 * SKUs are priced can still report an inventory value, but reporting it as <em>the</em> inventory
 * value would understate the total by two thirds with nothing on screen to say so. The card shows
 * the coverage next to the number instead.
 *
 * @param generatedAt when these figures were read, so a stale open tab is identifiable
 */
public record DashboardSummary(
        long totalSkus,
        Alerts alerts,
        InventoryValue inventoryValue,
        Accuracy forecastAccuracy,
        Instant generatedAt
) {

    /**
     * Open alert counts.
     *
     * @param bySeverity keyed by {@code Severity} name, always with an entry per severity — the
     *                   cards render a fixed set, and an absent key would mean a missing row
     *                   rather than a zero
     */
    public record Alerts(long open, Map<String, Long> bySeverity) {
    }

    /**
     * Stock valued at unit cost.
     *
     * @param amount       summed over priced SKUs only
     * @param skusPriced   how many SKUs contributed
     * @param skusUnpriced how many were left out for having no unit cost. Null cost means "not
     *                     priced", so folding it in as zero would claim free inventory
     */
    public record InventoryValue(double amount, long skusPriced, long skusUnpriced) {
    }

    /**
     * Forecast accuracy across scored SKUs.
     *
     * @param averagePercent {@code 100 - mean MAPE}, floored at zero, or null when nothing has
     *                       been scored yet. Null renders as "not yet scored"; zero would claim
     *                       every forecast was wrong
     * @param skusScored     the sample behind the average
     * @param lastScoredAt   when the most recent scan scored a SKU
     */
    public record Accuracy(Double averagePercent, long skusScored, Instant lastScoredAt) {
    }
}

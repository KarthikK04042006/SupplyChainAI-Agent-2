package com.supply.demandforecast.domain;

/**
 * What an alert is about.
 *
 * <p>Distinct from {@link RiskStatus}: a status describes a SKU's current inventory
 * position, whereas a type describes why a row was written to the alert table. Most
 * types derive from a scan verdict, but {@link #DEMAND_ANOMALY} comes from the demand
 * history rather than from stock, so the two enums are not interchangeable.
 */
public enum AlertType {

    /** Projected to run out inside the protection period. */
    STOCKOUT_RISK,

    /** Excess cover well beyond the overstock threshold. */
    OVERSTOCK,

    /** A spike or drop in recorded history that may be biasing the forecast. */
    DEMAND_ANOMALY;

    /**
     * Maps a scan verdict onto the alert type it justifies.
     *
     * @return {@code null} for {@link RiskStatus#OK}, which is not worth an alert
     */
    public static AlertType fromRiskStatus(RiskStatus status) {
        return switch (status) {
            case STOCKOUT_RISK -> STOCKOUT_RISK;
            case OVERSTOCK -> OVERSTOCK;
            case OK -> null;
        };
    }
}

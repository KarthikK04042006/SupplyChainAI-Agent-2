package com.supply.demandforecast.domain;

/**
 * Verdict the ML service returns for one SKU in a risk scan.
 *
 * <p>Already uppercase on the wire, so no spelling translation is needed here.
 */
public enum RiskStatus {

    /** Cover is adequate over the protection period. */
    OK,

    /** Projected to run out inside the lead time plus review period. */
    STOCKOUT_RISK,

    /** Holding materially more cover than the overstock threshold allows. */
    OVERSTOCK
}

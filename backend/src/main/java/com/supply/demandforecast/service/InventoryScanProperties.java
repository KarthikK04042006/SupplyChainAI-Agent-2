package com.supply.demandforecast.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the inventory risk scan, bound from the {@code inventory.scan} prefix.
 *
 * <p>Defaults are applied in the constructor rather than only in {@code application.yml}, so
 * a trimmed config or a test context still scans sensibly.
 *
 * @param riskThreshold          minimum risk score that earns an alert. The ML service scores
 *                               0–100 and flags anything off-target, including mild cases; a
 *                               floor here is what keeps the alert list actionable rather than
 *                               a mirror of the catalogue
 * @param horizonDays            days of demand to forecast before judging stock cover
 * @param defaultLeadTimeDays    lead time for a product that has none of its own
 * @param overstockThresholdDays days of cover above which stock counts as excess
 * @param reviewPeriodDays       how often replenishment is reviewed, which extends the window
 *                               a stockout can open in
 * @param autoResolve            whether an alert whose problem has cleared is closed
 *                               automatically. On by default: a scan that only ever opens
 *                               alerts leaves a list nobody trusts
 * @param enabled                whether the periodic scan runs. The on-demand endpoint ignores
 *                               this
 * @param interval               fixed delay between scheduled scans, as an ISO-8601 duration
 *                               or Spring duration string
 * @param initialDelay           quiet period before the first scheduled scan
 *
 * <p>{@code enabled}, {@code interval} and {@code initialDelay} are resolved by {@code
 * @Scheduled} from the raw property placeholders — annotation attributes must be constants, so
 * they cannot read this record. They are declared here anyway: bound but undeclared properties
 * are what {@code spring.config.import} strict mode and IDE completion both miss, and a
 * misspelled {@code inteval} would otherwise fail silently at the annotation instead.
 */
@ConfigurationProperties(prefix = "inventory.scan")
public record InventoryScanProperties(
        double riskThreshold,
        int horizonDays,
        double defaultLeadTimeDays,
        double overstockThresholdDays,
        double reviewPeriodDays,
        Boolean autoResolve,
        Boolean enabled,
        String interval,
        String initialDelay
) {

    public InventoryScanProperties {
        if (riskThreshold <= 0) {
            riskThreshold = 25.0;
        }
        if (horizonDays <= 0) {
            horizonDays = 14;
        }
        if (defaultLeadTimeDays <= 0) {
            defaultLeadTimeDays = 7.0;
        }
        if (overstockThresholdDays <= 0) {
            overstockThresholdDays = 60.0;
        }
        if (reviewPeriodDays < 0) {
            reviewPeriodDays = 7.0;
        }
        if (autoResolve == null) {
            autoResolve = true;
        }
        if (enabled == null) {
            enabled = true;
        }
    }

    public boolean autoResolveEnabled() {
        return Boolean.TRUE.equals(autoResolve);
    }
}

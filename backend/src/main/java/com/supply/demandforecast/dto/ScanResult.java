package com.supply.demandforecast.dto;

import java.time.Instant;
import java.util.List;

/**
 * What one inventory scan did, returned by {@code GET /api/inventory/scan} and logged by the
 * scheduled job.
 *
 * <p>Reports the alert bookkeeping, not just the ML verdicts: the caller needs to know which
 * problems are new since the last scan and which cleared. {@code RiskScanResponse.Summary}
 * covers the classification counts, so those are not restated here.
 *
 * @param scannedAt      when the scan ran
 * @param productsScanned products with enough history to assess
 * @param productsSkipped products left out, with the reason — a SKU whose forecast failed is
 *                        not the same as one that is fine, and silently dropping it would
 *                        read as the latter
 * @param alertsOpened   alerts that did not exist before this scan
 * @param alertsUpdated  standing alerts whose severity or score moved
 * @param alertsResolved alerts closed because their problem no longer appears
 * @param openAlerts     every alert now open, most urgent first
 */
public record ScanResult(
        Instant scannedAt,
        int productsScanned,
        List<Skipped> productsSkipped,
        int alertsOpened,
        int alertsUpdated,
        int alertsResolved,
        List<AlertView> openAlerts
) {

    /** A product the scan could not judge, and why. */
    public record Skipped(String sku, String reason) {
    }
}

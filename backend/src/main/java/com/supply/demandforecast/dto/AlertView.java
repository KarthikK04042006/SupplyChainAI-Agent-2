package com.supply.demandforecast.dto;

import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.entity.Alert;

import java.time.Instant;

/**
 * An alert flattened for the wire, used by both {@code GET /api/alerts} and the open-alert list
 * in {@link ScanResult}.
 *
 * <p>Separate from the {@link Alert} entity so a schema change does not silently alter the API,
 * and so lazily-loaded state can never leak into serialization.
 *
 * <p>The resolution fields are always present, and null on an open alert — which is what open
 * means. One shape for both states keeps a client from having to branch on which it received.
 *
 * @param occurrences   how many scans have reported this problem; 1 is new, 40 is standing
 * @param updatedAt     last time a scan restated this, so a stale alert reads as stale
 */
public record AlertView(
        Long id,
        String sku,
        AlertType type,
        Severity severity,
        String message,
        double riskScore,
        Double daysOfSupply,
        Double inventoryPosition,
        int occurrences,
        boolean resolved,
        Instant resolvedAt,
        String resolvedBy,
        String resolutionNote,
        Instant createdAt,
        Instant updatedAt
) {

    public static AlertView from(Alert alert) {
        return new AlertView(
                alert.getId(),
                alert.getSku(),
                alert.getType(),
                alert.getSeverity(),
                alert.getMessage(),
                alert.getRiskScore(),
                alert.getDaysOfSupply(),
                alert.getInventoryPosition(),
                alert.getOccurrences(),
                alert.isResolved(),
                alert.getResolvedAt(),
                alert.getResolvedBy(),
                alert.getResolutionNote(),
                alert.getCreatedAt(),
                alert.getUpdatedAt());
    }
}

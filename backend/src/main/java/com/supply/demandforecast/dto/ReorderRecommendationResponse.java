package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response from the ML service's {@code POST /inventory/reorder-recommendation}.
 *
 * <p>{@link #intermediates()} is the point of this endpoint: it carries every step of
 * the calculation with its formula, so a dashboard can explain why a quantity was
 * recommended instead of only showing the number.
 *
 * @param eoq null when no costs were supplied, in which case {@code orderingStrategy}
 *            reports the coverage heuristic instead
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReorderRecommendationResponse(
        String sku,
        Decision decision,
        List<Intermediate> intermediates,
        @JsonProperty("suggested_order") boolean suggestedOrder,
        Double eoq,
        @JsonProperty("ordering_strategy") String orderingStrategy,
        @JsonProperty("method_note") String methodNote
) {

    public List<Intermediate> intermediates() {
        return intermediates == null ? List.of() : intermediates;
    }

    /** The numbers a planner acts on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(
            @JsonProperty("order_quantity") double orderQuantity,
            @JsonProperty("reorder_point") double reorderPoint,
            @JsonProperty("safety_stock") double safetyStock,
            @JsonProperty("inventory_position") double inventoryPosition
    ) {
    }

    /** One step of the calculation, with the formula that produced it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Intermediate(
            String name,
            String label,
            double value,
            String unit,
            String formula,
            String description
    ) {
    }
}

package com.supply.demandforecast.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A stockable item, identified by its SKU.
 *
 * <p>Demand history and forecasts are keyed by SKU rather than by this entity's
 * surrogate id, so history can be ingested for a SKU before its product record
 * exists. See {@link DemandHistory}.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(length = 64)
    private String category;

    /** Days between placing a replenishment order and receiving it. */
    @PositiveOrZero
    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;

    /** Stock level that should trigger a reorder. */
    @PositiveOrZero
    @Column(name = "reorder_point")
    private Integer reorderPoint;

    /**
     * Units physically on hand.
     *
     * <p>Kept on the product rather than in a separate movements table: this system reads a
     * stock position, it does not keep a ledger. A real warehouse integration would replace
     * this field with a projection over receipts and issues.
     */
    @PositiveOrZero
    @Builder.Default
    @Column(name = "current_stock", nullable = false)
    private double currentStock = 0;

    /**
     * Units ordered from the supplier but not yet received.
     *
     * <p>Counted separately from {@link #currentStock} because the risk scan needs both:
     * inventory position is on-hand plus on-order, and a SKU with nothing on the shelf but
     * a delivery inbound is not the same emergency as one with neither.
     */
    @PositiveOrZero
    @Builder.Default
    @Column(name = "on_order", nullable = false)
    private double onOrder = 0;

    /**
     * Buffer held against demand and lead-time variability.
     *
     * <p>Null means "not set", which the risk scan treats differently from zero — zero is a
     * deliberate decision to hold no buffer.
     */
    @PositiveOrZero
    @Column(name = "safety_stock")
    private Double safetyStock;

    /**
     * Cost of one unit, in whatever single currency the deployment uses.
     *
     * <p>Null means "not priced", which the inventory-value KPI reports as an unpriced SKU
     * count rather than folding into the total as zero — a catalogue half-priced would
     * otherwise show a confident figure that understates by half.
     *
     * <p>Also what makes an EOQ-based reorder recommendation possible: the ML service's
     * {@code Costs} block derives holding cost from a unit cost, and falls back to a coverage
     * heuristic without one.
     */
    @PositiveOrZero
    @Column(name = "unit_cost")
    private Double unitCost;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void applyDefaults() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

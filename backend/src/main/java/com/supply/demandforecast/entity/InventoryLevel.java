package com.supply.demandforecast.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * How much of one SKU sits in one warehouse.
 *
 * <p>Keyed by SKU string rather than a foreign key to {@link Product}, matching {@link
 * DemandHistory}: stock can be imported for a SKU whose product record has not been created
 * yet, and the two files can then arrive in either order.
 *
 * <p>The unique constraint on {@code (sku, warehouse_id)} makes ingest idempotent — a
 * re-uploaded stock file restates quantities instead of accumulating rows, which for a
 * quantity would be silently wrong rather than merely untidy.
 *
 * <p>{@link Product#getCurrentStock()} remains the authoritative catalogue-wide figure and is
 * recomputed from these rows on import. The risk scan reads the product total, so it sees a
 * consistent number whether or not stock has ever been broken down by location.
 */
@Entity
@Table(
        name = "inventory_levels",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inventory_sku_warehouse",
                columnNames = {"sku", "warehouse_id"}
        ),
        indexes = @Index(name = "idx_inventory_sku", columnList = "sku")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, length = 64)
    private String sku;

    /**
     * Lazy: the heatmap loads warehouses once and joins in memory, so eagerly fetching one
     * per row would issue a query per cell.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    /** Units physically on hand at this location. */
    @PositiveOrZero
    @Builder.Default
    @Column(name = "quantity_on_hand", nullable = false)
    private double quantityOnHand = 0;

    /**
     * Units inbound to this location.
     *
     * <p>Tracked per location because a delivery arriving at the wrong warehouse does not
     * relieve a shortage at the right one, even though the catalogue-wide totals match.
     */
    @PositiveOrZero
    @Builder.Default
    @Column(name = "on_order", nullable = false)
    private double onOrder = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}

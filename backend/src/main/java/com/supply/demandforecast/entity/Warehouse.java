package com.supply.demandforecast.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A stocking location.
 *
 * <p>Identified by {@link #code} — a short handle like {@code WH-EAST} that appears in the
 * inventory CSV and in the dashboard's column headers. The surrogate id exists because
 * {@link InventoryLevel} points here with a foreign key; nothing else refers to a warehouse
 * by number.
 *
 * <p>Created implicitly by the inventory import rather than through a separate endpoint: the
 * set of warehouses is a property of the stock file being uploaded, and asking an operator to
 * declare them first would only add a step that could disagree with the data.
 */
@Entity
@Table(name = "warehouses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    /**
     * Display name, defaulting to the code when the CSV gives no name column.
     *
     * <p>Never null, so a dashboard can render it without falling back to the code itself.
     */
    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(length = 64)
    private String region;

    /**
     * Whether this location is in service.
     *
     * <p>Kept so a warehouse can be retired without deleting its stock history. The heatmap
     * shows active locations only; the rows themselves survive.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void applyDefaults() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (name == null || name.isBlank()) {
            name = code;
        }
    }
}

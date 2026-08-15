package com.supply.demandforecast.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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

import java.time.LocalDate;

/**
 * One day of observed demand for one SKU.
 *
 * <p>Keyed by SKU string rather than a foreign key to {@link Product} so that a CSV
 * import can land history for SKUs that have no product record yet. The unique
 * constraint on {@code (sku, demand_date)} makes ingest idempotent: re-importing
 * the same file updates quantities instead of duplicating rows.
 */
@Entity
@Table(
        name = "demand_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_demand_sku_date",
                columnNames = {"sku", "demand_date"}
        ),
        indexes = @Index(name = "idx_demand_sku_date", columnList = "sku, demand_date")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DemandHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, length = 64)
    private String sku;

    @NotNull
    @Column(name = "demand_date", nullable = false)
    private LocalDate demandDate;

    @PositiveOrZero
    @Column(nullable = false)
    private double quantity;
}

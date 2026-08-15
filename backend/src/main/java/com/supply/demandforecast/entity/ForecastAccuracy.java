package com.supply.demandforecast.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * How well the chosen model scored for one SKU, as of the last scan.
 *
 * <p>Written by {@code InventoryScanService}, which already forecasts the whole catalogue in
 * one batch call and receives {@code ForecastResponse.scores} alongside each prediction. The
 * accuracy KPI therefore costs no extra ML calls and refreshes on the same schedule as the
 * alerts.
 *
 * <p>Its own table rather than more columns on {@link Product}: a product is stock position
 * and supplier terms, and how a model scored last Tuesday is neither.
 *
 * <p>Every metric is nullable because a fitted model may report only some of them, and a SKU
 * can be forecast without being scored at all — {@code scores} is empty when the service had
 * too little history to run walk-forward validation. Null means "not measured", which the
 * dashboard reports as a smaller sample rather than as a zero dragging the average down.
 */
@Entity
@Table(name = "forecast_accuracy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForecastAccuracy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    /** Mean absolute percentage error, 0-100+. The accuracy KPI is derived from this. */
    @Column(name = "mape")
    private Double mape;

    @Column(name = "rmse")
    private Double rmse;

    @Column(name = "mae")
    private Double mae;

    /** Which candidate the service picked, so a poor score can be attributed. */
    @Column(name = "selected_model", length = 64)
    private String selectedModel;

    /** Observations behind the score — 400 days scoring 12% is a stronger claim than 20 days. */
    @Column(name = "observations")
    private Integer observations;

    @Column(name = "scored_at", nullable = false)
    private Instant scoredAt;
}

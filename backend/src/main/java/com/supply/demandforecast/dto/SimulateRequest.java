package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * A what-if shock to run against a product's forecast, for {@code POST /api/simulate}.
 *
 * <p>Two independent levers, either or both of which may be set:
 *
 * <ul>
 *   <li>a <b>demand multiplier</b> applied over a date window — {@code 1.30} models a promotion
 *       lifting demand 30% for a fortnight;
 *   <li>a <b>lead-time override</b> — modelling a slower or faster supplier for this run only.
 * </ul>
 *
 * <p>Nothing here is persisted: the shock perturbs an in-memory copy of the forecast and the
 * recommendation is recomputed from it. The demand window is anchored to the forecast's own
 * calendar days, which begin the day after the product's most recent recorded demand — so
 * {@code windowStartDate} is matched against those forecast dates, not against "today".
 *
 * <p>Validation enforces that a shock is actually described: an all-null body is rejected, and
 * the demand multiplier and its window travel together (one without the other is a no-op the
 * caller almost certainly did not intend).
 */
public record SimulateRequest(

        @NotBlank
        String sku,

        /** {@code 1.30} = +30%. Positive; a multiplier of zero would model "no demand", which
         *  is better expressed as a separate scenario than smuggled through here. */
        @Positive
        Double demandMultiplier,

        /** First forecast day the multiplier applies to, matched against the forecast's dates. */
        LocalDate windowStartDate,

        /** Length of the shock window in days, starting at {@code windowStartDate}. */
        @Min(1) @Max(365)
        Integer windowDays,

        /** Replacement lead time in days for this run. The ML service requires a positive lead
         *  time, so zero is rejected here rather than earning a 422 downstream. */
        @Positive @Max(365)
        Double leadTimeDaysOverride
) {

    /**
     * At least one lever must be pulled — an empty body has nothing to simulate.
     *
     * <p>A getter-style {@code @AssertTrue} rather than a class-level annotation: it needs no
     * extra constraint type, and reads as the rule it encodes. {@code @JsonIgnore} keeps it off
     * the wire — it is a validation predicate, not a field.
     */
    @JsonIgnore
    @AssertTrue(message = "specify at least one shock: a demandMultiplier (with its window) "
            + "and/or a leadTimeDaysOverride")
    public boolean isShockPresent() {
        return demandMultiplier != null || leadTimeDaysOverride != null;
    }

    /** A demand multiplier is meaningless without the window it applies over. */
    @JsonIgnore
    @AssertTrue(message = "windowStartDate and windowDays are required when demandMultiplier is set")
    public boolean isDemandWindowComplete() {
        return demandMultiplier == null || (windowStartDate != null && windowDays != null);
    }

    /** ...and a window is inert without a multiplier, so reject that rather than silently ignore it. */
    @JsonIgnore
    @AssertTrue(message = "demandMultiplier is required when a demand window is given")
    public boolean isMultiplierPresentForWindow() {
        return demandMultiplier != null || (windowStartDate == null && windowDays == null);
    }

    /** True when a complete demand shock (multiplier + window) was supplied. */
    public boolean hasDemandShock() {
        return demandMultiplier != null && windowStartDate != null && windowDays != null;
    }
}

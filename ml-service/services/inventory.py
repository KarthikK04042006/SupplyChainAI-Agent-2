"""Inventory decision logic: safety stock, reorder points, and order quantities.

This is where a forecast turns into an action. The forecast says how much will
probably sell; this module answers *when to order* and *how much*, which needs one
thing the forecast alone does not carry: how wrong the forecast might be.

Three ideas do the work:

**Safety stock covers variability, not the average.** Ordering for mean demand
stocks out roughly half the time. The buffer is sized from the standard deviation of
demand over the lead time and from the variability of the lead time itself, scaled by
the z-value for the requested service level. A supplier whose delivery slips by a
week is as dangerous as a demand spike, and a flat "two weeks of cover" buffer prices
neither.

**The reorder point is a horizon, not a level.** It has to cover demand for as long
as the order takes to arrive, plus the wait until the next review, plus the buffer.

**EOQ balances two costs that pull in opposite directions.** Ordering often is
expensive in fees; ordering rarely is expensive in held stock. EOQ is the minimum of
their sum. It needs both costs, so when they are absent we fall back to a stated
coverage horizon rather than inventing prices.

Every number returned carries its formula and inputs, so a dashboard can show *why*
a quantity was recommended instead of asking the reader to trust it.
"""

from __future__ import annotations

import logging
from statistics import NormalDist

import numpy as np

from models.schemas import (
    CostInputs,
    DemandPoint,
    ReorderDecision,
    ReorderIntermediate,
    ReorderRecommendationResponse,
    ReorderRequest,
    RiskAssessment,
    RiskStatus,
    Severity,
)

logger = logging.getLogger(__name__)

#: Days per year used to annualise demand for EOQ. 365 keeps it consistent with the
#: daily forecast granularity rather than mixing in a business-day convention.
DAYS_PER_YEAR = 365.0

#: Minimum observations before the history's own standard deviation is trusted over
#: the forecast's dispersion.
MIN_HISTORY_FOR_STD = 14


def recommend_reorder(request: ReorderRequest) -> ReorderRecommendationResponse:
    """Compute a reorder recommendation with every intermediate value exposed."""
    daily = np.asarray(request.forecast_daily_demand, dtype=float)
    mean_daily = float(daily.mean())
    lead_time = float(request.lead_time_days)
    review = float(request.review_period_days)

    demand_std, std_source = _demand_std(request, daily)
    z_value = _service_level_z(request.service_level)

    protection_days = lead_time + review
    demand_variance = protection_days * demand_std**2
    lead_time_variance = (mean_daily * float(request.lead_time_std_days)) ** 2
    combined_sigma = float(np.sqrt(demand_variance + lead_time_variance))

    safety_stock = z_value * combined_sigma
    cycle_demand = mean_daily * protection_days
    reorder_point = cycle_demand + safety_stock
    inventory_position = float(request.current_stock) + float(request.on_order)

    eoq, strategy, method_note = _order_quantity(
        request, mean_daily, safety_stock, inventory_position, reorder_point
    )
    raw_quantity, order_quantity = eoq
    suggested_order = order_quantity > 0

    intermediates = _build_intermediates(
        request=request,
        mean_daily=mean_daily,
        demand_std=demand_std,
        std_source=std_source,
        z_value=z_value,
        protection_days=protection_days,
        demand_variance=demand_variance,
        lead_time_variance=lead_time_variance,
        combined_sigma=combined_sigma,
        safety_stock=safety_stock,
        cycle_demand=cycle_demand,
        reorder_point=reorder_point,
        inventory_position=inventory_position,
        raw_quantity=raw_quantity,
        order_quantity=order_quantity,
    )

    logger.info(
        "Reorder for %s: position=%.1f rop=%.1f ss=%.1f order=%.1f strategy=%s",
        request.sku, inventory_position, reorder_point, safety_stock,
        order_quantity, strategy,
    )

    return ReorderRecommendationResponse(
        sku=request.sku,
        decision=ReorderDecision(
            order_quantity=round(order_quantity, 3),
            reorder_point=round(reorder_point, 3),
            safety_stock=round(safety_stock, 3),
            inventory_position=round(inventory_position, 3),
        ),
        intermediates=intermediates,
        suggested_order=suggested_order,
        eoq=round(raw_quantity, 3) if strategy == "eoq" else None,
        ordering_strategy=strategy,
        method_note=method_note,
    )


def _service_level_z(service_level: float) -> float:
    """One-sided normal quantile for the target service level.

    A 95% service level means accepting a 5% chance of stocking out during the
    protection period, which is the 95th percentile of the demand distribution —
    one-sided, not the two-sided 1.96 used for prediction intervals.
    """
    return float(NormalDist().inv_cdf(service_level))


def _demand_std(request: ReorderRequest, daily: np.ndarray) -> tuple[float, str]:
    """Daily demand standard deviation, and where it came from.

    Preference order matters. An explicit override wins because the caller may know
    something we do not. Otherwise observed history is preferred over the forecast:
    a forecast is a smoothed central estimate and its own spread understates real
    day-to-day variability, which would produce a confidently undersized buffer.
    """
    if request.demand_std_override is not None:
        return float(request.demand_std_override), "supplied by caller"

    history: list[DemandPoint] = request.demand_history or []
    if len(history) >= MIN_HISTORY_FOR_STD:
        quantities = np.asarray([point.quantity for point in history], dtype=float)
        return float(quantities.std(ddof=1)), f"observed history ({len(history)} days)"

    if len(daily) > 1:
        return float(daily.std(ddof=1)), "spread of the forecast horizon"

    # A single forecast value and no history: assume Poisson-like dispersion, where
    # variance equals the mean. Crude, but it beats a zero buffer.
    level = float(daily[0]) if len(daily) else 0.0
    return float(np.sqrt(max(level, 0.0))), "Poisson approximation (no variability data)"


def _order_quantity(
    request: ReorderRequest,
    mean_daily: float,
    safety_stock: float,
    inventory_position: float,
    reorder_point: float,
) -> tuple[tuple[float, float], str, str | None]:
    """Decide how much to order, returning ((raw, adjusted), strategy, note).

    ``raw`` is the unrounded economic quantity, kept for transparency; ``adjusted``
    is what the caller should actually order after the reorder-point trigger, minimum
    order quantity, and pack-size rounding are applied.
    """
    holding_cost = _holding_cost(request.costs)
    ordering_cost = request.costs.ordering_cost if request.costs else None

    if holding_cost and ordering_cost and holding_cost > 0 and ordering_cost > 0:
        annual_demand = mean_daily * DAYS_PER_YEAR
        raw = float(np.sqrt(2.0 * annual_demand * ordering_cost / holding_cost))
        strategy = "eoq"
        note = (
            f"EOQ from annual demand {annual_demand:.0f}, ordering cost "
            f"{ordering_cost:g}/order, holding cost {holding_cost:g}/unit/year"
        )
    else:
        raw = mean_daily * float(request.default_coverage_days)
        strategy = "coverage_heuristic"
        note = (
            f"no ordering/holding cost supplied, so EOQ is undefined; ordered "
            f"{request.default_coverage_days:g} days of forecast cover instead"
        )

    if inventory_position > reorder_point:
        return (raw, 0.0), strategy, (
            f"{note}. Inventory position {inventory_position:g} is above the reorder "
            f"point {reorder_point:.1f}, so no order is needed yet"
        )

    # Below the trigger: order enough to clear the reorder point as well as the
    # economic quantity, otherwise the next order would fire again immediately.
    shortfall = reorder_point - inventory_position
    quantity = max(raw, shortfall)
    quantity = max(quantity, float(request.min_order_quantity))

    multiple = float(request.order_multiple)
    if multiple > 0:
        quantity = float(np.ceil(quantity / multiple) * multiple)

    return (raw, quantity), strategy, note


def _holding_cost(costs: CostInputs | None) -> float | None:
    """Annual holding cost per unit, however the caller expressed it.

    Absolute cost wins when both are given. The rate form (a percentage of unit
    cost) is the one most businesses actually know, so it is accepted as a fallback.
    """
    if costs is None:
        return None
    if costs.holding_cost_per_unit_year is not None:
        return float(costs.holding_cost_per_unit_year)
    if costs.unit_cost is not None and costs.holding_cost_rate is not None:
        return float(costs.unit_cost) * float(costs.holding_cost_rate)
    return None


def _build_intermediates(
    *,
    request: ReorderRequest,
    mean_daily: float,
    demand_std: float,
    std_source: str,
    z_value: float,
    protection_days: float,
    demand_variance: float,
    lead_time_variance: float,
    combined_sigma: float,
    safety_stock: float,
    cycle_demand: float,
    reorder_point: float,
    inventory_position: float,
    raw_quantity: float,
    order_quantity: float,
) -> list[ReorderIntermediate]:
    """Every step of the calculation, in the order a reader would follow it.

    This is the explainability payload: each row names the quantity, shows the
    formula with the actual numbers substituted, and says why it matters. A dashboard
    can render the list top to bottom and the arithmetic will read as a derivation.
    """
    def row(
        name: str, label: str, value: float, unit: str, formula: str | None, description: str
    ) -> ReorderIntermediate:
        return ReorderIntermediate(
            name=name, label=label, value=round(float(value), 4),
            unit=unit, formula=formula, description=description,
        )

    return [
        row(
            "average_daily_demand", "Average daily demand", mean_daily, "units/day",
            f"mean of {len(request.forecast_daily_demand)} forecast day(s)",
            "The central estimate the whole calculation is built on.",
        ),
        row(
            "demand_std_dev", "Daily demand variability (σd)", demand_std, "units/day",
            f"standard deviation from {std_source}",
            "How much a typical day departs from the average. Drives the buffer size.",
        ),
        row(
            "service_level_z", "Service-level factor (z)", z_value, "σ",
            f"one-sided normal quantile at {request.service_level:.1%}",
            f"Covering {request.service_level:.1%} of outcomes needs {z_value:.3f} "
            f"standard deviations of buffer.",
        ),
        row(
            "protection_period", "Protection period", protection_days, "days",
            f"lead time {request.lead_time_days:g} + review period "
            f"{request.review_period_days:g}",
            "The window stock must survive alone: until the order lands, plus the wait "
            "for the next chance to order.",
        ),
        row(
            "demand_variance_component", "Variance from demand", demand_variance, "units²",
            f"{protection_days:g} × {demand_std:.3f}²",
            "Risk contributed by demand moving around during the protection period.",
        ),
        row(
            "lead_time_variance_component", "Variance from lead time",
            lead_time_variance, "units²",
            f"({mean_daily:.3f} × {request.lead_time_std_days:g})²",
            "Risk contributed by the supplier being late. Zero when lead time is "
            "treated as fixed.",
        ),
        row(
            "combined_sigma", "Combined variability (σ)", combined_sigma, "units",
            f"√({demand_variance:.3f} + {lead_time_variance:.3f})",
            "Total uncertainty over the protection period. Variances add; standard "
            "deviations do not, which is why they are combined under one root.",
        ),
        row(
            "safety_stock", "Safety stock", safety_stock, "units",
            f"{z_value:.3f} × {combined_sigma:.3f}",
            "Buffer held purely against variability, above expected demand.",
        ),
        row(
            "cycle_demand", "Expected demand in the protection period", cycle_demand, "units",
            f"{mean_daily:.3f} × {protection_days:g}",
            "What will probably sell before the replenishment arrives.",
        ),
        row(
            "reorder_point", "Reorder point", reorder_point, "units",
            f"{cycle_demand:.3f} + {safety_stock:.3f}",
            "Order when inventory position falls to this level.",
        ),
        row(
            "inventory_position", "Inventory position", inventory_position, "units",
            f"on hand {request.current_stock:g} + on order {request.on_order:g}",
            "Compared against the reorder point. On-order units count, or every review "
            "would re-order stock already in transit.",
        ),
        row(
            "economic_order_quantity", "Economic order quantity", raw_quantity, "units",
            "√(2 × annual demand × ordering cost ÷ holding cost)"
            if request.costs and _holding_cost(request.costs) and request.costs.ordering_cost
            else f"{mean_daily:.3f} × {request.default_coverage_days:g} days of cover",
            "The order size that minimises ordering plus holding cost, before "
            "shortfall and pack-size adjustments.",
        ),
        row(
            "recommended_order_quantity", "Recommended order quantity",
            order_quantity, "units",
            "0 when above the reorder point; otherwise max(EOQ, shortfall) raised to "
            "the minimum order quantity and rounded up to the pack multiple",
            "The actionable number.",
        ),
    ]


def scan_risks(
    items: list, lead_time_days: float, overstock_threshold_days: float,
    review_period_days: float,
) -> list:
    """Classify a batch of products against their forecasts.

    Each item is judged over a fixed window: the lead time plus the review period.
    Inside that window a stockout is the supplier's problem to fix — stock on hand
    simply has to last. Outside it, ordering remains possible, so running low there
    is a planning concern, not yet an emergency. Both horizons matter, but only the
    first one is a stockout.

    The days-of-supply framing converts stock and forecast into one comparable
    number; the risk score then maps that number to a 0-100 scale through whichever
    failure mode the product is heading into.
    """
    batch_lead_time = max(float(lead_time_days), 0.0)
    review = max(float(review_period_days), 0.0)
    threshold_days = max(float(overstock_threshold_days), 1.0)

    assessments = []
    for item in items:
        # Per item, because lead time is a property of the supplier, not the batch.
        # An imported SKU on a 21-day lead time and a local one on 7 days are not
        # comparably safe at the same days of cover, and a batch-wide window would
        # judge them as if they were.
        lead_time = (
            max(float(item.lead_time_days), 0.0)
            if item.lead_time_days is not None
            else batch_lead_time
        )
        window = lead_time + review

        daily = [max(float(value), 0.0) for value in item.forecast_daily_demand]
        if not daily:
            assessments.append(
                _no_forecast_assessment(item, window)
            )
            continue

        position = float(item.current_stock) + float(item.on_order)
        mean_daily = float(np.mean(daily))
        safety = float(item.safety_stock) if item.safety_stock is not None else 0.0

        if mean_daily <= 0:
            days_of_supply = float("inf")
            days_until = float("inf")
            excess_days = max(days_of_supply - threshold_days, 0.0)
            status = RiskStatus.OK
            severity = Severity.LOW
            score = 0.0
            reason = "no forecast demand; no action needed"
            excess_units = None
        else:
            days_of_supply = position / mean_daily
            cumulative = np.cumsum(daily)
            exhausted = int(np.searchsorted(cumulative, position, side="right"))
            # searchsorted with side="right" finds the first day whose *running sum*
            # strictly exceeds stock; the day before it is the last covered day, so
            # stockout happens on the following day (1-based).
            days_until = float(exhausted) if exhausted < len(daily) else float("inf")
            excess_days = max(days_of_supply - threshold_days, 0.0)
            excess_units = position - mean_daily * threshold_days

            if days_until <= window:
                status = RiskStatus.STOCKOUT_RISK
                # Scored on how much of the protection window is left uncovered, so
                # the number keeps ranking across its whole range. A ratio like
                # window/days_until saturates instead — at a 14-day window, running
                # out in 4 days and in 7 days would both clip to 100 and the scan
                # could not tell a planner which to handle first. The +1 keeps a
                # genuine risk at the window's edge from scoring 0, which is the
                # value an OK item carries.
                score = 100.0 * (window - days_until + 1.0) / (window + 1.0)
                severity = _stockout_severity(score)
                reason = (
                    f"stockout projected in {days_until:g} day(s), inside the "
                    f"{window:g}-day protection window (lead time {lead_time:g} + "
                    f"review {review:g}); order now"
                )
            elif days_of_supply > threshold_days:
                status = RiskStatus.OVERSTOCK
                # Excess days, compressed so the score keeps separating deep
                # overstock from mild. Dividing by the threshold saturates at twice
                # the threshold, which lumps a year of dead stock in with three
                # months of it; this reaches 50 at one threshold of excess and
                # approaches 100 without ever clipping.
                excess_ratio = excess_days / threshold_days
                score = 100.0 * excess_ratio / (excess_ratio + 1.0)
                severity = _overstock_severity(score)
                reason = (
                    f"stock covers {days_of_supply:g} day(s), more than the "
                    f"{threshold_days:g}-day threshold; excess is about "
                    f"{excess_units:.1f} units"
                )
            else:
                status = RiskStatus.OK
                score = 0.0
                severity = Severity.LOW
                reason = (
                    f"stock covers {days_of_supply:g} day(s); beyond the "
                    f"{window:g}-day protection window and under the "
                    f"{threshold_days:g}-day overstock threshold"
                )

        assessments.append(
            RiskAssessment(
                sku=item.sku,
                status=status,
                severity=severity,
                risk_score=round(score, 1),
                days_of_supply=round(days_of_supply, 2) if np.isfinite(days_of_supply) else None,
                days_until_stockout=round(days_until, 2) if np.isfinite(days_until) else None,
                excess_days_of_supply=round(excess_days, 2)
                if np.isfinite(excess_days) else None,
                excess_units=round(excess_units, 2) if excess_units is not None else None,
                projected_shortfall_units=(
                    round(max(0.0, float(cumulative[int(days_until)]) - position), 2)
                    if np.isfinite(days_until) and int(days_until) < len(daily)
                    else None
                ),
                inventory_position=round(position, 2),
                average_daily_demand=round(mean_daily, 4),
                risk_window_days=round(window, 2),
                reason=reason,
            )
        )

    logger.info(
        "Risk scan: %d item(s) assessed over a %.1f-day window (lead %.1f + review %.1f)",
        len(assessments), window, lead_time, review,
    )
    return assessments


def _stockout_severity(score: float) -> Severity:
    """Grade a stockout risk.

    The thresholds are set so that running out before an order could possibly arrive
    is CRITICAL, while a risk at the far edge of the review window — where there is
    still time to act — is not.
    """
    if score >= 85.0:
        return Severity.CRITICAL
    if score >= 60.0:
        return Severity.HIGH
    if score >= 35.0:
        return Severity.MEDIUM
    return Severity.LOW


def _overstock_severity(score: float) -> Severity:
    """Grade an overstock.

    Milder than the stockout scale by design: excess stock ties up cash, but a
    stockout loses the sale outright, so overstock never reaches CRITICAL.
    """
    if score >= 75.0:
        return Severity.HIGH
    if score >= 40.0:
        return Severity.MEDIUM
    return Severity.LOW


def _no_forecast_assessment(item, window: float) -> RiskAssessment:
    """A product with an empty forecast gets a distinct, unmissable classification."""
    return RiskAssessment(
        sku=item.sku,
        status=RiskStatus.STOCKOUT_RISK,
        severity=Severity.HIGH,
        risk_score=100.0,
        days_of_supply=None,
        days_until_stockout=None,
        excess_days_of_supply=None,
        excess_units=None,
        projected_shortfall_units=None,
        inventory_position=round(float(item.current_stock) + float(item.on_order), 2),
        average_daily_demand=0.0,
        risk_window_days=round(window, 2),
        reason="no forecast supplied; demand is unknown, treat as a stockout risk",
    )

"""Hand-calculated tests for the inventory formulas.

Every expected number here is computed by hand, in the docstring above the
assertion, from inputs chosen to make the arithmetic exact. Asserting against a
value the implementation produced would only prove the code is consistent with
itself; asserting against textbook arithmetic proves the formula is the right one.

The scenarios use constant forecasts and round numbers precisely so the expected
reorder point can be written down without a calculator.
"""

import math

import pytest
from fastapi.testclient import TestClient

from main import app
from models.schemas import (
    CostInputs,
    DemandPoint,
    ReorderRequest,
    RiskScanItem,
    RiskStatus,
    Severity,
)
from services.inventory import recommend_reorder, scan_risks

#: One-sided normal quantiles from standard tables, used to check the
#: implementation's z-value rather than trusting NormalDist blindly.
Z_TABLE = {0.90: 1.2816, 0.95: 1.6449, 0.975: 1.9600, 0.99: 2.3263}


def make_request(**overrides) -> ReorderRequest:
    """A reorder request with deliberately round inputs, overridable per test."""
    defaults = dict(
        sku="TEST-1",
        current_stock=100.0,
        on_order=0.0,
        lead_time_days=10.0,
        lead_time_std_days=0.0,
        service_level=0.95,
        forecast_daily_demand=[20.0] * 14,
        demand_std_override=5.0,
        review_period_days=0.0,
    )
    defaults.update(overrides)
    return ReorderRequest(**defaults)


def intermediate(response, name: str) -> float:
    """Pull one intermediate's value out by name."""
    for row in response.intermediates:
        if row.name == name:
            return row.value
    raise AssertionError(f"no intermediate named {name!r}")


@pytest.mark.parametrize("service_level, expected_z", sorted(Z_TABLE.items()))
def test_service_level_z_matches_the_standard_normal_table(service_level, expected_z):
    """The z-value scales the entire buffer, so it is checked against published
    quantiles rather than against another library call."""
    response = recommend_reorder(make_request(service_level=service_level))

    assert intermediate(response, "service_level_z") == pytest.approx(expected_z, abs=1e-3)


def test_reorder_point_with_fixed_lead_time():
    """Hand calculation, demand variability only:

        d̄ = 20 units/day, σd = 5, LT = 10 days, review = 0, service level 95%

        σ over lead time = 5 × √10              = 15.8114
        safety stock     = 1.6448536 × 15.8114  = 26.0074
        cycle demand     = 20 × 10              = 200
        reorder point    = 200 + 26.0074        = 226.0074
    """
    response = recommend_reorder(make_request())

    expected_sigma = 5.0 * math.sqrt(10.0)

    assert expected_sigma == pytest.approx(15.8114, abs=1e-4)
    assert intermediate(response, "combined_sigma") == pytest.approx(expected_sigma, abs=1e-4)
    assert intermediate(response, "cycle_demand") == pytest.approx(200.0)
    assert response.decision.safety_stock == pytest.approx(26.0074, abs=1e-3)
    assert response.decision.reorder_point == pytest.approx(226.0074, abs=1e-3)


def test_reorder_point_with_variable_lead_time():
    """Hand calculation, both sources of variability:

        d̄ = 20, σd = 5, LT = 10, σLT = 2 days, 95% service level

        demand variance    = 10 × 5²              = 250
        lead-time variance = (20 × 2)²            = 1600
        combined σ         = √1850                = 43.0116
        safety stock       = 1.6448536 × 43.0116  = 70.7478
        reorder point      = 200 + 70.7478        = 270.7478

    The lead-time term dominates: a supplier two days unreliable needs a far bigger
    buffer than demand noise of ±5 units/day. A flat days-of-cover buffer cannot
    express that difference, which is the whole reason for this formula.
    """
    response = recommend_reorder(make_request(lead_time_std_days=2.0))

    assert intermediate(response, "demand_variance_component") == pytest.approx(250.0)
    assert intermediate(response, "lead_time_variance_component") == pytest.approx(1600.0)
    assert intermediate(response, "combined_sigma") == pytest.approx(43.0116, abs=1e-3)
    assert response.decision.safety_stock == pytest.approx(70.7478, abs=1e-3)
    assert response.decision.reorder_point == pytest.approx(270.7478, abs=1e-3)


def test_review_period_extends_the_protection_window():
    """Hand calculation with a weekly review:

        LT = 10, review = 7 → protection period 17 days
        cycle demand  = 20 × 17              = 340
        σ             = 5 × √17              = 20.6155
        safety stock  = 1.6448536 × 20.6155  = 33.9095
        reorder point = 340 + 33.9095        = 373.9095

    Stock must survive until the order lands *and* until the next chance to order,
    so ignoring the review period would silently under-protect a weekly cycle.
    """
    response = recommend_reorder(make_request(review_period_days=7.0))

    assert intermediate(response, "protection_period") == pytest.approx(17.0)
    assert intermediate(response, "cycle_demand") == pytest.approx(340.0)
    assert response.decision.safety_stock == pytest.approx(33.9095, abs=1e-3)
    assert response.decision.reorder_point == pytest.approx(373.9095, abs=1e-3)


def test_zero_variability_makes_safety_stock_zero():
    """With perfectly predictable demand and a fixed lead time there is nothing to
    buffer against, so the reorder point collapses to plain lead-time demand:
    20 × 10 = 200. Returning a buffer here would mean a flat pad, which is exactly
    what the formula exists to avoid."""
    response = recommend_reorder(
        make_request(demand_std_override=0.0, lead_time_std_days=0.0)
    )

    assert response.decision.safety_stock == pytest.approx(0.0)
    assert response.decision.reorder_point == pytest.approx(200.0)


def test_higher_service_level_costs_more_safety_stock():
    """Monotonicity is the property a planner reasons about: asking for fewer
    stockouts must never recommend a smaller buffer."""
    buffers = [
        recommend_reorder(make_request(service_level=level)).decision.safety_stock
        for level in (0.80, 0.90, 0.95, 0.99)
    ]

    assert buffers == sorted(buffers)
    assert buffers[0] > 0


def test_eoq_matches_the_textbook_formula():
    """Hand calculation with numbers chosen to come out exact:

        d̄ = 20/day → annual demand D = 20 × 365 = 7300
        ordering cost S = 50, holding cost H = 2/unit/year

        EOQ = √(2DS / H) = √(2 × 7300 × 50 / 2) = √365000 = 604.152
    """
    response = recommend_reorder(
        make_request(
            current_stock=0.0,
            costs=CostInputs(ordering_cost=50.0, holding_cost_per_unit_year=2.0),
        )
    )

    assert response.ordering_strategy == "eoq"
    assert response.eoq == pytest.approx(math.sqrt(365000.0), abs=1e-2)
    assert response.eoq == pytest.approx(604.152, abs=1e-2)


def test_holding_cost_can_be_given_as_a_rate_of_unit_cost():
    """A business usually knows "carrying stock costs us 25% a year" rather than an
    absolute figure. 25% of an 8.00 unit cost is 2.00/unit/year, which must produce
    exactly the same EOQ as passing 2.0 directly."""
    absolute = recommend_reorder(
        make_request(
            current_stock=0.0,
            costs=CostInputs(ordering_cost=50.0, holding_cost_per_unit_year=2.0),
        )
    )
    as_rate = recommend_reorder(
        make_request(
            current_stock=0.0,
            costs=CostInputs(ordering_cost=50.0, unit_cost=8.0, holding_cost_rate=0.25),
        )
    )

    assert as_rate.eoq == pytest.approx(absolute.eoq)


def test_eoq_balances_ordering_against_holding_cost():
    """The economic claim behind EOQ, tested directly: the quantity it picks must
    have a lower total cost than nearby alternatives.

        total cost(Q) = (D/Q) × S  +  (Q/2) × H

    Checking the minimum this way tests the formula's *purpose*, not just its
    transcription.
    """
    annual_demand, ordering_cost, holding_cost = 7300.0, 50.0, 2.0
    response = recommend_reorder(
        make_request(
            current_stock=0.0,
            costs=CostInputs(
                ordering_cost=ordering_cost, holding_cost_per_unit_year=holding_cost
            ),
        )
    )

    def total_cost(quantity: float) -> float:
        return (annual_demand / quantity) * ordering_cost + (quantity / 2.0) * holding_cost

    best = total_cost(response.eoq)
    for ratio in (0.5, 0.8, 0.9, 1.1, 1.25, 2.0):
        assert best <= total_cost(response.eoq * ratio) + 1e-6


def test_without_costs_it_orders_a_coverage_horizon():
    """No prices supplied, so EOQ is undefined and inventing one would be dishonest.
    30 days of cover at 20 units/day is 600 units — a stated heuristic, and the
    response says so."""
    response = recommend_reorder(
        make_request(current_stock=0.0, default_coverage_days=30.0)
    )

    assert response.ordering_strategy == "coverage_heuristic"
    assert response.eoq is None, "EOQ must not be reported when it was not computed"
    assert intermediate(response, "economic_order_quantity") == pytest.approx(600.0)
    assert "no ordering/holding cost" in response.method_note


def test_no_order_when_inventory_position_is_above_the_reorder_point():
    """Reorder point is 226.02 (see the fixed-lead-time test). With 300 on hand there
    is nothing to do, and the recommendation must be zero rather than a token EOQ."""
    response = recommend_reorder(make_request(current_stock=300.0))

    assert response.decision.order_quantity == 0.0
    assert response.suggested_order is False
    assert "above the reorder point" in response.method_note


def test_on_order_units_count_towards_the_position():
    """150 on hand plus 150 already in transit is 300 against a 226.02 reorder point,
    so no order is needed. Ignoring on-order stock would re-order the same units at
    every review — the classic double-ordering bug."""
    response = recommend_reorder(make_request(current_stock=150.0, on_order=150.0))

    assert response.decision.inventory_position == pytest.approx(300.0)
    assert response.decision.order_quantity == 0.0


def test_order_covers_the_shortfall_when_eoq_is_too_small():
    """With stock at zero the reorder point itself is a 226.02-unit hole. A tiny EOQ
    (cheap to order, expensive to hold) must be raised to cover it, or the very next
    review would fire another order immediately.

        S = 1, H = 200 → EOQ = √(2 × 7300 × 1 / 200) = √73 = 8.544
        shortfall = 226.0074 → order 226.0074, not 8.544
    """
    response = recommend_reorder(
        make_request(
            current_stock=0.0,
            costs=CostInputs(ordering_cost=1.0, holding_cost_per_unit_year=200.0),
        )
    )

    assert response.eoq == pytest.approx(math.sqrt(73.0), abs=1e-3)
    assert response.decision.order_quantity == pytest.approx(226.0074, abs=1e-3)


def test_minimum_order_quantity_and_pack_multiple_are_respected():
    """Suppliers sell in cases, not units.

        stock 0 → shortfall = the reorder point itself = 226.0074
        5 days of cover = 100, so the shortfall is what drives the quantity
        cases of 50: ⌈226.0074 / 50⌉ = 5 cases = 250

    Rounding down to 200 would leave the position below the reorder point the moment
    the delivery landed.
    """
    response = recommend_reorder(
        make_request(
            current_stock=0.0,
            default_coverage_days=5.0,
            min_order_quantity=100.0,
            order_multiple=50.0,
        )
    )

    assert response.decision.order_quantity == pytest.approx(250.0)
    assert response.decision.order_quantity % 50 == pytest.approx(0.0)


def test_coverage_heuristic_wins_when_it_exceeds_the_shortfall():
    """The order is the larger of the economic quantity and the shortfall. With a
    30-day coverage horizon at 20/day that is 600 units, comfortably above the
    226.0074 shortfall, so 600 is ordered — buying a sensible cycle's worth rather
    than the bare minimum that clears the trigger."""
    response = recommend_reorder(
        make_request(current_stock=0.0, default_coverage_days=30.0)
    )

    assert response.decision.order_quantity == pytest.approx(600.0)


def test_minimum_order_quantity_lifts_a_small_order():
    """A 12-unit need against a 100-unit supplier minimum must become 100."""
    response = recommend_reorder(
        make_request(
            current_stock=0.0,
            forecast_daily_demand=[1.0] * 14,
            demand_std_override=0.0,
            lead_time_days=12.0,
            min_order_quantity=100.0,
            default_coverage_days=5.0,
        )
    )

    assert response.decision.reorder_point == pytest.approx(12.0)
    assert response.decision.order_quantity == pytest.approx(100.0)


def test_intermediates_form_a_readable_derivation():
    """The dashboard renders these top to bottom, so the chain has to be complete:
    every quantity used in the final answer must appear, with its formula."""
    response = recommend_reorder(make_request(lead_time_std_days=2.0))
    names = [row.name for row in response.intermediates]

    for required in (
        "average_daily_demand", "demand_std_dev", "service_level_z",
        "protection_period", "demand_variance_component",
        "lead_time_variance_component", "combined_sigma", "safety_stock",
        "cycle_demand", "reorder_point", "inventory_position",
        "recommended_order_quantity",
    ):
        assert required in names, f"{required} missing from the explanation"

    assert names.index("safety_stock") < names.index("reorder_point")
    assert all(row.description for row in response.intermediates)
    assert all(row.unit for row in response.intermediates)


def test_reported_intermediates_agree_with_the_decision():
    """The explanation and the answer must be the same numbers. If they can drift,
    the dashboard shows a derivation that never reaches the recommendation."""
    response = recommend_reorder(make_request(current_stock=50.0, on_order=25.0))

    assert intermediate(response, "safety_stock") == pytest.approx(
        response.decision.safety_stock, abs=1e-3
    )
    assert intermediate(response, "reorder_point") == pytest.approx(
        response.decision.reorder_point, abs=1e-3
    )
    assert intermediate(response, "inventory_position") == pytest.approx(75.0)
    assert intermediate(response, "recommended_order_quantity") == pytest.approx(
        response.decision.order_quantity, abs=1e-3
    )


def test_demand_std_prefers_observed_history_over_the_forecast():
    """A forecast is smoothed, so its own spread understates real variability and
    would size a confidently-too-small buffer. Given 20 days of history swinging
    between 10 and 30, the buffer must reflect that swing, not the flat forecast.

    Alternating 30/10 over 20 days has a sample standard deviation of ~10.26.
    """
    from datetime import date, timedelta

    start = date(2026, 1, 1)
    history = [
        DemandPoint(
            date=start + timedelta(days=index),
            quantity=10.0 if index % 2 else 30.0,
        )
        for index in range(20)
    ]

    response = recommend_reorder(
        make_request(demand_std_override=None, demand_history=history)
    )

    assert intermediate(response, "demand_std_dev") == pytest.approx(10.26, abs=0.05)
    assert "observed history" in next(
        row.formula for row in response.intermediates if row.name == "demand_std_dev"
    )


def scan_one(**kwargs):
    """Scan a single item and return its assessment."""
    lead_time = kwargs.pop("lead_time_days", 7.0)
    threshold = kwargs.pop("overstock_threshold_days", 90.0)
    review = kwargs.pop("review_period_days", 0.0)
    return scan_risks([RiskScanItem(**kwargs)], lead_time, threshold, review)[0]


def test_stockout_inside_the_lead_time_is_flagged():
    """30 units at 10/day is 3 days of cover, but an order takes 7 days to arrive —
    a stockout that can no longer be prevented."""
    assessment = scan_one(
        sku="FAST-MOVER", current_stock=30.0, forecast_daily_demand=[10.0] * 30
    )

    assert assessment.status is RiskStatus.STOCKOUT_RISK
    assert assessment.days_until_stockout == pytest.approx(3.0)
    assert assessment.days_of_supply == pytest.approx(3.0)
    assert assessment.severity in (Severity.HIGH, Severity.CRITICAL)
    assert assessment.risk_score > 0


def test_stock_that_outlasts_the_lead_time_is_ok():
    """200 units at 10/day is 20 days of cover against a 7-day lead time: there is
    still time to order, and 20 days is nowhere near the 90-day overstock line."""
    assessment = scan_one(
        sku="HEALTHY", current_stock=200.0, forecast_daily_demand=[10.0] * 30
    )

    assert assessment.status is RiskStatus.OK
    assert assessment.days_of_supply == pytest.approx(20.0)
    assert assessment.risk_score == 0.0
    assert assessment.severity is Severity.LOW


def test_excess_stock_is_flagged_as_overstock():
    """1000 units at 5/day is 200 days of cover, 110 days past the 90-day threshold.

        excess units = 1000 − (5 × 90)   = 550
        excess ratio = 110 / 90          = 1.2222
        risk score   = 100 × 1.2222 / 2.2222 = 55.0
    """
    assessment = scan_one(
        sku="DEAD-STOCK", current_stock=1000.0, forecast_daily_demand=[5.0] * 30
    )

    assert assessment.status is RiskStatus.OVERSTOCK
    assert assessment.days_of_supply == pytest.approx(200.0)
    assert assessment.excess_days_of_supply == pytest.approx(110.0)
    assert assessment.excess_units == pytest.approx(550.0)
    assert assessment.risk_score == pytest.approx(55.0, abs=0.1)


def test_days_until_stockout_follows_the_forecast_not_the_average():
    """An uneven forecast is where a naive stock÷mean calculation goes wrong.

        stock 100, forecast [50, 50, 5, 5, 5, ...]
        cumulative: 50, 100, 105 → exhausted during day 3, so 2 full days covered
        a mean-based estimate would be 100 / 15.8 ≈ 6.3 days — three times longer
    """
    assessment = scan_one(
        sku="FRONT-LOADED",
        current_stock=100.0,
        forecast_daily_demand=[50.0, 50.0] + [5.0] * 10,
        lead_time_days=7.0,
    )

    assert assessment.days_until_stockout == pytest.approx(2.0)
    assert assessment.status is RiskStatus.STOCKOUT_RISK
    assert assessment.days_of_supply > assessment.days_until_stockout, (
        "the average-based figure is the more optimistic one here"
    )


def test_review_period_widens_the_risk_window():
    """120 units at 10/day is 12 days of cover. Against a 7-day lead time alone that
    is fine; add a 7-day review period and the 14-day window is no longer covered,
    so the same stock becomes a risk."""
    without_review = scan_one(
        sku="BORDERLINE", current_stock=120.0,
        forecast_daily_demand=[10.0] * 30, lead_time_days=7.0,
    )
    with_review = scan_one(
        sku="BORDERLINE", current_stock=120.0,
        forecast_daily_demand=[10.0] * 30, lead_time_days=7.0, review_period_days=7.0,
    )

    assert without_review.status is RiskStatus.OK
    assert with_review.status is RiskStatus.STOCKOUT_RISK
    assert with_review.risk_window_days == pytest.approx(14.0)


def test_zero_demand_with_stock_is_not_a_stockout():
    """A discontinued line has no forecast demand. It can never stock out, and
    dividing by zero demand must not manufacture a risk."""
    assessment = scan_one(
        sku="DISCONTINUED", current_stock=500.0, forecast_daily_demand=[0.0] * 30
    )

    assert assessment.status is RiskStatus.OK
    assert assessment.days_until_stockout is None, "never runs out; not a finite number"
    assert assessment.risk_score == 0.0


def test_empty_stock_with_demand_is_the_worst_case():
    """Nothing on hand and demand tomorrow: stockout on day 0, top of the scale."""
    assessment = scan_one(
        sku="EMPTY", current_stock=0.0, forecast_daily_demand=[10.0] * 30
    )

    assert assessment.status is RiskStatus.STOCKOUT_RISK
    assert assessment.days_until_stockout == pytest.approx(0.0)
    assert assessment.risk_score == pytest.approx(100.0)
    assert assessment.severity is Severity.CRITICAL


def test_per_item_lead_time_overrides_the_batch_default():
    """Suppliers differ. 100 units at 10/day is 10 days of cover: safe on a 7-day
    lead time, a risk on this item's own 21-day lead time."""
    slow_supplier = RiskScanItem(
        sku="IMPORTED", current_stock=100.0,
        forecast_daily_demand=[10.0] * 30, lead_time_days=21.0,
    )
    quick_supplier = RiskScanItem(
        sku="LOCAL", current_stock=100.0, forecast_daily_demand=[10.0] * 30
    )

    imported, local = scan_risks([slow_supplier, quick_supplier], 7.0, 90.0, 0.0)

    assert imported.status is RiskStatus.STOCKOUT_RISK
    assert imported.risk_window_days == pytest.approx(21.0)
    assert local.status is RiskStatus.OK
    assert local.risk_window_days == pytest.approx(7.0)
    assert local.days_until_stockout == pytest.approx(10.0), "100 units / 10 per day"


def test_scan_handles_a_mixed_batch():
    """The realistic case: one of each, all classified in a single pass."""
    items = [
        RiskScanItem(sku="A-RISK", current_stock=10.0, forecast_daily_demand=[10.0] * 30),
        RiskScanItem(sku="B-OK", current_stock=200.0, forecast_daily_demand=[10.0] * 30),
        RiskScanItem(sku="C-OVER", current_stock=2000.0, forecast_daily_demand=[5.0] * 30),
    ]

    by_sku = {a.sku: a for a in scan_risks(items, 7.0, 90.0, 0.0)}

    assert by_sku["A-RISK"].status is RiskStatus.STOCKOUT_RISK
    assert by_sku["B-OK"].status is RiskStatus.OK
    assert by_sku["C-OVER"].status is RiskStatus.OVERSTOCK


def test_stockout_score_ranks_within_the_risk_class():
    """The score's whole job in a thousand-SKU scan is ordering what to handle
    first, so two items that are both at risk must not collapse to one number.

        14-day window: score = 100 × (14 − days_until + 1) / 15
        out in 2 days → 86.7    out in 7 days → 53.3    out in 13 days → 13.3
    """
    scores = [
        scan_one(
            sku=f"RISK-{days}", current_stock=10.0 * days,
            forecast_daily_demand=[10.0] * 30,
            lead_time_days=7.0, review_period_days=7.0,
        ).risk_score
        for days in (2, 7, 13)
    ]

    assert scores == sorted(scores, reverse=True), "a sooner stockout must score higher"
    assert len(set(scores)) == 3, "scores must not saturate at the cap"
    assert scores[0] == pytest.approx(86.7, abs=0.1)
    assert scores[1] == pytest.approx(53.3, abs=0.1)
    assert all(0.0 < score <= 100.0 for score in scores)


def test_overstock_score_ranks_within_its_class():
    """Deep overstock must separate from mild. The score reaches 50 at one threshold
    of excess and approaches 100 without clipping, so a year of dead stock never ties
    with three months of it.

        90-day threshold, 5/day: 900 units → 180 days → 90 excess → ratio 1.0 → 50.0
    """
    mild = scan_one(sku="MILD", current_stock=600.0, forecast_daily_demand=[5.0] * 30)
    one_over = scan_one(sku="ONE-OVER", current_stock=900.0, forecast_daily_demand=[5.0] * 30)
    deep = scan_one(sku="DEEP", current_stock=5000.0, forecast_daily_demand=[5.0] * 30)

    assert one_over.risk_score == pytest.approx(50.0, abs=0.1)
    assert mild.risk_score < one_over.risk_score < deep.risk_score
    assert deep.risk_score < 100.0, "the score must not clip"


def test_stockout_severity_outranks_overstock():
    """A lost sale is worse than tied-up cash, so overstock never reaches CRITICAL
    while an imminent stockout does."""
    imminent = scan_one(sku="IMMINENT", current_stock=5.0, forecast_daily_demand=[10.0] * 30)
    buried = scan_one(sku="BURIED", current_stock=100000.0, forecast_daily_demand=[5.0] * 30)

    assert imminent.severity is Severity.CRITICAL
    assert buried.severity is Severity.HIGH
    assert buried.risk_score > 90.0, "even extreme overstock stays below critical"


@pytest.fixture(scope="module")
def client() -> TestClient:
    return TestClient(app)


def test_reorder_endpoint_returns_the_hand_calculated_answer(client):
    """The same scenario as the unit test, over HTTP: 226.0074."""
    response = client.post(
        "/inventory/reorder-recommendation",
        json={
            "sku": "HTTP-1",
            "current_stock": 100.0,
            "lead_time_days": 10.0,
            "service_level": 0.95,
            "forecast_daily_demand": [20.0] * 14,
            "demand_std_override": 5.0,
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["decision"]["reorder_point"] == pytest.approx(226.0074, abs=1e-3)
    assert body["decision"]["safety_stock"] == pytest.approx(26.0074, abs=1e-3)
    assert len(body["intermediates"]) >= 10


def test_risk_scan_endpoint_summarises_and_orders_by_urgency(client):
    """A batch scan is only useful if what needs attention is at the top."""
    response = client.post(
        "/inventory/risk-scan",
        json={
            "lead_time_days": 7,
            "items": [
                {"sku": "OK-1", "current_stock": 200, "forecast_daily_demand": [10] * 30},
                {"sku": "OVER-1", "current_stock": 2000, "forecast_daily_demand": [5] * 30},
                {"sku": "RISK-1", "current_stock": 5, "forecast_daily_demand": [10] * 30},
            ],
        },
    )

    assert response.status_code == 200
    body = response.json()

    assert body["summary"] == {"total": 3, "ok": 1, "stockout_risk": 1, "overstock": 1}
    assert body["assessments"][0]["sku"] == "RISK-1", "the most urgent must come first"
    assert body["assessments"][-1]["status"] == "OK"


def test_reorder_endpoint_rejects_negative_stock(client):
    """Validation belongs at the boundary; negative stock is not a real state."""
    response = client.post(
        "/inventory/reorder-recommendation",
        json={
            "sku": "BAD",
            "current_stock": -5.0,
            "lead_time_days": 10.0,
            "forecast_daily_demand": [20.0],
        },
    )

    assert response.status_code == 422


def test_reorder_endpoint_rejects_an_empty_forecast(client):
    """Without a forecast there is no demand estimate to reason from. Failing loudly
    beats returning a confident zero."""
    response = client.post(
        "/inventory/reorder-recommendation",
        json={
            "sku": "BAD",
            "current_stock": 10.0,
            "lead_time_days": 10.0,
            "forecast_daily_demand": [],
        },
    )

    assert response.status_code == 422

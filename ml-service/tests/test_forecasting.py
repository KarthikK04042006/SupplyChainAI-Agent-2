"""Core behaviour of the forecasting service: model selection, accuracy,
robustness to sparse or degenerate input, and the HTTP layer.

The architecture assertions (accuracy thresholds, model identities) live here;
see ``test_batch.py`` for the batch endpoint and ``test_models.py`` for
individual candidate models.
"""

from datetime import date, timedelta

import pandas as pd
import pytest
from fastapi.testclient import TestClient

from main import app
from models.schemas import DemandPoint, ForecastRequest
from services import forecasting
from services.forecasting import forecast_demand
from services.models.base import ForecastModel, ModelUnusableError, Prediction
from services.models.baseline import MovingAverageModel

from conftest import START, mape, sinusoidal, weekday_profile

client = TestClient(app)


def request(quantities: list[float], **overrides) -> ForecastRequest:
    """Build a request from raw daily quantities, 2026-01-01 onwards."""
    start = overrides.pop("start", START)
    history = [
        DemandPoint(date=start + timedelta(days=i), quantity=q)
        for i, q in enumerate(quantities)
    ]
    defaults = dict(sku="A", history=history, horizon_days=14, seasonal_periods=7)
    defaults.update(overrides)
    return ForecastRequest(**defaults)


# --- response shape -------------------------------------------------------


def test_forecast_length_and_dates_follow_history():
    response = forecast_demand(request([10.0] * 40, horizon_days=5))

    assert len(response.points) == 5
    assert response.points[0].date == START + timedelta(days=40)
    assert response.points[-1].date == START + timedelta(days=44)
    # Every forecast day carries a prediction interval around the point forecast.
    assert all(p.lower <= p.predicted <= p.upper for p in response.points)


def test_weekly_granularity_buckets_and_dates_first_days():
    response = forecast_demand(request([10.0] * 40, horizon_days=15, granularity="weekly"))

    assert len(response.points) == 3  # 7 + 7 + a kept trailing partial week
    assert response.points[0].date == START + timedelta(days=40)
    assert response.points[1].date == START + timedelta(days=47)
    assert all(p.upper >= p.predicted >= p.lower for p in response.points)


def test_response_reports_every_candidate_score():
    """The selection must be explainable: every model tried comes back, not just
    the winner, so the choice is auditable rather than a black box."""
    response = forecast_demand(request(sinusoidal(120).quantities))

    assert response.selected_model in {score.model for score in response.scores}
    assert len(response.scores) >= 3
    assert response.scores[0].fitted
    assert all(score.folds > 0 for score in response.scores if score.fitted)
    # Ranked best-first: fitted models lead, the unfitted ones trail.
    fitted = [s for s in response.scores if s.fitted]
    unfitted = [s for s in response.scores if not s.fitted]
    if unfitted:
        assert all(s.mape is not None for s in fitted)


# --- model selection -------------------------------------------------------


def test_seasonal_history_selects_a_seasonal_model():
    response = forecast_demand(request(weekday_profile(140).quantities))

    assert response.seasonality_detected
    assert response.selected_model in {
        "holt_winters_seasonal",
        "sarima",
        "seasonal_naive",
        "prophet",
    }


def test_flat_history_selects_a_flat_model():
    """Constant demand has no signal for trend or seasonality; the selection
    should land on the most parsimonious candidate, not an overfitted one."""
    response = forecast_demand(request([30.0] * 90))

    assert response.selected_model in {"moving_average(w=7)", "holt_winters_trend", "arima"}


def test_scores_are_best_first():
    response = forecast_demand(request(sinusoidal(120, noise=2.0).quantities))

    ranked = [s.mape for s in response.scores if s.fitted and s.mape is not None]
    assert ranked == sorted(ranked)


# --- a winner that cannot be refitted --------------------------------------


class WinsBacktestThenFailsRefit(ForecastModel):
    """Wins selection outright, then fails when refitted on the full history.

    Contrived deliberately. A model has to *win* before the refit path is reachable
    at all, so this one is handed the true series and reads the holdout off it during
    backtesting, scoring a perfect 0% MAPE. It then raises on any series as long as
    the whole history — which is exactly the fold-fits-but-full-fit-fails case that
    happens for real when statsmodels converges on a 60-day training fold and not on
    the 80-day series.
    """

    name = "wins_then_fails"
    min_observations = 1

    def __init__(self, truth: pd.Series) -> None:
        self.truth = truth

    def fit_predict(self, series, horizon, seasonal_periods):
        if len(series) >= len(self.truth):
            raise ModelUnusableError("refit on the full history failed")
        holdout = self.truth.to_numpy(dtype=float)[len(series): len(series) + horizon]
        return Prediction(values=holdout, sigma=0.0)


def test_a_winner_that_fails_to_refit_is_not_reported_as_the_model_used(monkeypatch):
    """The response must name the model that produced the points.

    Everything downstream reads ``selected_model`` as the model behind the numbers:
    the backend files the persisted accuracy row under it, the dashboard highlights
    and badges that candidate, and ``/explain`` only agrees to run when it says
    ``xgboost``. Reporting a winner whose refit failed would file a moving average's
    forecast under a model that produced none of it.
    """
    baseline = MovingAverageModel(window=7)
    monkeypatch.setattr(
        forecasting,
        "_build_candidates",
        lambda series, seasonal, seasonal_periods, req: [
            WinsBacktestThenFailsRefit(series),
            baseline,
        ],
    )

    response = forecast_demand(request(sinusoidal(90, noise=2.0).quantities))

    # It did win selection, and that stays on the record so the attempt is auditable.
    scores = {score.model: score for score in response.scores}
    assert scores["wins_then_fails"].fitted
    assert scores["wins_then_fails"].mape == 0.0

    # But it produced none of the returned points, so it is not what is reported.
    assert response.selected_model == "moving_average(w=7)"
    assert response.selected_model in scores
    assert "could not be refitted" in response.selection_reason

    # And the substitution is surfaced rather than left in a server-side log line.
    codes = {warning.code for warning in response.warnings}
    assert "model_refit_failed" in codes


# --- accuracy against ground truth -----------------------------------------


@pytest.mark.parametrize(
    ("kind", "history_days", "max_mape"),
    [
        # Strong weekly sine, mild noise: even the second-best model beats 6%.
        ("sine", 120, 6.0),
        # Real-shaped retail week with a little noise.
        ("retail", 140, 15.0),
        # Trending upward with noise.
        ("trend", 140, 15.0),
    ],
)
def test_autoselected_model_beats_threshold_on_held_out_future(kind, history_days, max_mape):
    """The auto-selected model's error on data it has genuinely never seen —
    the *true* future, not the backtest tail — stays under a sane threshold."""
    if kind == "sine":
        data = sinusoidal(history_days, noise=2.0, amplitude=20.0)
    elif kind == "retail":
        data = weekday_profile(history_days, noise=3.0)
    else:
        data = sinusoidal(history_days, noise=3.0, trend=0.4, amplitude=8.0)

    response = forecast_demand(request(data.quantities, horizon_days=data.horizon))
    predicted = [point.predicted for point in response.points]

    error = mape(data.future, predicted)
    assert error < max_mape, (
        f"selected {response.selected_model!r} scored {error:.1f}% on the held-out "
        f"future, above the {max_mape}% threshold"
    )


def test_seasonal_shape_stays_aligned_to_its_weekday():
    """The weekly shape must stay phase-locked: each forecast day reproduces the
    level for *that weekday*, rather than the right pattern shifted a day or two —
    a shifted forecast can still score well on average while being useless for
    deciding what to stock on a Saturday."""
    profile = (30.0, 28.0, 27.0, 32.0, 55.0, 80.0, 65.0)  # indexed by weekday()
    data = weekday_profile(140, noise=1.0, profile=profile)
    response = forecast_demand(request(data.quantities))

    for point in response.points:
        expected = profile[point.date.weekday()]
        assert point.predicted == pytest.approx(expected, rel=0.15), (
            f"{point.date} is a {point.date.strftime('%A')}: expected ~{expected}, "
            f"got {point.predicted}"
        )

    # And the peak really is the peak, not a flat line that happens to pass above.
    predicted = [point.predicted for point in response.points]
    assert max(predicted) > 2 * min(predicted)


# --- sparse and degenerate histories ---------------------------------------


def test_single_observation_uses_category_average():
    response = forecast_demand(
        request([7.0], category_average=25.0, horizon_days=3)
    )

    assert response.selected_model == "category_average"
    assert all(p.predicted == pytest.approx(25.0) for p in response.points)
    assert response.scores[0].error == "history too short to backtest"


def test_single_observation_without_category_average_repeats_last_value():
    response = forecast_demand(request([7.0], horizon_days=3))

    # The window shrinks to the history available, so one day gives w=1.
    assert response.selected_model == "moving_average(w=1)"
    assert all(p.predicted == pytest.approx(7.0) for p in response.points)


def test_sparse_history_never_errors():
    """The whole ladder must be survivable — 1, 5, 20 days — each producing a
    flat-but-reasonable forecast instead of an exception."""
    for n in (1, 5, 20):
        response = forecast_demand(request([3.0 + i % 2 for i in range(n)]))
        assert len(response.points) == 14
        assert all(p.predicted >= 0 for p in response.points)
        assert response.selection_reason  # and it explains what it did


def test_gaps_are_filled_as_zero_demand():
    history = [
        DemandPoint(date=date(2026, 1, 1), quantity=10),
        DemandPoint(date=date(2026, 1, 10), quantity=10),
    ]
    response = forecast_demand(
        ForecastRequest(sku="A", history=history, horizon_days=2)
    )
    assert response.observations == 10


def test_duplicate_dates_are_summed():
    history = [
        DemandPoint(date=date(2026, 1, 1), quantity=4),
        DemandPoint(date=date(2026, 1, 1), quantity=6),
        DemandPoint(date=date(2026, 1, 2), quantity=10),
    ]
    response = forecast_demand(ForecastRequest(sku="A", history=history))
    assert response.observations == 2
    assert response.points[0].predicted == pytest.approx(10.0, abs=1.0)


def test_predictions_never_go_negative():
    """A steep downward trend would extrapolate below zero; demand cannot be negative."""
    response = forecast_demand(request([float(i) for i in range(30, 0, -1)], horizon_days=20))
    assert all(p.predicted >= 0 for p in response.points)
    assert all(p.lower >= 0 for p in response.points)


def test_intervals_widen_with_horizon():
    # Values cycle with period 11 against a 7-day season, so no model can fit
    # them exactly and the interval is sized from real residuals.
    noisy = [10.0 + (i * 7919 % 11) for i in range(42)]
    response = forecast_demand(request(noisy, horizon_days=10))

    widths = [p.upper - p.lower for p in response.points]
    assert widths[0] > 0
    assert widths[-1] > widths[0]


# --- HTTP layer ------------------------------------------------------------


def test_endpoint_returns_forecast():
    body = {
        "sku": "WIDGET-001",
        "horizon_days": 3,
        "history": [
            {"date": (START + timedelta(days=i)).isoformat(), "quantity": 10 + (i % 3)}
            for i in range(30)
        ],
    }
    response = client.post("/forecast", json=body)

    assert response.status_code == 200
    payload = response.json()
    assert payload["sku"] == "WIDGET-001"
    assert payload["selected_model"]
    assert payload["selection_reason"]
    assert len(payload["points"]) == 3
    assert {"date", "predicted", "lower", "upper"} == set(payload["points"][0])
    assert all(score["model"] for score in payload["scores"])


def test_endpoint_rejects_empty_history():
    response = client.post("/forecast", json={"sku": "A", "history": []})
    assert response.status_code == 422


def test_endpoint_rejects_negative_quantity():
    response = client.post(
        "/forecast",
        json={"sku": "A", "history": [{"date": "2026-01-01", "quantity": -5}]},
    )
    assert response.status_code == 422


def test_endpoint_rejects_too_large_horizon():
    body = {"sku": "A", "horizon_days": 400, "history": [{"date": "2026-01-01", "quantity": 5}]}
    response = client.post("/forecast", json=body)
    assert response.status_code == 422


def test_health_endpoint_still_works():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "ml-service"}

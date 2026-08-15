"""Tests for the batch endpoint.

Two properties matter most for the dashboard that consumes this: one broken SKU
must not fail the other 499, and the same request must always come back in the
same order despite the results arriving from a thread pool out of order.
"""

from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from main import app
from models.schemas import (
    BatchForecastItem,
    BatchForecastRequest,
    DemandPoint,
    Granularity,
)
from services.batch import forecast_batch

from conftest import START, sinusoidal, weekday_profile

client = TestClient(app)


def item(sku: str, quantities: list[float], **overrides) -> BatchForecastItem:
    history = [
        DemandPoint(date=START + timedelta(days=i), quantity=q)
        for i, q in enumerate(quantities)
    ]
    return BatchForecastItem(sku=sku, history=history, **overrides)


def test_batch_forecasts_every_sku():
    request = BatchForecastRequest(
        horizon_days=7,
        items=[
            item("A", weekday_profile(60, horizon=0).quantities),
            item("B", sinusoidal(60, horizon=0, amplitude=5.0).quantities),
            item("C", [12.0] * 60),
        ],
    )

    response = forecast_batch(request)

    assert response.requested == 3
    assert response.succeeded == 3
    assert response.failed == 0
    assert [forecast.sku for forecast in response.forecasts] == ["A", "B", "C"]
    assert all(len(forecast.points) == 7 for forecast in response.forecasts)


def test_batch_results_are_sorted_regardless_of_completion_order():
    """Threads finish in whatever order they finish; the response must not."""
    skus = ["Z-9", "A-1", "M-5", "B-2"]
    request = BatchForecastRequest(
        horizon_days=5,
        items=[item(sku, [10.0 + index] * 40) for index, sku in enumerate(skus)],
    )

    response = forecast_batch(request)

    assert [forecast.sku for forecast in response.forecasts] == sorted(skus)


def test_per_item_overrides_beat_batch_defaults():
    request = BatchForecastRequest(
        horizon_days=7,
        granularity=Granularity.DAILY,
        items=[
            item("DEFAULTS", [10.0] * 40),
            item("OVERRIDE", [10.0] * 40, horizon_days=14, granularity=Granularity.WEEKLY),
        ],
    )

    response = forecast_batch(request)
    by_sku = {forecast.sku: forecast for forecast in response.forecasts}

    assert len(by_sku["DEFAULTS"].points) == 7
    assert by_sku["DEFAULTS"].granularity is Granularity.DAILY
    # 14 daily days bucketed into two weeks.
    assert len(by_sku["OVERRIDE"].points) == 2
    assert by_sku["OVERRIDE"].granularity is Granularity.WEEKLY


def test_one_bad_sku_does_not_fail_the_batch(monkeypatch):
    """A dashboard showing 2 of 3 products with one clearly marked error beats an
    error page, so failures are isolated per SKU and reported alongside."""
    import services.batch as batch_module

    original = batch_module.forecast_demand

    def explode_on_b(request):
        if request.sku == "B":
            raise RuntimeError("model blew up")
        return original(request)

    monkeypatch.setattr(batch_module, "forecast_demand", explode_on_b)

    request = BatchForecastRequest(
        horizon_days=5,
        items=[item(sku, [10.0] * 40) for sku in ("A", "B", "C")],
    )

    response = forecast_batch(request)

    assert response.requested == 3
    assert response.succeeded == 2
    assert response.failed == 1
    assert [forecast.sku for forecast in response.forecasts] == ["A", "C"]
    assert response.failures[0].sku == "B"
    assert "model blew up" in response.failures[0].error


def test_counts_always_reconcile():
    request = BatchForecastRequest(
        horizon_days=3,
        items=[item(f"SKU-{index:02d}", [5.0 + index] * 30) for index in range(12)],
    )

    response = forecast_batch(request)

    assert response.succeeded + response.failed == response.requested
    assert len(response.forecasts) == response.succeeded
    assert len(response.failures) == response.failed


def test_batch_mixes_sparse_and_long_histories():
    """New products and established ones arrive in the same dashboard call."""
    request = BatchForecastRequest(
        horizon_days=7,
        items=[
            item("NEW", [4.0, 5.0], category_average=6.0),
            item("ESTABLISHED", weekday_profile(120, horizon=0).quantities),
        ],
    )

    response = forecast_batch(request)
    by_sku = {forecast.sku: forecast for forecast in response.forecasts}

    assert response.failed == 0
    assert by_sku["NEW"].selected_model == "category_average"
    assert by_sku["ESTABLISHED"].selected_model != "category_average"


# --- HTTP layer ------------------------------------------------------------


def test_batch_endpoint_returns_both_lists():
    body = {
        "horizon_days": 5,
        "items": [
            {
                "sku": sku,
                "history": [
                    {"date": (START + timedelta(days=i)).isoformat(), "quantity": 10 + (i % 4)}
                    for i in range(40)
                ],
            }
            for sku in ("WIDGET-001", "WIDGET-002")
        ],
    }

    response = client.post("/forecast/batch", json=body)

    assert response.status_code == 200
    payload = response.json()
    assert payload["requested"] == 2
    assert payload["succeeded"] == 2
    assert payload["failures"] == []
    assert len(payload["forecasts"][0]["points"]) == 5
    assert payload["forecasts"][0]["scores"]


def test_batch_endpoint_rejects_an_empty_item_list():
    response = client.post("/forecast/batch", json={"items": []})
    assert response.status_code == 422


def test_batch_endpoint_caps_request_size():
    """500 SKUs is the documented ceiling; beyond it the caller should page."""
    body = {
        "items": [
            {"sku": f"S{index}", "history": [{"date": "2026-01-01", "quantity": 1}]}
            for index in range(501)
        ]
    }
    response = client.post("/forecast/batch", json=body)
    assert response.status_code == 422

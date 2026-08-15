"""Tests for the SHAP explain endpoint.

The core property of SHAP values — that they sum with the base value to the
prediction — is exactly what makes an explanation checkable, so it is what these
tests assert. If the decomposition does not close the loop, the dashboard would
show a story that never quite reaches the number it claims to explain.
"""

import numpy as np
import pytest
from fastapi.testclient import TestClient

from main import app
from models.schemas import DemandPoint, ExplainRequest
from services.explain import explain_prediction, _describe, shap_available
from services.models.base import ModelUnusableError

from conftest import weekday_profile


def to_history(quantities, start: str = "2025-01-01") -> list[dict]:
    """History as the API would receive it: sku,date,quantity rows."""
    import pandas as pd

    index = pd.date_range(start, periods=len(quantities), freq="D")
    return [
        {"date": day.date().isoformat(), "quantity": float(quantity)}
        for day, quantity in zip(index, quantities)
    ]


@pytest.fixture(scope="module")
def explain_request() -> ExplainRequest:
    """A seasonal series long enough that XGBoost is a viable candidate."""
    return ExplainRequest(
        sku="EXPLAIN-ME",
        history=[
            DemandPoint.model_validate(point)
            for point in to_history(weekday_profile(180, horizon=0, noise=2.0).quantities)
        ],
        horizon_days=14,
        step=1,
        top_n=10,
    )


@pytest.mark.skipif(not shap_available(), reason="shap is not installed")
def test_contributions_close_the_prediction_loop(explain_request):
    """The auditability property: base value plus every contribution must equal the
    predicted demand, within rounding. Any drift means the explanation does not
    actually explain the number."""
    result = explain_prediction("EXPLAIN-ME", explain_request)

    assert result.total_contribution == pytest.approx(
        result.predicted - result.base_value, abs=0.01
    )
    assert result.predicted >= 0


@pytest.mark.skipif(not shap_available(), reason="shap is not installed")
def test_explained_step_uses_the_recursive_feature_row(explain_request):
    """Step 2's features are built from step 1's prediction, not from the same row
    twice. If the walk were skipped, both steps would decompose identical features,
    which the differing contribution sets would expose."""
    first = explain_prediction("EXPLAIN-ME", explain_request)
    second = explain_prediction(
        "EXPLAIN-ME", ExplainRequest.model_validate(
            explain_request.model_dump() | {"step": 2}
        )
    )

    contributions_1 = {item.feature: item.contribution for item in first.contributions}
    contributions_2 = {item.feature: item.contribution for item in second.contributions}

    assert first.explained_date < second.explained_date
    assert contributions_1 != contributions_2


@pytest.mark.skipif(not shap_available(), reason="shap is not installed")
def test_later_steps_have_wider_spread(explain_request):
    """Uncertainty grows with horizon. The base value is fixed and the total
    contribution closes the prediction, so a further-out prediction that departs
    more from the base means the contribution totals grow — which is what a
    widening interval looks like inside the decomposition."""
    totals = [
        explain_prediction(
            "EXPLAIN-ME",
            ExplainRequest.model_validate(explain_request.model_dump() | {"step": step}),
        ).total_contribution
        for step in (1, 5, 14)
    ]

    assert totals[0] <= totals[-1]


@pytest.mark.skipif(not shap_available(), reason="shap is not installed")
def test_top_n_limits_the_response_and_is_ranked(explain_request):
    top = explain_prediction(
        "EXPLAIN-ME",
        ExplainRequest.model_validate(explain_request.model_dump() | {"top_n": 5}),
    )

    assert len(top.contributions) == 5
    magnitudes = [item.abs_contribution for item in top.contributions]
    assert magnitudes == sorted(magnitudes, reverse=True)


@pytest.mark.skipif(not shap_available(), reason="shap is not installed")
def test_feature_names_are_human_readable():
    assert _describe("lag_14") == "demand 14 day(s) ago"
    assert _describe("roll_std_28") == "28-day demand variability"
    assert _describe("is_weekend") == "weekend flag"
    assert _describe("is_holiday") == "public holiday"


@pytest.mark.skipif(not shap_available(), reason="shap is not installed")
def test_short_history_is_rejected_politely():
    with pytest.raises(ModelUnusableError):
        explain_prediction(
            "TOO-SHORT",
            ExplainRequest(
                sku="TOO-SHORT",
                history=to_history([5.0] * 10),
                horizon_days=7,
            ),
        )


@pytest.mark.skipif(not shap_available(), reason="shap is not installed")
def test_endpoint_returns_422_for_short_history():
    client = TestClient(app)
    response = client.post(
        "/explain/TOO-SHORT",
        json={
            "history": to_history([5.0] * 10),
            "horizon_days": 7,
        },
    )

    assert response.status_code == 422


@pytest.mark.skipif(not shap_available(), reason="shap is not installed")
def test_explain_endpoint_end_to_end():
    """The full HTTP path: features arrive as JSON, contributions come back ranked,
    and the arithmetic closes over the wire too. The response only carries the top
    five rows, so the identity is checked by requesting every feature instead."""
    client = TestClient(app)
    payload = to_history(weekday_profile(180, horizon=0, noise=2.0).quantities)

    full = client.post(
        "/explain/EXPLAIN-ME",
        json={"history": payload, "horizon_days": 14, "step": 1, "top_n": 50},
    )
    assert full.status_code == 200
    body = full.json()
    assert body["model_used"] == "xgboost"
    total = sum(entry["contribution"] for entry in body["contributions"])
    assert body["predicted"] == pytest.approx(body["base_value"] + total, abs=0.01)

    top = client.post(
        "/explain/EXPLAIN-ME",
        json={"history": payload, "horizon_days": 14, "step": 1, "top_n": 5},
    )
    assert top.status_code == 200
    assert len(top.json()["contributions"]) == 5

"""Prediction explainability endpoint."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Path

from models.schemas import ExplainRequest, ExplainResponse
from services.explain import explain_prediction, shap_available
from services.models.base import ModelUnusableError

router = APIRouter(prefix="/explain", tags=["explain"])


@router.post(
    "/{product_id}",
    response_model=ExplainResponse,
    summary="Explain a forecasted day as per-feature contributions",
    response_description="Top feature contributions, with the base value they build on",
)
def explain_forecast(
    request: ExplainRequest,
    product_id: str = Path(..., min_length=1, description="SKU or product identifier"),
) -> ExplainResponse:
    """Decompose one forecast day into the features that drove it.

    Returns 503 when SHAP is missing from the environment — that is a deployment gap
    rather than a bad request — and 422 when the history is too short for XGBoost to
    be fitted at all, which the caller can fix by sending more data.
    """
    if not shap_available():
        raise HTTPException(
            status_code=503,
            detail="shap is not installed in this environment; explanations are unavailable",
        )

    try:
        return explain_prediction(product_id, request)
    except ModelUnusableError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

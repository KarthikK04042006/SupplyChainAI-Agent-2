"""Inventory decision endpoints: reorder recommendations and batch risk scanning."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException

from models.schemas import (
    ReorderRecommendationResponse,
    ReorderRequest,
    RiskScanRequest,
    RiskScanResponse,
    RiskScanSummary,
    RiskStatus,
)
from services.inventory import recommend_reorder, scan_risks

router = APIRouter(prefix="/inventory", tags=["inventory"])


@router.post(
    "/reorder-recommendation",
    response_model=ReorderRecommendationResponse,
    summary="Recommend when and how much to reorder",
    response_description="The decision plus every intermediate value behind it",
)
def create_reorder_recommendation(request: ReorderRequest) -> ReorderRecommendationResponse:
    """Size the safety stock, reorder point, and order quantity for one SKU.

    The response carries the full derivation in ``intermediates`` so a dashboard can
    justify the number rather than just display it.
    """
    try:
        return recommend_reorder(request)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@router.post(
    "/risk-scan",
    response_model=RiskScanResponse,
    summary="Classify a batch of products as OK, at risk of stockout, or overstocked",
    response_description="Per-SKU assessments with risk scores, plus headline counts",
)
def create_risk_scan(request: RiskScanRequest) -> RiskScanResponse:
    """Assess stock adequacy across many SKUs in one call.

    Ordered most urgent first: a scan of a thousand products is only useful if the
    handful that need attention today are at the top.
    """
    assessments = scan_risks(
        items=request.items,
        lead_time_days=request.lead_time_days,
        overstock_threshold_days=request.overstock_threshold_days,
        review_period_days=request.review_period_days,
    )

    severity_first = {
        RiskStatus.STOCKOUT_RISK: 0,
        RiskStatus.OVERSTOCK: 1,
        RiskStatus.OK: 2,
    }
    assessments.sort(
        key=lambda a: (severity_first[a.status], -a.risk_score, a.sku)
    )

    return RiskScanResponse(
        summary=RiskScanSummary(
            total=len(assessments),
            ok=sum(1 for a in assessments if a.status is RiskStatus.OK),
            stockout_risk=sum(
                1 for a in assessments if a.status is RiskStatus.STOCKOUT_RISK
            ),
            overstock=sum(1 for a in assessments if a.status is RiskStatus.OVERSTOCK),
        ),
        assessments=assessments,
    )

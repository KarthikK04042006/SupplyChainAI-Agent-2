"""Forecasting endpoint, called by the backend's ForecastService."""

import logging

from fastapi import APIRouter, HTTPException, status

from models.schemas import (
    BatchForecastRequest,
    BatchForecastResponse,
    ForecastRequest,
    ForecastResponse,
)
from services.batch import forecast_batch
from services.forecasting import forecast_demand

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/forecast", tags=["forecast"])


@router.post(
    "",
    response_model=ForecastResponse,
    summary="Forecast demand for one SKU",
    responses={422: {"description": "History failed validation"}},
)
def create_forecast(request: ForecastRequest) -> ForecastResponse:
    """Forecast daily demand from the history the backend supplies.

    Deliberately sync: model fitting is blocking CPU work, so FastAPI runs this
    in its threadpool rather than stalling the event loop for every other caller.

    Pydantic rejects malformed bodies before we get here, so a failure at this
    point means the series itself could not be modelled.
    """
    try:
        return forecast_demand(request)
    except ValueError as exc:
        logger.warning("Unmodellable history for %s: %s", request.sku, exc)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc


@router.post(
    "/batch",
    response_model=BatchForecastResponse,
    summary="Forecast demand for many SKUs in one call",
    responses={422: {"description": "Request failed validation"}},
)
def create_batch_forecast(request: BatchForecastRequest) -> BatchForecastResponse:
    """Forecast every SKU in parallel.

    Sync for the same reason as the single-SKU route: the work is blocking, and it
    fans out onto its own pool internally.

    Per-SKU failures come back in ``failures`` instead of failing the call, so a
    dashboard can render the 499 good forecasts and flag the one that broke.
    """
    return forecast_batch(request)

"""Batch forecasting.

The dashboard needs forecasts for every SKU at once, and fitting them one at a time
would leave most of the machine idle — each fit is CPU-bound and independent.

A thread pool is the right tool despite the GIL: the actual numerical work in
statsmodels, XGBoost, and Prophet happens in native code that releases the GIL, so
threads genuinely run in parallel here while avoiding the cost of pickling a series
per SKU to a subprocess. Individual models are pinned to a single thread each
(``n_jobs=1``) so the parallelism lives at this level rather than nesting.
"""

from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor, as_completed

from models.schemas import (
    BatchForecastRequest,
    BatchForecastResponse,
    ForecastFailure,
    ForecastRequest,
    ForecastResponse,
)

from .forecasting import forecast_demand

logger = logging.getLogger(__name__)

#: Upper bound on pool size. Past this, threads contend for cache more than they
#: gain from parallelism, and memory grows with concurrent Prophet fits.
MAX_WORKERS = 8


def forecast_batch(request: BatchForecastRequest) -> BatchForecastResponse:
    """Forecast every SKU in ``request``, in parallel.

    One SKU failing does not fail the batch: the successful forecasts come back in
    ``forecasts`` and the failures in ``failures``, each with its reason. A dashboard
    showing 499 of 500 products with one clearly marked error is far more useful than
    an error page.
    """
    workers = min(MAX_WORKERS, max(1, len(request.items)))
    forecasts: list[ForecastResponse] = []
    failures: list[ForecastFailure] = []

    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="forecast") as pool:
        futures = {
            pool.submit(_forecast_one, item, request): item.sku for item in request.items
        }

        for future in as_completed(futures):
            sku = futures[future]
            try:
                forecasts.append(future.result())
            except Exception as exc:  # noqa: BLE001 - isolate per-SKU failure
                logger.warning("Batch forecast failed for %s: %s", sku, exc)
                failures.append(ForecastFailure(sku=sku, error=str(exc)))

    # as_completed yields in finishing order, which is nondeterministic. Sort so the
    # response order is stable across identical requests.
    forecasts.sort(key=lambda response: response.sku)
    failures.sort(key=lambda failure: failure.sku)

    logger.info(
        "Batch forecast complete: %d succeeded, %d failed, %d workers",
        len(forecasts), len(failures), workers,
    )

    return BatchForecastResponse(
        requested=len(request.items),
        succeeded=len(forecasts),
        failed=len(failures),
        forecasts=forecasts,
        failures=failures,
    )


def _forecast_one(item, request: BatchForecastRequest) -> ForecastResponse:
    """Forecast a single batch item, applying the batch-level defaults.

    Per-item values win over batch defaults, so a caller can set one horizon for the
    whole dashboard and still override it for a specific SKU.
    """
    return forecast_demand(
        ForecastRequest(
            sku=item.sku,
            history=item.history,
            horizon_days=item.horizon_days or request.horizon_days,
            granularity=item.granularity or request.granularity,
            seasonal_periods=request.seasonal_periods,
            country_holidays=request.country_holidays,
            category_average=item.category_average,
        )
    )

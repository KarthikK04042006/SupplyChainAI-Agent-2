"""Demand forecasting with automatic model selection.

Stateless by design: the backend hands us a SKU's observed history and we hand back
point forecasts with prediction intervals. Nothing here touches a database.

The flow is: normalise the history into a gap-free daily series, decide which models
are viable candidates for its length and seasonality, backtest each with
walk-forward validation, pick the lowest-error candidate, then refit the winner on
the full history to produce the returned forecast. Every candidate's score comes
back in the response, so the choice is auditable rather than a black box.

Short histories never error. As data thins out the candidate pool shrinks to the
moving-average baseline, and below that to a flat forecast at the category average
or the last observed value.
"""

from __future__ import annotations

import logging
import warnings

import numpy as np
import pandas as pd

from models.schemas import (
    DemandPoint,
    ForecastPoint,
    ForecastRequest,
    ForecastResponse,
    ForecastWarning,
    Granularity,
    ModelScore,
    Severity,
)

from .anomalies import build_warnings, detect_anomalies
from .backtest import backtest_model, build_folds
from .models.base import ForecastModel, Prediction
from .models.baseline import MovingAverageModel, SeasonalNaiveModel
from .models.ml import XGBoostModel
from .models.statistical import HoltWintersModel, ProphetModel, SarimaModel
from .seasonality import detect_seasonality

logger = logging.getLogger(__name__)

# Normal quantile for a 95% two-sided interval.
Z_95 = 1.959963985

#: Below this many observations we do not attempt model selection at all — there is
#: not enough signal to distinguish candidates, so the baseline is used directly.
MIN_OBS_FOR_SELECTION = 21


def forecast_demand(request: ForecastRequest) -> ForecastResponse:
    """Forecast ``request.horizon_days`` of demand for a single SKU."""
    series = _to_daily_series(request.history)
    seasonal_periods = request.seasonal_periods
    seasonal = detect_seasonality(series, seasonal_periods)
    anomalies, anomaly_warnings = _scan_anomalies(request, series, seasonal_periods)

    if len(series) < MIN_OBS_FOR_SELECTION:
        return _sparse_history_forecast(
            request, series, seasonal, anomalies, anomaly_warnings
        )

    candidates = _build_candidates(series, seasonal, seasonal_periods, request)
    folds = build_folds(len(series), request.horizon_days)

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        scores = [
            backtest_model(model, series, request.horizon_days, seasonal_periods, folds)
            for model in candidates
        ]

    ranked = _rank(scores)
    winner, reason = _pick_winner(candidates, ranked, folds)

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        prediction, producer = _fit_winner(
            winner, series, request.horizon_days, seasonal_periods
        )

    refit_warnings: list[ForecastWarning] = []
    if producer.name != winner.name:
        reason, refit_warnings = _refit_fallback_report(winner, producer, reason)

    logger.info(
        "Forecast for %s: model=%s observations=%d horizon=%d folds=%d seasonal=%s",
        request.sku, producer.name, len(series), request.horizon_days, len(folds), seasonal,
    )

    return ForecastResponse(
        sku=request.sku,
        selected_model=producer.name,
        selection_reason=reason,
        granularity=request.granularity,
        observations=len(series),
        seasonality_detected=seasonal,
        scores=ranked,
        points=_to_points(series, prediction, request),
        warnings=anomaly_warnings + refit_warnings,
        anomalies=anomalies,
    )


def _scan_anomalies(
    request: ForecastRequest, series: pd.Series, seasonal_periods: int
) -> tuple[list, list]:
    """Detect historical anomalies and derive forecast warnings from them.

    Detection is advisory only — nothing is removed from the series. Silently
    smoothing a spike would change the number the caller gets without telling them;
    reporting it lets them decide whether the promotion repeats next month.
    """
    if not request.detect_anomalies:
        return [], []

    anomalies = detect_anomalies(series, seasonal_periods)
    if not anomalies:
        return [], []

    warnings_out = build_warnings(anomalies, series)
    logger.info(
        "Detected %d anomalous day(s) for %s, %d warning(s) raised",
        len(anomalies), request.sku, len(warnings_out),
    )
    return anomalies, warnings_out


def _build_candidates(
    series: pd.Series, seasonal: bool, seasonal_periods: int, request: ForecastRequest
) -> list[ForecastModel]:
    """Assemble the candidate pool for this series.

    The moving-average baseline is always included as a sanity floor. Seasonal
    variants of the statistical models are only offered when a cycle was actually
    detected, so seasonal parameters are not fitted to noise. Prophet and XGBoost
    are gated on their own ``min_observations``, being the expensive candidates.
    """
    pool: list[ForecastModel] = [MovingAverageModel(window=7)]

    if seasonal:
        pool.append(SeasonalNaiveModel())
        pool.append(HoltWintersModel(seasonal=True))
        pool.append(SarimaModel(seasonal=True))
    else:
        pool.append(HoltWintersModel(seasonal=False))
        pool.append(SarimaModel(seasonal=False))

    pool.append(XGBoostModel(country_holidays=request.country_holidays))

    if len(series) >= ProphetModel.min_observations:
        pool.append(ProphetModel(country_holidays=request.country_holidays))

    return [model for model in pool if model.supports(series, seasonal_periods)]


def _rank(scores: list[ModelScore]) -> list[ModelScore]:
    """Order scores best-first, unfitted candidates last.

    MAPE is the primary key because it is scale-free and comparable across SKUs,
    with RMSE breaking ties and standing in when MAPE is undefined (all-zero
    holdout). Unfitted candidates sort to the bottom but are still returned, so the
    response shows what was tried and why it was skipped.
    """
    def sort_key(score: ModelScore) -> tuple[int, float, float]:
        if not score.fitted:
            return (1, float("inf"), float("inf"))
        primary = score.mape if score.mape is not None else float("inf")
        secondary = score.rmse if score.rmse is not None else float("inf")
        return (0, primary, secondary)

    return sorted(scores, key=sort_key)


def _pick_winner(
    candidates: list[ForecastModel], ranked: list[ModelScore], folds: list
) -> tuple[ForecastModel, str]:
    """Choose the model to actually forecast with, and explain the choice."""
    by_name = {model.name: model for model in candidates}

    for score in ranked:
        if not score.fitted:
            continue
        model = by_name.get(score.model)
        if model is None:  # pragma: no cover - names come from these same models
            continue
        if score.mape is not None:
            reason = (
                f"lowest backtest MAPE ({score.mape:.2f}%) across "
                f"{score.folds} walk-forward fold(s)"
            )
        else:
            reason = (
                f"lowest backtest RMSE ({score.rmse:.3f}) across {score.folds} "
                f"walk-forward fold(s); MAPE undefined on an all-zero holdout"
            )
        return model, reason

    # Every candidate failed to fit. The baseline is unconditional, so use it.
    fallback = MovingAverageModel(window=7)
    return fallback, "no candidate completed backtesting; fell back to the moving-average baseline"


def _fit_winner(
    model: ForecastModel, series: pd.Series, horizon: int, seasonal_periods: int
) -> tuple[Prediction, ForecastModel]:
    """Refit the winning model on the full history, saying which model produced it.

    Backtesting deliberately withholds the most recent days; the forecast the caller
    receives should use them. A model can fit on a shorter training fold and still
    fail on the whole series — statsmodels convergence is not monotone in sample size
    — so the baseline takes over rather than failing the request.

    The second element is the model the returned prediction actually came from. It
    differs from ``model`` exactly when the refit fell back, and the caller reports
    that one: a fallback carrying the winner's name is indistinguishable from a
    successful refit in the response.
    """
    try:
        return model.fit_predict(series, horizon, seasonal_periods), model
    except Exception as exc:  # noqa: BLE001 - any refit failure falls back
        logger.warning(
            "Winner %s failed to refit on full history, using baseline: %s", model.name, exc
        )
        fallback = MovingAverageModel(window=7)
        return fallback.fit_predict(series, horizon, seasonal_periods), fallback


def _refit_fallback_report(
    winner: ForecastModel, producer: ForecastModel, backtest_reason: str
) -> tuple[str, list[ForecastWarning]]:
    """Restate the selection when the backtest winner could not be refitted.

    ``selected_model`` names the model that produced the points, not the one that won
    backtesting, because everything downstream treats those as the same thing. The
    backend stores it as the model a persisted accuracy row describes, the dashboard
    highlights that row in the candidate table and badges it, and ``/explain`` refuses
    unless it reads ``xgboost``. Naming an unfitted winner would therefore file a
    moving average's error under a model that produced none of these numbers, and
    would offer SHAP contributions for a fit the caller never received — the exact
    mismatch that endpoint's 422 exists to prevent.

    The winner still appears in ``scores`` with the error that won it, so the attempt
    stays auditable. The warning is what keeps the substitution from being silent:
    a fallback forecast is not wrong, but it is weaker than the score implies.
    """
    reason = (
        f"{winner.name} won backtesting ({backtest_reason}) but could not be refitted on "
        f"the full history; the {producer.name} baseline produced these points instead"
    )
    warning = ForecastWarning(
        code="model_refit_failed",
        message=(
            f"{winner.name} scored best in backtesting but failed when refitted on the "
            f"complete history, so this forecast comes from the {producer.name} baseline. "
            f"Treat it as a fallback rather than the selected model's output."
        ),
        severity=Severity.HIGH,
    )
    return reason, [warning]


def _sparse_history_forecast(
    request: ForecastRequest,
    series: pd.Series,
    seasonal: bool,
    anomalies: list,
    anomaly_warnings: list,
) -> ForecastResponse:
    """Forecast for a series too short to justify model selection.

    New products land here. A supplied ``category_average`` is preferred over the
    product's own thin history, since a category mean built from many SKUs is a
    better estimate than a few days of one. Otherwise the moving-average baseline
    runs on whatever exists.
    """
    horizon = request.horizon_days

    if request.category_average is not None:
        level = float(request.category_average)
        values = np.full(horizon, level)
        # Spread is unknown from a category mean; assume Poisson-like dispersion so
        # the interval is not misleadingly tight.
        sigma = float(np.sqrt(level)) if level > 0 else 0.0
        model_name = "category_average"
        reason = (
            f"only {len(series)} observation(s), below the {MIN_OBS_FOR_SELECTION} needed "
            f"for model selection; used the supplied category average"
        )
    else:
        baseline = MovingAverageModel(window=min(7, max(1, len(series))))
        prediction = baseline.fit_predict(series, horizon, request.seasonal_periods)
        values, sigma = prediction.values, prediction.sigma
        model_name = baseline.name
        reason = (
            f"only {len(series)} observation(s), below the {MIN_OBS_FOR_SELECTION} needed "
            f"for model selection; used the moving-average baseline"
        )

    logger.info(
        "Sparse-history forecast for %s: model=%s observations=%d",
        request.sku, model_name, len(series),
    )

    return ForecastResponse(
        sku=request.sku,
        selected_model=model_name,
        selection_reason=reason,
        granularity=request.granularity,
        observations=len(series),
        seasonality_detected=seasonal,
        scores=[
            ModelScore(
                model=model_name, folds=0, fitted=False,
                error="history too short to backtest",
            )
        ],
        points=_to_points(series, Prediction(values=values, sigma=sigma), request),
        warnings=anomaly_warnings + [
            ForecastWarning(
                code="sparse_history",
                message=(
                    f"only {len(series)} observation(s); the forecast is a fallback estimate "
                    f"rather than a fitted model and should be treated as provisional"
                ),
                severity=Severity.HIGH,
            )
        ],
        anomalies=anomalies,
    )


def _to_daily_series(history: list[DemandPoint]) -> pd.Series:
    """Collapse history into a gap-free daily series.

    Repeated dates are summed, and days absent from the history are filled with
    zero — for demand data, a day with no recorded sales is a day of no demand,
    not a missing measurement.
    """
    frame = pd.DataFrame({
        "date": pd.to_datetime([point.date for point in history]),
        "quantity": [float(point.quantity) for point in history],
    })
    daily = frame.groupby("date")["quantity"].sum().sort_index()
    full_range = pd.date_range(daily.index.min(), daily.index.max(), freq="D")
    return daily.reindex(full_range, fill_value=0.0)


def _to_points(
    series: pd.Series, prediction: Prediction, request: ForecastRequest
) -> list[ForecastPoint]:
    """Turn a raw prediction into dated points, bucketed per the request."""
    start = series.index.max() + pd.Timedelta(days=1)
    dates = pd.date_range(start, periods=request.horizon_days, freq="D")
    values = np.asarray(prediction.values, dtype=float)

    daily = [
        _daily_point(date, value, prediction.sigma, step)
        for step, (date, value) in enumerate(zip(dates, values), start=1)
    ]

    if request.granularity is Granularity.WEEKLY:
        return _to_weekly(daily)
    return daily


def _daily_point(
    date: pd.Timestamp, value: float, sigma: float, step: int
) -> ForecastPoint:
    """Build one forecast day, widening the interval the further out it sits.

    The ``sqrt(step)`` widening is an approximation, not the exact analytic interval
    for any one model, but it captures the right shape: uncertainty grows with
    horizon. Using it uniformly also keeps intervals comparable across models.
    """
    margin = Z_95 * sigma * np.sqrt(step)
    return ForecastPoint(
        date=date.date(),
        predicted=round(float(value), 3),
        lower=round(max(0.0, float(value - margin)), 3),
        upper=round(float(value + margin), 3),
    )


def _to_weekly(daily: list[ForecastPoint]) -> list[ForecastPoint]:
    """Sum daily points into 7-day buckets dated by the bucket's first day.

    A trailing partial week is kept rather than dropped: the caller asked for that
    horizon and silently shortening it would misreport the total.
    """
    buckets: list[ForecastPoint] = []
    for index in range(0, len(daily), 7):
        chunk = daily[index : index + 7]
        buckets.append(
            ForecastPoint(
                date=chunk[0].date,
                predicted=round(sum(point.predicted for point in chunk), 3),
                lower=round(sum(point.lower for point in chunk), 3),
                upper=round(sum(point.upper for point in chunk), 3),
            )
        )
    return buckets

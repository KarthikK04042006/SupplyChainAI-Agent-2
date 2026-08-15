"""SHAP attribution for the XGBoost forecaster.

A forecast that cannot be questioned cannot be trusted. This module answers "why
this number?" by decomposing a single prediction into per-feature contributions:
how many units the model added or subtracted because of last week's demand, because
it is a Saturday, because the 28-day average is rising.

SHAP values are exact for tree ensembles rather than an approximation — they come
from walking the trees, not from perturbing inputs and refitting. Each contribution
is in units of demand, and they sum with the base value to the prediction itself,
which is the property that makes the breakdown auditable: a dashboard can show the
arithmetic closing.

The subtlety is *which* feature row to explain. Multi-step forecasts are recursive,
so day 7's features are built from days 1-6's own predictions. Explaining day 7 means
replaying that walk to rebuild the exact row the model saw, which is why this module
reuses :mod:`services.features` rather than assembling features itself.
"""

from __future__ import annotations

import functools
import logging
import warnings

import numpy as np
import pandas as pd

from models.schemas import (
    ExplainRequest,
    ExplainResponse,
    FeatureContribution,
)

from .features import build_features, feature_columns, next_row
from .models.base import ModelUnusableError
from .models.ml import XGBoostModel

logger = logging.getLogger(__name__)

#: Human-readable descriptions are not stored per feature; the naming scheme carries
#: the meaning and this maps the prefixes to plain language for the dashboard.
FEATURE_PREFIXES = {
    "lag_": "demand {n} day(s) ago",
    "roll_mean_": "{n}-day average demand",
    "roll_std_": "{n}-day demand variability",
}

FEATURE_LABELS = {
    "day_of_week": "day of week",
    "month": "month of year",
    "day_of_month": "day of month",
    "week_of_year": "week of year",
    "is_weekend": "weekend flag",
    "is_month_start": "start of month",
    "is_month_end": "end of month",
    "dow_sin": "day-of-week cycle (sine)",
    "dow_cos": "day-of-week cycle (cosine)",
    "month_sin": "month cycle (sine)",
    "month_cos": "month cycle (cosine)",
    "is_holiday": "public holiday",
}


@functools.lru_cache(maxsize=1)
def shap_available() -> bool:
    """Whether SHAP can be imported, probed once per process.

    The endpoint needs to answer "is explanation possible here?" without paying an
    import on every request, and a missing optional dependency should degrade to a
    clear 503 rather than a stack trace.
    """
    try:
        import shap  # noqa: F401
    except ImportError:
        logger.warning("shap is not installed; the explain endpoint is unavailable")
        return False
    return True


def explain_prediction(sku: str, request: ExplainRequest) -> ExplainResponse:
    """Decompose one forecast day into per-feature contributions.

    Raises :class:`ModelUnusableError` when the history is too short for XGBoost or
    the model cannot be fitted — the caller turns that into a 4xx, since it is a
    property of the request rather than a server fault.
    """
    if not shap_available():
        raise ModelUnusableError("shap is not installed in this environment")

    series = _to_daily_series(request.history)
    step = min(request.step, request.horizon_days)

    if len(series) < XGBoostModel.min_observations:
        raise ModelUnusableError(
            f"XGBoost needs at least {XGBoostModel.min_observations} days of history "
            f"to explain a prediction; got {len(series)}"
        )

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        model, columns = _fit_model(series, request.country_holidays)
        row, predicted = _replay_to_step(
            model, series, step, columns, request.country_holidays
        )
        contributions, base_value = _shap_contributions(model, row, columns)

    ranked = sorted(contributions, key=lambda item: item.abs_contribution, reverse=True)
    top = ranked[: request.top_n]

    logger.info(
        "Explained %s step=%d predicted=%.3f base=%.3f with %d/%d features returned",
        sku, step, predicted, base_value, len(top), len(ranked),
    )

    return ExplainResponse(
        sku=sku,
        explained_date=(series.index.max() + pd.Timedelta(days=step)).date(),
        step=step,
        predicted=round(predicted, 3),
        base_value=round(base_value, 3),
        contributions=top,
        total_contribution=round(
            float(sum(item.contribution for item in ranked)), 3
        ),
        model_used=XGBoostModel.name,
        note=(
            f"showing the {len(top)} largest of {len(ranked)} features; base value plus "
            f"all contributions equals the prediction"
            if len(top) < len(ranked)
            else None
        ),
    )


def _to_daily_series(history: list) -> pd.Series:
    """Gap-free daily series, matching how the forecaster normalises history.

    Kept identical to ``forecasting._to_daily_series`` on purpose: an explanation
    built from a differently-shaped series would explain a prediction nobody asked
    for.
    """
    frame = pd.DataFrame({
        "date": pd.to_datetime([point.date for point in history]),
        "quantity": [float(point.quantity) for point in history],
    })
    daily = frame.groupby("date")["quantity"].sum().sort_index()
    full_range = pd.date_range(daily.index.min(), daily.index.max(), freq="D")
    return daily.reindex(full_range, fill_value=0.0)


def _fit_model(series: pd.Series, country_holidays: str | None):
    """Fit XGBoost on the full history and return it with its column order.

    Refitting here rather than reusing a model from a previous forecast call is
    deliberate: the service is stateless, and an explanation of a stale model would
    be worse than no explanation.

    Hyperparameters mirror :class:`XGBoostModel` exactly. If they drifted, this
    endpoint would faithfully explain a model the forecast endpoint never used.
    """
    try:
        from xgboost import XGBRegressor
    except ImportError as exc:  # pragma: no cover - dependency is pinned
        raise ModelUnusableError(f"XGBoost unavailable: {exc}") from exc

    frame = build_features(series, country_holidays=country_holidays)
    columns = feature_columns(frame)
    trainable = frame.dropna(
        subset=[column for column in columns if column.startswith("lag_")]
    )

    if len(trainable) < 15:
        raise ModelUnusableError(
            f"only {len(trainable)} complete feature rows after warm-up; need 15"
        )

    model = XGBRegressor(
        n_estimators=300, max_depth=4, learning_rate=0.05, subsample=0.9,
        colsample_bytree=0.9, reg_lambda=1.0, objective="reg:squarederror",
        n_jobs=1, verbosity=0, random_state=42,
    )

    try:
        model.fit(trainable[columns].fillna(0.0), trainable["y"])
    except Exception as exc:  # noqa: BLE001
        raise ModelUnusableError(f"XGBoost fit failed: {exc}") from exc

    return model, columns


def _replay_to_step(
    model, series: pd.Series, step: int, columns: list[str],
    country_holidays: str | None,
) -> tuple[pd.DataFrame, float]:
    """Rebuild the feature row the model saw at ``step`` days ahead.

    Steps beyond the first are only reachable by replaying the recursive walk: each
    intermediate prediction is appended to the history, exactly as the forecaster
    does, so the lag and rolling features on the explained day hold the same values.
    """
    history = series.copy()
    row = None
    predicted = 0.0

    for current in range(1, step + 1):
        target_date = series.index.max() + pd.Timedelta(days=current)
        row = next_row(history, target_date, columns, country_holidays=country_holidays)
        predicted = max(0.0, float(np.asarray(model.predict(row)).ravel()[0]))
        history = pd.concat(
            [history, pd.Series([predicted], index=pd.DatetimeIndex([target_date]))]
        )

    return row, predicted


def _shap_contributions(
    model, row: pd.DataFrame, columns: list[str]
) -> tuple[list[FeatureContribution], float]:
    """SHAP values for one feature row, in demand units.

    ``TreeExplainer`` is exact for gradient-boosted trees, so the returned values
    genuinely sum with the base value to the prediction rather than approximating it.
    """
    import shap

    explainer = shap.TreeExplainer(model)
    values = np.asarray(explainer.shap_values(row)).ravel()
    base_value = float(np.asarray(explainer.expected_value).ravel()[0])

    contributions = [
        FeatureContribution(
            feature=_describe(column),
            value=round(float(row.iloc[0][column]), 4),
            contribution=round(float(contribution), 4),
            direction="increase" if contribution >= 0 else "decrease",
            abs_contribution=round(abs(float(contribution)), 4),
        )
        for column, contribution in zip(columns, values)
    ]

    return contributions, base_value


def _describe(column: str) -> str:
    """Plain-language name for a feature column.

    The raw names (``lag_14``, ``roll_std_28``) are precise but opaque to whoever
    reads the dashboard, and this is the one endpoint whose entire purpose is being
    understood.
    """
    for prefix, template in FEATURE_PREFIXES.items():
        if column.startswith(prefix):
            return template.format(n=column[len(prefix):])
    return FEATURE_LABELS.get(column, column)

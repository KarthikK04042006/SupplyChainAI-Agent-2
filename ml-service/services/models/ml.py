"""Gradient-boosted tree forecaster.

XGBoost sees demand as a supervised regression on engineered temporal features
rather than as a series. That trades away clean trend extrapolation — a tree cannot
predict beyond the range it was trained on — for the ability to learn interactions
the statistical models cannot express, like "weekends spike, but only at month end".

Multi-step forecasts are produced recursively: predict tomorrow, append it to the
history, derive the next feature row, repeat. Errors compound over the horizon,
which the backtester will surface honestly against the other candidates.
"""

from __future__ import annotations

import logging
import warnings

import numpy as np
import pandas as pd

from ..features import build_features, feature_columns, next_row
from .base import ForecastModel, ModelUnusableError, Prediction, clean_forecast, residual_sigma

logger = logging.getLogger(__name__)


class XGBoostModel(ForecastModel):
    """XGBoost regression over lag, rolling, and calendar features.

    ``min_observations`` is set well above the deepest lag (28 days) so there are
    enough complete rows left to learn from after the warm-up period is dropped.
    """

    name = "xgboost"
    min_observations = 45

    def __init__(self, country_holidays: str | None = None, n_estimators: int = 300) -> None:
        self.country_holidays = country_holidays
        self.n_estimators = n_estimators

    def fit_predict(
        self, series: pd.Series, horizon: int, seasonal_periods: int
    ) -> Prediction:
        try:
            from xgboost import XGBRegressor
        except ImportError as exc:  # pragma: no cover - dependency is pinned
            raise ModelUnusableError(f"XGBoost unavailable: {exc}") from exc

        frame = build_features(series, country_holidays=self.country_holidays)
        columns = feature_columns(frame)

        # Drop the warm-up rows where the deepest lags are undefined.
        trainable = frame.dropna(subset=[column for column in columns if column.startswith("lag_")])
        if len(trainable) < 15:
            raise ModelUnusableError(
                f"only {len(trainable)} complete feature rows after warm-up; need 15"
            )

        features = trainable[columns].fillna(0.0)
        target = trainable["y"]

        try:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                model = XGBRegressor(
                    n_estimators=self.n_estimators,
                    max_depth=4,
                    learning_rate=0.05,
                    subsample=0.9,
                    colsample_bytree=0.9,
                    reg_lambda=1.0,
                    objective="reg:squarederror",
                    n_jobs=1,  # the batch endpoint parallelises across SKUs instead
                    verbosity=0,
                    random_state=42,
                )
                model.fit(features, target)
        except Exception as exc:
            raise ModelUnusableError(f"XGBoost fit failed: {exc}") from exc

        in_sample = np.asarray(model.predict(features), dtype=float)
        sigma = residual_sigma(target.to_numpy(dtype=float), in_sample)

        values = self._predict_recursive(model, series, horizon, columns)
        last = float(series.iloc[-1])
        return Prediction(values=clean_forecast(values, last, horizon), sigma=sigma)

    def _predict_recursive(
        self, model, series: pd.Series, horizon: int, columns: list[str]
    ) -> np.ndarray:
        """Predict ``horizon`` days, feeding each prediction back as history."""
        history = series.copy()
        predictions: list[float] = []

        for step in range(1, horizon + 1):
            target_date = series.index.max() + pd.Timedelta(days=step)
            row = next_row(
                history, target_date, columns, country_holidays=self.country_holidays
            )
            predicted = float(np.asarray(model.predict(row), dtype=float).ravel()[0])
            predicted = max(0.0, predicted)
            predictions.append(predicted)

            history = pd.concat(
                [history, pd.Series([predicted], index=pd.DatetimeIndex([target_date]))]
            )

        return np.array(predictions, dtype=float)

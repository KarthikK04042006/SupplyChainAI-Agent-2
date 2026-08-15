"""Naive baselines.

These are the sanity floor. A moving average is always a candidate, so the
selector can never return something worse than "average of recent demand" without
that being visible in the scores. If a sophisticated model cannot beat this, that
is a finding about the data, not a bug.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .base import ForecastModel, Prediction, clean_forecast, residual_sigma


class MovingAverageModel(ForecastModel):
    """Flat forecast at the mean of the most recent ``window`` days.

    Deliberately has no trend or seasonal component: its job is to be a stable,
    hard-to-embarrass reference point.
    """

    name = "moving_average"
    min_observations = 1

    def __init__(self, window: int = 7) -> None:
        self.window = window
        # A 7-day window on a 3-day series would silently become a 3-day window;
        # that is fine, but the name should still say what was asked for.
        self.name = f"moving_average(w={window})"

    def fit_predict(
        self, series: pd.Series, horizon: int, seasonal_periods: int
    ) -> Prediction:
        values = series.to_numpy(dtype=float)
        window = min(self.window, values.size)
        level = float(values[-window:].mean())

        # In-sample fit: each day predicted by the mean of the preceding window.
        rolled = series.rolling(window=window, min_periods=1).mean().shift(1)
        rolled = rolled.bfill().to_numpy(dtype=float)
        sigma = residual_sigma(values, rolled)

        return Prediction(
            values=clean_forecast(np.full(horizon, level), level, horizon),
            sigma=sigma,
        )


class SeasonalNaiveModel(ForecastModel):
    """Repeat the last observed season forward.

    Cheap but surprisingly strong on stable weekly retail patterns, and it gives
    the selector a seasonal candidate that cannot fail to converge.
    """

    name = "seasonal_naive"

    def supports(self, series: pd.Series, seasonal_periods: int) -> bool:
        return len(series) >= seasonal_periods

    def fit_predict(
        self, series: pd.Series, horizon: int, seasonal_periods: int
    ) -> Prediction:
        values = series.to_numpy(dtype=float)
        if values.size < seasonal_periods:
            raise_value = float(values[-1]) if values.size else 0.0
            return Prediction(
                values=clean_forecast(np.full(horizon, raise_value), raise_value, horizon),
                sigma=0.0,
            )

        last_season = values[-seasonal_periods:]
        # Tile the season out to the horizon, starting from its first day.
        repeats = int(np.ceil(horizon / seasonal_periods))
        projected = np.tile(last_season, repeats)[:horizon]

        # In-sample fit: every day predicted by the same weekday one season earlier.
        if values.size > seasonal_periods:
            actual = values[seasonal_periods:]
            fitted = values[:-seasonal_periods]
            sigma = residual_sigma(actual, fitted)
        else:
            sigma = 0.0

        return Prediction(
            values=clean_forecast(projected, float(values[-1]), horizon),
            sigma=sigma,
        )

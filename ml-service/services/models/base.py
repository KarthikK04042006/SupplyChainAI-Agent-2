"""The common interface every forecasting model implements.

A model here is a thin, stateless adapter: ``fit_predict`` takes a gap-free daily
series and returns a point forecast plus a residual spread used to size prediction
intervals. Keeping the surface this narrow is what lets the backtester and the
selector treat SARIMA, Prophet, XGBoost, and a moving average interchangeably.

Models must not raise on merely-awkward input. If a series is too short or too
degenerate for the model to work, raise :class:`ModelUnusableError` so the caller
can score it as unfitted and move on to the next candidate.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass

import numpy as np
import pandas as pd


class ModelUnusableError(RuntimeError):
    """Raised when a model cannot be applied to the given series.

    Distinct from an unexpected bug: this is the expected signal for "not enough
    data for this technique", and the selector treats it as a skip rather than a
    failure of the whole request.
    """


@dataclass(frozen=True)
class Prediction:
    """A model's output for one forecast call.

    ``values`` is the point forecast, one entry per requested step. ``sigma`` is the
    residual standard deviation the model observed in-sample; the caller turns it
    into prediction intervals. Models that cannot estimate a spread return 0.0,
    which yields a degenerate but honest zero-width interval.
    """

    values: np.ndarray
    sigma: float

    def __post_init__(self) -> None:
        if self.values.ndim != 1:
            raise ValueError("Prediction.values must be one-dimensional")


class ForecastModel(ABC):
    """Base class for all candidate models."""

    #: Stable identifier reported in API responses and backtest scores.
    name: str = "unnamed"

    #: Minimum daily observations before this model is worth attempting. The
    #: selector uses this to skip candidates rather than let them fit garbage.
    min_observations: int = 2

    def supports(self, series: pd.Series, seasonal_periods: int) -> bool:
        """Whether this model should be offered as a candidate for this series."""
        return len(series) >= self.min_observations

    @abstractmethod
    def fit_predict(
        self, series: pd.Series, horizon: int, seasonal_periods: int
    ) -> Prediction:
        """Fit on ``series`` and forecast ``horizon`` steps beyond its end.

        Args:
            series: Gap-free daily demand indexed by ``DatetimeIndex``.
            horizon: Number of days to predict.
            seasonal_periods: Season length in days, e.g. 7 for a weekly cycle.

        Raises:
            ModelUnusableError: The series cannot support this model.
        """

    def __repr__(self) -> str:
        return f"<{type(self).__name__} name={self.name!r}>"


def residual_sigma(actual: np.ndarray, fitted: np.ndarray) -> float:
    """Standard deviation of in-sample residuals, guarding against short input.

    Shared by every model so interval widths are derived the same way regardless
    of which technique produced the forecast.
    """
    resid = np.asarray(actual, dtype=float) - np.asarray(fitted, dtype=float)
    resid = resid[np.isfinite(resid)]
    if resid.size < 2:
        return 0.0
    return float(resid.std(ddof=1))


def clean_forecast(values: np.ndarray, fallback: float, horizon: int) -> np.ndarray:
    """Make a raw model output safe to return.

    Replaces non-finite values with ``fallback``, clamps negatives to zero since
    demand cannot be negative, and pads or truncates to exactly ``horizon`` steps
    so a model that returns the wrong length cannot corrupt the response.
    """
    arr = np.asarray(values, dtype=float).ravel()
    arr = np.nan_to_num(arr, nan=fallback, posinf=fallback, neginf=fallback)

    if arr.size < horizon:
        pad = np.full(horizon - arr.size, arr[-1] if arr.size else fallback)
        arr = np.concatenate([arr, pad])

    return np.clip(arr[:horizon], 0.0, None)

"""Candidate forecasting models behind a common interface.

Each module here adapts one family of techniques to :class:`~.base.ForecastModel`,
so the backtester and selector can treat them interchangeably.
"""

from .base import ForecastModel, ModelUnusableError, Prediction
from .baseline import MovingAverageModel, SeasonalNaiveModel
from .ml import XGBoostModel
from .statistical import HoltWintersModel, ProphetModel, SarimaModel

__all__ = [
    "ForecastModel",
    "ModelUnusableError",
    "Prediction",
    "MovingAverageModel",
    "SeasonalNaiveModel",
    "XGBoostModel",
    "HoltWintersModel",
    "ProphetModel",
    "SarimaModel",
]

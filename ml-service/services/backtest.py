"""Walk-forward backtesting.

Model selection is only trustworthy if the scores come from data the model never
saw. A single train/test split gives one noisy estimate that depends heavily on
where the split happened to land; walk-forward validation instead slides the split
forward several times and averages, so a model that wins by luck on one window
does not win overall.

Each fold trains on everything before a cut point and predicts the ``horizon`` days
after it. Folds never overlap in their test windows, and training always uses only
the past — no future data leaks backwards.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import numpy as np
import pandas as pd

from models.schemas import ModelScore

from .models.base import ForecastModel, ModelUnusableError

logger = logging.getLogger(__name__)

#: Folds to attempt when there is enough history. More folds means a steadier
#: estimate but linearly more fitting work, which matters for batch calls.
DEFAULT_FOLDS = 3

#: Minimum training days in the first fold. Below this, a fit is not meaningful.
MIN_TRAIN_SIZE = 14


@dataclass(frozen=True)
class Fold:
    """One train/test split of the walk-forward schedule."""

    train_end: int
    test_start: int
    test_end: int

    @property
    def test_size(self) -> int:
        return self.test_end - self.test_start


def build_folds(
    n_observations: int, horizon: int, max_folds: int = DEFAULT_FOLDS
) -> list[Fold]:
    """Plan the walk-forward schedule for a series of ``n_observations`` days.

    Test windows are capped at ``horizon`` so scores reflect the horizon actually
    being forecast. Returns an empty list when there is not enough history to hold
    out anything meaningful — the caller then falls back to in-sample selection.
    """
    # Keep test windows small enough that several fit inside the tail.
    test_size = max(1, min(horizon, max(1, n_observations // 5)))

    folds: list[Fold] = []
    for index in range(max_folds):
        # Later folds train on more data; the last fold ends at the series end.
        test_end = n_observations - index * test_size
        test_start = test_end - test_size
        if test_start < MIN_TRAIN_SIZE:
            break
        folds.append(Fold(train_end=test_start, test_start=test_start, test_end=test_end))

    # Oldest cut point first, purely so logs read chronologically.
    return list(reversed(folds))


def backtest_model(
    model: ForecastModel,
    series: pd.Series,
    horizon: int,
    seasonal_periods: int,
    folds: list[Fold],
) -> ModelScore:
    """Score one model across the walk-forward schedule.

    A model that fails to fit on any fold is reported as unfitted with the reason
    attached, rather than raising — one broken candidate must not fail the request.
    """
    if not folds:
        return ModelScore(
            model=model.name, folds=0, fitted=False, error="no walk-forward folds available"
        )

    actuals: list[np.ndarray] = []
    predictions: list[np.ndarray] = []
    last_error: str | None = None

    for fold in folds:
        train = series.iloc[: fold.train_end]
        test = series.iloc[fold.test_start : fold.test_end]

        if not model.supports(train, seasonal_periods):
            last_error = f"unsupported on {len(train)} training observations"
            continue

        try:
            prediction = model.fit_predict(train, fold.test_size, seasonal_periods)
        except ModelUnusableError as exc:
            last_error = str(exc)
            continue
        except Exception as exc:  # noqa: BLE001 - a bad candidate must not sink the request
            logger.warning("Model %s raised during backtest: %s", model.name, exc)
            last_error = f"unexpected error: {exc}"
            continue

        actuals.append(test.to_numpy(dtype=float))
        predictions.append(np.asarray(prediction.values, dtype=float)[: fold.test_size])

    if not actuals:
        return ModelScore(
            model=model.name, folds=0, fitted=False,
            error=last_error or "no fold produced a forecast",
        )

    actual = np.concatenate(actuals)
    predicted = np.concatenate(predictions)

    return ModelScore(
        model=model.name,
        mape=mape(actual, predicted),
        rmse=rmse(actual, predicted),
        mae=mae(actual, predicted),
        folds=len(actuals),
        fitted=True,
        error=None,
    )


def mape(actual: np.ndarray, predicted: np.ndarray) -> float | None:
    """Mean absolute percentage error, in percent.

    Days with zero actual demand are excluded: the percentage error against zero is
    undefined, and including them would let a single quiet day dominate the score.
    Returns ``None`` when every actual was zero, which the selector reads as "this
    metric cannot rank anything here" and falls back to RMSE.
    """
    actual = np.asarray(actual, dtype=float)
    predicted = np.asarray(predicted, dtype=float)

    nonzero = actual != 0
    if not nonzero.any():
        return None

    errors = np.abs((actual[nonzero] - predicted[nonzero]) / actual[nonzero])
    return round(float(errors.mean() * 100.0), 3)


def rmse(actual: np.ndarray, predicted: np.ndarray) -> float:
    """Root mean squared error. Defined even when actuals are all zero."""
    actual = np.asarray(actual, dtype=float)
    predicted = np.asarray(predicted, dtype=float)
    return round(float(np.sqrt(np.mean((actual - predicted) ** 2))), 3)


def mae(actual: np.ndarray, predicted: np.ndarray) -> float:
    """Mean absolute error."""
    actual = np.asarray(actual, dtype=float)
    predicted = np.asarray(predicted, dtype=float)
    return round(float(np.mean(np.abs(actual - predicted))), 3)

"""Tests for walk-forward validation and the scoring metrics.

The point of walk-forward over a single split is that scores come from several
independent windows, and that no future data ever reaches training. Both of those
are structural properties of the fold schedule, so they are asserted directly
rather than inferred from a model's score.
"""

import numpy as np
import pandas as pd
import pytest

from services.backtest import (
    DEFAULT_FOLDS,
    MIN_TRAIN_SIZE,
    backtest_model,
    build_folds,
    mae,
    mape,
    rmse,
)
from services.models.base import ModelUnusableError, Prediction
from services.models.baseline import MovingAverageModel

from conftest import weekday_profile


def to_pandas(quantities: list[float]) -> pd.Series:
    index = pd.date_range("2026-01-01", periods=len(quantities), freq="D")
    return pd.Series(quantities, index=index, dtype=float)


# --- fold schedule ---------------------------------------------------------


def test_folds_never_leak_future_data_into_training():
    """The property that makes the score trustworthy: training always ends at or
    before the test window starts, for every fold."""
    for n in (60, 120, 365):
        for horizon in (7, 14, 30):
            for fold in build_folds(n, horizon):
                assert fold.train_end <= fold.test_start
                assert fold.test_end <= n
                assert fold.test_size > 0


def test_fold_test_windows_do_not_overlap():
    """Overlapping windows would score the same days twice and weight them
    double in the average."""
    folds = build_folds(200, 14)
    assert len(folds) > 1

    spans = sorted((fold.test_start, fold.test_end) for fold in folds)
    for (_, earlier_end), (later_start, _) in zip(spans, spans[1:]):
        assert earlier_end <= later_start


def test_later_folds_train_on_more_data():
    """Walk-forward means the origin rolls forward; chronological order out."""
    folds = build_folds(200, 14)
    train_sizes = [fold.train_end for fold in folds]

    assert train_sizes == sorted(train_sizes)
    assert len(set(train_sizes)) == len(train_sizes)


def test_last_fold_reaches_the_series_end():
    """The most recent days are the most informative about current behaviour, so
    they must be scored rather than left unused."""
    folds = build_folds(200, 14)
    assert max(fold.test_end for fold in folds) == 200


def test_fold_count_is_capped():
    assert len(build_folds(2000, 7)) <= DEFAULT_FOLDS


def test_test_window_never_exceeds_the_horizon():
    """Scores should reflect the horizon actually being forecast."""
    for horizon in (1, 5, 14):
        for fold in build_folds(300, horizon):
            assert fold.test_size <= horizon


def test_short_history_yields_no_folds():
    """Below the training minimum there is nothing meaningful to hold out; the
    caller reads the empty list as 'skip selection'."""
    assert build_folds(MIN_TRAIN_SIZE, 14) == []
    assert build_folds(5, 3) == []


# --- scoring ---------------------------------------------------------------


def test_mape_matches_a_hand_computed_value():
    actual = np.array([100.0, 50.0])
    predicted = np.array([110.0, 45.0])
    # |10|/100 = 10%, |5|/50 = 10% -> 10%
    assert mape(actual, predicted) == pytest.approx(10.0)


def test_mape_skips_zero_actuals_instead_of_dividing_by_them():
    actual = np.array([0.0, 100.0])
    predicted = np.array([8.0, 90.0])
    # The zero day is excluded, leaving the single 10% error.
    assert mape(actual, predicted) == pytest.approx(10.0)


def test_mape_is_none_when_every_actual_is_zero():
    """Undefined rather than zero or infinite — the selector reads None as
    'this metric cannot rank anything here' and falls back to RMSE."""
    assert mape(np.zeros(5), np.ones(5)) is None


def test_rmse_and_mae_are_defined_on_all_zero_actuals():
    actual, predicted = np.zeros(4), np.array([2.0, 2.0, 2.0, 2.0])
    assert rmse(actual, predicted) == pytest.approx(2.0)
    assert mae(actual, predicted) == pytest.approx(2.0)


def test_rmse_punishes_large_errors_more_than_mae():
    actual = np.array([10.0, 10.0, 10.0, 10.0])
    spread = np.array([10.0, 10.0, 10.0, 30.0])  # one big miss
    assert rmse(actual, spread) > mae(actual, spread)


def test_perfect_prediction_scores_zero():
    values = np.array([3.0, 7.0, 11.0])
    assert mape(values, values) == pytest.approx(0.0)
    assert rmse(values, values) == pytest.approx(0.0)
    assert mae(values, values) == pytest.approx(0.0)


# --- backtest_model --------------------------------------------------------


def test_backtest_scores_a_working_model_across_all_folds():
    series = to_pandas(weekday_profile(150, horizon=0, noise=2.0).quantities)
    folds = build_folds(len(series), 14)

    score = backtest_model(MovingAverageModel(window=7), series, 14, 7, folds)

    assert score.fitted
    assert score.folds == len(folds)
    assert score.mape is not None and score.mape > 0
    assert score.rmse is not None and score.rmse > 0
    assert score.error is None


def test_backtest_reports_no_folds_rather_than_failing():
    series = to_pandas([5.0] * 10)
    score = backtest_model(MovingAverageModel(window=7), series, 14, 7, [])

    assert not score.fitted
    assert score.folds == 0
    assert score.error == "no walk-forward folds available"


class _Broken(MovingAverageModel):
    """A candidate that always fails, to prove one bad model cannot sink a request."""

    name = "broken"

    def fit_predict(self, series, horizon, seasonal_periods):
        raise ModelUnusableError("deliberately broken")


class _Exploding(MovingAverageModel):
    """A candidate raising something the interface never promised."""

    name = "exploding"

    def fit_predict(self, series, horizon, seasonal_periods):
        raise RuntimeError("unexpected library failure")


@pytest.mark.parametrize("model", [_Broken(), _Exploding()], ids=["unusable", "unexpected"])
def test_backtest_isolates_a_failing_candidate(model):
    series = to_pandas(weekday_profile(150, horizon=0).quantities)
    folds = build_folds(len(series), 14)

    score = backtest_model(model, series, 14, 7, folds)

    assert not score.fitted
    assert score.error
    assert score.mape is None


def test_backtest_truncates_an_overlong_prediction():
    """A model returning more days than asked for must not corrupt the score by
    misaligning actuals and predictions."""

    class _TooLong(MovingAverageModel):
        name = "too_long"

        def fit_predict(self, series, horizon, seasonal_periods):
            return Prediction(values=np.full(horizon + 5, float(series.mean())), sigma=1.0)

    series = to_pandas(weekday_profile(150, horizon=0).quantities)
    folds = build_folds(len(series), 14)

    score = backtest_model(_TooLong(), series, 14, 7, folds)

    assert score.fitted
    assert score.folds == len(folds)

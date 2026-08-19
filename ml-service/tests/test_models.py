"""Contract tests for the individual candidate models.

The selector and backtester treat every model as an interchangeable
:class:`ForecastModel`, so the contract each one must honour is tested here once:
return exactly ``horizon`` finite non-negative values, refuse politely via
``supports``/``ModelUnusableError`` rather than raising something unexpected, and
never emit a negative sigma.
"""

import logging
import threading

import numpy as np
import pandas as pd
import pytest

from services.models.base import ForecastModel, ModelUnusableError
from services.models.baseline import MovingAverageModel, SeasonalNaiveModel
from services.models.ml import XGBoostModel
from services.models.statistical import (
    HoltWintersModel,
    ProphetModel,
    SarimaModel,
    _suppressed_stan_logs,
)

from conftest import weekday_profile

HORIZON = 10
SEASONAL_PERIODS = 7


def to_pandas(quantities: list[float]) -> pd.Series:
    index = pd.date_range("2026-01-01", periods=len(quantities), freq="D")
    return pd.Series(quantities, index=index, dtype=float)


#: Every candidate, with enough history that all of them are viable.
ALL_MODELS: list[ForecastModel] = [
    MovingAverageModel(window=7),
    SeasonalNaiveModel(),
    HoltWintersModel(seasonal=True),
    HoltWintersModel(seasonal=False),
    SarimaModel(seasonal=True),
    SarimaModel(seasonal=False),
    XGBoostModel(),
    ProphetModel(),
]

IDS = [model.name for model in ALL_MODELS]


@pytest.fixture(scope="module")
def long_series() -> pd.Series:
    return to_pandas(weekday_profile(180, horizon=0, noise=2.0).quantities)


@pytest.mark.parametrize("model", ALL_MODELS, ids=IDS)
def test_model_honours_the_prediction_contract(model, long_series):
    if not model.supports(long_series, SEASONAL_PERIODS):
        pytest.skip(f"{model.name} is unavailable in this environment")

    prediction = model.fit_predict(long_series, HORIZON, SEASONAL_PERIODS)
    values = np.asarray(prediction.values, dtype=float)

    assert len(values) == HORIZON
    assert np.isfinite(values).all()
    assert (values >= 0).all(), "demand forecasts must never be negative"
    assert np.isfinite(prediction.sigma)
    assert prediction.sigma >= 0


@pytest.mark.parametrize("model", ALL_MODELS, ids=IDS)
def test_supports_is_an_honest_promise(model):
    """``supports`` is what the selector trusts to build the candidate pool, so a
    True answer has to mean the model really can deliver on that input.

    The converse is not required: a model that declines is simply skipped, and is
    free to raise or to cope if called anyway. Only the promise is binding."""
    tiny = to_pandas([5.0, 6.0])

    if not model.supports(tiny, SEASONAL_PERIODS):
        pytest.skip(f"{model.name} declines a 2-day history, as expected")

    prediction = model.fit_predict(tiny, HORIZON, SEASONAL_PERIODS)
    assert len(prediction.values) == HORIZON
    assert np.isfinite(np.asarray(prediction.values, dtype=float)).all()


@pytest.mark.parametrize("model", ALL_MODELS, ids=IDS)
def test_declined_input_never_escapes_as_an_unexpected_exception(model):
    """When a model does fail, it must fail as ``ModelUnusableError`` — the type
    the backtester treats as 'skip this candidate' rather than a defect."""
    tiny = to_pandas([5.0, 6.0])

    try:
        model.fit_predict(tiny, HORIZON, SEASONAL_PERIODS)
    except ModelUnusableError:
        pass  # the documented failure mode
    except Exception as exc:  # noqa: BLE001
        pytest.fail(f"{model.name} leaked {type(exc).__name__} instead of ModelUnusableError")


@pytest.mark.parametrize("model", ALL_MODELS, ids=IDS)
def test_model_survives_all_zero_history(model, long_series):
    """A discontinued or never-sold product is all zeros. Models must return a
    zero-ish forecast rather than dividing by zero or producing NaN."""
    zeros = to_pandas([0.0] * 120)

    if not model.supports(zeros, SEASONAL_PERIODS):
        pytest.skip(f"{model.name} declines an all-zero series")

    try:
        prediction = model.fit_predict(zeros, HORIZON, SEASONAL_PERIODS)
    except ModelUnusableError:
        return  # declining explicitly is also correct

    values = np.asarray(prediction.values, dtype=float)
    assert np.isfinite(values).all()
    assert (values >= 0).all()
    assert values.max() < 1.0


def test_moving_average_is_the_mean_of_the_window():
    series = to_pandas([1.0] * 20 + [10.0] * 7)
    prediction = MovingAverageModel(window=7).fit_predict(series, 3, SEASONAL_PERIODS)

    assert all(value == pytest.approx(10.0) for value in prediction.values)


def test_seasonal_naive_tiles_the_last_season():
    season = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0]
    series = to_pandas(season * 6)
    prediction = SeasonalNaiveModel().fit_predict(series, 10, SEASONAL_PERIODS)

    # Ten days ahead is the season repeated once plus its first three days.
    assert list(prediction.values) == pytest.approx(season + season[:3])


def test_moving_average_always_supports_any_history():
    """It is the unconditional sanity floor — the one candidate that can never
    drop out, which is what makes the fallback ladder terminate."""
    assert MovingAverageModel(window=7).min_observations == 1
    assert MovingAverageModel(window=7).supports(to_pandas([1.0]), SEASONAL_PERIODS)


def test_prophet_and_xgboost_gate_on_history_length():
    """The expensive candidates must not be offered on thin data."""
    assert ProphetModel.min_observations >= 30
    assert XGBoostModel.min_observations >= 45

    short = to_pandas([5.0] * 25)
    assert not ProphetModel().supports(short, SEASONAL_PERIODS)
    assert not XGBoostModel().supports(short, SEASONAL_PERIODS)


def test_xgboost_recovers_a_weekly_pattern():
    """Feature engineering has to actually work: lag and calendar features should
    let the tree model reproduce a clean weekly cycle."""
    data = weekday_profile(180, horizon=7, noise=1.0)
    series = to_pandas(data.quantities)

    prediction = XGBoostModel().fit_predict(series, 7, SEASONAL_PERIODS)

    for predicted, actual in zip(prediction.values, data.future):
        assert predicted == pytest.approx(actual, rel=0.25)


def test_stan_log_suppression_survives_overlapping_fits():
    """Prophet's log suppression must not outlive the fits that asked for it.

    ``services.batch`` fits up to eight SKUs at once and logger levels are
    process-global, so two fits are routinely inside this context manager together.
    The ordering pinned below — A enters, B enters, A leaves, B leaves — is the one
    that breaks naive save-and-restore: B records the level A already suppressed, so
    the last exit re-pins WARNING instead of putting the real level back, and
    cmdstanpy stays silenced for the rest of the process.
    """
    cmdstanpy = logging.getLogger("cmdstanpy")
    original = cmdstanpy.level
    cmdstanpy.setLevel(logging.DEBUG)

    a_inside = threading.Event()
    b_inside = threading.Event()
    a_left = threading.Event()
    seen_inside: list[int] = []

    def first():
        with _suppressed_stan_logs():
            a_inside.set()
            b_inside.wait(timeout=5)
            seen_inside.append(cmdstanpy.level)
        a_left.set()

    def second():
        a_inside.wait(timeout=5)  # A is fully inside before B enters
        with _suppressed_stan_logs():
            b_inside.set()
            a_left.wait(timeout=5)
            # A has already left; suppression must still hold for B's own fit.
            seen_inside.append(cmdstanpy.level)

    try:
        threads = [threading.Thread(target=first), threading.Thread(target=second)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=10)
        assert not any(thread.is_alive() for thread in threads), "threads deadlocked"

        assert seen_inside == [logging.WARNING, logging.WARNING]
        assert cmdstanpy.level == logging.DEBUG
    finally:
        cmdstanpy.setLevel(original)

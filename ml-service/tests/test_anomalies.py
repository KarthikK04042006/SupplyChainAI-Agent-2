"""Tests for anomaly detection on demand history.

The hard part of anomaly detection is not catching the obvious spike; it is *not*
flagging ordinary variation. A detector that reports every weekend in a
weekend-peaking series is worse than none, because a dashboard full of false
warnings trains people to ignore real ones. Most of these tests therefore assert
silence on data that is merely noisy, seasonal, or sparse.
"""

import numpy as np
import pandas as pd
import pytest

from models.schemas import AnomalyKind, Severity
from services.anomalies import build_warnings, detect_anomalies

from conftest import intermittent, weekday_profile

SEASONAL_PERIODS = 7


def to_series(quantities, start: str = "2026-01-01") -> pd.Series:
    return pd.Series(
        quantities,
        index=pd.date_range(start, periods=len(quantities), freq="D"),
        dtype=float,
    )


@pytest.fixture(scope="module")
def clean_seasonal() -> pd.Series:
    """A 30-week weekend-peaking series with ordinary noise and no real anomalies."""
    return to_series(weekday_profile(210, horizon=0, noise=2.0).quantities)


def test_clean_seasonal_history_raises_nothing(clean_seasonal):
    """The false-positive test that matters most. Saturdays in this series run near
    80 against a Monday of 30 — a plain rolling z-score would flag all 30 of them."""
    assert detect_anomalies(clean_seasonal, SEASONAL_PERIODS) == []


def test_injected_spike_is_caught(clean_seasonal):
    """A 400-unit day against a ~27-unit Wednesday baseline is unambiguous."""
    series = clean_seasonal.copy()
    series.iloc[104] = 400.0

    found = detect_anomalies(series, SEASONAL_PERIODS)

    assert len(found) == 1
    assert found[0].kind is AnomalyKind.SPIKE
    assert found[0].date == series.index[104].date()
    assert found[0].observed == pytest.approx(400.0)
    assert found[0].z_score > 0
    assert found[0].severity is Severity.CRITICAL


def test_injected_drop_is_caught(clean_seasonal):
    """A zero-demand day where 27 was expected: often a stockout recorded as demand,
    which is the case where a naive forecast quietly understates true demand."""
    series = clean_seasonal.copy()
    series.iloc[181] = 0.0

    found = detect_anomalies(series, SEASONAL_PERIODS)

    assert len(found) == 1
    assert found[0].kind is AnomalyKind.DROP
    assert found[0].observed == pytest.approx(0.0)
    assert found[0].z_score < 0
    assert found[0].deviation < 0


def test_both_directions_are_found_together(clean_seasonal):
    series = clean_seasonal.copy()
    series.iloc[104] = 400.0
    series.iloc[181] = 0.0

    found = detect_anomalies(series, SEASONAL_PERIODS)
    kinds = {anomaly.kind for anomaly in found}

    assert len(found) == 2
    assert kinds == {AnomalyKind.SPIKE, AnomalyKind.DROP}


def test_results_are_most_recent_first(clean_seasonal):
    """A dashboard reads top-down and the newest distortion is the one most likely
    to be biasing the current forecast."""
    series = clean_seasonal.copy()
    series.iloc[60] = 500.0
    series.iloc[104] = 400.0
    series.iloc[181] = 0.0

    dates = [anomaly.date for anomaly in detect_anomalies(series, SEASONAL_PERIODS)]

    assert dates == sorted(dates, reverse=True)


def test_intermittent_demand_is_left_alone():
    """A slow mover is zero most days, so its same-weekday median is zero and every
    ordinary order would score an enormous z. Those are real orders, not anomalies —
    the detector must decline rather than flood the dashboard."""
    sparse = to_series(intermittent(210, horizon=0, rate=0.22, size=7.0).quantities)

    assert (sparse == 0).mean() > 0.5, "fixture should be mostly zeros"
    assert detect_anomalies(sparse, SEASONAL_PERIODS) == []


def test_slow_mover_near_the_intermittency_boundary_is_still_left_alone():
    """The regression this guard exists for. At roughly half zeros, per-weekday
    non-zero fractions scatter across a 50% cutoff by sampling noise alone: some
    weekdays get scored and others skipped, and the scored ones report every real
    order as a spike against a near-zero median. Intermittency is a property of the
    product, so it has to be judged once for the whole series."""
    sparse = to_series(intermittent(210, horizon=0, rate=0.5, size=7.0).quantities)
    fraction_zero = (sparse == 0).mean()

    assert 0.4 < fraction_zero < 0.6, "fixture should straddle the old cutoff"
    assert detect_anomalies(sparse, SEASONAL_PERIODS) == []


def test_dense_history_is_still_scanned():
    """The guard must not swallow ordinary products. A series with demand every day
    is not intermittent no matter how much it varies, so its outliers still report."""
    dense = to_series(weekday_profile(210, horizon=0, noise=2.0).quantities)
    dense.iloc[100] = 400.0

    assert (dense > 0).all(), "fixture should have demand every day"
    assert len(detect_anomalies(dense, SEASONAL_PERIODS)) == 1


def test_all_zero_history_raises_nothing():
    """A discontinued product has no demand to be anomalous about."""
    assert detect_anomalies(to_series([0.0] * 120), SEASONAL_PERIODS) == []


def test_flat_history_still_detects_a_real_departure():
    """MAD is zero on a constant series, so the scale falls back to the standard
    deviation. A jump from a rock-steady 12 to 60 must still register."""
    series = to_series([12.0] * 120)
    series.iloc[100] = 60.0

    found = detect_anomalies(series, SEASONAL_PERIODS)

    assert len(found) == 1
    assert found[0].kind is AnomalyKind.SPIKE


def test_short_history_is_skipped():
    """With fewer same-weekday peers than the minimum there is no trustworthy
    baseline, and guessing one would produce noise."""
    assert detect_anomalies(to_series([10.0, 50.0, 10.0]), SEASONAL_PERIODS) == []


def test_a_single_outlier_cannot_hide_behind_its_own_spread():
    """The reason for median/MAD over mean/std. One 300-unit day inflates the
    standard deviation enough to make itself look ordinary; the robust scale is
    unmoved, so the day is still caught."""
    series = to_series(([20.0, 22.0, 19.0, 21.0, 20.0, 23.0, 18.0] * 12))
    series.iloc[70] = 300.0

    found = detect_anomalies(series, SEASONAL_PERIODS)

    assert len(found) == 1
    assert found[0].observed == pytest.approx(300.0)
    assert abs(found[0].z_score) > 10


def test_expected_and_deviation_are_internally_consistent(clean_seasonal):
    """The reported numbers have to add up, since the dashboard shows them side by
    side: deviation must be observed minus expected."""
    series = clean_seasonal.copy()
    series.iloc[104] = 400.0

    anomaly = detect_anomalies(series, SEASONAL_PERIODS)[0]

    assert anomaly.deviation == pytest.approx(anomaly.observed - anomaly.expected, abs=1e-3)
    assert np.isfinite(anomaly.z_score)


def test_severity_grades_by_z_magnitude(clean_seasonal):
    """Severity has to be ordered: a bigger departure cannot be graded milder."""
    mild = clean_seasonal.copy()
    mild.iloc[104] = 70.0
    extreme = clean_seasonal.copy()
    extreme.iloc[104] = 900.0

    mild_found = detect_anomalies(mild, SEASONAL_PERIODS)
    extreme_found = detect_anomalies(extreme, SEASONAL_PERIODS)

    order = [Severity.LOW, Severity.MEDIUM, Severity.HIGH, Severity.CRITICAL]
    if mild_found:
        assert order.index(mild_found[0].severity) <= order.index(
            extreme_found[0].severity
        )
    assert extreme_found[0].severity is Severity.CRITICAL


def test_recent_spike_becomes_a_forecast_warning(clean_seasonal):
    """The deliverable from the user's example: a spike near the end of history turns
    into a message naming the date, because that is what a planner needs to see."""
    series = clean_seasonal.copy()
    series.iloc[-5] = 400.0

    warnings = build_warnings(detect_anomalies(series, SEASONAL_PERIODS), series)
    codes = {warning.code for warning in warnings}

    assert "recent_demand_spike" in codes
    spike = next(w for w in warnings if w.code == "recent_demand_spike")
    assert series.index[-5].date().isoformat() in spike.message
    assert "bias" in spike.message


def test_recent_drop_warning_names_the_stockout_risk(clean_seasonal):
    """A drop and a spike need different advice: a drop may be a stockout, which
    means the forecast understates true demand rather than overstating it."""
    series = clean_seasonal.copy()
    series.iloc[-4] = 0.0

    warnings = build_warnings(detect_anomalies(series, SEASONAL_PERIODS), series)
    drop = next(w for w in warnings if w.code == "recent_demand_drop")

    assert "stockout" in drop.message
    assert "understates" in drop.message


def test_old_anomalies_do_not_warn_about_the_current_forecast(clean_seasonal):
    """An outlier from six months ago is real history and is still reported in
    ``anomalies``, but it is no longer feeding the model's 28-day lags, so it must not
    be dressed up as a warning about tomorrow's number."""
    series = clean_seasonal.copy()
    series.iloc[20] = 400.0

    anomalies = detect_anomalies(series, SEASONAL_PERIODS)
    warnings = build_warnings(anomalies, series)

    assert len(anomalies) == 1
    assert [warning.code for warning in warnings] == ["historical_anomalies"]
    assert warnings[0].severity is Severity.LOW


def test_many_recent_anomalies_flag_unstable_history(clean_seasonal):
    """Three or more recent outliers say something different from any one of them:
    the recent history itself is unreliable, so the prediction interval is optimistic."""
    series = clean_seasonal.copy()
    for offset in (-3, -6, -9, -12):
        series.iloc[offset] = 400.0

    warnings = build_warnings(detect_anomalies(series, SEASONAL_PERIODS), series)
    unstable = next(w for w in warnings if w.code == "unstable_recent_history")

    assert unstable.severity in (Severity.MEDIUM, Severity.HIGH)
    assert "volatile" in unstable.message


def test_no_anomalies_means_no_warnings(clean_seasonal):
    assert build_warnings([], clean_seasonal) == []

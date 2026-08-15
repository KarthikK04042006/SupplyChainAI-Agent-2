"""Anomaly detection on observed demand history.

A forecast is only as trustworthy as the history behind it. A one-off promotion, a
stockout recorded as zero demand, or a data-entry error all look like real signal to
a model, and the model will happily project them forward. Detecting them lets the
forecast carry a warning rather than quietly inheriting the distortion.

Two design choices matter here:

**The baseline is seasonal.** Comparing each day against a plain rolling window
flags every Saturday in a weekend-peaking series — the spike is real but it is not an
anomaly. Instead each day is compared against the same weekday in recent weeks, so
only departures from a day's *own* pattern register.

**The scale is robust.** Mean and standard deviation are themselves inflated by the
outlier being tested, which masks exactly the large spikes worth catching. Median and
MAD are not, so a single extreme day cannot hide behind the spread it created.

**Intermittent demand is out of scope.** A zero-inflated slow mover has a median of
zero, so every real order would be flagged as a spike. The detector declines for such
series (ADI >= 1.32) rather than flooding a dashboard with false warnings.
"""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd

from models.schemas import AnomalyKind, DemandAnomaly, ForecastWarning, Severity

logger = logging.getLogger(__name__)

#: Scale factor making MAD a consistent estimator of standard deviation for normal
#: data, so the resulting z-scores keep their usual interpretation.
MAD_TO_SIGMA = 1.4826

#: Robust z-score past which a day is reported. 3.5 is the Iglewicz-Hoaglin
#: recommendation for the modified z-score and is stricter than the familiar 3.0,
#: which is appropriate here because demand is naturally noisy.
Z_THRESHOLD = 3.5

#: Same-weekday observations needed before a seasonal baseline is trustworthy.
MIN_SEASONAL_SAMPLES = 3

#: Days from the end of history within which an anomaly is "recent" enough to bias a
#: forecast. Lag features reach back 28 days, so anomalies inside that window are
#: still directly feeding the model.
RECENT_DAYS = 28

#: Floor on the robust scale, in units. Without it, a series that is briefly flat
#: gives MAD ≈ 0 and every small wobble becomes an infinite z-score.
MIN_SCALE = 0.5

#: Intermittency cutoff as the average demand interval (ADI) — the ratio of all days
#: to days with demand. ADI >= 1.32 is the standard Syntetos-Boylan dividing line
#: between intermittent and smooth demand. The same median/MAD z-score that works on
#: smooth demand is meaningless here: a zero-inflated series has a median of zero,
#: so every ordinary order looks like a huge spike. Intermittent series are skipped
#: whole — a different instrument is needed for their outliers, and a fractional
#: series-level cutoff can never be right because an intermittent series spans the
#: whole (0, 1) range of non-zero fractions.
ADI_INTERMITTENT = 1.32


def detect_anomalies(
    series: pd.Series,
    seasonal_periods: int = 7,
    z_threshold: float = Z_THRESHOLD,
) -> list[DemandAnomaly]:
    """Find days whose demand departs sharply from their own baseline.

    Returns most recent first, which is the order a dashboard wants: the newest
    distortion is the one most likely to be affecting the current forecast.
    """
    if len(series) < MIN_SEASONAL_SAMPLES + 1:
        return []

    values = series.to_numpy(dtype=float)

    if _is_intermittent(values):
        # Judged once for the whole series, because intermittency is a property of the
        # product, not of a particular weekday. Testing it per weekday let sampling
        # noise scatter a single slow mover across the cutoff, so some weekdays were
        # scored and others skipped, and the scored ones reported every real order as
        # a spike against a near-zero median.
        logger.debug(
            "skipping anomaly scan: intermittent demand (ADI %.2f over %d days)",
            _demand_interval(values),
            len(values),
        )
        return []

    baselines, scales = _seasonal_baseline(values, seasonal_periods)

    anomalies: list[DemandAnomaly] = []
    for index, (observed, expected, scale) in enumerate(zip(values, baselines, scales)):
        if not np.isfinite(expected) or not np.isfinite(scale):
            continue

        z_score = (observed - expected) / max(scale, MIN_SCALE)
        if abs(z_score) < z_threshold:
            continue

        anomalies.append(
            DemandAnomaly(
                date=series.index[index].date(),
                kind=AnomalyKind.SPIKE if z_score > 0 else AnomalyKind.DROP,
                observed=round(float(observed), 3),
                expected=round(float(expected), 3),
                deviation=round(float(observed - expected), 3),
                z_score=round(float(z_score), 3),
                severity=_severity(abs(z_score)),
            )
        )

    anomalies.sort(key=lambda anomaly: anomaly.date, reverse=True)
    return anomalies


def _demand_interval(values: np.ndarray) -> float:
    """Average demand interval: days of history per day that had demand.

    1.0 means demand every day; 5.0 means an order roughly every fifth day. Returns
    infinity for an all-zero series, which is intermittent in the limit.
    """
    with_demand = int(np.count_nonzero(values))
    if with_demand == 0:
        return float("inf")
    return len(values) / with_demand


def _is_intermittent(values: np.ndarray) -> bool:
    """Whether this series is too zero-inflated for a z-score to mean anything."""
    return _demand_interval(values) >= ADI_INTERMITTENT


def _seasonal_baseline(
    values: np.ndarray, seasonal_periods: int
) -> tuple[np.ndarray, np.ndarray]:
    """Expected value and robust scale for every day in the series.

    Each day is compared against *all other* observations at the same seasonal
    position — same weekday, for the usual weekly cycle. Both directions are used
    deliberately: this is a retrospective scan of completed history, not a causal
    forecast, so a spike on day 3 should be judged against the whole series rather
    than the two days that happened to precede it. A robust median over many peers
    cannot be dragged far by the single day being tested.

    Days with too few same-position peers get NaN and are skipped. Using a
    mixed-weekday trailing window for them instead would flag every weekend in a
    weekend-peaking series — precisely the false positive the seasonal baseline
    exists to avoid.
    """
    count = len(values)
    baselines = np.full(count, np.nan)
    scales = np.full(count, np.nan)

    if seasonal_periods <= 0:
        return baselines, scales

    for index in range(count):
        position = np.arange(index % seasonal_periods, count, seasonal_periods)
        peers = values[position[position != index]]

        if len(peers) < MIN_SEASONAL_SAMPLES:
            continue

        median = float(np.median(peers))
        mad = float(np.median(np.abs(peers - median)))
        scale = mad * MAD_TO_SIGMA

        if scale <= 0:
            # A perfectly flat baseline: fall back to standard deviation so a genuine
            # departure from a constant series is still detectable.
            scale = float(np.std(peers, ddof=1)) if len(peers) > 1 else 0.0

        baselines[index] = median
        scales[index] = scale

    return baselines, scales


def _severity(z_magnitude: float) -> Severity:
    """Grade an anomaly by how far past the threshold it sits."""
    if z_magnitude >= 10.0:
        return Severity.CRITICAL
    if z_magnitude >= 6.0:
        return Severity.HIGH
    if z_magnitude >= 4.5:
        return Severity.MEDIUM
    return Severity.LOW


def build_warnings(
    anomalies: list[DemandAnomaly], series: pd.Series
) -> list[ForecastWarning]:
    """Turn detected anomalies into caveats worth attaching to a forecast.

    Only anomalies recent enough to still be feeding the model's lag and rolling
    features become warnings. An outlier from a year ago is real history and is
    reported in ``anomalies``, but it is not biasing tomorrow's number.
    """
    if not anomalies or series.empty:
        return []

    cutoff = (series.index.max() - pd.Timedelta(days=RECENT_DAYS)).date()
    recent = [anomaly for anomaly in anomalies if anomaly.date >= cutoff]

    if not recent:
        return [
            ForecastWarning(
                code="historical_anomalies",
                message=(
                    f"{len(anomalies)} anomalous day(s) found in history, none within the "
                    f"last {RECENT_DAYS} days; unlikely to bias the current forecast"
                ),
                severity=Severity.LOW,
            )
        ]

    warnings: list[ForecastWarning] = []
    worst = max(recent, key=lambda anomaly: abs(anomaly.z_score))
    spikes = [anomaly for anomaly in recent if anomaly.kind is AnomalyKind.SPIKE]
    drops = [anomaly for anomaly in recent if anomaly.kind is AnomalyKind.DROP]

    if spikes:
        latest = max(spikes, key=lambda anomaly: anomaly.date)
        warnings.append(
            ForecastWarning(
                code="recent_demand_spike",
                message=(
                    f"recent spike detected on {latest.date.isoformat()} "
                    f"({latest.observed:g} vs {latest.expected:g} expected), may bias forecast"
                ),
                severity=_severity(abs(latest.z_score)),
            )
        )

    if drops:
        latest = max(drops, key=lambda anomaly: anomaly.date)
        warnings.append(
            ForecastWarning(
                code="recent_demand_drop",
                message=(
                    f"recent drop detected on {latest.date.isoformat()} "
                    f"({latest.observed:g} vs {latest.expected:g} expected); if this was a "
                    f"stockout the forecast understates true demand"
                ),
                severity=_severity(abs(latest.z_score)),
            )
        )

    if len(recent) >= 3:
        warnings.append(
            ForecastWarning(
                code="unstable_recent_history",
                message=(
                    f"{len(recent)} anomalous days in the last {RECENT_DAYS}; the recent "
                    f"history is volatile and the prediction interval may be optimistic"
                ),
                severity=Severity.HIGH if len(recent) >= 5 else Severity.MEDIUM,
            )
        )

    logger.debug(
        "Built %d warning(s) from %d recent anomalies (worst z=%.2f)",
        len(warnings), len(recent), worst.z_score,
    )
    return warnings

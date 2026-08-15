"""Seasonality detection.

Drives the statistical-model choice: a series with a real weekly cycle gets SARIMA
with a seasonal order (or Prophet with weekly terms), while a flat or purely
trending series gets the cheaper non-seasonal variant. Guessing wrong is not fatal
— the backtester still scores every candidate — but it keeps the candidate set
sensible and avoids fitting seasonal parameters to noise.
"""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd

logger = logging.getLogger(__name__)

# Autocorrelation at the seasonal lag above which we call the cycle real. Chosen
# to be permissive: a missed weekly pattern costs more than an extra candidate.
ACF_THRESHOLD = 0.3


def detect_seasonality(series: pd.Series, seasonal_periods: int) -> bool:
    """Whether ``series`` shows a repeating cycle of length ``seasonal_periods``.

    Uses the autocorrelation at the seasonal lag. Needs two full seasons to have
    anything to compare, and a non-constant series for correlation to be defined.
    """
    values = series.to_numpy(dtype=float)

    if values.size < 2 * seasonal_periods:
        return False
    if np.allclose(values, values[0]):
        # A constant series is perfectly periodic at every lag, which is not a
        # seasonal signal worth modelling.
        return False

    strength = seasonal_strength(values, seasonal_periods)
    detected = strength >= ACF_THRESHOLD
    logger.debug(
        "Seasonality at lag %d: acf=%.3f detected=%s", seasonal_periods, strength, detected
    )
    return detected


def seasonal_strength(values: np.ndarray, lag: int) -> float:
    """Autocorrelation of ``values`` at ``lag``, clamped to ``[0, 1]``.

    Negative correlation is reported as 0.0: an anti-correlated lag is not evidence
    of a cycle of that length.
    """
    if values.size <= lag:
        return 0.0

    centred = values - values.mean()
    denominator = float(np.dot(centred, centred))
    if denominator == 0.0:
        return 0.0

    numerator = float(np.dot(centred[lag:], centred[:-lag]))
    return max(0.0, min(1.0, numerator / denominator))


def dominant_period(series: pd.Series, candidates: tuple[int, ...] = (7, 14, 30)) -> int | None:
    """The candidate period with the strongest autocorrelation, if any qualifies.

    Not used for the default weekly case, where the caller supplies the period
    outright, but useful when the caller has no prior about the cycle length.
    """
    values = series.to_numpy(dtype=float)
    viable = [p for p in candidates if values.size >= 2 * p]
    if not viable:
        return None

    scored = {period: seasonal_strength(values, period) for period in viable}
    best = max(scored, key=lambda period: scored[period])
    return best if scored[best] >= ACF_THRESHOLD else None

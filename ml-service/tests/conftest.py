"""Synthetic time series with known generative properties.

Every series here comes from an explicit formula with a seeded RNG. That matters
for the accuracy tests: because the generator can produce the *true* continuation
as well as the history, a test can measure the engine's real out-of-sample error
instead of trusting the engine's own backtest score. A model that games the
backtest still fails against ground truth.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta

import numpy as np

from models.schemas import DemandPoint

#: A Thursday, so weekday-profile series do not accidentally start on a boundary
#: that makes a bug look like correct behaviour.
START = date(2026, 1, 1)

#: Fixed so failures are reproducible; these tests must never be flaky.
SEED = 20260807


@dataclass(frozen=True)
class Synthetic:
    """A generated series, split into observed history and its true future."""

    history: list[DemandPoint]
    future: list[float]

    @property
    def horizon(self) -> int:
        return len(self.future)

    @property
    def quantities(self) -> list[float]:
        return [point.quantity for point in self.history]


def _to_points(values: np.ndarray, start: date) -> list[DemandPoint]:
    return [
        DemandPoint(date=start + timedelta(days=offset), quantity=float(value))
        for offset, value in enumerate(values)
    ]


def _split(values: np.ndarray, n_history: int, start: date) -> Synthetic:
    return Synthetic(
        history=_to_points(values[:n_history], start),
        future=[float(value) for value in values[n_history:]],
    )


def sinusoidal(
    n_history: int,
    horizon: int = 14,
    *,
    base: float = 40.0,
    trend: float = 0.0,
    amplitude: float = 0.0,
    period: int = 7,
    noise: float = 0.0,
    start: date = START,
    seed: int = SEED,
) -> Synthetic:
    """Generate ``base + trend*t + amplitude*sin(2*pi*t/period) + noise``.

    Values are floored at zero and rounded to whole units, because demand is a
    count — the rounding also stops any model from fitting the smooth generator
    exactly and reporting an unrealistically perfect score.
    """
    total = n_history + horizon
    t = np.arange(total, dtype=float)

    values = base + trend * t + amplitude * np.sin(2.0 * np.pi * t / period)
    if noise:
        values = values + np.random.default_rng(seed).normal(0.0, noise, total)

    return _split(np.maximum(values, 0.0).round(), n_history, start)


def weekday_profile(
    n_history: int,
    horizon: int = 14,
    *,
    profile: tuple[float, ...] = (30.0, 28.0, 27.0, 32.0, 55.0, 80.0, 65.0),
    trend: float = 0.0,
    noise: float = 0.0,
    start: date = START,
    seed: int = SEED,
) -> Synthetic:
    """Generate a retail-shaped week: quiet midweek, busy weekend.

    Closer to real demand than a sine wave, and the shape a seasonal model is
    supposed to capture. ``profile`` is indexed by day-of-week of ``start``.
    """
    total = n_history + horizon
    t = np.arange(total, dtype=float)
    weekday = (start.weekday() + np.arange(total)) % 7

    values = np.array([profile[index % len(profile)] for index in weekday], dtype=float)
    values = values + trend * t
    if noise:
        values = values + np.random.default_rng(seed).normal(0.0, noise, total)

    return _split(np.maximum(values, 0.0).round(), n_history, start)


def intermittent(
    n_history: int,
    horizon: int = 14,
    *,
    rate: float = 0.25,
    size: float = 6.0,
    start: date = START,
    seed: int = SEED,
) -> Synthetic:
    """Generate a sparse series: mostly zero days with occasional orders.

    Slow-moving stock looks like this, and it is the case that breaks MAPE — the
    engine has to survive a holdout window that is entirely zeros.
    """
    total = n_history + horizon
    rng = np.random.default_rng(seed)
    sells = rng.random(total) < rate
    values = np.where(sells, rng.poisson(size, total), 0).astype(float)

    return _split(values, n_history, start)


def mape(actual: list[float], predicted: list[float]) -> float:
    """Out-of-sample MAPE against ground truth, in percent.

    Deliberately a separate implementation from ``services.backtest.mape``: a test
    that reuses the code under test cannot catch that code being wrong.
    """
    pairs = [(a, p) for a, p in zip(actual, predicted) if a != 0]
    if not pairs:
        raise ValueError("cannot compute MAPE against an all-zero actual window")
    return 100.0 * sum(abs(a - p) / a for a, p in pairs) / len(pairs)

"""Feature engineering for the ML forecaster.

Tree models have no notion of time, so temporal structure has to be handed to them
as columns: lagged demand, rolling statistics, calendar position, and holiday
flags. This module is the single place that layout is defined, so training and
recursive prediction cannot drift apart — the classic source of silent ML
forecasting bugs.
"""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd

logger = logging.getLogger(__name__)

#: Past days offered directly to the model. 1-7 covers the immediate week, 14 and
#: 28 give same-weekday comparisons one and four weeks back.
LAG_DAYS = (1, 2, 3, 4, 5, 6, 7, 14, 28)

#: Trailing windows for rolling mean and standard deviation.
ROLLING_WINDOWS = (3, 7, 14, 28)


def build_features(
    series: pd.Series, country_holidays: str | None = None
) -> pd.DataFrame:
    """Build the full feature matrix for a daily demand series.

    The returned frame is indexed like ``series`` and includes a ``y`` target
    column. Rows where the deepest lag is unavailable still appear, with NaNs —
    callers decide whether to drop them.
    """
    frame = pd.DataFrame({"y": series.to_numpy(dtype=float)}, index=series.index)

    for lag in LAG_DAYS:
        frame[f"lag_{lag}"] = frame["y"].shift(lag)

    for window in ROLLING_WINDOWS:
        # shift(1) first: a rolling window that includes today would leak the target.
        shifted = frame["y"].shift(1)
        frame[f"roll_mean_{window}"] = shifted.rolling(window, min_periods=1).mean()
        frame[f"roll_std_{window}"] = shifted.rolling(window, min_periods=2).std()

    frame = frame.join(calendar_features(series.index))

    if country_holidays:
        frame["is_holiday"] = holiday_flags(series.index, country_holidays)

    return frame


def calendar_features(index: pd.DatetimeIndex) -> pd.DataFrame:
    """Calendar position features.

    Day-of-week and month are encoded both as integers and as sine/cosine pairs.
    The cyclical encoding tells the model that December is adjacent to January,
    which a raw integer split cannot express.
    """
    day_of_week = index.dayofweek.to_numpy()
    month = index.month.to_numpy()
    day_of_month = index.day.to_numpy()

    return pd.DataFrame(
        {
            "day_of_week": day_of_week,
            "month": month,
            "day_of_month": day_of_month,
            "week_of_year": index.isocalendar().week.to_numpy(),
            "is_weekend": (day_of_week >= 5).astype(int),
            "is_month_start": index.is_month_start.astype(int),
            "is_month_end": index.is_month_end.astype(int),
            "dow_sin": np.sin(2 * np.pi * day_of_week / 7),
            "dow_cos": np.cos(2 * np.pi * day_of_week / 7),
            "month_sin": np.sin(2 * np.pi * month / 12),
            "month_cos": np.cos(2 * np.pi * month / 12),
        },
        index=index,
    )


def holiday_flags(index: pd.DatetimeIndex, country: str) -> np.ndarray:
    """1 where the date is a public holiday in ``country``, else 0.

    Uses the ``holidays`` package that ships as a Prophet dependency. If it is
    unavailable or the country code is unrecognised, every day is flagged 0 — a
    missing feature is preferable to failing the whole forecast.
    """
    try:
        import holidays as holidays_lib
    except ImportError:  # pragma: no cover - arrives with prophet
        logger.debug("holidays package unavailable; holiday features disabled")
        return np.zeros(len(index), dtype=int)

    try:
        years = sorted({int(year) for year in index.year.unique()})
        # Include the following year so a forecast crossing New Year still resolves.
        calendar = holidays_lib.country_holidays(country, years=years + [max(years) + 1])
    except (KeyError, NotImplementedError):
        logger.warning("Unknown holiday country %r; holiday features disabled", country)
        return np.zeros(len(index), dtype=int)

    return np.array([1 if day.date() in calendar else 0 for day in index], dtype=int)


def feature_columns(frame: pd.DataFrame) -> list[str]:
    """Model input columns, i.e. everything except the target."""
    return [column for column in frame.columns if column != "y"]


def next_row(
    history: pd.Series, target_date: pd.Timestamp, columns: list[str],
    country_holidays: str | None = None,
) -> pd.DataFrame:
    """Build the single feature row for ``target_date`` given demand up to it.

    Used for recursive multi-step prediction: each predicted day is appended to
    ``history`` and fed back in to derive the next row. Reusing
    :func:`build_features` here rather than recomputing the layout by hand is what
    keeps train-time and predict-time features identical.
    """
    extended = pd.concat(
        [history, pd.Series([np.nan], index=pd.DatetimeIndex([target_date]))]
    )
    built = build_features(extended, country_holidays=country_holidays)
    row = built.iloc[[-1]]

    # Any column the training frame had but this row lacks (e.g. a disabled holiday
    # flag) is filled with 0 so the column order always matches the fitted model.
    missing = [column for column in columns if column not in row.columns]
    for column in missing:
        row = row.assign(**{column: 0})

    return row[columns].fillna(0.0)

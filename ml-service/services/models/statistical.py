"""Statistical models: exponential smoothing, SARIMA, and Prophet.

All three are classical time-series techniques that model the series as a function
of its own history. They need less data than the ML approach and extrapolate trend
and seasonality more reliably, which is why they stay in the candidate pool even
when XGBoost is available.
"""

from __future__ import annotations

import functools
import logging
import os
import warnings

import numpy as np
import pandas as pd

from .base import ForecastModel, ModelUnusableError, Prediction, clean_forecast, residual_sigma

logger = logging.getLogger(__name__)


class HoltWintersModel(ForecastModel):
    """Exponential smoothing with optional additive trend and seasonality.

    Cheap, robust, and needs only two seasons of history — usually the first model
    to become viable as a new product accumulates data.
    """

    name = "holt_winters"
    min_observations = 3

    def __init__(self, seasonal: bool = True) -> None:
        self.seasonal = seasonal
        self.name = "holt_winters_seasonal" if seasonal else "holt_winters_trend"

    def supports(self, series: pd.Series, seasonal_periods: int) -> bool:
        if self.seasonal:
            return len(series) >= 2 * seasonal_periods
        return len(series) >= self.min_observations

    def fit_predict(
        self, series: pd.Series, horizon: int, seasonal_periods: int
    ) -> Prediction:
        from statsmodels.tsa.holtwinters import ExponentialSmoothing

        kwargs = {"trend": "add", "initialization_method": "estimated"}
        if self.seasonal:
            kwargs |= {"seasonal": "add", "seasonal_periods": seasonal_periods}

        try:
            with warnings.catch_warnings():
                # Convergence chatter on short or flat series is expected here.
                warnings.simplefilter("ignore")
                fit = ExponentialSmoothing(series, **kwargs).fit()
                values = np.asarray(fit.forecast(horizon), dtype=float)
        except Exception as exc:
            raise ModelUnusableError(f"Holt-Winters fit failed: {exc}") from exc

        last = float(series.iloc[-1])
        return Prediction(
            values=clean_forecast(values, last, horizon),
            sigma=_sigma_from_resid(fit),
        )


class SarimaModel(ForecastModel):
    """(S)ARIMA via statsmodels' SARIMAX.

    The order is fixed rather than searched: a full grid search per SKU is far too
    slow for a batch dashboard call, and (1,1,1) with a seasonal (1,1,1,m) term
    covers the trend-plus-weekly-cycle shape that retail demand usually takes.
    """

    name = "sarima"
    min_observations = 10

    def __init__(self, seasonal: bool = True) -> None:
        self.seasonal = seasonal
        self.name = "sarima" if seasonal else "arima"

    def supports(self, series: pd.Series, seasonal_periods: int) -> bool:
        if self.seasonal:
            # Seasonal differencing consumes a whole season; two is the practical floor.
            return len(series) >= max(self.min_observations, 2 * seasonal_periods + 2)
        return len(series) >= self.min_observations

    def fit_predict(
        self, series: pd.Series, horizon: int, seasonal_periods: int
    ) -> Prediction:
        from statsmodels.tsa.statespace.sarimax import SARIMAX

        seasonal_order = (1, 1, 1, seasonal_periods) if self.seasonal else (0, 0, 0, 0)

        try:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                fit = SARIMAX(
                    series,
                    order=(1, 1, 1),
                    seasonal_order=seasonal_order,
                    enforce_stationarity=False,
                    enforce_invertibility=False,
                ).fit(disp=False)
                values = np.asarray(fit.forecast(steps=horizon), dtype=float)
        except Exception as exc:
            raise ModelUnusableError(f"SARIMA fit failed: {exc}") from exc

        last = float(series.iloc[-1])
        return Prediction(
            values=clean_forecast(values, last, horizon),
            sigma=_sigma_from_resid(fit),
        )


class ProphetModel(ForecastModel):
    """Facebook Prophet.

    Handles multiple seasonalities and holidays natively and is unusually tolerant
    of gaps and outliers. It is also by far the slowest candidate — it fits a Stan
    model per call — so it is only offered when there is enough history to justify
    the cost.
    """

    name = "prophet"
    min_observations = 30

    def __init__(self, country_holidays: str | None = None) -> None:
        self.country_holidays = country_holidays

    def supports(self, series: pd.Series, seasonal_periods: int) -> bool:
        """Require both enough history and a working Stan backend.

        Prophet compiles its model through cmdstanpy, and that install is fragile
        — some wheels ship an incomplete cmdstan tree. Checking here means a
        broken install drops the candidate once, rather than raising on all three
        backtest folds and again on the refit, for every SKU in a batch.
        """
        return len(series) >= self.min_observations and _prophet_available()

    def fit_predict(
        self, series: pd.Series, horizon: int, seasonal_periods: int
    ) -> Prediction:
        try:
            from prophet import Prophet
        except ImportError as exc:  # pragma: no cover - dependency is pinned
            raise ModelUnusableError(f"Prophet unavailable: {exc}") from exc

        frame = pd.DataFrame({"ds": series.index, "y": series.to_numpy(dtype=float)})

        try:
            with warnings.catch_warnings(), _suppressed_stan_logs():
                warnings.simplefilter("ignore")
                model = Prophet(
                    weekly_seasonality=seasonal_periods == 7,
                    yearly_seasonality=len(series) >= 730,
                    daily_seasonality=False,
                    interval_width=0.95,
                )
                if seasonal_periods not in (7,):
                    # Give Prophet the caller's cycle when it is not the built-in week.
                    model.add_seasonality(
                        name=f"custom_{seasonal_periods}",
                        period=seasonal_periods,
                        fourier_order=3,
                    )
                if self.country_holidays:
                    model.add_country_holidays(country_name=self.country_holidays)

                fit = model.fit(frame)
                future = fit.make_future_dataframe(periods=horizon, freq="D")
                forecast = fit.predict(future)
        except Exception as exc:
            raise ModelUnusableError(f"Prophet fit failed: {exc}") from exc

        values = forecast["yhat"].to_numpy(dtype=float)[-horizon:]
        in_sample = forecast["yhat"].to_numpy(dtype=float)[: len(series)]
        sigma = residual_sigma(series.to_numpy(dtype=float), in_sample)

        last = float(series.iloc[-1])
        return Prediction(values=clean_forecast(values, last, horizon), sigma=sigma)


@functools.lru_cache(maxsize=1)
def _prophet_available() -> bool:
    """Whether Prophet can actually fit on this machine. Probed once, then cached.

    A successful import is not enough: the Stan backend is a separate compiled
    artifact loaded lazily during construction. Building one throwaway model is
    the cheapest honest test, and the process-lifetime cache keeps it off the
    per-SKU path.
    """
    try:
        from prophet import Prophet

        with warnings.catch_warnings(), _suppressed_stan_logs():
            warnings.simplefilter("ignore")
            Prophet()
    except Exception as exc:  # noqa: BLE001 - any failure means "do not offer it"
        logger.warning("Prophet unavailable, dropping it from the candidate pool: %s", exc)
        return False
    return True


def _sigma_from_resid(fit) -> float:
    """Residual standard deviation from a fitted statsmodels result."""
    resid = np.asarray(getattr(fit, "resid", []), dtype=float)
    resid = resid[np.isfinite(resid)]
    if resid.size < 2:
        return 0.0
    return float(resid.std(ddof=1))


class _suppressed_stan_logs:
    """Silence cmdstanpy's per-fit INFO logging.

    Prophet logs several lines to stdout for every single fit. In a 500-SKU batch
    that is thousands of lines of noise, so it is suppressed for the duration of
    the fit and restored afterwards.
    """

    def __enter__(self):
        self._loggers = {}
        for name in ("cmdstanpy", "prophet"):
            logger_obj = logging.getLogger(name)
            self._loggers[name] = logger_obj.level
            logger_obj.setLevel(logging.WARNING)
        self._devnull = open(os.devnull, "w")
        return self

    def __exit__(self, *exc_info):
        for name, level in self._loggers.items():
            logging.getLogger(name).setLevel(level)
        self._devnull.close()
        return False

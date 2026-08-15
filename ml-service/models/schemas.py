"""Pydantic request/response schemas.

These define the OpenAPI contract the Spring Boot backend calls against. FastAPI
publishes it at ``/openapi.json`` with interactive docs at ``/docs``.
"""

from datetime import date as Date
from enum import Enum

from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    """Response body for ``GET /health``."""

    status: str = Field(..., description="UP when the service is ready to serve requests")
    service: str = Field(..., description="Service identifier")

    model_config = {
        "json_schema_extra": {
            "example": {"status": "UP", "service": "ml-service"}
        }
    }


class Granularity(str, Enum):
    """Output bucket size. Weekly sums the daily forecast into 7-day buckets."""

    DAILY = "daily"
    WEEKLY = "weekly"


class DemandPoint(BaseModel):
    """One observed day of demand for a single SKU."""

    date: Date = Field(..., description="Calendar day the demand was observed")
    quantity: float = Field(..., ge=0, description="Units demanded on this day")


class ForecastRequest(BaseModel):
    """Request body for ``POST /forecast``.

    The backend owns the data, so it sends the full history it wants modelled and
    this service keeps nothing between calls. History may arrive in any order and
    may have gaps; missing days are treated as zero demand.
    """

    sku: str = Field(..., min_length=1, description="SKU or product identifier")
    horizon_days: int = Field(14, ge=1, le=365, description="Number of days to forecast ahead")
    seasonal_periods: int = Field(
        7, ge=2, le=365, description="Season length in days; 7 models a weekly cycle"
    )
    granularity: Granularity = Field(
        Granularity.DAILY, description="Return per-day points or per-week aggregates"
    )
    country_holidays: str | None = Field(
        None,
        description="ISO country code (e.g. 'US') enabling holiday features; null disables them",
    )
    category_average: float | None = Field(
        None,
        ge=0,
        description="Category-level mean daily demand, used as the floor for new products "
                    "with too little history to model",
    )
    detect_anomalies: bool = Field(
        True,
        description="Scan the history for spikes and drops and report them as warnings",
    )
    history: list[DemandPoint] = Field(
        ..., min_length=1, description="Observed demand history, any order"
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "sku": "WIDGET-001",
                "horizon_days": 14,
                "seasonal_periods": 7,
                "granularity": "daily",
                "country_holidays": "US",
                "history": [
                    {"date": "2026-07-01", "quantity": 12},
                    {"date": "2026-07-02", "quantity": 15},
                ],
            }
        }
    }


class ForecastPoint(BaseModel):
    """A single forecast bucket with a prediction interval."""

    date: Date = Field(..., description="Forecast day, or the first day of the week bucket")
    predicted: float = Field(..., description="Point forecast in units, never negative")
    lower: float = Field(..., description="Lower bound of the 95% prediction interval")
    upper: float = Field(..., description="Upper bound of the 95% prediction interval")


class AnomalyKind(str, Enum):
    """Direction of an anomalous observation."""

    SPIKE = "spike"
    DROP = "drop"


class Severity(str, Enum):
    """How far past its threshold a finding sits.

    Shared by anomalies and inventory risk so the dashboard has one severity
    vocabulary to colour-code against.
    """

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class DemandAnomaly(BaseModel):
    """One historical day whose demand departs sharply from its own baseline."""

    date: Date = Field(..., description="Day the anomaly was observed")
    kind: AnomalyKind = Field(..., description="Whether demand spiked or dropped")
    observed: float = Field(..., description="Actual demand on that day")
    expected: float = Field(..., description="Baseline demand the day was compared against")
    deviation: float = Field(..., description="observed minus expected, in units")
    z_score: float = Field(
        ..., description="Robust z-score: how many scaled deviations from the baseline"
    )
    severity: Severity = Field(..., description="Graded from the z-score magnitude")


class ForecastWarning(BaseModel):
    """A caveat attached to a forecast the caller should surface, not swallow.

    A forecast can be the best available and still rest on distorted history. These
    make that visible rather than letting a clean-looking number imply clean input.
    """

    code: str = Field(..., description="Stable machine-readable warning identifier")
    message: str = Field(..., description="Human-readable explanation for the dashboard")
    severity: Severity = Field(..., description="How much this should worry the reader")


class ModelScore(BaseModel):
    """Backtest accuracy for one candidate model.

    Returned for every candidate, not just the winner, so the selection is
    explainable rather than a black box.
    """

    model: str = Field(..., description="Model identifier")
    mape: float | None = Field(
        None,
        description="Mean absolute percentage error across backtest folds. Null when every "
                    "actual in the holdout was zero, which makes the ratio undefined",
    )
    rmse: float | None = Field(None, description="Root mean squared error across backtest folds")
    mae: float | None = Field(None, description="Mean absolute error across backtest folds")
    folds: int = Field(0, description="Walk-forward folds this model was scored on")
    fitted: bool = Field(True, description="False when the model failed to fit and was skipped")
    error: str | None = Field(None, description="Why the model was skipped, when fitted is false")

    # ``model_`` is a protected namespace in Pydantic v2; the field is named
    # ``model`` deliberately for the API, so the warning is disabled here.
    model_config = {"protected_namespaces": ()}


class ForecastResponse(BaseModel):
    """Response body for ``POST /forecast``."""

    sku: str = Field(..., description="SKU this forecast is for")
    selected_model: str = Field(..., description="Model chosen by backtest, used for the points")
    selection_reason: str = Field(
        ..., description="Why this model won: best backtest score, or a fallback path"
    )
    granularity: Granularity = Field(..., description="Bucket size of the returned points")
    observations: int = Field(..., description="Daily observations after gap filling")
    seasonality_detected: bool = Field(
        ..., description="Whether a repeating seasonal signal was found in the history"
    )
    scores: list[ModelScore] = Field(
        ..., description="Backtest accuracy for every candidate, best first"
    )
    points: list[ForecastPoint] = Field(..., description="One entry per forecast bucket")
    warnings: list[ForecastWarning] = Field(
        default_factory=list,
        description="Caveats about this forecast, e.g. history distorted by a recent spike",
    )
    anomalies: list[DemandAnomaly] = Field(
        default_factory=list,
        description="Anomalous days detected in the supplied history, most recent first",
    )

    model_config = {
        "protected_namespaces": (),
        "json_schema_extra": {
            "example": {
                "sku": "WIDGET-001",
                "selected_model": "sarima",
                "selection_reason": "lowest backtest MAPE across 3 walk-forward folds",
                "granularity": "daily",
                "observations": 180,
                "seasonality_detected": True,
                "scores": [
                    {"model": "sarima", "mape": 8.2, "rmse": 2.1, "mae": 1.7, "folds": 3,
                     "fitted": True, "error": None},
                    {"model": "moving_average", "mape": 15.4, "rmse": 4.0, "mae": 3.2,
                     "folds": 3, "fitted": True, "error": None},
                ],
                "points": [
                    {"date": "2026-08-08", "predicted": 13.2, "lower": 8.5, "upper": 17.9}
                ],
            }
        },
    }


class BatchForecastItem(BaseModel):
    """One product within a batch request.

    Only ``sku`` and ``history`` are required. ``horizon_days`` and ``granularity``
    override the batch-level defaults for this SKU alone, so a dashboard can set one
    horizon for everything and still special-case a product.
    """

    sku: str = Field(..., min_length=1, description="SKU or product identifier")
    history: list[DemandPoint] = Field(
        ..., min_length=1, description="Observed demand history, any order"
    )
    horizon_days: int | None = Field(
        None, ge=1, le=365, description="Overrides the batch horizon for this SKU"
    )
    granularity: Granularity | None = Field(
        None, description="Overrides the batch granularity for this SKU"
    )
    category_average: float | None = Field(
        None, ge=0,
        description="Category mean daily demand, used when this SKU's history is too short",
    )


class BatchForecastRequest(BaseModel):
    """Request body for ``POST /forecast/batch``.

    The dashboard needs many SKUs at once, so these are fanned out across a worker
    pool rather than modelled one at a time. Settings that are usually uniform live
    at the batch level; per-item fields override them.
    """

    items: list[BatchForecastItem] = Field(
        ..., min_length=1, max_length=500, description="Products to forecast"
    )
    horizon_days: int = Field(
        14, ge=1, le=365, description="Default horizon for items that do not override it"
    )
    seasonal_periods: int = Field(
        7, ge=2, le=365, description="Season length in days, applied to every item"
    )
    granularity: Granularity = Field(
        Granularity.DAILY, description="Default bucket size for items that do not override it"
    )
    country_holidays: str | None = Field(
        None, description="ISO country code enabling holiday features for every item"
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "horizon_days": 14,
                "granularity": "weekly",
                "items": [
                    {"sku": "WIDGET-001", "history": [{"date": "2026-07-01", "quantity": 12}]},
                    {"sku": "WIDGET-002", "history": [{"date": "2026-07-01", "quantity": 4}]},
                ],
            }
        }
    }


class ForecastFailure(BaseModel):
    """A SKU that could not be forecast, and why.

    One product failing must not fail the batch, so failures are reported alongside
    the successful forecasts rather than replacing them.
    """

    sku: str = Field(..., description="SKU that failed")
    error: str = Field(..., description="Failure reason")


class BatchForecastResponse(BaseModel):
    """Response body for ``POST /forecast/batch``."""

    requested: int = Field(..., description="Number of SKUs in the request")
    succeeded: int = Field(..., description="How many produced a forecast")
    failed: int = Field(..., description="How many failed")
    forecasts: list[ForecastResponse] = Field(
        ..., description="Successful forecasts, sorted by SKU"
    )
    failures: list[ForecastFailure] = Field(
        ..., description="Per-SKU failures, sorted by SKU"
    )


# --------------------------------------------------------------------------------
# Inventory decisions
# --------------------------------------------------------------------------------


class CostInputs(BaseModel):
    """Costs that turn a reorder quantity from a guess into an optimum.

    EOQ needs a cost per order and a cost of holding one unit for a year. Businesses
    usually know holding cost as a percentage of unit value rather than an absolute,
    so either form is accepted.
    """

    ordering_cost: float | None = Field(
        None, gt=0, description="Fixed cost of placing one order, in currency"
    )
    holding_cost_per_unit_year: float | None = Field(
        None, gt=0, description="Cost to hold one unit for a year, in currency"
    )
    unit_cost: float | None = Field(
        None, gt=0, description="Unit purchase cost; with holding_cost_rate this derives holding cost"
    )
    holding_cost_rate: float | None = Field(
        None, gt=0, le=5,
        description="Annual holding cost as a fraction of unit cost, e.g. 0.25 for 25%",
    )


class ReorderRequest(BaseModel):
    """Request body for ``POST /inventory/reorder-recommendation``.

    ``forecast_daily_demand`` is meant to be fed straight from a ``/forecast``
    response, so the two endpoints compose without the caller reshaping anything.
    """

    sku: str = Field(..., min_length=1, description="SKU this recommendation is for")
    current_stock: float = Field(..., ge=0, description="Units physically on hand")
    on_order: float = Field(
        0.0, ge=0, description="Units already ordered but not yet received"
    )
    lead_time_days: float = Field(
        ..., gt=0, le=365, description="Average days between placing and receiving an order"
    )
    lead_time_std_days: float = Field(
        0.0, ge=0, le=365,
        description="Standard deviation of lead time; 0 treats the supplier as perfectly reliable",
    )
    service_level: float = Field(
        0.95, gt=0.5, lt=1.0,
        description="Probability of not stocking out during a replenishment cycle",
    )
    forecast_daily_demand: list[float] = Field(
        ..., min_length=1,
        description="Forecast daily demand over the planning window, from POST /forecast",
    )
    demand_std_override: float | None = Field(
        None, ge=0,
        description="Daily demand standard deviation; defaults to the spread of the forecast "
                    "itself, or of demand_history when supplied",
    )
    demand_history: list[DemandPoint] | None = Field(
        None,
        description="Observed history. Preferred source for demand variability, since a "
                    "forecast is smoothed and understates real volatility",
    )
    review_period_days: float = Field(
        0.0, ge=0, le=365,
        description="Days between stock reviews; adds to the risk window for periodic ordering",
    )
    costs: CostInputs | None = Field(
        None, description="Ordering and holding costs; without them a coverage heuristic is used"
    )
    default_coverage_days: float = Field(
        30.0, gt=0, le=365,
        description="Days of demand one order should cover when costs are not supplied",
    )
    min_order_quantity: float = Field(
        0.0, ge=0, description="Supplier minimum; the recommendation is raised to meet it"
    )
    order_multiple: float = Field(
        0.0, ge=0, description="Case or pallet size; the recommendation is rounded up to a multiple"
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "sku": "WIDGET-001",
                "current_stock": 400,
                "lead_time_days": 7,
                "lead_time_std_days": 2,
                "service_level": 0.95,
                "forecast_daily_demand": [45.0] * 14,
                "costs": {"ordering_cost": 100, "holding_cost_per_unit_year": 5},
            }
        }
    }


class ReorderIntermediate(BaseModel):
    """One row of the calculation, shown so the dashboard can explain the number.

    Every value feeds the recommendation, so each is returned with its formula and
    what it means. Fields are Optional because which ones apply depends on the path
    taken (cost-based EOQ vs. coverage heuristic, demand vs. lead-time variation).
    """

    name: str = Field(..., description="Identifier of the intermediate quantity")
    label: str = Field(..., description="Human-readable name for the dashboard")
    value: float = Field(..., description="The computed value")
    unit: str = Field("units", description="Measurement unit for the value")
    formula: str | None = Field(None, description="The formula this value came from")
    description: str = Field(..., description="Why this quantity matters")


class ReorderDecision(BaseModel):
    """The actionable answer: how much to order, and when the trigger fires."""

    order_quantity: float = Field(..., description="Units to order right now, if any")
    reorder_point: float = Field(..., description="Units on hand+on-order that triggers an order")
    safety_stock: float = Field(..., description="Buffer held against variability")
    inventory_position: float = Field(..., description="current_stock + on_order, what reorders compare against")


class ReorderRecommendationResponse(BaseModel):
    """Response body for ``POST /inventory/reorder-recommendation``."""

    sku: str = Field(..., description="SKU this recommendation is for")
    decision: ReorderDecision = Field(..., description="The actionable answer")
    intermediates: list[ReorderIntermediate] = Field(
        ..., description="Every calculation step, in order, so the number is explainable"
    )
    suggested_order: bool = Field(
        ..., description="True when the recommendation is to place an order now"
    )
    eoq: float | None = Field(
        None, description="Economic order quantity when costs were provided; null for the heuristic"
    )
    ordering_strategy: str = Field(
        ..., description="eoq when costs were given, else coverage-based heuristic"
    )
    method_note: str | None = Field(
        None, description="Any caveat worth surfacing, e.g. zero demand or cost inputs ignored"
    )

    model_config = {
        "protected_namespaces": (),
        "json_schema_extra": {
            "example": {
                "sku": "WIDGET-001",
                "decision": {
                    "order_quantity": 630,
                    "reorder_point": 425,
                    "safety_stock": 95,
                    "inventory_position": 400,
                },
                "suggested_order": True,
                "eoq": 630.0,
                "ordering_strategy": "eoq",
            }
        },
    }


class RiskStatus(str, Enum):
    """Inventory health classification for a single product."""

    OK = "OK"
    STOCKOUT_RISK = "STOCKOUT_RISK"
    OVERSTOCK = "OVERSTOCK"


class RiskScanItem(BaseModel):
    """One product's stock position and expected demand, for the risk scan."""

    sku: str = Field(..., min_length=1, description="SKU being assessed")
    current_stock: float = Field(..., ge=0, description="Units physically on hand")
    on_order: float = Field(0.0, ge=0, description="Units ordered but not yet received")
    forecast_daily_demand: list[float] = Field(
        ..., min_length=1, description="Forecast daily demand, from POST /forecast"
    )
    lead_time_days: float | None = Field(
        None, gt=0, le=365, description="Overrides the scan-level lead time for this SKU"
    )
    safety_stock: float | None = Field(
        None, ge=0, description="Known safety stock; otherwise the scan default applies"
    )


class RiskScanRequest(BaseModel):
    """Request body for ``POST /inventory/risk-scan``."""

    items: list[RiskScanItem] = Field(
        ..., min_length=1, max_length=1000, description="Products to assess"
    )
    lead_time_days: float = Field(
        7.0, gt=0, le=365, description="Default lead time when an item does not override it"
    )
    overstock_threshold_days: float = Field(
        90.0, gt=0, le=3650,
        description="Days of supply above which stock counts as excess capital",
    )
    review_period_days: float = Field(
        0.0, ge=0, le=365, description="Days between reviews, added to the risk window"
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "lead_time_days": 7,
                "items": [
                    {"sku": "WIDGET-001", "current_stock": 120, "forecast_daily_demand": [45.0] * 14}
                ],
            }
        }
    }


class RiskAssessment(BaseModel):
    """One product's classification, with the numbers that drove it."""

    sku: str = Field(..., description="SKU assessed")
    status: RiskStatus = Field(..., description="OK, STOCKOUT_RISK, or OVERSTOCK")
    severity: Severity = Field(..., description="How urgent this is")
    risk_score: float = Field(
        ..., ge=0, le=100,
        description="0-100, comparable across products so a dashboard can sort by it",
    )
    days_of_supply: float | None = Field(
        None, description="Days current stock covers at forecast demand; null if demand is zero"
    )
    days_until_stockout: float | None = Field(
        None, description="Days until stock hits zero; null if it does not within the horizon"
    )
    excess_days_of_supply: float | None = Field(
        None, description="Days of supply beyond the overstock threshold; null unless overstocked"
    )
    excess_units: float | None = Field(
        None, description="Units above what the threshold justifies; null unless overstocked"
    )
    projected_shortfall_units: float | None = Field(
        None, description="Units short over the risk window; null when there is no shortfall"
    )
    inventory_position: float = Field(..., description="current_stock + on_order")
    average_daily_demand: float = Field(..., description="Mean of the supplied forecast")
    risk_window_days: float = Field(
        ..., description="Lead time plus review period: the exposure window assessed"
    )
    reason: str = Field(..., description="Plain-language explanation of the classification")


class RiskScanSummary(BaseModel):
    """Counts per status, so a dashboard can render headline tiles without recounting."""

    total: int = Field(..., description="Products assessed")
    ok: int = Field(..., description="Count classified OK")
    stockout_risk: int = Field(..., description="Count classified STOCKOUT_RISK")
    overstock: int = Field(..., description="Count classified OVERSTOCK")


class RiskScanResponse(BaseModel):
    """Response body for ``POST /inventory/risk-scan``."""

    summary: RiskScanSummary = Field(..., description="Headline counts per status")
    assessments: list[RiskAssessment] = Field(
        ..., description="Per-product results, most urgent first"
    )


# --------------------------------------------------------------------------------
# Explainability
# --------------------------------------------------------------------------------


class FeatureContribution(BaseModel):
    """One feature's SHAP contribution to a single prediction."""

    feature: str = Field(..., description="Feature name from the engineered matrix")
    value: float = Field(..., description="The feature's value for the explained row")
    contribution: float = Field(
        ..., description="SHAP value: units of demand this feature added or removed"
    )
    direction: str = Field(..., description="increase or decrease")
    abs_contribution: float = Field(..., description="Magnitude, for ranking")


class ExplainRequest(BaseModel):
    """Request body for ``POST /explain/{product_id}``.

    The history is re-supplied because the service is stateless — it holds no
    fitted models between calls, so explaining a prediction means refitting on the
    same data that produced it.
    """

    history: list[DemandPoint] = Field(
        ..., min_length=1, description="The same history the forecast was produced from"
    )
    horizon_days: int = Field(
        14, ge=1, le=365, description="Horizon whose first day is explained by default"
    )
    step: int = Field(
        1, ge=1, le=365, description="Which forecast day to explain, 1 = first day ahead"
    )
    top_n: int = Field(10, ge=1, le=50, description="How many features to return")
    seasonal_periods: int = Field(7, ge=2, le=365, description="Season length in days")
    country_holidays: str | None = Field(
        None, description="ISO country code for holiday features, e.g. US"
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "step": 1,
                "top_n": 5,
                "history": [{"date": "2026-01-01", "quantity": 42}],
            }
        }
    }


class ExplainResponse(BaseModel):
    """Response body for ``POST /explain/{product_id}``."""

    sku: str = Field(..., description="Product explained")
    explained_date: Date = Field(..., description="The forecast day these contributions explain")
    step: int = Field(..., description="Days ahead of the history end")
    predicted: float = Field(..., description="The prediction being decomposed")
    base_value: float = Field(
        ..., description="Model's expected output before features, i.e. the SHAP base value"
    )
    contributions: list[FeatureContribution] = Field(
        ..., description="Top contributions by magnitude, largest first"
    )
    total_contribution: float = Field(
        ..., description="Sum of all SHAP values; base_value + this reconstructs the prediction"
    )
    model_used: str = Field(..., description="Model explained, always xgboost")
    note: str | None = Field(None, description="Caveat, e.g. XGBoost was not the selected model")

    model_config = {"protected_namespaces": ()}

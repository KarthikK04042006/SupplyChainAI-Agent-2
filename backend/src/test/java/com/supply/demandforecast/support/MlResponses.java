package com.supply.demandforecast.support;

import com.supply.demandforecast.domain.RiskStatus;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.dto.BatchForecastResponse;
import com.supply.demandforecast.dto.ExplainResponse;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.dto.ReorderRecommendationResponse;
import com.supply.demandforecast.dto.RiskScanResponse;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Builders for the ML service's responses, for tests that mock {@code MlServiceClient}.
 *
 * <p>Shared rather than duplicated per test class: these records have ten-odd components each,
 * and a test that has to spell all of them out to assert on two ends up hiding what it is
 * actually about. Every builder takes only the fields the assertions care about and fills the
 * rest with plausible values.
 *
 * <p>Being plausible matters — a fixture with {@code observations: 0} on a forecast built from
 * 30 points would let a bug that mixes up those fields pass.
 */
public final class MlResponses {

    private MlResponses() {
    }

    /** A forecast of {@code days} days at a flat {@code perDay}, as the ML service would shape it. */
    public static ForecastResponse forecast(String sku, int days, double perDay) {
        return forecast(sku, days, perDay, 30);
    }

    public static ForecastResponse forecast(String sku, int days, double perDay, int observations) {
        LocalDate start = LocalDate.of(2026, 3, 1);

        return new ForecastResponse(
                sku,
                "holt_winters",
                "lowest MAPE over 3 folds",
                "daily",
                observations,
                true,
                List.of(new ForecastResponse.ModelScore(
                        "holt_winters", 8.4, 12.1, 9.7, 3, true, null)),
                java.util.stream.IntStream.range(0, days)
                        .mapToObj(i -> new ForecastResponse.ForecastPoint(
                                start.plusDays(i), perDay, perDay * 0.8, perDay * 1.2))
                        .toList(),
                List.of(),
                List.of());
    }

    /** A batch response where every forecast succeeded. */
    public static BatchForecastResponse batch(ForecastResponse... forecasts) {
        return new BatchForecastResponse(
                forecasts.length, forecasts.length, 0, Arrays.asList(forecasts), List.of());
    }

    /** A batch response mixing successes with per-SKU failures, as the real endpoint can return. */
    public static BatchForecastResponse batch(List<ForecastResponse> forecasts,
                                             List<BatchForecastResponse.Failure> failures) {
        return new BatchForecastResponse(
                forecasts.size() + failures.size(),
                forecasts.size(),
                failures.size(),
                forecasts,
                failures);
    }

    /** A risk scan whose summary is derived from the assessments, so the two cannot disagree. */
    public static RiskScanResponse riskScan(RiskScanResponse.Assessment... assessments) {
        List<RiskScanResponse.Assessment> list = Arrays.asList(assessments);

        return new RiskScanResponse(
                new RiskScanResponse.Summary(
                        list.size(),
                        (int) list.stream().filter(a -> a.status() == RiskStatus.OK).count(),
                        (int) list.stream().filter(a -> a.status() == RiskStatus.STOCKOUT_RISK).count(),
                        (int) list.stream().filter(a -> a.status() == RiskStatus.OVERSTOCK).count()),
                list);
    }

    public static RiskScanResponse.Assessment stockoutRisk(
            String sku, Severity severity, double riskScore) {
        return assessment(sku, RiskStatus.STOCKOUT_RISK, severity, riskScore,
                "Projected to run out in 3 days, inside the 10-day protection period");
    }

    public static RiskScanResponse.Assessment overstock(
            String sku, Severity severity, double riskScore) {
        return assessment(sku, RiskStatus.OVERSTOCK, severity, riskScore,
                "182 days of cover against a 60-day threshold");
    }

    public static RiskScanResponse.Assessment ok(String sku) {
        return assessment(sku, RiskStatus.OK, Severity.LOW, 0.0, "Cover is within target");
    }

    public static RiskScanResponse.Assessment assessment(String sku,
                                                        RiskStatus status,
                                                        Severity severity,
                                                        double riskScore,
                                                        String reason) {
        return new RiskScanResponse.Assessment(
                sku, status, severity, riskScore,
                6.0, 3.0, null, null, 42.0,
                120.0, 20.0, 10.0,
                reason);
    }

    /**
     * An EOQ-based recommendation, as the service answers when costs were supplied.
     *
     * <p>The intermediates carry their formulas, which is what the product page renders as the
     * "why" beneath the numbers — a fixture with an empty list would let a panel that silently
     * dropped them pass.
     */
    public static ReorderRecommendationResponse reorder(String sku, double orderQuantity) {
        return new ReorderRecommendationResponse(
                sku,
                new ReorderRecommendationResponse.Decision(orderQuantity, 310.0, 95.0, 180.0),
                List.of(
                        new ReorderRecommendationResponse.Intermediate(
                                "average_daily_demand", "Average daily demand", 21.5, "units/day",
                                "mean(forecast_daily_demand)",
                                "Mean of the 14-day forecast"),
                        new ReorderRecommendationResponse.Intermediate(
                                "safety_stock", "Safety stock", 95.0, "units",
                                "z × σ_demand × √(lead_time + review_period)",
                                "Buffer for demand variability over the protection period"),
                        new ReorderRecommendationResponse.Intermediate(
                                "reorder_point", "Reorder point", 310.0, "units",
                                "average_daily_demand × (lead_time + review_period) + safety_stock",
                                "Position at which to place an order")),
                true,
                262.0,
                "eoq",
                "Economic order quantity, using the supplied unit cost");
    }

    /** The coverage-heuristic answer: no costs supplied, so no EOQ and a note saying why. */
    public static ReorderRecommendationResponse reorderWithoutCosts(String sku) {
        return new ReorderRecommendationResponse(
                sku,
                new ReorderRecommendationResponse.Decision(430.0, 310.0, 95.0, 180.0),
                List.of(new ReorderRecommendationResponse.Intermediate(
                        "coverage_days", "Target coverage", 20.0, "days",
                        "default_coverage_days",
                        "No costs supplied, so cover is targeted directly")),
                true,
                null,
                "coverage",
                "No unit cost supplied; ordered to a 20-day coverage target instead of EOQ");
    }

    /** A SHAP decomposition of the first forecast day. */
    public static ExplainResponse explanation(String sku) {
        return new ExplainResponse(
                sku,
                LocalDate.of(2026, 3, 1),
                1,
                118.4,
                100.0,
                List.of(
                        new ExplainResponse.Contribution(
                                "demand 7 day(s) ago", 132.0, 12.6, "increase", 12.6),
                        new ExplainResponse.Contribution(
                                "rolling 28-day mean", 96.0, 8.2, "increase", 8.2),
                        new ExplainResponse.Contribution(
                                "day of week", 5.0, -2.4, "decrease", 2.4)),
                18.4,
                "xgboost",
                null);
    }

    /**
     * The other shape {@code /explain} returns: a model without SHAP support.
     *
     * <p>Not an error — the service answers 200 with an empty contribution list and a note. The
     * panel has to tell this apart from "nothing influenced the prediction", which is why the
     * note is populated and the list is empty rather than the other way round.
     */
    public static ExplainResponse noExplanation(String sku, String note) {
        return new ExplainResponse(
                sku, LocalDate.of(2026, 3, 1), 1, 118.4, 0.0,
                List.of(), 0.0, "holt_winters", note);
    }
}

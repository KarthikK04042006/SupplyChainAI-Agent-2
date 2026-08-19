package com.supply.demandforecast.service;

import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.entity.ForecastAccuracy;
import com.supply.demandforecast.repository.ForecastAccuracyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which score the accuracy KPI is built from, and which forecasts are left unmeasured.
 *
 * <p>{@code selectedScore} is the whole of the logic here and it is private, so it is exercised
 * through {@link ForecastAccuracyRecorder#record(List)} — the persisted row is the only thing
 * that observes it, and it is what the dashboard reads.
 *
 * <p>Against a real database rather than a mocked repository because half the behaviour is
 * persistence: the recorder upserts by SKU against a unique index, and "leave the existing row
 * alone" is a claim about what is in the table afterwards, which a verify-no-interactions
 * assertion would only approximate.
 */
@DataJpaTest
@ActiveProfiles("test")
class ForecastAccuracyRecorderTest {

    @Autowired
    private ForecastAccuracyRepository forecastAccuracyRepository;

    private ForecastAccuracyRecorder recorder;

    @BeforeEach
    void setUp() {
        // A plain @Component with one dependency; @DataJpaTest does not scan for it, and standing
        // the whole context up to get one constructor call would be the slower way to be less sure.
        recorder = new ForecastAccuracyRecorder(forecastAccuracyRepository);
    }

    /**
     * The selected model's score is persisted, not the first entry and not the best one.
     *
     * <p>{@code scores} is ordered by the order candidates were tried, and selection can turn on
     * more than MAPE — seasonality support, for one — so the winner is neither reliably first nor
     * reliably the lowest error. The fixture makes those three answers different on purpose: the
     * selected model is second <em>and</em> scores worse than the candidate above it, so reading
     * either position instead of matching by name gives 4.1 rather than 8.4.
     */
    @Test
    void persistsTheSelectedModelsScoreRatherThanTheFirstOrTheBest() {
        int scored = recorder.record(List.of(forecast("WIDGET-001", "holt_winters", 180,
                score("moving_average(w=7)", 4.1, 1.2, 0.9),
                score("holt_winters", 8.4, 2.6, 2.1))));

        assertThat(scored).isEqualTo(1);
        ForecastAccuracy row = forecastAccuracyRepository.findBySku("WIDGET-001").orElseThrow();
        assertThat(row.getSelectedModel()).isEqualTo("holt_winters");
        assertThat(row.getMape()).isEqualTo(8.4);
        assertThat(row.getRmse()).isEqualTo(2.6);
        assertThat(row.getMae()).isEqualTo(2.1);
        assertThat(row.getObservations()).isEqualTo(180);
        assertThat(row.getScoredAt()).isNotNull();
    }

    /**
     * A refit fallback files the baseline's error, not the winner's unreachable score.
     *
     * <p>When the ML service cannot refit its backtest winner on the full history it forecasts
     * with the moving-average baseline and reports the baseline as {@code selected_model}, while
     * leaving the winner in {@code scores} so the attempt stays auditable. Both are fitted and
     * carry real metrics, so nothing but the name distinguishes them here.
     *
     * <p>This is the case the KPI would misreport most badly: the winner scored a perfect 0.00%
     * on the folds it never had to survive, and filing that would have the dashboard advertise a
     * flawless forecast that no model produced.
     */
    @Test
    void refitFallbackFilesTheProducingModelsError() {
        int scored = recorder.record(List.of(forecast("WIDGET-002", "moving_average(w=7)", 90,
                score("wins_then_fails", 0.0, 0.0, 0.0),
                score("moving_average(w=7)", 5.584, 2.362, 2.206))));

        assertThat(scored).isEqualTo(1);
        ForecastAccuracy row = forecastAccuracyRepository.findBySku("WIDGET-002").orElseThrow();
        assertThat(row.getSelectedModel()).isEqualTo("moving_average(w=7)");
        assertThat(row.getMape()).isEqualTo(5.584);
        assertThat(row.getMape()).isNotEqualTo(0.0);
    }

    /**
     * A forecast carrying no score for its selected model leaves an older measurement standing.
     *
     * <p>The service omits walk-forward validation when history is too short to hold out a fold,
     * so this SKU is one the KPI should count as unmeasured. Writing a null-metric row would
     * replace a figure that was once real with an absence, and the KPI averages over non-null
     * MAPE — so the SKU would silently leave the sample it used to be in.
     */
    @Test
    void leavesAnExistingRowAloneWhenTheNewForecastCarriesNoScore() {
        forecastAccuracyRepository.save(ForecastAccuracy.builder()
                .sku("WIDGET-003")
                .mape(11.5)
                .rmse(3.0)
                .mae(2.5)
                .selectedModel("sarima")
                .observations(400)
                .scoredAt(Instant.parse("2026-08-01T00:00:00Z"))
                .build());

        int scored = recorder.record(List.of(forecast("WIDGET-003", "naive_last_value", 12)));

        assertThat(scored).isZero();
        ForecastAccuracy row = forecastAccuracyRepository.findBySku("WIDGET-003").orElseThrow();
        assertThat(row.getMape()).isEqualTo(11.5);
        assertThat(row.getSelectedModel()).isEqualTo("sarima");
        assertThat(row.getObservations()).isEqualTo(400);
    }

    /**
     * A candidate listed with no metrics at all is not a measurement.
     *
     * <p>An unfitted candidate is still returned, with {@code error} in place of scores, so the
     * comparison shows what was tried. If it is somehow also the selected model, the row it would
     * write is three nulls and a name — indistinguishable from a scored SKU to the {@code
     * count(mape is not null)} the sample size comes from, and useless to the average.
     */
    @Test
    void doesNotRecordASelectedModelWhoseMetricsAreAllNull() {
        int scored = recorder.record(List.of(forecast("WIDGET-004", "prophet", 30,
                unfitted("prophet", "prophet is not installed in this environment"))));

        assertThat(scored).isZero();
        assertThat(forecastAccuracyRepository.findBySku("WIDGET-004")).isEmpty();
    }

    /**
     * Re-scoring a SKU updates its row instead of adding a second one.
     *
     * <p>The scan runs on a schedule, so all but the first pass over a SKU takes this path. {@code
     * sku} carries a unique index, which means the alternative to updating is not a duplicate row
     * but a constraint violation that fails the whole batch.
     */
    @Test
    void upsertsBySkuAcrossRepeatedScans() {
        recorder.record(List.of(forecast("WIDGET-005", "holt_winters", 100,
                score("holt_winters", 9.0, 3.1, 2.4))));
        recorder.record(List.of(forecast("WIDGET-005", "sarima", 130,
                score("sarima", 6.2, 2.0, 1.7))));
        forecastAccuracyRepository.flush();

        assertThat(forecastAccuracyRepository.findAll()).hasSize(1);
        ForecastAccuracy row = forecastAccuracyRepository.findBySku("WIDGET-005").orElseThrow();
        assertThat(row.getSelectedModel()).isEqualTo("sarima");
        assertThat(row.getMape()).isEqualTo(6.2);
        assertThat(row.getObservations()).isEqualTo(130);
    }

    /**
     * A batch mixing scored and unscored SKUs records only the scored ones.
     *
     * <p>The return value is what the scan logs as its accuracy sample, so it has to count rows
     * written rather than forecasts received.
     */
    @Test
    void recordsOnlyTheScorableForecastsInAMixedBatch() {
        int scored = recorder.record(List.of(
                forecast("A-1", "holt_winters", 180, score("holt_winters", 7.0, 2.0, 1.5)),
                forecast("A-2", "naive_last_value", 9),
                forecast("A-3", "sarima", 200, score("sarima", 12.0, 4.0, 3.2))));

        assertThat(scored).isEqualTo(2);
        assertThat(forecastAccuracyRepository.findAll())
                .extracting(ForecastAccuracy::getSku)
                .containsExactlyInAnyOrder("A-1", "A-3");
    }

    /** Nothing to score is not an error, and must not cost a query. */
    @Test
    void recordsNothingForAnEmptyOrNullBatch() {
        assertThat(recorder.record(List.of())).isZero();
        assertThat(recorder.record(null)).isZero();
        assertThat(forecastAccuracyRepository.findAll()).isEmpty();
    }

    private static ForecastResponse forecast(String sku, String selectedModel, int observations,
                                            ForecastResponse.ModelScore... scores) {
        return new ForecastResponse(
                sku,
                selectedModel,
                "chosen by the fixture",
                "daily",
                observations,
                true,
                List.of(scores),
                List.of(new ForecastResponse.ForecastPoint(
                        LocalDate.of(2026, 3, 1), 40.0, 32.0, 48.0)),
                List.of(),
                List.of());
    }

    private static ForecastResponse.ModelScore score(
            String model, Double mape, Double rmse, Double mae) {
        return new ForecastResponse.ModelScore(model, mape, rmse, mae, 3, true, null);
    }

    private static ForecastResponse.ModelScore unfitted(String model, String error) {
        return new ForecastResponse.ModelScore(model, null, null, null, null, false, error);
    }
}

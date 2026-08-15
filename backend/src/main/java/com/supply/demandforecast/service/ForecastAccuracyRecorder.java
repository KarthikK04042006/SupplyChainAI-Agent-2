package com.supply.demandforecast.service;

import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.entity.ForecastAccuracy;
import com.supply.demandforecast.repository.ForecastAccuracyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores how well the selected model scored for each forecast SKU.
 *
 * <p>Called by {@link InventoryScanService} with the batch it already fetched, so the accuracy
 * KPI costs no extra ML calls and refreshes whenever the alerts do.
 *
 * <p>Only the <em>selected</em> model's scores are kept. The ML service returns the whole
 * candidate comparison, which is what makes its choice auditable, but a dashboard average over
 * every candidate — including the ones rejected for scoring badly — would describe a forecast
 * nobody is using.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForecastAccuracyRecorder {

    private final ForecastAccuracyRepository forecastAccuracyRepository;

    /**
     * Upserts one row per forecast that carries a score for its selected model.
     *
     * <p>A forecast with no scores is left alone rather than written as a null-metric row: the
     * ML service omits walk-forward validation when history is too short to hold out a fold,
     * and an unscored SKU is one the KPI should count as unmeasured. Overwriting an older,
     * genuine score with nulls would lose a measurement that was once real.
     *
     * @return how many SKUs were scored
     */
    public int record(List<ForecastResponse> forecasts) {
        if (forecasts == null || forecasts.isEmpty()) {
            return 0;
        }

        Map<String, ForecastResponse> scored = new LinkedHashMap<>();
        for (ForecastResponse forecast : forecasts) {
            if (selectedScore(forecast) != null) {
                scored.put(forecast.sku(), forecast);
            }
        }
        if (scored.isEmpty()) {
            log.debug("No forecast in this batch carried a score for its selected model");
            return 0;
        }

        Map<String, ForecastAccuracy> existing = new LinkedHashMap<>();
        forecastAccuracyRepository.findBySkuIn(scored.keySet())
                .forEach(row -> existing.put(row.getSku(), row));

        Instant scoredAt = Instant.now();
        List<ForecastAccuracy> toSave = new ArrayList<>();

        for (ForecastResponse forecast : scored.values()) {
            ForecastResponse.ModelScore score = selectedScore(forecast);
            ForecastAccuracy row = existing.get(forecast.sku());

            if (row == null) {
                row = ForecastAccuracy.builder().sku(forecast.sku()).build();
            }

            row.setMape(score.mape());
            row.setRmse(score.rmse());
            row.setMae(score.mae());
            row.setSelectedModel(forecast.selectedModel());
            row.setObservations(forecast.observations());
            row.setScoredAt(scoredAt);
            toSave.add(row);
        }

        forecastAccuracyRepository.saveAll(toSave);
        return toSave.size();
    }

    /**
     * The score belonging to the model the service actually chose, or null if it has none.
     *
     * <p>Matched by name rather than taken as the first or best-scoring entry: {@code scores}
     * lists candidates in the order they were tried, and the selection can turn on more than
     * MAPE alone — seasonality support, for one. Reading the winner off the list ourselves
     * would be a second, quietly different selection rule.
     */
    private static ForecastResponse.ModelScore selectedScore(ForecastResponse forecast) {
        if (forecast == null || forecast.selectedModel() == null) {
            return null;
        }
        return forecast.scores().stream()
                .filter(score -> forecast.selectedModel().equals(score.model()))
                .filter(score -> score.mape() != null || score.rmse() != null || score.mae() != null)
                .findFirst()
                .orElse(null);
    }
}

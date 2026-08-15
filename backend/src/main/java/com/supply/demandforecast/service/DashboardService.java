package com.supply.demandforecast.service;

import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.dto.DashboardSummary;
import com.supply.demandforecast.repository.AlertRepository;
import com.supply.demandforecast.repository.ForecastAccuracyRepository;
import com.supply.demandforecast.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The overview page's KPI figures.
 *
 * <p>Counts and aggregates are pushed into the database rather than computed over loaded entities:
 * the totals describe the whole catalogue, and reading it into memory to sum four numbers would
 * make the cheapest page the heaviest query.
 *
 * <p>Every figure reports its own sample size — see {@link DashboardSummary}. That is the whole
 * reason this is a service and not four repository calls in a controller: an average MAPE over
 * three scored SKUs and one over three thousand look identical as a percentage, and the card has
 * to be able to tell them apart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ProductRepository productRepository;
    private final AlertRepository alertRepository;
    private final ForecastAccuracyRepository forecastAccuracyRepository;

    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        return new DashboardSummary(
                productRepository.count(),
                alerts(),
                inventoryValue(),
                accuracy(),
                Instant.now());
    }

    /** Open alerts, total and per severity. */
    private DashboardSummary.Alerts alerts() {
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity.name(),
                    alertRepository.countByResolvedFalseAndSeverity(severity));
        }
        return new DashboardSummary.Alerts(alertRepository.countByResolvedFalse(), bySeverity);
    }

    private DashboardSummary.InventoryValue inventoryValue() {
        return new DashboardSummary.InventoryValue(
                productRepository.sumInventoryValue(),
                productRepository.countByUnitCostNotNull(),
                productRepository.countByUnitCostIsNull());
    }

    /**
     * Mean accuracy as {@code 100 - MAPE}.
     *
     * <p>Floored at zero because MAPE is unbounded above: a forecast off by triple scores 300,
     * and a card reading -200% accuracy invites a bug report about the sign rather than about the
     * forecast. Nothing scored yet stays null rather than becoming zero.
     */
    private DashboardSummary.Accuracy accuracy() {
        Double meanMape = forecastAccuracyRepository.averageMape();
        Double percent = meanMape == null ? null : Math.max(0.0, 100.0 - meanMape);

        return new DashboardSummary.Accuracy(
                percent,
                forecastAccuracyRepository.countByMapeNotNull(),
                forecastAccuracyRepository.lastScoredAt());
    }
}

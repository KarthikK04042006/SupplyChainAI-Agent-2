package com.supply.demandforecast.service;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.dto.AlertView;
import com.supply.demandforecast.dto.BatchForecastRequest;
import com.supply.demandforecast.dto.BatchForecastResponse;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.dto.MlForecastRequest;
import com.supply.demandforecast.dto.RiskScanRequest;
import com.supply.demandforecast.dto.RiskScanResponse;
import com.supply.demandforecast.dto.ScanResult;
import com.supply.demandforecast.entity.Alert;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.AlertRepository;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import com.supply.demandforecast.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scans the whole catalogue for inventory risk and keeps the alert table in step with it.
 *
 * <p>Four stages: read stock and history from the database, forecast every SKU in one batch
 * call, hand stock plus forecast to the ML service for classification, then reconcile the
 * verdicts against the alerts already open.
 *
 * <p>That last stage is the one that makes the alert list trustworthy. A scan that only ever
 * inserted rows would restate the same problem on every run and never admit when one cleared,
 * so an operator would learn to ignore it. Instead a standing problem updates its existing
 * alert, and a problem that no longer appears is resolved automatically — see {@link
 * #reconcile}.
 *
 * <p>Runs in one transaction. A scan that flagged half the catalogue and then failed would
 * leave alerts that disagree with the reconciliation that never ran; all-or-nothing is easier
 * to reason about than a partial pass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryScanService {

    /** Below this many observations the ML service cannot forecast, so the SKU is skipped. */
    private static final int MIN_HISTORY_POINTS = 2;

    /** Username recorded on alerts the scan closes itself. */
    private static final String SYSTEM_ACTOR = "system:scan";

    private final ProductRepository productRepository;
    private final DemandHistoryRepository demandHistoryRepository;
    private final AlertRepository alertRepository;
    private final MlServiceClient mlServiceClient;
    private final ForecastAccuracyRecorder accuracyRecorder;
    private final InventoryScanProperties properties;

    /**
     * Runs a full scan and persists the resulting alerts.
     *
     * <p>Propagates {@link com.supply.demandforecast.client.MlServiceException} rather than
     * catching it: a scan that could not reach the ML service produced no verdicts, and
     * returning an empty result would look identical to a catalogue with nothing wrong.
     */
    @Transactional
    public ScanResult scan() {
        Instant startedAt = Instant.now();
        List<Product> products = productRepository.findAll();
        List<ScanResult.Skipped> skipped = new ArrayList<>();

        if (products.isEmpty()) {
            log.info("Inventory scan found no products");
            return emptyResult(startedAt, skipped);
        }

        Map<String, List<DemandHistory>> historyBySku = loadHistory(products);
        List<BatchForecastRequest.Item> forecastItems =
                buildForecastItems(products, historyBySku, skipped);

        if (forecastItems.isEmpty()) {
            log.info("Inventory scan skipped all {} product(s): none had usable history",
                    products.size());
            return emptyResult(startedAt, skipped);
        }

        BatchForecastResponse forecasts = mlServiceClient.forecastBatch(
                BatchForecastRequest.of(forecastItems, properties.horizonDays()));

        forecasts.failures().forEach(failure ->
                skipped.add(new ScanResult.Skipped(failure.sku(), "forecast failed: " + failure.error())));

        // The batch already carries each model's walk-forward scores, so the accuracy KPI is a
        // by-product of the scan rather than a second pass over the catalogue.
        accuracyRecorder.record(forecasts.forecasts());

        Map<String, List<Double>> demandBySku = forecasts.forecasts().stream()
                .collect(Collectors.toMap(ForecastResponse::sku,
                        InventoryScanService::dailyDemand,
                        (first, second) -> first,
                        LinkedHashMap::new));

        List<RiskScanRequest.Item> items = buildRiskItems(products, demandBySku);
        if (items.isEmpty()) {
            log.warn("Inventory scan produced no forecastable items from {} product(s)",
                    products.size());
            return emptyResult(startedAt, skipped);
        }

        RiskScanResponse scan = mlServiceClient.riskScan(new RiskScanRequest(
                items,
                properties.defaultLeadTimeDays(),
                properties.overstockThresholdDays(),
                properties.reviewPeriodDays()));

        Reconciliation reconciliation = reconcile(scan);

        log.info("Inventory scan assessed {} SKU(s): {} alert(s) opened, {} updated, {} resolved",
                items.size(), reconciliation.opened, reconciliation.updated, reconciliation.resolved);

        return new ScanResult(
                startedAt,
                items.size(),
                skipped,
                reconciliation.opened,
                reconciliation.updated,
                reconciliation.resolved,
                alertRepository.findByResolvedFalse().stream()
                        .sorted((a, b) -> Double.compare(b.getRiskScore(), a.getRiskScore()))
                        .map(AlertView::from)
                        .toList());
    }

    /** History for every product, in one query, grouped by SKU. */
    private Map<String, List<DemandHistory>> loadHistory(List<Product> products) {
        Set<String> skus = products.stream()
                .map(Product::getSku)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return demandHistoryRepository.findBySkuInOrderBySkuAscDemandDateAsc(skus).stream()
                .collect(Collectors.groupingBy(DemandHistory::getSku));
    }

    /** Batch forecast items for products with enough history, recording the rest as skipped. */
    private List<BatchForecastRequest.Item> buildForecastItems(
            List<Product> products,
            Map<String, List<DemandHistory>> historyBySku,
            List<ScanResult.Skipped> skipped) {

        List<BatchForecastRequest.Item> items = new ArrayList<>();

        for (Product product : products) {
            List<DemandHistory> history = historyBySku.getOrDefault(product.getSku(), List.of());

            if (history.size() < MIN_HISTORY_POINTS) {
                skipped.add(new ScanResult.Skipped(product.getSku(),
                        "only %d demand observation(s); %d required"
                                .formatted(history.size(), MIN_HISTORY_POINTS)));
                continue;
            }

            items.add(BatchForecastRequest.Item.of(product.getSku(), history.stream()
                    .map(point -> new MlForecastRequest.DemandPoint(
                            point.getDemandDate(), point.getQuantity()))
                    .toList()));
        }
        return items;
    }

    /**
     * Risk-scan items pairing each product's stock position with its forecast.
     *
     * <p>A product whose forecast failed is left out entirely rather than sent with an empty
     * demand series: zero forecast demand reads as "this will never run out", which is the
     * opposite of what a failed forecast tells us.
     */
    private List<RiskScanRequest.Item> buildRiskItems(List<Product> products,
                                                      Map<String, List<Double>> demandBySku) {
        List<RiskScanRequest.Item> items = new ArrayList<>();

        for (Product product : products) {
            List<Double> demand = demandBySku.get(product.getSku());
            if (demand == null || demand.isEmpty()) {
                continue;
            }

            items.add(new RiskScanRequest.Item(
                    product.getSku(),
                    product.getCurrentStock(),
                    product.getOnOrder(),
                    demand,
                    // Zero would claim same-day resupply, which no product has. Fall back to
                    // the batch default instead of asserting something false.
                    product.getLeadTimeDays() > 0 ? (double) product.getLeadTimeDays() : null,
                    product.getSafetyStock()));
        }
        return items;
    }

    /**
     * Brings the alert table in line with a fresh set of verdicts.
     *
     * <p>Three cases. A verdict at or above the risk threshold either opens an alert or
     * restates the open one. A verdict below the threshold, or {@code OK}, leaves nothing —
     * and any alert of that type still open for the SKU is then resolved, because the scan
     * that would have restated it did not.
     *
     * <p>Only alerts for SKUs actually assessed in this pass are eligible for auto-resolution.
     * A product skipped for thin history contributed no verdict, which is not evidence its
     * problem went away.
     */
    private Reconciliation reconcile(RiskScanResponse scan) {
        Reconciliation result = new Reconciliation();
        Set<String> assessedSkus = new LinkedHashSet<>();
        Set<String> stillFlagged = new LinkedHashSet<>();

        for (RiskScanResponse.Assessment assessment : scan.assessments()) {
            assessedSkus.add(assessment.sku());

            AlertType type = AlertType.fromRiskStatus(assessment.status());
            if (type == null || assessment.riskScore() < properties.riskThreshold()) {
                continue;
            }
            stillFlagged.add(alertKey(assessment.sku(), type));

            alertRepository.findBySkuAndTypeAndResolvedFalse(assessment.sku(), type)
                    .ifPresentOrElse(
                            open -> {
                                open.restate(
                                        assessment.severity(),
                                        assessment.reason(),
                                        assessment.riskScore(),
                                        assessment.daysOfSupply(),
                                        assessment.inventoryPosition());
                                result.updated++;
                            },
                            () -> {
                                alertRepository.save(Alert.builder()
                                        .sku(assessment.sku())
                                        .type(type)
                                        .severity(assessment.severity())
                                        .message(assessment.reason())
                                        .riskScore(assessment.riskScore())
                                        .daysOfSupply(assessment.daysOfSupply())
                                        .inventoryPosition(assessment.inventoryPosition())
                                        .build());
                                result.opened++;
                            });
        }

        if (properties.autoResolveEnabled()) {
            result.resolved = autoResolve(assessedSkus, stillFlagged);
        }
        return result;
    }

    /** Closes open alerts for assessed SKUs that this scan did not re-flag. */
    private int autoResolve(Set<String> assessedSkus, Set<String> stillFlagged) {
        int resolved = 0;

        for (Alert alert : alertRepository.findByResolvedFalse()) {
            if (!assessedSkus.contains(alert.getSku())
                    || stillFlagged.contains(alertKey(alert.getSku(), alert.getType()))) {
                continue;
            }
            // DEMAND_ANOMALY alerts come from history, not from stock, so a stock scan has
            // no standing to say they cleared.
            if (alert.getType() == AlertType.DEMAND_ANOMALY) {
                continue;
            }

            alert.resolve(SYSTEM_ACTOR, "Cleared automatically: no longer flagged by the scan");
            resolved++;
        }
        return resolved;
    }

    private static String alertKey(String sku, AlertType type) {
        return sku + " " + type;
    }

    /** The predicted daily quantities, which is all the risk scan needs from a forecast. */
    private static List<Double> dailyDemand(ForecastResponse forecast) {
        return forecast.points().stream()
                .map(ForecastResponse.ForecastPoint::predicted)
                .toList();
    }

    private ScanResult emptyResult(Instant startedAt, List<ScanResult.Skipped> skipped) {
        return new ScanResult(startedAt, 0, skipped, 0, 0, 0,
                alertRepository.findByResolvedFalse().stream()
                        .map(AlertView::from)
                        .toList());
    }

    /** Mutable tally, kept local to one scan. */
    private static final class Reconciliation {
        private int opened;
        private int updated;
        private int resolved;
    }
}

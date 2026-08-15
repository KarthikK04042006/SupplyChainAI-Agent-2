package com.supply.demandforecast.service;

import com.supply.demandforecast.domain.RiskStatus;
import com.supply.demandforecast.dto.InventoryGrid;
import com.supply.demandforecast.entity.InventoryLevel;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.entity.Warehouse;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import com.supply.demandforecast.repository.InventoryLevelRepository;
import com.supply.demandforecast.repository.ProductRepository;
import com.supply.demandforecast.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the products × warehouses grid behind {@code GET /api/inventory/levels}.
 *
 * <p><b>The cell status is computed here, not by the ML service.</b> The risk scan classifies at
 * SKU level against aggregate stock; it has no per-warehouse view, so there is no ML verdict to
 * ask for. Each cell instead gets an equal share of the SKU's average daily demand — see
 * {@link #allocationShare(int)} for why an equal split rather than a stock-weighted one — which
 * turns into days of supply and then into a status against the same thresholds the scan uses.
 *
 * <p>That allocation is a heuristic, and it can disagree with the alert list — a SKU healthy in
 * aggregate can show a starved location, which is the useful part, but a cell is not evidence the
 * ML service concluded anything. The page's legend says the same thing, because a cell coloured
 * like an alert would otherwise be read as one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryLevelService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;

    /**
     * Ceiling on products pulled into one grid.
     *
     * <p>The status filter is applied after the cells are built, so the rows have to exist before
     * they can be counted — which rules out paging in the database. A catalogue this size is one
     * query and a few thousand small objects; beyond it the response says {@code truncated} rather
     * than quietly describing part of the catalogue as all of it.
     */
    private static final int MAX_GRID_PRODUCTS = 2000;

    /** Days of history averaged for the per-cell demand rate. */
    private static final int DEMAND_WINDOW_DAYS = 90;

    private final ProductRepository productRepository;
    private final InventoryLevelRepository inventoryLevelRepository;
    private final WarehouseRepository warehouseRepository;
    private final DemandHistoryRepository demandHistoryRepository;
    private final InventoryScanProperties scanProperties;

    /**
     * The grid, filtered and paged.
     *
     * @param status when given, keeps only products with at least one cell in that status — the
     *               page exists to find trouble, and a row of healthy cells is not it
     */
    @Transactional(readOnly = true)
    public InventoryGrid grid(String q, String category, String warehouse, RiskStatus status,
                              Integer page, Integer size) {

        List<Product> products = productRepository.search(
                        blankToNull(q), blankToNull(category),
                        PageRequest.of(0, MAX_GRID_PRODUCTS, Sort.by("sku")))
                .getContent();

        boolean truncated = products.size() == MAX_GRID_PRODUCTS;
        if (truncated) {
            log.warn("Inventory grid truncated at {} products; narrow the filter for the rest",
                    MAX_GRID_PRODUCTS);
        }

        List<InventoryGrid.Column> columns = columns(blankToNull(warehouse));
        List<InventoryGrid.Row> rows = buildRows(products, columns);

        if (status != null) {
            rows = rows.stream()
                    .filter(row -> row.cells().values().stream()
                            .anyMatch(cell -> status == cell.status()))
                    .toList();
        }

        Map<String, Long> statusCounts = countStatuses(rows);

        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int pageNumber = page == null || page < 0 ? 0 : page;
        int from = Math.min(pageNumber * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());

        return new InventoryGrid(
                columns,
                rows.subList(from, to),
                productRepository.findDistinctCategories(),
                statusCounts,
                rows.size(),
                pageNumber,
                pageSize,
                (int) Math.ceil((double) rows.size() / pageSize),
                truncated);
    }

    /** Active warehouses in code order, narrowed to one when the caller asked for it. */
    private List<InventoryGrid.Column> columns(String warehouseCode) {
        return warehouseRepository.findByActiveTrueOrderByCodeAsc().stream()
                .filter(w -> warehouseCode == null || warehouseCode.equalsIgnoreCase(w.getCode()))
                .map(w -> new InventoryGrid.Column(
                        w.getId(), w.getCode(), w.getName(), w.getRegion()))
                .toList();
    }

    /** One row per product, with its cells keyed by warehouse code. */
    private List<InventoryGrid.Row> buildRows(List<Product> products,
                                              List<InventoryGrid.Column> columns) {
        if (products.isEmpty() || columns.isEmpty()) {
            return List.of();
        }

        List<String> skus = products.stream().map(Product::getSku).toList();
        Map<String, List<InventoryLevel>> levelsBySku = new LinkedHashMap<>();
        inventoryLevelRepository.findBySkuInWithWarehouse(skus).forEach(level ->
                levelsBySku.computeIfAbsent(level.getSku(), k -> new ArrayList<>()).add(level));

        Map<String, Double> demandBySku = dailyDemand(skus);
        List<String> visible = columns.stream().map(InventoryGrid.Column::code).toList();
        List<InventoryGrid.Row> rows = new ArrayList<>(products.size());

        for (Product product : products) {
            List<InventoryLevel> levels = levelsBySku.getOrDefault(product.getSku(), List.of());
            rows.add(row(product, levels, visible, demandBySku.get(product.getSku())));
        }
        return rows;
    }

    private InventoryGrid.Row row(Product product, List<InventoryLevel> levels,
                                  List<String> visibleWarehouses, Double dailyDemand) {

        Map<String, InventoryGrid.Cell> cells = new LinkedHashMap<>();
        double onHandSum = 0;
        double onOrderSum = 0;

        for (InventoryLevel level : levels) {
            Warehouse warehouse = level.getWarehouse();
            if (!visibleWarehouses.contains(warehouse.getCode())) {
                continue;
            }

            double share = allocationShare(levels.size());
            Double allocated = dailyDemand == null ? null : dailyDemand * share;
            Double days = daysOfSupply(level, allocated);

            cells.put(warehouse.getCode(), new InventoryGrid.Cell(
                    warehouse.getCode(),
                    level.getQuantityOnHand(),
                    level.getOnOrder(),
                    days,
                    days == null ? null : classify(days, product)));

            onHandSum += level.getQuantityOnHand();
            onOrderSum += level.getOnOrder();
        }

        return new InventoryGrid.Row(
                product.getId(), product.getSku(), product.getName(), product.getCategory(),
                product.getLeadTimeDays(), onHandSum, onOrderSum,
                product.getCurrentStock(), dailyDemand, cells);
    }

    /**
     * A location's share of the SKU's demand: an equal split across its stocking locations.
     *
     * <p>The tempting alternative is to allocate in proportion to stock held, on the assumption
     * that a network stocks each location for the demand it serves. It cannot be used here, and
     * the reason is arithmetic rather than taste. Under it a cell's cover is
     * {@code onHand / (demand × onHand / totalOnHand)}, and the {@code onHand} cancels, leaving
     * {@code totalOnHand / demand} — the same number for every location in the row. The grid
     * would colour whole rows and never an individual cell, so the one thing a products ×
     * warehouses heatmap exists to show would be the one thing it could not.
     *
     * <p>An equal split makes a cell answer a question worth asking: is this location holding its
     * share? The cost is assuming the locations serve comparable demand, and where they do not a
     * small satellite reads short against a regional hub. That is a heuristic talking, which is
     * why the page's legend says so and why the alert list, not this grid, is what gets acted on.
     * Allocating truthfully would need demand recorded per warehouse, which
     * {@code DemandHistory} does not carry.
     *
     * <p>Counted over every stocking location, not just the visible ones — narrowing to one
     * warehouse column is a question about the view, and it should not change what the network is
     * understood to be selling.
     */
    private static double allocationShare(int locations) {
        return locations > 0 ? 1.0 / locations : 1.0;
    }

    /**
     * Days this location's stock and inbound cover, or null when demand is unknown.
     *
     * <p>Zero allocated demand is not zero cover: it means this location is expected to sell
     * nothing, so anything it holds lasts indefinitely. Reported as null rather than infinity,
     * which JSON cannot represent.
     */
    private static Double daysOfSupply(InventoryLevel level, Double allocatedDailyDemand) {
        if (allocatedDailyDemand == null) {
            return null;
        }
        double position = level.getQuantityOnHand() + level.getOnOrder();
        if (allocatedDailyDemand <= 0) {
            return position > 0 ? null : 0.0;
        }
        return position / allocatedDailyDemand;
    }

    /**
     * Cover against the same thresholds the risk scan uses.
     *
     * <p>Deliberately not new numbers: a cell calling 40 days healthy while the alert list calls
     * it overstocked would leave an operator to decide which page to believe. The product's own
     * lead time wins where it has one, since that is the window a shortage has to open in.
     */
    private RiskStatus classify(double daysOfSupply, Product product) {
        // Zero would claim same-day resupply, so an unset lead time falls back to the batch
        // default — the same substitution the risk scan makes.
        double leadTime = product.getLeadTimeDays() > 0
                ? product.getLeadTimeDays()
                : scanProperties.defaultLeadTimeDays();

        if (daysOfSupply < leadTime + scanProperties.reviewPeriodDays()) {
            return RiskStatus.STOCKOUT_RISK;
        }
        if (daysOfSupply > scanProperties.overstockThresholdDays()) {
            return RiskStatus.OVERSTOCK;
        }
        return RiskStatus.OK;
    }

    /**
     * Mean daily demand per SKU over the recent window.
     *
     * <p>Averaged over the window's length rather than over the number of rows, so days with no
     * sales count as zero-demand days. Averaging only the days that recorded something would
     * overstate the rate for anything that does not sell daily, and overstating demand
     * understates cover.
     */
    private Map<String, Double> dailyDemand(List<String> skus) {
        Map<String, Double> rates = new LinkedHashMap<>();
        LocalDate from = LocalDate.now().minusDays(DEMAND_WINDOW_DAYS);

        demandHistoryRepository.sumQuantityBySkuSince(skus, from).forEach(total ->
                rates.put(total.getSku(), total.getTotal() / DEMAND_WINDOW_DAYS));

        return rates;
    }

    private static Map<String, Long> countStatuses(List<InventoryGrid.Row> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RiskStatus status : RiskStatus.values()) {
            counts.put(status.name(), 0L);
        }
        counts.put("UNKNOWN", 0L);

        rows.forEach(row -> row.cells().values().forEach(cell -> {
            String key = cell.status() == null ? "UNKNOWN" : cell.status().name();
            counts.merge(key, 1L, Long::sum);
        }));
        return counts;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

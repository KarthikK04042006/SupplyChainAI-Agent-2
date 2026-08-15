package com.supply.demandforecast.dto;

import com.supply.demandforecast.domain.RiskStatus;

import java.util.List;
import java.util.Map;

/**
 * Products × warehouses, as the heatmap page renders it.
 *
 * <p>Columns come back as their own list rather than being inferred from the cells, so the grid
 * has a stable column order even for a product stocked in only one location — otherwise every
 * row would need its own header.
 *
 * <p>{@link Row#cells()} is keyed by warehouse code, and a missing key means exactly that: no
 * stock row for that pair. That is different from a zero, which is a counted, empty shelf.
 *
 * @param warehouses    the columns, in code order
 * @param rows          one per product on this page
 * @param categories    every category in the catalogue, for the filter
 * @param statusCounts  how many cells fell into each status across the whole filtered grid, not
 *                      just this page, so the legend can show totals that do not shift as the
 *                      user pages
 * @param truncated     true when the filter matched more products than the grid will build; the
 *                      page says so rather than presenting a partial grid as complete
 */
public record InventoryGrid(
        List<Column> warehouses,
        List<Row> rows,
        List<String> categories,
        Map<String, Long> statusCounts,
        int totalMatching,
        int page,
        int size,
        int totalPages,
        boolean truncated
) {

    /** One warehouse column. */
    public record Column(Long id, String code, String name, String region) {
    }

    /**
     * One product row.
     *
     * @param totalOnHand   summed from this row's cells
     * @param productStock  the aggregate stored on the product, which the risk scan and the KPI
     *                      cards read. Returned alongside the summed cells so a divergence
     *                      between the two is visible on the page rather than silent
     * @param dailyDemand   average daily demand behind every cell's days-of-supply figure, or
     *                      null when the SKU has no history to average
     */
    public record Row(
            Long productId,
            String sku,
            String name,
            String category,
            Integer leadTimeDays,
            double totalOnHand,
            double totalOnOrder,
            double productStock,
            Double dailyDemand,
            Map<String, Cell> cells
    ) {
    }

    /**
     * One product at one warehouse.
     *
     * @param daysOfSupply  cover at this location given its share of demand, or null when the
     *                      SKU has no demand history
     * @param status        null when {@code daysOfSupply} is, because a cell with no demand
     *                      signal is unknown rather than healthy
     */
    public record Cell(
            String warehouse,
            double onHand,
            double onOrder,
            Double daysOfSupply,
            RiskStatus status
    ) {
    }
}

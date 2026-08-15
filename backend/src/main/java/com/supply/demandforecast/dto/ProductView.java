package com.supply.demandforecast.dto;

import com.supply.demandforecast.entity.Product;

/**
 * A product as the catalogue list and the dashboard show it.
 *
 * <p>Distinct from {@link ProductForecastResponse.ProductSummary}, which carries only the fields
 * relevant to reading a forecast. This one adds the commercial and derived figures a list view
 * needs: unit cost, the stock value it implies, and inventory position.
 *
 * @param inventoryPosition on hand plus on order — the number that decides whether stock is
 *                          short, as opposed to what is on the shelf right now
 * @param stockValue        null when the SKU has no unit cost, rather than zero: an unpriced
 *                          product is not a worthless one
 */
public record ProductView(
        Long id,
        String sku,
        String name,
        String category,
        int leadTimeDays,
        Integer reorderPoint,
        double currentStock,
        double onOrder,
        double inventoryPosition,
        Double safetyStock,
        Double unitCost,
        Double stockValue,
        boolean belowReorderPoint
) {

    public static ProductView from(Product product) {
        double position = product.getCurrentStock() + product.getOnOrder();
        Double unitCost = product.getUnitCost();

        return new ProductView(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getCategory(),
                product.getLeadTimeDays(),
                product.getReorderPoint(),
                product.getCurrentStock(),
                product.getOnOrder(),
                position,
                product.getSafetyStock(),
                unitCost,
                unitCost == null ? null : unitCost * product.getCurrentStock(),
                // Compared against inventory position, not on-hand: a SKU under its reorder
                // point with a delivery already inbound has been reordered, which is what a
                // reorder point exists to prompt.
                product.getReorderPoint() != null && position < product.getReorderPoint());
    }
}

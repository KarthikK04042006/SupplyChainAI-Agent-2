package com.supply.demandforecast.dto;

import com.supply.demandforecast.entity.Product;

import java.time.LocalDate;
import java.util.List;

/**
 * Combined result of {@code GET /api/products/{id}/forecast}: the product as this backend
 * knows it, the history the forecast was computed from, and the ML service's prediction.
 *
 * <p>The forecast alone would leave the caller unable to act on it — a predicted 40 units a
 * day means one thing against 600 in stock and another against 20. Returning stock position
 * and history extent alongside it saves a second round trip and, more usefully, guarantees
 * the two are consistent with each other.
 *
 * @param product  stock position and supplier terms
 * @param history  what the prediction was based on
 * @param forecast the ML service's response, passed through unaltered
 */
public record ProductForecastResponse(
        ProductSummary product,
        HistorySummary history,
        ForecastResponse forecast
) {

    /** The product fields relevant to reading a forecast. */
    public record ProductSummary(
            Long id,
            String sku,
            String name,
            String category,
            double currentStock,
            double onOrder,
            int leadTimeDays,
            Integer reorderPoint,
            Double safetyStock
    ) {

        public static ProductSummary from(Product product) {
            return new ProductSummary(
                    product.getId(),
                    product.getSku(),
                    product.getName(),
                    product.getCategory(),
                    product.getCurrentStock(),
                    product.getOnOrder(),
                    product.getLeadTimeDays(),
                    product.getReorderPoint(),
                    product.getSafetyStock());
        }
    }

    /**
     * Extent of the history behind the forecast.
     *
     * <p>The observations themselves are not included: 180 days of dailies would dwarf the
     * forecast in the payload, and the caller asking for a prediction is not asking for the
     * raw series. {@code observations} against the date span is enough to see a gap.
     *
     * @param observations recorded days, which may be fewer than the span if days are missing
     */
    public record HistorySummary(
            int observations,
            LocalDate from,
            LocalDate to,
            double totalQuantity,
            double averageDailyQuantity
    ) {

        public static HistorySummary of(List<LocalDate> dates, List<Double> quantities) {
            double total = quantities.stream().mapToDouble(Double::doubleValue).sum();
            int count = quantities.size();

            return new HistorySummary(
                    count,
                    dates.isEmpty() ? null : dates.getFirst(),
                    dates.isEmpty() ? null : dates.getLast(),
                    total,
                    count == 0 ? 0 : total / count);
        }
    }
}

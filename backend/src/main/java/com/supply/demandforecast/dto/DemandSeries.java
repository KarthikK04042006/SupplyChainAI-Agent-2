package com.supply.demandforecast.dto;

import com.supply.demandforecast.entity.DemandHistory;

import java.time.LocalDate;
import java.util.List;

/**
 * One SKU's recorded demand, oldest first.
 *
 * <p>Its own endpoint rather than a field on {@link ProductForecastResponse}, which states why it
 * omits the series: a caller asking for a prediction is not asking for 180 days of dailies. The
 * chart genuinely needs them, so it asks for them, and every other forecast caller keeps the
 * smaller payload.
 *
 * @param truncated true when older observations were dropped to bound the response; the caller
 *                  can say the chart starts where the window does rather than implying the SKU
 *                  has no earlier history
 */
public record DemandSeries(
        String sku,
        List<Point> points,
        int observations,
        boolean truncated
) {

    /** One recorded day. */
    public record Point(LocalDate date, double quantity) {
    }

    /** Keeps the most recent {@code limit} observations, since a chart reads right to left. */
    public static DemandSeries of(String sku, List<DemandHistory> history, int limit) {
        boolean truncated = history.size() > limit;
        List<DemandHistory> window = truncated
                ? history.subList(history.size() - limit, history.size())
                : history;

        return new DemandSeries(
                sku,
                window.stream()
                        .map(row -> new Point(row.getDemandDate(), row.getQuantity()))
                        .toList(),
                history.size(),
                truncated);
    }
}

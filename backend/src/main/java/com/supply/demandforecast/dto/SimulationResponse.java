package com.supply.demandforecast.dto;

/**
 * Baseline and shocked reorder recommendations, side by side, for {@code POST /api/simulate}.
 *
 * <p>Both are computed from a <em>single</em> forecast fetch over the same horizon — only the
 * in-memory perturbation differs — so the two are directly comparable and the difference is
 * attributable to the shock alone, not to a second model run or a changed horizon.
 *
 * <p>The baseline here reflects the simulation's horizon, which is widened to reach a far-future
 * demand window. It can therefore differ slightly from {@code GET
 * /api/products/{id}/reorder-recommendation}, which always uses the standard scan horizon.
 *
 * @param baseline  the recommendation as things stand, no shock applied
 * @param simulated the recommendation under the requested shock
 */
public record SimulationResponse(
        String sku,
        ReorderRecommendationResponse baseline,
        ReorderRecommendationResponse simulated
) {
}

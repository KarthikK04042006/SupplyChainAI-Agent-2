package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * Response from the ML service's {@code POST /explain/{product_id}}.
 *
 * <p>SHAP on a gradient-boosted tree is exact, so {@code baseValue} plus every
 * contribution equals {@code predicted}. That identity is what makes the explanation
 * checkable — but it only holds over the *full* contribution list, and {@code topN}
 * truncates it, so do not assert it against a truncated response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExplainResponse(
        String sku,
        @JsonProperty("explained_date") LocalDate explainedDate,
        int step,
        double predicted,
        @JsonProperty("base_value") double baseValue,
        List<Contribution> contributions,
        @JsonProperty("total_contribution") double totalContribution,
        @JsonProperty("model_used") String modelUsed,
        String note
) {

    public List<Contribution> contributions() {
        return contributions == null ? List.of() : contributions;
    }

    /**
     * One feature's push on the prediction.
     *
     * @param feature   human-readable label, e.g. "demand 14 day(s) ago"
     * @param direction "increase" or "decrease"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contribution(
            String feature,
            double value,
            double contribution,
            String direction,
            @JsonProperty("abs_contribution") double absContribution
    ) {
    }
}

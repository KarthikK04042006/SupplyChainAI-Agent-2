package com.supply.demandforecast.controller;

import com.supply.demandforecast.dto.DemandSeries;
import com.supply.demandforecast.dto.ExplainResponse;
import com.supply.demandforecast.dto.ForecastRequest;
import com.supply.demandforecast.dto.ImportResult;
import com.supply.demandforecast.dto.ProductForecastResponse;
import com.supply.demandforecast.dto.ProductPage;
import com.supply.demandforecast.dto.ProductView;
import com.supply.demandforecast.dto.ReorderRecommendationResponse;
import com.supply.demandforecast.service.ForecastService;
import com.supply.demandforecast.service.ProductImportService;
import com.supply.demandforecast.service.ProductService;
import com.supply.demandforecast.service.ReplenishmentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Product-scoped reads that combine what this backend stores with what the ML service predicts. */
@Validated
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ForecastService forecastService;
    private final ReplenishmentService replenishmentService;
    private final ProductImportService productImportService;

    /**
     * The catalogue, filtered and paged.
     *
     * <p>Returns the full category list alongside the page so a client can populate its filter
     * without a second endpoint — and without the options shrinking as the results narrow.
     */
    @GetMapping
    public ProductPage list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) Integer size) {

        return productService.search(q, category, page, size);
    }

    /** One product. {@code id} may be a product id or a SKU. */
    @GetMapping("/{id}")
    public ProductView get(@PathVariable String id) {
        return productService.get(id);
    }

    /**
     * One product's recorded demand, oldest first.
     *
     * <p>Separate from {@code /forecast} because {@link ProductForecastResponse} states why it
     * omits the series — a caller asking for a prediction is not asking for months of dailies.
     * The chart is the one caller that needs both, and it asks for both.
     */
    @GetMapping("/{id}/demand-history")
    public DemandSeries demandHistory(
            @PathVariable String id,
            @RequestParam(required = false) @Min(1) Integer limit) {

        return productService.demandHistory(id, limit);
    }

    /**
     * Forecasts one product's demand, returned with its stock position and the extent of the
     * history behind it.
     *
     * <p>{@code id} may be a product id or a SKU — see {@link
     * ForecastService#forecastForProduct}. Tuning arrives as query parameters rather than a
     * body, because a GET body is ignored by enough intermediaries that sending one is a
     * silent way to lose the horizon.
     */
    @GetMapping("/{id}/forecast")
    public ProductForecastResponse forecast(
            @PathVariable String id,
            @RequestParam(required = false) @Min(1) @Max(365) Integer horizonDays,
            @RequestParam(required = false) @Min(2) @Max(365) Integer seasonalPeriods) {

        return forecastService.forecastForProduct(
                id, new ForecastRequest(horizonDays, seasonalPeriods));
    }

    /**
     * What to order and why, with each step of the calculation.
     *
     * <p>A GET despite the ML call behind it: nothing is written, and the same request always
     * describes the same stock position, so it is cacheable and safe to retry.
     */
    @GetMapping("/{id}/reorder-recommendation")
    public ReorderRecommendationResponse reorderRecommendation(@PathVariable String id) {
        return replenishmentService.recommend(id);
    }

    /**
     * Which features drove the next predicted day.
     *
     * <p>Answers 422 when the selected model has no SHAP support, carrying the ML service's own
     * reason as {@code upstreamError} — see {@code GlobalExceptionHandler}. The page treats that
     * as "no explanation available" and keeps the rest of the view.
     */
    @GetMapping("/{id}/explain")
    public ExplainResponse explain(@PathVariable String id) {
        return replenishmentService.explain(id);
    }

    /**
     * Imports the product catalogue from CSV. ADMIN only, per {@code SecurityConfig}.
     *
     * <p>Answers 200 with the per-row error list even when rows were rejected: a file where nine
     * of ten rows loaded is a partial success, and a 400 would tell the caller nothing landed.
     * Only an unreadable or empty file is a 400.
     */
    @PostMapping("/import")
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return productImportService.importCsv(file);
    }
}

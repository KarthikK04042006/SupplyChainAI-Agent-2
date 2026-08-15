package com.supply.demandforecast.service;

import com.supply.demandforecast.dto.DemandSeries;
import com.supply.demandforecast.dto.ProductPage;
import com.supply.demandforecast.dto.ProductView;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import com.supply.demandforecast.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the product catalogue.
 *
 * <p>Writing it is {@link ProductImportService}'s job — the catalogue arrives as a file, not as
 * individual API calls, which is why there is no create or update endpoint here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    /** Cap on page size, matching {@code AlertService}: asking for everything gets a large page. */
    private static final int MAX_PAGE_SIZE = 500;
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** Roughly a year of dailies — enough for a chart to show a seasonal shape. */
    private static final int MAX_SERIES_POINTS = 400;

    private final ProductRepository productRepository;
    private final DemandHistoryRepository demandHistoryRepository;
    private final ProductLookup productLookup;

    /**
     * Filtered, paged catalogue. Both filters are optional.
     *
     * <p>Sorted by SKU. Unlike the alert list — which is ordered by urgency because it exists to
     * be worked top-down — a catalogue is something to look things up in, and a stable
     * alphabetical order is what makes that possible.
     */
    @Transactional(readOnly = true)
    public ProductPage search(String q, String category, Integer page, Integer size) {
        Pageable pageable = PageRequest.of(
                page == null || page < 0 ? 0 : page,
                size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE),
                Sort.by("sku"));

        return ProductPage.of(
                productRepository.search(blankToNull(q), blankToNull(category), pageable)
                        .map(ProductView::from),
                productRepository.findDistinctCategories());
    }

    /**
     * One product by id or SKU, resolved by {@link ProductLookup} like every other endpoint
     * under {@code /api/products/{id}}.
     */
    @Transactional(readOnly = true)
    public ProductView get(String idOrSku) {
        return ProductView.from(productLookup.resolve(idOrSku));
    }

    /**
     * One product's recorded demand, most recent {@value #MAX_SERIES_POINTS} days.
     *
     * <p>Bounded because the caller is a chart: a SKU with five years of dailies would send
     * 1,800 points to draw a line a few hundred pixels wide. {@link DemandSeries} reports the
     * truncation so the page can say where the window starts.
     */
    @Transactional(readOnly = true)
    public DemandSeries demandHistory(String idOrSku, Integer limit) {
        String sku = productLookup.resolve(idOrSku).getSku();
        int window = limit == null || limit < 1
                ? MAX_SERIES_POINTS
                : Math.min(limit, MAX_SERIES_POINTS);

        return DemandSeries.of(
                sku, demandHistoryRepository.findBySkuOrderByDemandDateAsc(sku), window);
    }

    /** A blank filter is an absent one — {@code ?category=} is what an unset dropdown sends. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

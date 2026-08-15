package com.supply.demandforecast.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * One page of products, plus the categories available to filter by.
 *
 * <p>Mirrors {@link AlertPage}: Spring Data's {@code Page} is not serialized directly, because
 * its JSON shape is tied to a Spring version and carries {@code pageable} internals a client has
 * no use for.
 *
 * <p>{@link #categories()} covers the whole catalogue rather than this page, so the filter
 * dropdown does not lose options as the user narrows the list — the same reasoning as
 * {@code AlertPage.Counts}.
 */
public record ProductPage(
        List<ProductView> products,
        long totalMatching,
        int page,
        int size,
        int totalPages,
        List<String> categories
) {

    public static ProductPage of(Page<ProductView> page, List<String> categories) {
        return new ProductPage(
                page.getContent(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize(),
                page.getTotalPages(),
                categories);
    }
}

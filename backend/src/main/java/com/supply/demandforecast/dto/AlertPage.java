package com.supply.demandforecast.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * One page of alerts, plus counts of what is open across the <em>whole</em> table.
 *
 * <p>Spring Data's {@code Page} is not serialized directly: its JSON shape is tied to a Spring
 * version and carries {@code pageable} internals that mean nothing to a client. This exposes
 * only the four numbers a caller needs to page.
 *
 * <p>{@link Counts} is deliberately unfiltered. A dashboard header showing "3 critical" must
 * keep saying 3 while the user filters the list down to one type — computing the badge from the
 * page in hand would make it change every time the filter did.
 *
 * @param alerts       this page, most urgent first
 * @param totalMatching alerts matching the filter, across all pages
 */
public record AlertPage(
        List<AlertView> alerts,
        long totalMatching,
        int page,
        int size,
        int totalPages,
        Counts counts
) {

    /** Open-alert tallies for the whole table, independent of the filter or page. */
    public record Counts(long open, long critical, long high) {
    }

    public static AlertPage of(Page<AlertView> page, Counts counts) {
        return new AlertPage(
                page.getContent(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize(),
                page.getTotalPages(),
                counts);
    }
}

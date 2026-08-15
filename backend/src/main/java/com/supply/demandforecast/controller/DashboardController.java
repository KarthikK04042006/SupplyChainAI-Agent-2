package com.supply.demandforecast.controller;

import com.supply.demandforecast.dto.DashboardSummary;
import com.supply.demandforecast.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The overview page's KPI cards.
 *
 * <p>One endpoint rather than four, because the cards are read together and a page that issued
 * four requests could render a total that never existed — alerts counted after a scan that the
 * SKU count was read before.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** Every KPI figure, each with the sample size behind it. */
    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboardService.summary();
    }
}

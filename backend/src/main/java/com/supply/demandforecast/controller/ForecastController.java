package com.supply.demandforecast.controller;

import com.supply.demandforecast.dto.ForecastRequest;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.service.ForecastService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Forecasting endpoints. History comes from the database, predictions from the ML service. */
@RestController
@RequestMapping("/api/forecast")
@RequiredArgsConstructor
public class ForecastController {

    private final ForecastService forecastService;

    /**
     * Forecasts demand for one SKU.
     *
     * <p>The body is optional; omitting it uses the defaults on {@link ForecastRequest}.
     */
    @PostMapping("/{sku}")
    public ForecastResponse forecast(
            @PathVariable String sku,
            @Valid @RequestBody(required = false) ForecastRequest request) {
        return forecastService.forecast(
                sku, request != null ? request : new ForecastRequest(null, null));
    }
}

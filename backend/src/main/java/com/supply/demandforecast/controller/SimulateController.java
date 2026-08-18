package com.supply.demandforecast.controller;

import com.supply.demandforecast.dto.SimulateRequest;
import com.supply.demandforecast.dto.SimulationResponse;
import com.supply.demandforecast.service.ReplenishmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The what-if endpoint: recompute a product's reorder recommendation under a hypothetical shock
 * without changing anything.
 *
 * <p>A POST despite reading nothing back into the database — the body carries the scenario, and a
 * scenario is not a resource with a stable URL to GET. Nothing is persisted; the result is derived
 * on the fly from a perturbed copy of the forecast (see {@link ReplenishmentService#simulate}).
 *
 * <p>Its own controller rather than another method on {@code ProductController}: the SKU arrives
 * in the body, not the path, so it does not share that controller's {@code /{id}} shape or its
 * {@code @Validated} query-parameter handling.
 */
@RestController
@RequestMapping("/api/simulate")
@RequiredArgsConstructor
public class SimulateController {

    private final ReplenishmentService replenishmentService;

    /**
     * Runs one what-if scenario. A body that describes no shock, or an impossible one, is a 400
     * from bean validation before any ML call — see {@link SimulateRequest}.
     */
    @PostMapping
    public SimulationResponse simulate(@Valid @RequestBody SimulateRequest request) {
        return replenishmentService.simulate(request);
    }
}

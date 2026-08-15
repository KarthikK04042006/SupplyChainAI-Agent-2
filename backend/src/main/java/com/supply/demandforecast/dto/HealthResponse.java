package com.supply.demandforecast.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Aggregate health response for {@code GET /api/health}.
 *
 * <p>Reports this service's own status alongside the two dependencies it owns
 * or orchestrates: the MySQL database and the ML service.
 */
public record HealthResponse(
        String backend,
        @JsonProperty("ml-service") String mlService,
        String database
) {
    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
}

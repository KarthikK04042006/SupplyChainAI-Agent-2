package com.supply.demandforecast.controller;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.dto.HealthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * End-to-end health check across the whole stack: this service, the MySQL database
 * it owns, and the ML service it orchestrates.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 3;

    private final MlServiceClient mlServiceClient;
    private final DataSource dataSource;

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(
                HealthResponse.UP,
                mlServiceClient.isHealthy() ? HealthResponse.UP : HealthResponse.DOWN,
                isDatabaseHealthy() ? HealthResponse.UP : HealthResponse.DOWN
        );
    }

    private boolean isDatabaseHealthy() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}

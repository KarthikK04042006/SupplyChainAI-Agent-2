package com.supply.demandforecast.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} processing for {@link
 * com.supply.demandforecast.service.ScheduledInventoryScan}.
 *
 * <p>Its own class rather than an annotation on the application class, so a test slice can
 * exclude it and not have a background scan firing mid-assertion.
 *
 * <p>The default single-threaded scheduler is left in place. One job runs here, and giving it a
 * pool would only let a second scan start while the first still held the alert table.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

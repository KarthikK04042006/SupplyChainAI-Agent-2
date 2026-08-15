package com.supply.demandforecast.service;

import com.supply.demandforecast.client.MlServiceException;
import com.supply.demandforecast.dto.ScanResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the inventory risk scan on a timer. This is the monitoring loop: alerts appear without
 * anyone asking for them, and clear the same way.
 *
 * <p>A separate bean from {@link InventoryScanService} so the trigger and the work stay
 * independent — the scan can be called from the controller, a test, or here, and the
 * transaction boundary belongs to the service either way.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: the delay is measured from the end of the
 * previous run, so a scan slower than the interval cannot have the next one queued up behind
 * it. A catalogue big enough for the batch forecast to take minutes would otherwise build a
 * backlog that never drains.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "inventory.scan", name = "enabled", matchIfMissing = true)
public class ScheduledInventoryScan {

    private final InventoryScanService inventoryScanService;

    /**
     * One scheduled pass.
     *
     * <p>Swallows failures deliberately, which is the opposite of what the on-demand endpoint
     * does. There is no caller to return an error to, and an exception escaping here would be
     * reported by the scheduler as an unexplained stack trace; worse, a persistent ML outage
     * would produce one every interval. Logged at ERROR with the reason, the next run simply
     * tries again.
     */
    @Scheduled(
            fixedDelayString = "${inventory.scan.interval:1h}",
            initialDelayString = "${inventory.scan.initial-delay:2m}")
    public void run() {
        try {
            ScanResult result = inventoryScanService.scan();

            log.info("Scheduled scan: {} SKU(s) assessed, {} skipped, "
                            + "{} alert(s) opened, {} updated, {} resolved, {} now open",
                    result.productsScanned(),
                    result.productsSkipped().size(),
                    result.alertsOpened(),
                    result.alertsUpdated(),
                    result.alertsResolved(),
                    result.openAlerts().size());

        } catch (MlServiceException e) {
            log.error("Scheduled scan could not reach the ML service: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Scheduled scan failed", e);
        }
    }
}

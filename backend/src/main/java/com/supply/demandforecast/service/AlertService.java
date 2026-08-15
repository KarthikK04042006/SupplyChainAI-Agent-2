package com.supply.demandforecast.service;

import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.dto.AlertPage;
import com.supply.demandforecast.dto.AlertView;
import com.supply.demandforecast.entity.Alert;
import com.supply.demandforecast.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and acting on alerts.
 *
 * <p>Writing them is {@link InventoryScanService}'s job; this is the operator side of the same
 * table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    /** Cap on page size. A caller asking for everything gets a large page, not the whole table. */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AlertRepository alertRepository;

    /**
     * Filtered, paged alerts. Every filter is optional; passing none returns everything.
     *
     * <p>Ordering is fixed by the repository query — severity, then risk score, then recency —
     * rather than accepted from the caller. The list exists to be worked top-down, and a
     * client-chosen sort is how the critical row ends up on page three.
     */
    @Transactional(readOnly = true)
    public AlertPage search(Severity severity,
                            AlertType type,
                            Boolean resolved,
                            String sku,
                            Integer page,
                            Integer size) {

        Pageable pageable = PageRequest.of(
                page == null || page < 0 ? 0 : page,
                size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE));

        return AlertPage.of(
                alertRepository.search(severity, type, resolved, blankToNull(sku), pageable)
                        .map(AlertView::from),
                new AlertPage.Counts(
                        alertRepository.countByResolvedFalse(),
                        alertRepository.countByResolvedFalseAndSeverity(Severity.CRITICAL),
                        alertRepository.countByResolvedFalseAndSeverity(Severity.HIGH)));
    }

    @Transactional(readOnly = true)
    public AlertView get(Long id) {
        return AlertView.from(alertRepository.findById(id)
                .orElseThrow(() -> new AlertNotFoundException(id)));
    }

    /**
     * Resolves one alert on an operator's authority.
     *
     * <p>{@code username} comes from the security context, not the request body. Resolving an
     * already-resolved alert is a conflict rather than a no-op — see {@link
     * AlertAlreadyResolvedException}.
     *
     * <p>Resolving frees the (sku, type) slot, so if the underlying problem has not actually
     * gone away the next scan opens a fresh alert. That is the intended behaviour: dismissing
     * an alert is a statement about the alert, not a suppression of the condition.
     */
    @Transactional
    public AlertView resolve(Long id, String username, String note) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new AlertNotFoundException(id));

        if (alert.isResolved()) {
            throw new AlertAlreadyResolvedException(id, alert.getResolvedBy());
        }

        alert.resolve(username, note);
        log.info("Alert {} ({} on {}) resolved by {}",
                id, alert.getType(), alert.getSku(), username);

        return AlertView.from(alertRepository.save(alert));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

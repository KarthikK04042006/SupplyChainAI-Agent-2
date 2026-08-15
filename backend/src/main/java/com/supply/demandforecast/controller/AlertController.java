package com.supply.demandforecast.controller;

import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.dto.AlertPage;
import com.supply.demandforecast.dto.AlertView;
import com.supply.demandforecast.dto.ResolveAlertRequest;
import com.supply.demandforecast.service.AlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The alert list and the one action that can be taken on it.
 *
 * <p>Reads need VIEWER, resolving needs MANAGER — enforced by URL rules in {@code
 * SecurityConfig}, which is where the whole authorization map is readable at once.
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    /**
     * Alerts matching the given filters, most urgent first.
     *
     * <p>All four filters are optional and combine with AND. {@code resolved} is left unset
     * rather than defaulted to {@code false}: defaulting it would make {@code GET /api/alerts}
     * quietly mean "open alerts only", and a caller wanting the full history would have no way
     * to tell the default from a deliberate filter.
     *
     * <p>{@code severity} and {@code type} accept either casing — see {@code WebConfig}.
     */
    @GetMapping
    public AlertPage list(
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) AlertType type,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        return alertService.search(severity, type, resolved, sku, page, size);
    }

    @GetMapping("/{id}")
    public AlertView get(@PathVariable Long id) {
        return alertService.get(id);
    }

    /**
     * Marks an alert resolved.
     *
     * <p>PATCH, not DELETE: the row survives as the audit trail of what was wrong and who
     * decided it no longer was.
     *
     * <p>The resolving username is taken from {@link Authentication}, so the audit trail records
     * who actually held the token rather than whatever the body claimed.
     */
    @PatchMapping("/{id}/resolve")
    public AlertView resolve(@PathVariable Long id,
                             @Valid @RequestBody(required = false) ResolveAlertRequest request,
                             Authentication authentication) {

        return alertService.resolve(
                id,
                authentication.getName(),
                request != null ? request.noteOrNull() : null);
    }
}

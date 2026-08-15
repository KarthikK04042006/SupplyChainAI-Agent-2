package com.supply.demandforecast.service;

/**
 * Raised when resolving an alert that is already resolved.
 *
 * <p>A conflict rather than a silent no-op: two managers working the same list would otherwise
 * both believe they closed it, and the second would overwrite the first's name and note on the
 * audit trail.
 */
public class AlertAlreadyResolvedException extends RuntimeException {

    public AlertAlreadyResolvedException(Long id, String resolvedBy) {
        super("Alert " + id + " was already resolved"
                + (resolvedBy != null ? " by " + resolvedBy : ""));
    }
}

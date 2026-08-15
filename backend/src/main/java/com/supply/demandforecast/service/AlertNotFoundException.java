package com.supply.demandforecast.service;

/** Raised when an alert id does not exist. */
public class AlertNotFoundException extends RuntimeException {

    public AlertNotFoundException(Long id) {
        super("No alert with id " + id);
    }
}

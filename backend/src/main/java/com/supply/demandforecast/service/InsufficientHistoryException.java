package com.supply.demandforecast.service;

/** Raised when a SKU has too little recorded demand to forecast from. */
public class InsufficientHistoryException extends RuntimeException {

    public InsufficientHistoryException(String sku, long found, int required) {
        super("SKU %s has %d demand observations; at least %d are required to forecast"
                .formatted(sku, found, required));
    }
}

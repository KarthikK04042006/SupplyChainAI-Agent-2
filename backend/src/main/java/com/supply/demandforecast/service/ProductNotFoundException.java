package com.supply.demandforecast.service;

/** Raised when a product id or SKU does not resolve. */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String idOrSku) {
        super("No product with id or SKU '" + idOrSku + "'");
    }
}

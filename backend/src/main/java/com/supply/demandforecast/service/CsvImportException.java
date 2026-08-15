package com.supply.demandforecast.service;

/** Raised when an uploaded CSV cannot be read at all. */
public class CsvImportException extends RuntimeException {

    public CsvImportException(String message) {
        super(message);
    }

    public CsvImportException(String message, Throwable cause) {
        super(message, cause);
    }
}

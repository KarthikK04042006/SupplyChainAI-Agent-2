package com.supply.demandforecast.service;

/**
 * Raised when an uploaded CSV carries more rows than an import will process in one call.
 *
 * <p>Distinct from {@link CsvImportException}: that file was malformed and is a 400, this one was
 * well-formed but too big and is a 413. The multipart size limit bounds the bytes on the wire; this
 * bounds the rows, catching a file that is small on disk but pathologically long.
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}

package com.supply.demandforecast.service;

/**
 * Raised when an operation would remove the last usable admin.
 *
 * <p>Nothing exposed over the API could restore access afterwards, so this is refused
 * rather than warned about.
 */
public class LastAdminException extends RuntimeException {

    public LastAdminException() {
        super("Cannot demote or disable the last enabled admin");
    }
}

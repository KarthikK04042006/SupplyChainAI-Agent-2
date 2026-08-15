package com.supply.demandforecast.service;

/** Raised when a user id does not resolve. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("No user with id " + id);
    }
}

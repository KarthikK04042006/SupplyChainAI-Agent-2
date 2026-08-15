package com.supply.demandforecast.service;

/** Raised when creating a user whose username is already taken. */
public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException(String username) {
        super("Username '" + username + "' is already taken");
    }
}

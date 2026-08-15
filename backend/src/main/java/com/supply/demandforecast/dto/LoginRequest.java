package com.supply.demandforecast.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login credentials.
 *
 * <p>No length or character constraints beyond non-blank: rejecting a password here on
 * format grounds only tells an attacker which guesses are not worth making.
 */
public record LoginRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "password is required") String password
) {
}

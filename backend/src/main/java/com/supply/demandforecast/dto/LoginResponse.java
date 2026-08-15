package com.supply.demandforecast.dto;

/**
 * A successful login.
 *
 * @param token     the bearer token to send as {@code Authorization: Bearer <token>}
 * @param tokenType always {@code Bearer}; stated so clients need not assume it
 * @param expiresIn seconds until the token expires, letting a client refresh before a
 *                  request fails rather than after
 * @param username  the authenticated user
 * @param role      that user's role, so a UI can hide actions it would be denied anyway
 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        String username,
        String role
) {

    public static LoginResponse bearer(String token, long expiresIn, String username, String role) {
        return new LoginResponse(token, "Bearer", expiresIn, username, role);
    }
}

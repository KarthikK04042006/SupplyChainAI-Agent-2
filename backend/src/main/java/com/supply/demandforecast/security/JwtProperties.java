package com.supply.demandforecast.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT signing and seeding settings, bound from the {@code security.jwt} prefix.
 *
 * @param secret        HMAC-SHA key material. Must be supplied in any real deployment —
 *                      see {@link #hasConfiguredSecret()}.
 * @param expiryMinutes how long an issued token stays valid
 * @param issuer        the {@code iss} claim, so tokens minted by another service are
 *                      rejected even if it happens to share the key
 * @param seedAdminUsername username of the admin created at startup when no admin exists
 * @param seedAdminPassword password for that admin
 */
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
        String secret,
        long expiryMinutes,
        String issuer,
        String seedAdminUsername,
        String seedAdminPassword
) {

    /**
     * Length of a generated fallback key in bytes. HS256 requires at least 256 bits, and
     * {@code Keys.hmacShaKeyFor} throws below that rather than silently weakening.
     */
    public static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (expiryMinutes <= 0) {
            expiryMinutes = 60;
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "demand-forecast";
        }
        if (seedAdminUsername == null || seedAdminUsername.isBlank()) {
            seedAdminUsername = "admin";
        }
    }

    /**
     * Whether a signing secret was actually supplied.
     *
     * <p>Checked at startup so a missing secret fails loudly in production instead of
     * falling back to a random key, which would look fine until the second instance
     * started rejecting the first instance's tokens.
     */
    public boolean hasConfiguredSecret() {
        return secret != null && secret.length() >= MIN_SECRET_BYTES;
    }

    /** Whether a seed password was supplied, rather than one needing to be generated. */
    public boolean hasSeedAdminPassword() {
        return seedAdminPassword != null && !seedAdminPassword.isBlank();
    }
}

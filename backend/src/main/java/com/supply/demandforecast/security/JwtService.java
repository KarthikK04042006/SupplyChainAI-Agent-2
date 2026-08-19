package com.supply.demandforecast.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

/**
 * Issues and validates the bearer tokens used for authentication.
 *
 * <p>The token carries the username as its subject and the role as a {@code role} claim. The
 * subject is the part that matters on an authenticated request: {@link
 * JwtAuthenticationFilter} verifies the signature here and then reads the account's current
 * role and enabled flag from the database, so a disabled account stops working immediately
 * rather than at expiry. The claim is retained because it describes the role at issue time
 * and the login response reports it, but it is not what authorization is decided on — a token
 * is proof of identity, not a standing grant of the privileges it was minted with.
 */
@Slf4j
@Service
public class JwtService {

    /** Claim holding the user's role name, e.g. {@code ADMIN}. */
    static final String ROLE_CLAIM = "role";

    private final SecretKey signingKey;
    private final Duration expiry;
    private final String issuer;

    public JwtService(JwtProperties properties) {
        this.signingKey = resolveKey(properties);
        this.expiry = Duration.ofMinutes(properties.expiryMinutes());
        this.issuer = properties.issuer();
    }

    /**
     * A token for an authenticated user.
     *
     * @param username the subject
     * @param role     bare role name; the {@code ROLE_} prefix is added at authority time,
     *                 not stored in the token
     */
    public String issue(String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(ROLE_CLAIM, role)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses and verifies a token.
     *
     * <p>Signature, expiry, and issuer are all checked here; anything that fails raises
     * {@link JwtException}, so a caller holding a {@link Claims} can trust its contents.
     *
     * @throws JwtException if the token is malformed, expired, foreign, or unsigned by us
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** How long an issued token remains valid — surfaced to clients on login. */
    public Duration expiry() {
        return expiry;
    }

    /**
     * The HMAC key, from configuration when present.
     *
     * <p>Falling back to a random key keeps tests and a bare {@code docker compose up}
     * working without ceremony, at the cost of invalidating every token on restart and
     * being unusable across more than one instance. That is a development affordance, so
     * it logs a warning rather than passing quietly.
     */
    private static SecretKey resolveKey(JwtProperties properties) {
        if (properties.hasConfiguredSecret()) {
            return Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        }

        byte[] generated = new byte[JwtProperties.MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(generated);
        log.warn("No security.jwt.secret configured (or shorter than {} characters) — "
                        + "generated an ephemeral signing key. Tokens will not survive a restart "
                        + "and will not validate across instances. Set JWT_SECRET before deploying.",
                JwtProperties.MIN_SECRET_BYTES);
        return Keys.hmacShaKeyFor(HexFormat.of().formatHex(generated)
                .getBytes(StandardCharsets.UTF_8));
    }
}

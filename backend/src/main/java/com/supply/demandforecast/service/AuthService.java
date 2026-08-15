package com.supply.demandforecast.service;

import com.supply.demandforecast.dto.LoginRequest;
import com.supply.demandforecast.dto.LoginResponse;
import com.supply.demandforecast.entity.User;
import com.supply.demandforecast.repository.UserRepository;
import com.supply.demandforecast.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks credentials and mints tokens.
 *
 * <p>Deliberately does not go through {@code AuthenticationManager}: the work is one
 * lookup, one hash comparison, and one token, and routing it through a provider chain
 * would add indirection without adding behaviour.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * A valid BCrypt digest of a value nobody knows, compared against when the username
     * does not exist so the response does not return measurably faster than a wrong
     * password. No password matches it, so it cannot authenticate anyone.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Authenticates a user and issues a token.
     *
     * <p>An unknown username still runs a hash comparison against a dummy digest. Without
     * that, a missing user returns measurably faster than a wrong password, which is enough
     * to enumerate valid usernames.
     *
     * @throws BadCredentialsException if the username or password is wrong
     * @throws DisabledException       if the account exists but has been disabled
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username()).orElse(null);
        String hashToCheck = user != null ? user.getPasswordHash() : DUMMY_HASH;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (user == null || !passwordMatches) {
            log.debug("Failed login for username '{}'", request.username());
            throw new BadCredentialsException("Invalid username or password");
        }
        if (!user.isEnabled()) {
            throw new DisabledException("Account is disabled");
        }

        String token = jwtService.issue(user.getUsername(), user.getRole().name());
        log.info("Issued token for '{}' ({})", user.getUsername(), user.getRole());

        return LoginResponse.bearer(
                token,
                jwtService.expiry().toSeconds(),
                user.getUsername(),
                user.getRole().name());
    }
}

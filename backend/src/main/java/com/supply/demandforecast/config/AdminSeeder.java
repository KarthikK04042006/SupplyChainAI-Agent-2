package com.supply.demandforecast.config;

import com.supply.demandforecast.domain.Role;
import com.supply.demandforecast.entity.User;
import com.supply.demandforecast.repository.UserRepository;
import com.supply.demandforecast.security.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the first admin account when the database has none.
 *
 * <p>Runs on every startup but is a no-op once any admin exists, so it cannot resurrect an
 * account that was deliberately disabled or overwrite a changed password. Keying off "any
 * admin" rather than "an admin with this username" matters: keying off the username would
 * recreate the seed account every restart after it had been renamed away.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

    /** Bytes of randomness in a generated password, before Base64 encoding. */
    private static final int GENERATED_PASSWORD_BYTES = 24;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties properties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.ADMIN)) {
            log.debug("An admin account already exists; skipping seed");
            return;
        }

        String username = properties.seedAdminUsername();
        if (userRepository.existsByUsername(username)) {
            log.warn("Cannot seed admin: username '{}' is taken by a non-admin account. "
                    + "Set security.jwt.seed-admin-username to a free name.", username);
            return;
        }

        boolean generated = !properties.hasSeedAdminPassword();
        String password = generated ? generatePassword() : properties.seedAdminPassword();

        userRepository.save(User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(Role.ADMIN)
                .enabled(true)
                .build());

        if (generated) {
            // The only time this password is ever recoverable. Logged rather than left
            // unknowable, because an unreachable admin account is the same as no account.
            log.warn("""

                    ============================================================
                    Seeded initial admin account
                      username: {}
                      password: {}
                    This password is shown once. Change it, or set
                    ADMIN_PASSWORD to control it explicitly.
                    ============================================================""",
                    username, password);
        } else {
            log.info("Seeded initial admin account '{}' from configuration", username);
        }
    }

    /** A URL-safe random password, used when none was configured. */
    private static String generatePassword() {
        byte[] bytes = new byte[GENERATED_PASSWORD_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

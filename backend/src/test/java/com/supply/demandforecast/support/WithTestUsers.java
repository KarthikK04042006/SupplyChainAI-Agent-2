package com.supply.demandforecast.support;

import com.supply.demandforecast.domain.Role;
import com.supply.demandforecast.entity.User;
import com.supply.demandforecast.repository.UserRepository;
import com.supply.demandforecast.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * One user per role, and real bearer tokens for them.
 *
 * <p>Deliberately not {@code @WithMockUser}: these tests go through {@code
 * JwtAuthenticationFilter} like a client would, so an authorization rule that only works for a
 * pre-populated {@code SecurityContext} fails here rather than in production. It also means the
 * resolved principal is a real {@link User}, which the resolve endpoint needs to record who
 * closed an alert.
 */
@TestComponent
@RequiredArgsConstructor
public class WithTestUsers {

    public static final String PASSWORD = "test-password-not-a-secret";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Recreates the three role users.
     *
     * <p>Wipes first: the Spring context is shared across test classes, so the seeded admin and
     * anything an earlier class created would otherwise still be here.
     */
    public void reset() {
        userRepository.deleteAll();
        save("test-viewer", Role.VIEWER);
        save("test-manager", Role.MANAGER);
        save("test-admin", Role.ADMIN);
    }

    public RequestPostProcessor viewer() {
        return bearer("test-viewer", Role.VIEWER);
    }

    public RequestPostProcessor manager() {
        return bearer("test-manager", Role.MANAGER);
    }

    public RequestPostProcessor admin() {
        return bearer("test-admin", Role.ADMIN);
    }

    /** The username {@link #manager()} authenticates as, for asserting on recorded actors. */
    public String managerUsername() {
        return "test-manager";
    }

    private RequestPostProcessor bearer(String username, Role role) {
        String token = jwtService.issue(username, role.name());
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private void save(String username, Role role) {
        userRepository.save(User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(role)
                .enabled(true)
                .build());
    }
}

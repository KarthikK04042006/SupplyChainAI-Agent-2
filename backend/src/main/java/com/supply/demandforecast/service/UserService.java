package com.supply.demandforecast.service;

import com.supply.demandforecast.dto.UserDtos.CreateUserRequest;
import com.supply.demandforecast.dto.UserDtos.UserResponse;
import com.supply.demandforecast.domain.Role;
import com.supply.demandforecast.entity.User;
import com.supply.demandforecast.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Account administration. Every method here is reachable only by an ADMIN. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userRepository.findAll().stream().map(UserResponse::from).toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateUsernameException(request.username());
        }

        User saved = userRepository.save(User.builder()
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(request.role())
                .enabled(true)
                .build());

        log.info("Created user '{}' with role {}", saved.getUsername(), saved.getRole());
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse updateRole(Long id, Role role) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        if (user.getRole() == Role.ADMIN && role != Role.ADMIN && isLastAdmin()) {
            throw new LastAdminException();
        }

        user.setRole(role);
        log.info("Changed role of '{}' to {}", user.getUsername(), role);
        return UserResponse.from(user);
    }

    /**
     * Enables or disables an account.
     *
     * <p>Disabling rather than deleting, so references from resolved alerts stay intact.
     */
    @Transactional
    public UserResponse setEnabled(Long id, boolean enabled) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        if (!enabled && user.getRole() == Role.ADMIN && isLastAdmin()) {
            throw new LastAdminException();
        }

        user.setEnabled(enabled);
        log.info("{} user '{}'", enabled ? "Enabled" : "Disabled", user.getUsername());
        return UserResponse.from(user);
    }

    /**
     * Whether exactly one enabled admin is left.
     *
     * <p>Guards the two operations that could otherwise lock everyone out of user
     * management, which no API call could then undo.
     */
    private boolean isLastAdmin() {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN && u.isEnabled())
                .count() <= 1;
    }
}

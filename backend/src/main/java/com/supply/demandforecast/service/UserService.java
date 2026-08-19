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

        if (role != Role.ADMIN && wouldRemoveTheLastEnabledAdmin(user)) {
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

        if (!enabled && wouldRemoveTheLastEnabledAdmin(user)) {
            throw new LastAdminException();
        }

        user.setEnabled(enabled);
        log.info("{} user '{}'", enabled ? "Enabled" : "Disabled", user.getUsername());
        return UserResponse.from(user);
    }

    /**
     * Whether demoting or disabling this user would leave nobody able to administer accounts.
     *
     * <p>Guards the two operations that could otherwise lock everyone out of user management,
     * which no API call could then undo.
     *
     * <p>The question is asked about <em>this user</em>, not about the admin population in
     * general. Someone who is not currently an enabled admin is not holding the door open, so
     * removing their admin role cannot be what closes it: a <em>disabled</em> admin account is
     * already unable to log in, and demoting or re-disabling it changes nothing about who can.
     * Testing only "is there at most one enabled admin" refused those operations too, and did it
     * while reporting "cannot demote or disable the last enabled admin" — a statement that was
     * simply untrue when another enabled admin was sitting right there.
     */
    private boolean wouldRemoveTheLastEnabledAdmin(User user) {
        if (user.getRole() != Role.ADMIN || !user.isEnabled()) {
            return false;
        }
        return userRepository.countByRoleAndEnabledTrue(Role.ADMIN) <= 1;
    }
}

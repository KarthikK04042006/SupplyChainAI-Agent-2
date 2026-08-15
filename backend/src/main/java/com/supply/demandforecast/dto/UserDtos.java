package com.supply.demandforecast.dto;

import com.supply.demandforecast.domain.Role;
import com.supply.demandforecast.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request and response shapes for user administration. */
public final class UserDtos {

    private UserDtos() {
    }

    /**
     * A new account.
     *
     * @param password minimum length enforced here because this is an admin creating an
     *                 account, not a login attempt — there is no enumeration risk in
     *                 telling a caller their chosen password is too short
     */
    public record CreateUserRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 12, max = 128,
                    message = "password must be at least 12 characters") String password,
            @NotNull Role role
    ) {
    }

    /** A role change on an existing account. */
    public record UpdateRoleRequest(@NotNull Role role) {
    }

    /** An account as returned by the API. Never carries the password hash. */
    public record UserResponse(
            Long id,
            String username,
            Role role,
            boolean enabled,
            Instant createdAt
    ) {
        public static UserResponse from(User user) {
            return new UserResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getRole(),
                    user.isEnabled(),
                    user.getCreatedAt());
        }
    }
}

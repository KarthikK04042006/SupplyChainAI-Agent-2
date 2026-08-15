package com.supply.demandforecast.entity;

import com.supply.demandforecast.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An account that can authenticate against the API.
 *
 * <p>Exactly one {@link Role} per user. Multiple roles would need a join table, and the
 * three levels here are strictly ordered, so the extra table would only ever hold
 * redundant rows — the {@code RoleHierarchy} in {@code SecurityConfig} already grants a
 * MANAGER everything a VIEWER has.
 *
 * <p>{@code passwordHash} holds a BCrypt digest, never the password. The field is named
 * for what it stores so a plaintext assignment reads as obviously wrong at the call site.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt digest of the password. */
    @NotBlank
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    /**
     * Whether the account may authenticate.
     *
     * <p>Disabling is preferred to deletion: alerts and audit trails reference the user
     * who acted on them, and those references should survive an account being retired.
     *
     * <p>Defaulted to {@code true} through {@link lombok.Builder.Default} — without it a
     * builder-made user would silently be born unable to log in.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void applyDefaults() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** The authority Spring Security checks, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return role.authority();
    }
}

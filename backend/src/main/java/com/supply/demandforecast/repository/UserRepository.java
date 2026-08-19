package com.supply.demandforecast.repository;

import com.supply.demandforecast.domain.Role;
import com.supply.demandforecast.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Used on every authenticated request by {@code JwtAuthenticationFilter}, which re-reads the
     * account so a disabled or demoted user stops working immediately, and at login by
     * {@code DatabaseUserDetailsService}. The username column is unique and indexed.
     */
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Drives the startup seeder: an admin is only created when none exists. */
    boolean existsByRole(Role role);

    /**
     * How many accounts of a role can currently log in.
     *
     * <p>Counted in the database rather than by filtering {@code findAll()}, so the last-admin
     * guard does not load every user to answer a question about one number.
     */
    long countByRoleAndEnabledTrue(Role role);
}

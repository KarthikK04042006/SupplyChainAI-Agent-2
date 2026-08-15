package com.supply.demandforecast.repository;

import com.supply.demandforecast.domain.Role;
import com.supply.demandforecast.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** Used on every authenticated request, via {@code DatabaseUserDetailsService}. */
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Drives the startup seeder: an admin is only created when none exists. */
    boolean existsByRole(Role role);
}

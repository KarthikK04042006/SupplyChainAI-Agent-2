package com.supply.demandforecast.security;

import com.supply.demandforecast.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Looks users up for authentication.
 *
 * <p>Used by the login flow, where the password actually has to be checked. Authenticated
 * requests carrying a valid token do not come through here — {@link JwtAuthenticationFilter}
 * reads the account directly instead, because it needs the entity's {@code enabled} flag and
 * role rather than a {@code UserDetails} built around a password it will never verify.
 */
@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .map(user -> (UserDetails) org.springframework.security.core.userdetails.User
                        .withUsername(user.getUsername())
                        .password(user.getPasswordHash())
                        .authorities(List.of(new SimpleGrantedAuthority(user.authority())))
                        .disabled(!user.isEnabled())
                        .build())
                // Same message whether the user is missing or the password is wrong: naming
                // which one turns the login endpoint into a username oracle.
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
    }
}

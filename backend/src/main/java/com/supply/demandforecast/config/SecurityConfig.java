package com.supply.demandforecast.config;

import com.supply.demandforecast.domain.Role;
import com.supply.demandforecast.security.JwtAuthenticationFilter;
import com.supply.demandforecast.security.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Authentication and authorization.
 *
 * <p>Stateless: there is no session, and every request proves who it is with a bearer
 * token. CSRF protection is therefore off — it defends against a browser attaching an
 * ambient credential automatically, and a token a caller has to read and set explicitly is
 * not one.
 *
 * <p>Authorization is expressed here as URL rules rather than as {@code @PreAuthorize}
 * annotations spread across controllers. With three roles and a small surface, one readable
 * map beats hunting for the annotation governing a given path, and a new endpoint under an
 * existing prefix inherits a rule instead of defaulting to open.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /**
     * MANAGER inherits VIEWER, ADMIN inherits MANAGER.
     *
     * <p>Spring Security matches authorities exactly, so without this an ADMIN would be
     * refused a VIEWER-only endpoint. Declared here, next to the rules it affects, rather
     * than implied by the declaration order of {@link Role}'s constants.
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMIN.name()).implies(Role.MANAGER.name())
                .role(Role.MANAGER.name()).implies(Role.VIEWER.name())
                .build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Open: liveness and login. Nothing else.
                        .requestMatchers("/api/health", "/actuator/health/**", "/actuator/info")
                                .permitAll()
                        .requestMatchers("/api/auth/login").permitAll()

                        // The dashboard's own files. These are empty shells: every figure they
                        // show arrives from an authenticated /api call the page makes itself, so
                        // serving them to an anonymous browser discloses nothing. It has to be
                        // this way — a browser does not attach an Authorization header to a page
                        // navigation, so gating the HTML would make the login page unreachable.
                        .requestMatchers(HttpMethod.GET,
                                "/", "/index.html", "/*.html",
                                "/css/**", "/js/**", "/vendor/**",
                                "/favicon.ico", "/error").permitAll()

                        // ADMIN only: managing accounts, and every data upload.
                        .requestMatchers("/api/users/**").hasRole(Role.ADMIN.name())
                        .requestMatchers("/api/demand/**").hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/products/import")
                                .hasRole(Role.ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/inventory/import")
                                .hasRole(Role.ADMIN.name())

                        // MANAGER and up: acting on alerts, and triggering a scan on demand.
                        .requestMatchers(HttpMethod.PATCH, "/api/alerts/**")
                                .hasRole(Role.MANAGER.name())
                        // Both verbs, and before the blanket GET rule below: a scan writes
                        // alert rows, so letting the GET form fall through to VIEWER would
                        // have made "read-only" false for the one GET that is not.
                        .requestMatchers("/api/inventory/scan").hasRole(Role.MANAGER.name())

                        // VIEWER and up: reads. A forecast is a GET, so it lands here.
                        .requestMatchers(HttpMethod.GET, "/api/**").hasRole(Role.VIEWER.name())

                        // Any /api write not matched above is one this map has not
                        // considered. Denying by default means adding an endpoint cannot
                        // silently expose it — it fails closed until a rule is written.
                        .requestMatchers("/api/**").denyAll()

                        .anyRequest().authenticated()
                )
                .exceptionHandling(handling -> handling
                        // 401 with no WWW-Authenticate challenge: a browser credential popup
                        // is useless for a token API and only confuses a fetch() caller.
                        .authenticationEntryPoint(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * BCrypt for password storage.
     *
     * <p>Default strength (10) — raising it slows an offline cracker and the login endpoint
     * in equal measure, and the digest records its own cost factor, so this can be raised
     * later without invalidating existing hashes.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

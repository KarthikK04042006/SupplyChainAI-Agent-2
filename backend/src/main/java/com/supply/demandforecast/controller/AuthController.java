package com.supply.demandforecast.controller;

import com.supply.demandforecast.dto.LoginRequest;
import com.supply.demandforecast.dto.LoginResponse;
import com.supply.demandforecast.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Authentication endpoints. The only part of {@code /api} reachable without a token. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** Exchanges username and password for a bearer token. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Who the current token belongs to.
     *
     * <p>Answered from the token's own claims, so a client can confirm a token is still
     * accepted and see its role without needing a user-management permission.
     */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of(
                "username", authentication.getName(),
                "authorities", authentication.getAuthorities().stream()
                        .map(Object::toString)
                        .toList());
    }
}

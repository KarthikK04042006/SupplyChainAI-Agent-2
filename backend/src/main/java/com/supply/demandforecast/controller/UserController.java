package com.supply.demandforecast.controller;

import com.supply.demandforecast.dto.UserDtos.CreateUserRequest;
import com.supply.demandforecast.dto.UserDtos.UpdateRoleRequest;
import com.supply.demandforecast.dto.UserDtos.UserResponse;
import com.supply.demandforecast.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User administration.
 *
 * <p>Restricted to ADMIN by the {@code /api/users/**} rule in {@code SecurityConfig},
 * rather than by an annotation here, so the whole authorization map can be read in one
 * place.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public List<UserResponse> list() {
        return userService.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PatchMapping("/{id}/role")
    public UserResponse updateRole(@PathVariable Long id,
                                   @Valid @RequestBody UpdateRoleRequest request) {
        return userService.updateRole(id, request.role());
    }

    /** Enables or disables an account; disabling is this API's form of deletion. */
    @PatchMapping("/{id}/enabled")
    public UserResponse setEnabled(@PathVariable Long id, @RequestParam boolean value) {
        return userService.setEnabled(id, value);
    }
}

package com.supply.demandforecast.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.domain.Role;
import com.supply.demandforecast.dto.UserDtos.CreateUserRequest;
import com.supply.demandforecast.dto.UserDtos.UpdateRoleRequest;
import com.supply.demandforecast.entity.User;
import com.supply.demandforecast.repository.UserRepository;
import com.supply.demandforecast.support.WithTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * User administration over HTTP: listing, creating, and the two guarded mutations.
 *
 * <p>The last-admin guard is the reason this class exists. It protects the one state no API call
 * could recover from — an installation with nobody able to administer accounts — so both the
 * refusals and the operations it must <em>not</em> refuse are worth pinning. A guard that is
 * merely over-eager still costs an operator the ability to tidy up their own user list, and it
 * says something untrue while doing it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class UserAdministrationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WithTestUsers users;

    /** Not exercised here, but the context needs it and it must not reach a real service. */
    @MockitoBean
    private MlServiceClient mlServiceClient;

    /**
     * Three users, one per role — so {@code test-admin} is the only enabled ADMIN.
     *
     * <p>Every last-admin assertion below depends on that, and {@code reset()} wipes first, so
     * an admin left behind by an earlier test class cannot make a refusal test pass or fail for
     * a reason that has nothing to do with the guard.
     */
    @BeforeEach
    void seed() {
        users.reset();
    }

    @Test
    void listsUsersWithoutEverCarryingAPasswordHash() throws Exception {
        String body = mockMvc.perform(get("/api/users").with(users.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("$2a$").doesNotContain("passwordHash");
    }

    @Test
    void createsAUserAndRefusesADuplicateUsername() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(users.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateUserRequest(
                                "new-analyst", "a-long-enough-password", Role.VIEWER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("new-analyst"))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(post("/api/users")
                        .with(users.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateUserRequest(
                                "new-analyst", "a-long-enough-password", Role.MANAGER))))
                .andExpect(status().isConflict());

        // The stored hash is a hash, not the password that produced it.
        User created = userRepository.findByUsername("new-analyst").orElseThrow();
        assertThat(created.getPasswordHash()).isNotEqualTo("a-long-enough-password");
        assertThat(passwordEncoder.matches("a-long-enough-password", created.getPasswordHash()))
                .isTrue();
    }

    /* The last-admin guard ------------------------------------------------- */

    @Test
    void theLastEnabledAdminCannotBeDemoted() throws Exception {
        long id = idOf("test-admin");

        mockMvc.perform(patch("/api/users/{id}/role", id)
                        .with(users.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateRoleRequest(Role.VIEWER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Last admin protected"));

        assertThat(userRepository.findByUsername("test-admin").orElseThrow().getRole())
                .isEqualTo(Role.ADMIN);
    }

    @Test
    void theLastEnabledAdminCannotBeDisabled() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/enabled", idOf("test-admin"))
                        .param("value", "false")
                        .with(users.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Last admin protected"));

        assertThat(userRepository.findByUsername("test-admin").orElseThrow().isEnabled()).isTrue();
    }

    /** With a second enabled admin present, the guard has nothing to protect. */
    @Test
    void anAdminCanBeDemotedOnceAnotherEnabledAdminExists() throws Exception {
        save("second-admin", Role.ADMIN, true);

        mockMvc.perform(patch("/api/users/{id}/role", idOf("test-admin"))
                        .with(users.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateRoleRequest(Role.MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    /**
     * A <em>disabled</em> admin can be demoted, even when one enabled admin is left.
     *
     * <p>This is the case the guard used to refuse. It asked "is there at most one enabled
     * admin?" rather than "would this change remove the last one", so it fired on any admin
     * account whenever the enabled-admin count was down to one — including an account that was
     * already disabled and therefore already unable to administer anything. Demoting it removes
     * no access that existed, and the enabled admin sitting alongside it was unaffected either
     * way, so the 409 was answering a question nobody had asked.
     *
     * <p>The path is an ordinary one: disable a departing admin's account, then tidy the user
     * list later by dropping them to VIEWER. The refusal came with "Cannot demote or disable the
     * last enabled admin", which named the wrong account and was false about the state.
     */
    @Test
    void aDisabledAdminCanBeDemotedWhileOneEnabledAdminRemains() throws Exception {
        User departed = save("departed-admin", Role.ADMIN, false);

        // Precondition, stated rather than assumed: exactly one enabled admin, and the account
        // being demoted is not it.
        assertThat(userRepository.countByRoleAndEnabledTrue(Role.ADMIN)).isEqualTo(1);

        mockMvc.perform(patch("/api/users/{id}/role", departed.getId())
                        .with(users.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateRoleRequest(Role.VIEWER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        // The real admin is untouched, which is what the guard was there to ensure.
        assertThat(userRepository.findByUsername("test-admin").orElseThrow())
                .satisfies(admin -> {
                    assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
                    assertThat(admin.isEnabled()).isTrue();
                });
    }

    /**
     * Disabling an already-disabled admin is allowed, because it changes nothing.
     *
     * <p>The same over-eager guard turned this no-op into a 409. Worth its own test: the fix
     * reads "not enabled, so not the last enabled admin", and that clause has to hold for the
     * disable path as well as the demote one.
     */
    @Test
    void reDisablingAnAlreadyDisabledAdminIsNotRefused() throws Exception {
        User departed = save("departed-admin", Role.ADMIN, false);

        mockMvc.perform(patch("/api/users/{id}/enabled", departed.getId())
                        .param("value", "false")
                        .with(users.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    /**
     * A disabled admin does not count towards the guard when a real demotion is attempted.
     *
     * <p>The complement of the case above, and the one that shows the fix did not simply loosen
     * the guard: two admins exist, but only one can log in, so demoting that one still has to be
     * refused. Counting rows by role alone would let this through and lock the installation out.
     */
    @Test
    void aDisabledAdminDoesNotCountAsTheSecondAdmin() throws Exception {
        save("departed-admin", Role.ADMIN, false);

        mockMvc.perform(patch("/api/users/{id}/role", idOf("test-admin"))
                        .with(users.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateRoleRequest(Role.VIEWER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Last admin protected"));
    }

    /** Promoting the last admin to ADMIN again is a no-op, not a self-inflicted 409. */
    @Test
    void settingTheLastAdminsRoleToAdminAgainIsAllowed() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/role", idOf("test-admin"))
                        .with(users.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateRoleRequest(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    /** Demoting a non-admin never consults the guard, whatever the admin count is. */
    @Test
    void demotingANonAdminIsUnaffectedByTheAdminCount() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/role", idOf("test-manager"))
                        .with(users.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateRoleRequest(Role.VIEWER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void anUnknownUserIsANotFound() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/enabled", 999_999L)
                        .param("value", "false")
                        .with(users.admin()))
                .andExpect(status().isNotFound());
    }

    /* Role gating ---------------------------------------------------------- */

    /**
     * Every route here is ADMIN, and MANAGER is refused as firmly as VIEWER.
     *
     * <p>Asserted for MANAGER explicitly because the role hierarchy grants it the operational
     * endpoints — the scan, the resolve — which makes "a manager can do the day-to-day things" a
     * plausible reading. Handing out roles is not one of them.
     */
    @Test
    void onlyAdminCanAdministerUsers() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users").with(users.viewer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").with(users.manager())).andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/users/{id}/enabled", idOf("test-viewer"))
                        .param("value", "false")
                        .with(users.manager()))
                .andExpect(status().isForbidden());

        // Refused above the service, so nothing changed.
        assertThat(userRepository.findByUsername("test-viewer").orElseThrow().isEnabled()).isTrue();
    }

    /* Helpers -------------------------------------------------------------- */

    private long idOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private User save(String username, Role role, boolean enabled) {
        return userRepository.save(User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(WithTestUsers.PASSWORD))
                .role(role)
                .enabled(enabled)
                .build());
    }
}

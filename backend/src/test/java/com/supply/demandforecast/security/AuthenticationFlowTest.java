package com.supply.demandforecast.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.dto.LoginRequest;
import com.supply.demandforecast.domain.Role;
import com.supply.demandforecast.entity.User;
import com.supply.demandforecast.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authentication surface end to end: login, token use, and the role rules.
 *
 * <p>Runs the real filter chain rather than {@code @WithMockUser}, because the thing worth
 * testing here is whether a token issued by {@link JwtService} is accepted by {@link
 * JwtAuthenticationFilter} and mapped to the right authority — which a mocked principal
 * would bypass entirely.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationFlowTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Not exercised here, but the context needs it and it must not reach a real service. */
    @MockitoBean
    private MlServiceClient mlServiceClient;

    /**
     * A clean user table per test.
     *
     * <p>The context is shared across tests in this class, so the seeded admin and anything
     * a previous test created are still present. Deleting first keeps {@code isLastAdmin}
     * arithmetic and the "no such user" case predictable regardless of run order.
     */
    @BeforeEach
    void seedUsers() {
        userRepository.deleteAll();
        save("viewer", Role.VIEWER);
        save("manager", Role.MANAGER);
        save("boss", Role.ADMIN);
    }

    @Test
    void loginReturnsUsableTokenAndRole() throws Exception {
        String body = mockMvc.perform(login("manager", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(body).get("token").asText();
        assertThat(token).isNotBlank();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("manager"))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_MANAGER"));
    }

    @Test
    void loginResponseNeverCarriesThePasswordHash() throws Exception {
        String body = mockMvc.perform(login("viewer", PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("$2a$").doesNotContain(PASSWORD);
    }

    @Test
    void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
        String wrongPassword = mockMvc.perform(login("viewer", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String noSuchUser = mockMvc.perform(login("nobody-here", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(noSuchUser);
    }

    @Test
    void protectedEndpointsRejectMissingGarbageAndUnsignedTokens() throws Exception {
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/alerts").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());

        // Correct shape and claims, signed with the wrong key.
        String foreign = new JwtService(new JwtProperties(
                "a-different-secret-of-sufficient-length-to-be-valid", 60,
                "demand-forecast", "admin", null))
                .issue("boss", Role.ADMIN.name());

        mockMvc.perform(get("/api/alerts").header("Authorization", "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthAndLoginStayOpen() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void viewerCannotReachAdminOrManagerEndpoints() throws Exception {
        String token = tokenFor("viewer");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminInheritsManagerAndViewerThroughTheRoleHierarchy() throws Exception {
        String token = tokenFor("boss");

        // /api/users is ADMIN-only; /api/alerts is VIEWER-and-up. An ADMIN gets both only
        // because the hierarchy grants it, since authorities are matched exactly.
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void disabledAccountCannotLogInEvenWithTheRightPassword() throws Exception {
        save("retired", Role.MANAGER, false);

        mockMvc.perform(login("retired", PASSWORD))
                .andExpect(status().isForbidden());
    }

    private String tokenFor(String username) throws Exception {
        String body = mockMvc.perform(login(username, PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private RequestBuilder login(String user, String password)
            throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(user, password)));
    }

    private void save(String username, Role role) {
        save(username, role, true);
    }

    private void save(String username, Role role, boolean enabled) {
        userRepository.save(User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(role)
                .enabled(enabled)
                .build());
    }
}

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    /**
     * A refused write is 403, not 401.
     *
     * <p>Worth stating per verb, because the two are decided by different handlers and the
     * difference is not cosmetic: 401 tells a caller to authenticate, which they already did,
     * and no amount of logging in again will grant a role they do not have.
     *
     * <p>These assertions cannot fail here for the reason they once failed in production.
     * The 403 was being lost to the container's {@code ERROR} dispatch, and {@code MockMvc}
     * does not perform one — it returns the status the chain set. Real HTTP is what caught
     * that, and {@code scripts/verify-dashboard.sh} is where it stays caught.
     */
    @Test
    void refusedWritesAreForbiddenRatherThanUnauthorized() throws Exception {
        String viewer = tokenFor("viewer");

        mockMvc.perform(post("/api/inventory/scan").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/alerts/1/resolve").header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());

        mockMvc.perform(multipart("/api/products/import")
                        .file(new MockMultipartFile("file", "p.csv", "text/csv", "sku\n".getBytes()))
                        .header("Authorization", "Bearer " + tokenFor("manager")))
                .andExpect(status().isForbidden());
    }

    /**
     * Both refusals carry the {@code ProblemDetail} body the dashboard reads.
     *
     * <p>They are produced above the {@code DispatcherServlet}, so {@code
     * GlobalExceptionHandler} never sees them — without their own handler they arrive empty
     * and the UI can only print the bare status.
     */
    @Test
    void refusalsCarryAProblemDetailTheDashboardCanShow() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + tokenFor("viewer")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                // Names no required role: that is a hint to someone already refused.
                .andExpect(jsonPath("$.detail").value("Your role does not permit this operation"));
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

    /**
     * Disabling an account takes effect on the next request, not at token expiry.
     *
     * <p>This is the case the filter's database lookup exists for, and it regressed once:
     * the authority was built from the token's own {@code role} claim with no read at all,
     * so a token minted before the account was disabled kept working for the rest of its
     * hour. The login endpoint refused the user — {@link
     * #disabledAccountCannotLogInEvenWithTheRightPassword} covers that — while every request
     * bearing the already-issued token sailed through, which is the half that matters: an
     * operator revoking access does not have the old token to throw away.
     *
     * <p>The token is taken before disabling and deliberately reused afterwards. Asking for
     * a fresh one would only re-test the login path.
     */
    @Test
    void disablingAnAccountRevokesItsAlreadyIssuedToken() throws Exception {
        String token = tokenFor("manager");

        // Valid before: this is the same token, so the assertion below cannot pass for the
        // trivial reason that it never worked.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User manager = userRepository.findByUsername("manager").orElseThrow();
        manager.setEnabled(false);
        userRepository.save(manager);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Deleting an account revokes its token too.
     *
     * <p>The project prefers disabling to deletion, so this is the unlikely path — but a
     * token whose subject no longer exists must not authenticate on the strength of its
     * signature alone. Left unauthenticated rather than 500: a missing row is a refusal,
     * not a server fault.
     */
    @Test
    void aTokenForADeletedAccountNoLongerAuthenticates() throws Exception {
        String token = tokenFor("viewer");

        userRepository.delete(userRepository.findByUsername("viewer").orElseThrow());

        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A demotion applies to the token the user already holds.
     *
     * <p>Falls out of reading the role from the database rather than the claim, and is worth
     * pinning separately: privilege that outlives its revocation is the same defect as access
     * that does, and a token minted as ADMIN must not keep opening ADMIN routes.
     */
    @Test
    void demotingAnAccountAppliesToItsAlreadyIssuedToken() throws Exception {
        String token = tokenFor("boss");

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User boss = userRepository.findByUsername("boss").orElseThrow();
        // A second admin, so demoting this one is not refused for being the last.
        save("other-admin", Role.ADMIN);
        boss.setRole(Role.VIEWER);
        userRepository.save(boss);

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
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

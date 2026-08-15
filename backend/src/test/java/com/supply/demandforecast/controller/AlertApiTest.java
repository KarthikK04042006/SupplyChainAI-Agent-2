package com.supply.demandforecast.controller;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.entity.Alert;
import com.supply.demandforecast.repository.AlertRepository;
import com.supply.demandforecast.support.WithTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/alerts} and {@code PATCH /api/alerts/{id}/resolve}.
 *
 * <p>No ML service involved — these read and act on alerts already written. The mock exists only
 * so the context cannot reach a real service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class AlertApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private WithTestUsers users;

    @MockitoBean
    private MlServiceClient mlServiceClient;

    private Long criticalStockoutId;

    @BeforeEach
    void seed() {
        alertRepository.deleteAll();
        users.reset();

        criticalStockoutId = alert("A-1", AlertType.STOCKOUT_RISK, Severity.CRITICAL, 95.0).getId();
        alert("B-2", AlertType.STOCKOUT_RISK, Severity.MEDIUM, 40.0);
        alert("C-3", AlertType.OVERSTOCK, Severity.HIGH, 70.0);

        Alert closed = alert("D-4", AlertType.OVERSTOCK, Severity.LOW, 30.0);
        closed.resolve("someone-else", "Stock written off");
        alertRepository.save(closed);
    }

    /**
     * Unfiltered means everything, resolved rows included.
     *
     * <p>Defaulting {@code resolved} to false would make a bare {@code GET /api/alerts} quietly
     * mean "open only", and a caller wanting the history would have no way to tell the default
     * from a deliberate filter.
     */
    @Test
    void unfilteredListReturnsEveryAlertMostUrgentFirst() throws Exception {
        mockMvc.perform(get("/api/alerts").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(4))
                .andExpect(jsonPath("$.totalMatching").value(4))
                .andExpect(jsonPath("$.alerts[0].sku").value("A-1"))
                .andExpect(jsonPath("$.alerts[1].sku").value("C-3"))
                .andExpect(jsonPath("$.alerts[2].sku").value("B-2"))
                .andExpect(jsonPath("$.alerts[3].sku").value("D-4"));
    }

    @Test
    void filtersBySeverityTypeAndResolvedStatusIndependently() throws Exception {
        mockMvc.perform(get("/api/alerts").param("severity", "critical").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].sku").value("A-1"));

        mockMvc.perform(get("/api/alerts").param("type", "OVERSTOCK").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(2));

        mockMvc.perform(get("/api/alerts").param("resolved", "false").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(3));

        mockMvc.perform(get("/api/alerts").param("resolved", "true").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].sku").value("D-4"))
                .andExpect(jsonPath("$.alerts[0].resolvedBy").value("someone-else"));

        mockMvc.perform(get("/api/alerts").param("sku", "B-2").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1));
    }

    @Test
    void filtersCombineWithAnd() throws Exception {
        mockMvc.perform(get("/api/alerts")
                        .param("type", "STOCKOUT_RISK")
                        .param("resolved", "false")
                        .param("severity", "MEDIUM")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].sku").value("B-2"));
    }

    /** The severity the API prints is the severity it accepts back. */
    @Test
    void severityIsAcceptedInEitherCasing() throws Exception {
        for (String spelling : new String[] {"critical", "CRITICAL", "Critical"}) {
            mockMvc.perform(get("/api/alerts").param("severity", spelling).with(users.viewer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alerts.length()").value(1));
        }
    }

    /**
     * The header counts describe the whole table, not the filtered page.
     *
     * <p>A dashboard badge reading "1 critical open" must keep saying that while the user
     * filters the list down to overstocks.
     */
    @Test
    void openCountsAreUnaffectedByTheFilter() throws Exception {
        mockMvc.perform(get("/api/alerts").param("type", "OVERSTOCK").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(2))
                .andExpect(jsonPath("$.counts.open").value(3))
                .andExpect(jsonPath("$.counts.critical").value(1))
                .andExpect(jsonPath("$.counts.high").value(1));
    }

    @Test
    void pagesAndCapsThePageSize() throws Exception {
        mockMvc.perform(get("/api/alerts").param("size", "2").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.totalMatching").value(4));

        mockMvc.perform(get("/api/alerts").param("size", "2").param("page", "1")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(2))
                .andExpect(jsonPath("$.page").value(1));

        // Asking for the whole table gets a large page, not an unbounded one.
        mockMvc.perform(get("/api/alerts").param("size", "100000").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));
    }

    @Test
    void resolveRecordsTheAuthenticatedUserAndTheNote() throws Exception {
        mockMvc.perform(patch("/api/alerts/{id}/resolve", criticalStockoutId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Emergency order placed with the supplier\"}")
                        .with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.resolvedBy").value(users.managerUsername()))
                .andExpect(jsonPath("$.resolutionNote")
                        .value("Emergency order placed with the supplier"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        assertThat(alertRepository.findById(criticalStockoutId).orElseThrow().isResolved()).isTrue();
        assertThat(alertRepository.countByResolvedFalse()).isEqualTo(2);
    }

    /** Resolving with no explanation is legitimate; requiring a note only teaches people to type "ok". */
    @Test
    void resolveWorksWithNoBodyAtAll() throws Exception {
        mockMvc.perform(patch("/api/alerts/{id}/resolve", criticalStockoutId)
                        .with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.resolutionNote").doesNotExist());
    }

    /**
     * Resolving twice is a conflict, not a silent no-op.
     *
     * <p>Two managers working the same list would otherwise both believe they closed it, and
     * the second would overwrite the first's name on the audit trail.
     */
    @Test
    void resolvingAnAlreadyResolvedAlertIsAConflict() throws Exception {
        mockMvc.perform(patch("/api/alerts/{id}/resolve", criticalStockoutId)
                        .with(users.manager()))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/alerts/{id}/resolve", criticalStockoutId)
                        .with(users.manager()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString(users.managerUsername())));

        // The first resolver's record is intact.
        assertThat(alertRepository.findById(criticalStockoutId).orElseThrow().getResolvedBy())
                .isEqualTo(users.managerUsername());
    }

    /** The row survives resolution: it is the audit trail of what was wrong and who closed it. */
    @Test
    void resolvingDoesNotDeleteTheAlert() throws Exception {
        mockMvc.perform(patch("/api/alerts/{id}/resolve", criticalStockoutId)
                        .with(users.manager()))
                .andExpect(status().isOk());

        assertThat(alertRepository.findAll()).hasSize(4);
        mockMvc.perform(get("/api/alerts/{id}", criticalStockoutId).with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void unknownAlertIsNotFound() throws Exception {
        mockMvc.perform(patch("/api/alerts/{id}/resolve", 999999).with(users.manager()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/alerts/{id}", 999999).with(users.viewer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewerCanReadButNotResolve() throws Exception {
        mockMvc.perform(get("/api/alerts").with(users.viewer()))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/alerts/{id}/resolve", criticalStockoutId)
                        .with(users.viewer()))
                .andExpect(status().isForbidden());

        assertThat(alertRepository.findById(criticalStockoutId).orElseThrow().isResolved())
                .isFalse();
    }

    /** ADMIN inherits MANAGER through the role hierarchy, so it can act on alerts too. */
    @Test
    void adminInheritsTheManagerAbilityToResolve() throws Exception {
        mockMvc.perform(patch("/api/alerts/{id}/resolve", criticalStockoutId)
                        .with(users.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedBy").value("test-admin"));
    }

    @Test
    void alertsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/alerts/{id}/resolve", criticalStockoutId))
                .andExpect(status().isUnauthorized());
    }

    private Alert alert(String sku, AlertType type, Severity severity, double riskScore) {
        return alertRepository.save(Alert.builder()
                .sku(sku)
                .type(type)
                .severity(severity)
                .message(severity + " " + type + " on " + sku)
                .riskScore(riskScore)
                .daysOfSupply(4.0)
                .inventoryPosition(120.0)
                .build());
    }
}

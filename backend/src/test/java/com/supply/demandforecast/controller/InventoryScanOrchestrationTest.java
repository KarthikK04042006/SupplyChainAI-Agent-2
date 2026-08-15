package com.supply.demandforecast.controller;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.client.MlServiceException;
import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.dto.BatchForecastRequest;
import com.supply.demandforecast.dto.BatchForecastResponse;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.dto.RiskScanResponse;
import com.supply.demandforecast.entity.Alert;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.AlertRepository;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import com.supply.demandforecast.repository.ProductRepository;
import com.supply.demandforecast.support.MlResponses;
import com.supply.demandforecast.support.WithTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.ConnectException;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/inventory/scan} end to end, with the ML service mocked.
 *
 * <p>What matters here is not the classification — that is the ML service's job and is mocked —
 * but the reconciliation around it: which verdicts become alerts, which restate an alert that
 * already exists, and which close one. That logic is what decides whether the alert list is
 * worth reading after the hundredth scan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class InventoryScanOrchestrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private DemandHistoryRepository demandHistoryRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private WithTestUsers users;

    @MockitoBean
    private MlServiceClient mlServiceClient;

    @BeforeEach
    void seed() {
        alertRepository.deleteAll();
        demandHistoryRepository.deleteAll();
        productRepository.deleteAll();
        users.reset();

        product("LOW-1", 10, 0, 3);
        product("HIGH-2", 5000, 0, 7);
        product("FINE-3", 400, 0, 5);

        history("LOW-1", 30, 25.0);
        history("HIGH-2", 30, 30.0);
        history("FINE-3", 30, 20.0);
    }

    @Test
    void opensAlertsForVerdictsAboveTheThresholdAndIgnoresHealthySkus() throws Exception {
        mockScan(MlResponses.riskScan(
                MlResponses.stockoutRisk("LOW-1", Severity.CRITICAL, 92.0),
                MlResponses.overstock("HIGH-2", Severity.MEDIUM, 41.0),
                MlResponses.ok("FINE-3")));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productsScanned").value(3))
                .andExpect(jsonPath("$.alertsOpened").value(2))
                .andExpect(jsonPath("$.alertsUpdated").value(0))
                .andExpect(jsonPath("$.alertsResolved").value(0))
                .andExpect(jsonPath("$.openAlerts.length()").value(2))
                // Most urgent first, so the top of the list is the thing to act on.
                .andExpect(jsonPath("$.openAlerts[0].sku").value("LOW-1"))
                .andExpect(jsonPath("$.openAlerts[0].type").value("STOCKOUT_RISK"))
                .andExpect(jsonPath("$.openAlerts[0].severity").value("critical"))
                .andExpect(jsonPath("$.openAlerts[0].occurrences").value(1));

        assertThat(alertRepository.findByResolvedFalse())
                .extracting(Alert::getSku)
                .containsExactlyInAnyOrder("LOW-1", "HIGH-2");
    }

    /**
     * A verdict the ML service reported but scored below the configured floor earns nothing.
     *
     * <p>This is the threshold in the user's spec: the service flags anything off-target,
     * including mild cases, and without a floor the alert list becomes a copy of the catalogue.
     */
    @Test
    void verdictsBelowTheRiskThresholdDoNotBecomeAlerts() throws Exception {
        mockScan(MlResponses.riskScan(
                MlResponses.stockoutRisk("LOW-1", Severity.LOW, 12.0),
                MlResponses.overstock("HIGH-2", Severity.LOW, 24.9)));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsOpened").value(0))
                .andExpect(jsonPath("$.openAlerts.length()").value(0));

        assertThat(alertRepository.findAll()).isEmpty();
    }

    /**
     * A standing problem updates its alert instead of writing a second one.
     *
     * <p>Without this the scheduled scan would bury a genuinely new problem under hundreds of
     * restatements of an old one, and {@code occurrences} is what turns "how long has this been
     * wrong" into a readable field.
     */
    @Test
    void rescanRestatesTheOpenAlertRatherThanDuplicatingIt() throws Exception {
        mockScan(MlResponses.riskScan(
                MlResponses.stockoutRisk("LOW-1", Severity.HIGH, 70.0)));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsOpened").value(1));

        // Same problem, now worse.
        mockScan(MlResponses.riskScan(
                MlResponses.stockoutRisk("LOW-1", Severity.CRITICAL, 95.0)));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsOpened").value(0))
                .andExpect(jsonPath("$.alertsUpdated").value(1))
                .andExpect(jsonPath("$.openAlerts.length()").value(1))
                .andExpect(jsonPath("$.openAlerts[0].occurrences").value(2))
                // Severity reflects the situation now, not the worst it has ever been.
                .andExpect(jsonPath("$.openAlerts[0].severity").value("critical"))
                .andExpect(jsonPath("$.openAlerts[0].riskScore").value(95.0));

        assertThat(alertRepository.findAll()).hasSize(1);
    }

    /** A problem that no longer appears is closed, so the list does not accumulate stale rows. */
    @Test
    void alertsNoLongerFlaggedAreResolvedAutomatically() throws Exception {
        mockScan(MlResponses.riskScan(
                MlResponses.stockoutRisk("LOW-1", Severity.HIGH, 70.0)));
        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(jsonPath("$.alertsOpened").value(1));

        mockScan(MlResponses.riskScan(MlResponses.ok("LOW-1")));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsResolved").value(1))
                .andExpect(jsonPath("$.openAlerts.length()").value(0));

        Alert closed = alertRepository.findAll().getFirst();
        assertThat(closed.isResolved()).isTrue();
        assertThat(closed.getResolvedBy()).isEqualTo("system:scan");
        assertThat(closed.getResolvedAt()).isNotNull();
    }

    /**
     * A SKU the scan could not judge keeps its alert.
     *
     * <p>A product skipped for thin history contributed no verdict, and no verdict is not
     * evidence the problem went away. Auto-resolving it would quietly close a real alert.
     */
    @Test
    void anAlertForAnUnassessedSkuSurvivesTheScan() throws Exception {
        alertRepository.save(Alert.builder()
                .sku("FINE-3")
                .type(AlertType.STOCKOUT_RISK)
                .severity(Severity.HIGH)
                .message("Flagged by an earlier scan")
                .riskScore(80.0)
                .build());

        // This pass assesses only LOW-1, so FINE-3 is unjudged rather than judged healthy.
        mockScan(MlResponses.riskScan(MlResponses.ok("LOW-1")));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsResolved").value(0))
                .andExpect(jsonPath("$.openAlerts.length()").value(1))
                .andExpect(jsonPath("$.openAlerts[0].sku").value("FINE-3"));
    }

    /**
     * A product with too little history is reported as skipped, with the reason.
     *
     * <p>Silently dropping it would read as "this product is fine", which is a different claim
     * from "this product was not looked at".
     */
    @Test
    void productsWithoutEnoughHistoryAreReportedAsSkipped() throws Exception {
        product("BRAND-NEW", 0, 0, 4);
        history("BRAND-NEW", 1, 3.0);

        mockScan(MlResponses.riskScan(MlResponses.ok("LOW-1")));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productsSkipped.length()").value(1))
                .andExpect(jsonPath("$.productsSkipped[0].sku").value("BRAND-NEW"))
                .andExpect(jsonPath("$.productsSkipped[0].reason")
                        .value(containsString("1 demand observation")));
    }

    /**
     * A SKU whose forecast failed is skipped, not sent on with nothing.
     *
     * <p>Zero forecast demand reads as "this will never run out", which is the opposite of what
     * a failed forecast tells us — so the risk scan must never see that SKU.
     */
    @Test
    void aFailedForecastRemovesTheSkuFromTheRiskScan() throws Exception {
        when(mlServiceClient.forecastBatch(any())).thenReturn(MlResponses.batch(
                List.of(MlResponses.forecast("LOW-1", 14, 25.0),
                        MlResponses.forecast("FINE-3", 14, 20.0)),
                List.of(new BatchForecastResponse.Failure("HIGH-2", "series is constant"))));

        when(mlServiceClient.riskScan(any())).thenReturn(MlResponses.riskScan(
                MlResponses.stockoutRisk("LOW-1", Severity.HIGH, 88.0),
                MlResponses.ok("FINE-3")));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productsScanned").value(2))
                .andExpect(jsonPath("$.productsSkipped[0].sku").value("HIGH-2"))
                .andExpect(jsonPath("$.productsSkipped[0].reason")
                        .value(containsString("series is constant")));

        verify(mlServiceClient).riskScan(argThat(request -> {
            assertThat(request.items()).extracting("sku")
                    .containsExactlyInAnyOrder("LOW-1", "FINE-3");
            return true;
        }));
    }

    /** Stock position and per-product lead time reach the ML service, not just the forecast. */
    @Test
    void stockPositionFromTheDatabaseReachesTheRiskScan() throws Exception {
        mockScan(MlResponses.riskScan(MlResponses.ok("LOW-1")));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk());

        verify(mlServiceClient).riskScan(argThat(request -> {
            var low = request.items().stream()
                    .filter(i -> i.sku().equals("LOW-1")).findFirst().orElseThrow();
            assertThat(low.currentStock()).isEqualTo(10.0);
            assertThat(low.leadTimeDays()).isEqualTo(3.0);
            assertThat(low.forecastDailyDemand()).hasSize(14);
            return true;
        }));
    }

    /**
     * An ML outage fails the request rather than reporting an empty scan.
     *
     * <p>A scan that could not reach the service produced no verdicts, and "0 alerts" would be
     * indistinguishable from a catalogue with nothing wrong.
     */
    @Test
    void anMlOutageFailsTheScanRatherThanReportingNothingWrong() throws Exception {
        when(mlServiceClient.forecastBatch(any())).thenThrow(new MlServiceException(
                "ML service unreachable after 3 attempts",
                new ConnectException("Connection refused")));

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isServiceUnavailable());

        assertThat(alertRepository.findAll()).isEmpty();
    }

    /** An empty catalogue is not an error, and must not call the ML service at all. */
    @Test
    void anEmptyCatalogueScansCleanlyWithoutCallingTheMlService() throws Exception {
        demandHistoryRepository.deleteAll();
        productRepository.deleteAll();

        mockMvc.perform(get("/api/inventory/scan").with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productsScanned").value(0))
                .andExpect(jsonPath("$.alertsOpened").value(0));

        verifyNoInteractions(mlServiceClient);
    }

    /**
     * A scan writes alerts, so it is not a read.
     *
     * <p>The path is a GET as specified, which would otherwise fall through to the blanket
     * VIEWER rule for GETs. VIEWER being genuinely read-only is what this asserts.
     */
    @Test
    void viewerCannotTriggerAScan() throws Exception {
        mockMvc.perform(get("/api/inventory/scan").with(users.viewer()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/inventory/scan"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(mlServiceClient);
    }

    /**
     * Both mocked calls a full scan makes.
     *
     * <p>The batch forecast answers whatever SKUs it was asked about rather than a fixed list,
     * so a test that adds a product does not have to restate the forecast stub as well.
     *
     * <p>{@code doAnswer}/{@code doReturn} rather than {@code when(...)}: the tests that scan
     * twice call this method twice, and {@code when(mock.forecastBatch(any()))} would
     * <em>invoke</em> the existing stub with a null argument to get its return value before
     * restubbing it.
     */
    private void mockScan(RiskScanResponse scan) {
        doAnswer(invocation -> {
            BatchForecastRequest request = invocation.getArgument(0);
            return MlResponses.batch(request.items().stream()
                    .map(item -> MlResponses.forecast(item.sku(), 14, 25.0))
                    .toArray(ForecastResponse[]::new));
        }).when(mlServiceClient).forecastBatch(any());

        doReturn(scan).when(mlServiceClient).riskScan(any());
    }

    private void product(String sku, double stock, double onOrder, int leadTimeDays) {
        productRepository.save(Product.builder()
                .sku(sku)
                .name(sku + " product")
                .leadTimeDays(leadTimeDays)
                .currentStock(stock)
                .onOrder(onOrder)
                .build());
    }

    private void history(String sku, int days, double quantity) {
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < days; i++) {
            demandHistoryRepository.save(DemandHistory.builder()
                    .sku(sku).demandDate(start.plusDays(i)).quantity(quantity).build());
        }
    }
}

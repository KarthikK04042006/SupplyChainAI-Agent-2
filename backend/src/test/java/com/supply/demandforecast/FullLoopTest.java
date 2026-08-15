package com.supply.demandforecast;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.dto.BatchForecastRequest;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.AlertRepository;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import com.supply.demandforecast.repository.ProductRepository;
import com.supply.demandforecast.service.ScheduledInventoryScan;
import com.supply.demandforecast.support.MlResponses;
import com.supply.demandforecast.support.WithTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole loop in one pass: upload demand, let the scheduled scan run, query the alerts it
 * opened, resolve one, and read an ML-backed forecast for the product.
 *
 * <p>Every other test in this suite checks one endpoint with its neighbours stubbed out. This one
 * exists because the parts can each be right while the seams between them are wrong — an upload
 * that stores a SKU the scan does not look at, or a scan whose alerts the query endpoint filters
 * away. Only the ML service is mocked; everything between the HTTP boundary and the database is
 * real, including authentication and the scheduler's entry point.
 */
// The test profile disables the scheduler so it cannot fire mid-assertion elsewhere; this test
// is the one that needs the bean, and drives it by hand. The default two-minute initial delay
// means its own timer never comes round before the test finishes.
@SpringBootTest(properties = "inventory.scan.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class FullLoopTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private DemandHistoryRepository demandHistoryRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private ScheduledInventoryScan scheduledScan;

    @Autowired
    private WithTestUsers users;

    @MockitoBean
    private MlServiceClient mlServiceClient;

    @BeforeEach
    void clean() {
        alertRepository.deleteAll();
        demandHistoryRepository.deleteAll();
        productRepository.deleteAll();
        users.reset();
    }

    @Test
    void uploadThenScheduledScanThenAlertsThenForecast() throws Exception {
        // A product that will run out, and one that is comfortable.
        productRepository.save(Product.builder()
                .sku("SKU-THIN").name("Fast mover").category("hardware")
                .leadTimeDays(6).reorderPoint(200).currentStock(45).onOrder(0)
                .safetyStock(30.0).build());
        Long steadyId = productRepository.save(Product.builder()
                .sku("SKU-STEADY").name("Slow mover").category("hardware")
                .leadTimeDays(4).reorderPoint(60).currentStock(900).onOrder(0)
                .build()).getId();

        // 1. Upload demand history as an ADMIN, the only role allowed to import data.
        mockMvc.perform(multipart("/api/demand/import").file(csv()).with(users.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsParsed").value(60));

        assertThat(demandHistoryRepository.countBySku("SKU-THIN")).isEqualTo(30);
        assertThat(demandHistoryRepository.countBySku("SKU-STEADY")).isEqualTo(30);

        // 2. The scheduled job runs — the same entry point @Scheduled calls, not the controller.
        stubMlService();
        scheduledScan.run();

        assertThat(alertRepository.countByResolvedFalse()).isEqualTo(1);

        // 3. The alert is queryable, with the filters a dashboard would use.
        mockMvc.perform(get("/api/alerts").param("resolved", "false").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].sku").value("SKU-THIN"))
                .andExpect(jsonPath("$.alerts[0].type").value("STOCKOUT_RISK"))
                .andExpect(jsonPath("$.alerts[0].severity").value("critical"))
                .andExpect(jsonPath("$.counts.open").value(1))
                .andExpect(jsonPath("$.counts.critical").value(1));

        Long alertId = alertRepository.findByResolvedFalse().getFirst().getId();

        // 4. A forecast for the flagged product, ML-backed and combined with its stock position.
        mockMvc.perform(get("/api/products/{id}/forecast", "SKU-THIN")
                        .param("horizonDays", "14")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.currentStock").value(45.0))
                .andExpect(jsonPath("$.product.leadTimeDays").value(6))
                .andExpect(jsonPath("$.history.observations").value(30))
                .andExpect(jsonPath("$.forecast.points.length()").value(14))
                .andExpect(jsonPath("$.forecast.points[0].predicted").value(24.0));

        // The healthy product forecasts too, and never produced an alert.
        mockMvc.perform(get("/api/products/{id}/forecast", steadyId).with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sku").value("SKU-STEADY"));

        // 5. A MANAGER acts on the alert.
        mockMvc.perform(patch("/api/alerts/{id}/resolve", alertId)
                        .contentType("application/json")
                        .content("{\"note\":\"Expedited 400 units\"}")
                        .with(users.manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedBy").value(users.managerUsername()));

        mockMvc.perform(get("/api/alerts").param("resolved", "false").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(0));

        // 6. The next scan still sees the problem, so it opens a fresh alert rather than
        // reviving the resolved one — a manual resolution is not a fix.
        scheduledScan.run();

        mockMvc.perform(get("/api/alerts").param("resolved", "false").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].sku").value("SKU-THIN"))
                .andExpect(jsonPath("$.alerts[0].occurrences").value(1));

        assertThat(alertRepository.findAll()).hasSize(2);
    }

    /** Forecasts for whatever the scan asks about; flags SKU-THIN and clears SKU-STEADY. */
    private void stubMlService() {
        doAnswer(invocation -> {
            BatchForecastRequest request = invocation.getArgument(0);
            return MlResponses.batch(request.items().stream()
                    .map(item -> MlResponses.forecast(item.sku(), 14, 24.0))
                    .toArray(ForecastResponse[]::new));
        }).when(mlServiceClient).forecastBatch(any());

        doReturn(MlResponses.riskScan(
                MlResponses.stockoutRisk("SKU-THIN", Severity.CRITICAL, 91.0),
                MlResponses.ok("SKU-STEADY")))
                .when(mlServiceClient).riskScan(any());

        doAnswer(invocation -> {
            com.supply.demandforecast.dto.MlForecastRequest request = invocation.getArgument(0);
            return MlResponses.forecast(request.sku(), request.horizonDays(), 24.0);
        }).when(mlServiceClient).forecast(any());
    }

    /** 30 days for each of the two SKUs, in the {@code sku,date,quantity} form the import expects. */
    private MockMultipartFile csv() {
        StringBuilder csv = new StringBuilder("sku,date,quantity\n");
        LocalDate start = LocalDate.of(2026, 1, 1);

        for (int i = 0; i < 30; i++) {
            LocalDate day = start.plusDays(i);
            csv.append("SKU-THIN,").append(day).append(',').append(24 + i % 3).append('\n');
            csv.append("SKU-STEADY,").append(day).append(',').append(8 + i % 2).append('\n');
        }

        return new MockMultipartFile("file", "demand.csv", "text/csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}

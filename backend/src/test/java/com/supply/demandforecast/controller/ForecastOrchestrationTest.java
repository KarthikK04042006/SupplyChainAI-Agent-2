package com.supply.demandforecast.controller;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.client.MlServiceException;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.entity.Product;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.ConnectException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/products/{id}/forecast} end to end, with the ML service mocked.
 *
 * <p>The orchestration is what is under test: history has to come out of the database, reach the
 * ML client, and come back combined with the product's stock position. The mock is what makes
 * the middle step observable — {@link #historyFromTheDatabaseReachesTheMlService()} asserts on
 * what the client was handed, which a real service would hide.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class ForecastOrchestrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private DemandHistoryRepository demandHistoryRepository;

    @Autowired
    private WithTestUsers users;

    @MockitoBean
    private MlServiceClient mlServiceClient;

    private Long widgetId;

    @BeforeEach
    void seed() {
        demandHistoryRepository.deleteAll();
        productRepository.deleteAll();
        users.reset();

        widgetId = productRepository.save(Product.builder()
                .sku("WIDGET-1")
                .name("Widget")
                .category("hardware")
                .leadTimeDays(5)
                .reorderPoint(80)
                .currentStock(120)
                .onOrder(40)
                .safetyStock(25.0)
                .build()).getId();

        history("WIDGET-1", 30, 20.0);
    }

    @Test
    void combinesStockPositionHistoryExtentAndMlPrediction() throws Exception {
        when(mlServiceClient.forecast(any()))
                .thenReturn(MlResponses.forecast("WIDGET-1", 14, 22.5));

        mockMvc.perform(get("/api/products/{id}/forecast", widgetId).with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sku").value("WIDGET-1"))
                .andExpect(jsonPath("$.product.currentStock").value(120.0))
                .andExpect(jsonPath("$.product.onOrder").value(40.0))
                .andExpect(jsonPath("$.product.leadTimeDays").value(5))
                .andExpect(jsonPath("$.history.observations").value(30))
                .andExpect(jsonPath("$.history.from").value("2026-01-01"))
                .andExpect(jsonPath("$.history.to").value("2026-01-30"))
                .andExpect(jsonPath("$.history.totalQuantity").value(600.0))
                .andExpect(jsonPath("$.history.averageDailyQuantity").value(20.0))
                .andExpect(jsonPath("$.forecast.sku").value("WIDGET-1"))
                // snake_case, unlike the fields around it: ForecastResponse mirrors the ML
                // contract and its @JsonProperty names apply on the way out as well as in.
                .andExpect(jsonPath("$.forecast.selected_model").value("holt_winters"))
                .andExpect(jsonPath("$.forecast.points.length()").value(14))
                .andExpect(jsonPath("$.forecast.points[0].predicted").value(22.5));
    }

    /** The seam the architecture depends on: the ML service reads no database, so history ships. */
    @Test
    void historyFromTheDatabaseReachesTheMlService() throws Exception {
        when(mlServiceClient.forecast(any()))
                .thenReturn(MlResponses.forecast("WIDGET-1", 14, 22.5));

        mockMvc.perform(get("/api/products/{id}/forecast", widgetId)
                        .param("horizonDays", "14")
                        .with(users.viewer()))
                .andExpect(status().isOk());

        verify(mlServiceClient).forecast(argThat(request -> {
            assertThat(request.sku()).isEqualTo("WIDGET-1");
            assertThat(request.horizonDays()).isEqualTo(14);
            assertThat(request.history()).hasSize(30);
            assertThat(request.history().getFirst().date()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(request.history().getFirst().quantity()).isEqualTo(20.0);
            assertThat(request.history().getLast().date()).isEqualTo(LocalDate.of(2026, 1, 30));
            return true;
        }));
    }

    /**
     * A SKU in the path works as well as an id.
     *
     * <p>Both are natural: a UI holding a product row has the id, an import or a report has the
     * SKU. Worth a test because the resolution order is a deliberate choice, not an accident.
     */
    @Test
    void resolvesABareSkuAsWellAsAnId() throws Exception {
        when(mlServiceClient.forecast(any()))
                .thenReturn(MlResponses.forecast("WIDGET-1", 7, 19.0));

        mockMvc.perform(get("/api/products/{id}/forecast", "WIDGET-1").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.id").value(widgetId))
                .andExpect(jsonPath("$.forecast.points.length()").value(7));
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        mockMvc.perform(get("/api/products/{id}/forecast", "NO-SUCH-SKU").with(users.viewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("NO-SUCH-SKU")));

        mockMvc.perform(get("/api/products/{id}/forecast", "99999").with(users.viewer()))
                .andExpect(status().isNotFound());
    }

    /**
     * Too little history is the request's problem, not the server's.
     *
     * <p>422 rather than 200 with an empty forecast: a caller cannot act on a prediction that
     * does not exist, and an empty success reads as "no demand expected".
     */
    @Test
    void tooLittleHistoryIsUnprocessableAndNeverReachesTheMlService() throws Exception {
        productRepository.save(Product.builder()
                .sku("NEW-2").name("Just listed").leadTimeDays(3).currentStock(10).build());
        history("NEW-2", 1, 4.0);

        mockMvc.perform(get("/api/products/{id}/forecast", "NEW-2").with(users.viewer()))
                .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(mlServiceClient);
    }

    /**
     * An ML outage surfaces as 503, not as a success with nothing in it — the "don't swallow
     * ml-service errors" requirement, seen from the API boundary.
     */
    @Test
    void mlOutageSurfacesAsServiceUnavailable() throws Exception {
        when(mlServiceClient.forecast(any())).thenThrow(new MlServiceException(
                "ML service unreachable after 3 attempts",
                new ConnectException("Connection refused")));

        mockMvc.perform(get("/api/products/{id}/forecast", widgetId).with(users.viewer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("unreachable")));
    }

    /** A 4xx upstream keeps its own body, so the real objection stays readable. */
    @Test
    void mlRejectionKeepsTheUpstreamDetail() throws Exception {
        when(mlServiceClient.forecast(any())).thenThrow(new MlServiceException(
                "ML service rejected the request",
                null,
                HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"detail\":[{\"loc\":[\"body\",\"horizon_days\"],\"msg\":\"must be positive\"}]}"));

        mockMvc.perform(get("/api/products/{id}/forecast", widgetId).with(users.viewer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.upstreamStatus").value(422))
                .andExpect(jsonPath("$.upstreamError").value(containsString("horizon_days")));
    }

    @Test
    void forecastRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/products/{id}/forecast", widgetId))
                .andExpect(status().isUnauthorized());
    }

    /** {@code days} consecutive days of history from 2026-01-01, oldest first. */
    private void history(String sku, int days, double quantity) {
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < days; i++) {
            demandHistoryRepository.save(DemandHistory.builder()
                    .sku(sku)
                    .demandDate(start.plusDays(i))
                    .quantity(quantity)
                    .build());
        }
    }
}

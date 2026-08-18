package com.supply.demandforecast.controller;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.dto.MlForecastRequest;
import com.supply.demandforecast.dto.ReorderRecommendationResponse;
import com.supply.demandforecast.dto.ReorderRequest;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import com.supply.demandforecast.repository.ProductRepository;
import com.supply.demandforecast.support.MlResponses;
import com.supply.demandforecast.support.WithTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/simulate}, the what-if panel that recomputes a reorder recommendation under a
 * hypothetical shock.
 *
 * <p>The ML client is mocked so the perturbation itself is observable. The reorder stub answers a
 * quantity equal to the sum of the forecast it was handed, which turns "did the shock change the
 * inputs" into a plain numeric assertion on the response: a demand multiplier over a window raises
 * that sum, a lead-time override does not touch it. Safety stock is returned fixed, mirroring the
 * real service — it is derived from history variability, which no shock here alters — so the panel
 * can be trusted when it says the buffer held.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class SimulationApiTest {

    /** Seeded history runs 40 days from this date; the last day anchors the forecast's start. */
    private static final LocalDate HISTORY_START = LocalDate.of(2026, 1, 1);
    private static final int HISTORY_DAYS = 40;

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

    @BeforeEach
    void seed() {
        demandHistoryRepository.deleteAll();
        productRepository.deleteAll();
        users.reset();

        productRepository.save(Product.builder()
                .sku("WIDGET-1").name("Widget").category("Components")
                .leadTimeDays(7).currentStock(180).onOrder(50).unitCost(12.50)
                .build());
        history("WIDGET-1", HISTORY_DAYS, 20.0);

        // One data point: below what the recommendation can extrapolate from.
        productRepository.save(Product.builder()
                .sku("SPARSE-3").name("Barely sold").category("Spares")
                .leadTimeDays(30).currentStock(2).onOrder(0)
                .build());
        history("SPARSE-3", 1, 1.0);

        // The reorder stub returns an order quantity equal to the forecast it was given, so a
        // shock that changes the forecast changes the number, and one that does not, does not.
        when(mlServiceClient.reorderRecommendation(any())).thenAnswer(invocation -> {
            ReorderRequest req = invocation.getArgument(0);
            double total = req.forecastDailyDemand().stream().mapToDouble(Double::doubleValue).sum();
            return new ReorderRecommendationResponse(
                    req.sku(),
                    new ReorderRecommendationResponse.Decision(total, 310.0, 95.0, 180.0),
                    List.of(),
                    true,
                    total,
                    "eoq",
                    null);
        });
    }

    /**
     * A demand multiplier over a window lifts the simulated order, while safety stock stays put.
     *
     * <p>Both figures come from one forecast fetch, so the difference is the shock and nothing
     * else. The fixed 30-day forecast (20 units/day) is shocked ×1.5 across its first 14 days:
     * {@code 14×30 + 16×20 = 740} against a {@code 30×20 = 600} baseline. Safety stock is
     * unmoved because the ML service reads it off history variability, which the multiplier does
     * not touch — the one property of this feature most likely to be misunderstood, so it is
     * pinned.
     */
    @Test
    void aDemandMultiplierRaisesTheSimulatedOrderButNotSafetyStock() throws Exception {
        // Fixed 30-day forecast dated from 2026-03-01, independent of the requested horizon, so
        // the arithmetic below does not depend on the horizon-widening formula.
        when(mlServiceClient.forecast(any()))
                .thenReturn(MlResponses.forecast("WIDGET-1", 30, 20.0));

        mockMvc.perform(post("/api/simulate")
                        .with(users.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"WIDGET-1","demandMultiplier":1.5,
                                 "windowStartDate":"2026-03-01","windowDays":14}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("WIDGET-1"))
                .andExpect(jsonPath("$.baseline.decision.order_quantity").value(closeTo(600.0, 1e-6)))
                .andExpect(jsonPath("$.simulated.decision.order_quantity").value(closeTo(740.0, 1e-6)))
                .andExpect(jsonPath("$.baseline.decision.safety_stock").value(closeTo(95.0, 1e-6)))
                .andExpect(jsonPath("$.simulated.decision.safety_stock").value(closeTo(95.0, 1e-6)));
    }

    /**
     * A lead-time override reaches the simulated recommendation only; the baseline keeps the
     * product's own lead time.
     *
     * <p>Captured rather than read off the response: with no demand shock the two forecasts are
     * identical, so the stub returns the same quantity for both, and the only observable
     * difference is the lead time each request carried.
     */
    @Test
    void aLeadTimeOverrideAppliesToTheSimulatedRunAlone() throws Exception {
        when(mlServiceClient.forecast(any()))
                .thenReturn(MlResponses.forecast("WIDGET-1", 14, 20.0));

        mockMvc.perform(post("/api/simulate")
                        .with(users.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"WIDGET-1\",\"leadTimeDaysOverride\":21}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReorderRequest> sent = ArgumentCaptor.forClass(ReorderRequest.class);
        verify(mlServiceClient, times(2)).reorderRecommendation(sent.capture());
        List<ReorderRequest> requests = sent.getAllValues();

        // Baseline first, then simulated — the order simulate() issues them in.
        assertThat(requests.get(0).leadTimeDays()).isEqualTo(7.0);
        assertThat(requests.get(1).leadTimeDays()).isEqualTo(21.0);
    }

    /**
     * The forecast horizon is widened so a window weeks past the forecast's start still lands
     * inside it.
     *
     * <p>Without this, a promotion dated beyond the standard 14-day horizon would fall past the
     * end of the returned series and multiply nothing — the simulation would silently equal the
     * baseline. The window here starts 2026-06-01; the forecast begins the day after the seeded
     * history ends (2026-02-10), so the horizon must reach at least to the window's end.
     */
    @Test
    void widensTheForecastHorizonToReachAFarFutureWindow() throws Exception {
        when(mlServiceClient.forecast(any()))
                .thenReturn(MlResponses.forecast("WIDGET-1", 30, 20.0));

        mockMvc.perform(post("/api/simulate")
                        .with(users.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"WIDGET-1","demandMultiplier":2.0,
                                 "windowStartDate":"2026-06-01","windowDays":7}"""))
                .andExpect(status().isOk());

        LocalDate forecastStart = HISTORY_START.plusDays(HISTORY_DAYS); // day after the last day
        long minimumHorizon =
                java.time.temporal.ChronoUnit.DAYS.between(forecastStart, LocalDate.of(2026, 6, 1)) + 7;

        ArgumentCaptor<MlForecastRequest> forecasted =
                ArgumentCaptor.forClass(MlForecastRequest.class);
        verify(mlServiceClient).forecast(forecasted.capture());
        assertThat(forecasted.getValue().horizonDays())
                .isGreaterThan(14)
                .isGreaterThanOrEqualTo((int) minimumHorizon)
                .isLessThanOrEqualTo(365);
    }

    /** A body describing no shock at all is a 400, raised before any ML call. */
    @Test
    void anEmptyShockBodyIsRejected() throws Exception {
        mockMvc.perform(post("/api/simulate")
                        .with(users.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"WIDGET-1\"}"))
                .andExpect(status().isBadRequest());

        verify(mlServiceClient, never()).forecast(any());
        verify(mlServiceClient, never()).reorderRecommendation(any());
    }

    /** A demand multiplier without its window is a 400 — the two travel together. */
    @Test
    void aMultiplierWithoutAWindowIsRejected() throws Exception {
        mockMvc.perform(post("/api/simulate")
                        .with(users.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"WIDGET-1\",\"demandMultiplier\":1.5}"))
                .andExpect(status().isBadRequest());

        verify(mlServiceClient, never()).reorderRecommendation(any());
    }

    @Test
    void anUnknownProductIsNotFound() throws Exception {
        mockMvc.perform(post("/api/simulate")
                        .with(users.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"NO-SUCH-SKU\",\"leadTimeDaysOverride\":10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    /** Too little history is a 422, raised before any ML call — as on the reorder endpoint. */
    @Test
    void tooLittleHistoryIsUnprocessable() throws Exception {
        mockMvc.perform(post("/api/simulate")
                        .with(users.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SPARSE-3\",\"leadTimeDaysOverride\":10}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Insufficient demand history"));

        verify(mlServiceClient, never()).reorderRecommendation(any());
    }

    /**
     * A read despite being a POST: anonymous is refused, but a VIEWER token is enough.
     *
     * <p>The gate is explicit in {@code SecurityConfig} because a POST does not match the blanket
     * GET-is-a-read rule and would otherwise fall into {@code denyAll()}. Pinning it here means a
     * refactor of that config that drops the rule fails loudly rather than silently locking every
     * role out of the panel.
     */
    @Test
    void isAReadThatAnyAuthenticatedRoleMayRun() throws Exception {
        when(mlServiceClient.forecast(any()))
                .thenReturn(MlResponses.forecast("WIDGET-1", 14, 20.0));

        String body = "{\"sku\":\"WIDGET-1\",\"leadTimeDaysOverride\":21}";

        mockMvc.perform(post("/api/simulate")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        for (var user : List.of(users.viewer(), users.manager(), users.admin())) {
            mockMvc.perform(post("/api/simulate")
                            .with(user)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
    }

    /** {@code days} consecutive days of history from {@link #HISTORY_START}, oldest first. */
    private void history(String sku, int days, double quantity) {
        for (int i = 0; i < days; i++) {
            demandHistoryRepository.save(DemandHistory.builder()
                    .sku(sku).demandDate(HISTORY_START.plusDays(i)).quantity(quantity).build());
        }
    }
}

package com.supply.demandforecast.controller;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.client.MlServiceException;
import com.supply.demandforecast.dto.ExplainRequest;
import com.supply.demandforecast.dto.MlForecastRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/products/{id}/reorder-recommendation} and {@code /explain}, the two panels on the
 * product page that call the ML service directly.
 *
 * <p>The ML client is mocked, so what is under test is everything around the call: which stock
 * position and history get sent, whether costs are included, and how each failure mode reaches
 * the page. The ML service's own arithmetic is not this suite's business — {@code MlContractTest}
 * covers the wire format.
 *
 * <p>The recommendation runs a forecast first, so its tests stub {@code forecast} as well. That is
 * not incidental: a recommendation built on a flat historical mean would ignore the trend, and
 * asserting the forecast's numbers reach the reorder request is how that stays true. {@code
 * /explain} does not forecast — it sends the recorded history straight to the ML service — so its
 * tests deliberately leave {@code forecast} unstubbed, and would fail if that changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class ReplenishmentApiTest {

    /** {@code inventory.scan.horizon-days} in the test profile, which sizes the forecast. */
    private static final int HORIZON_DAYS = 14;

    /** {@code inventory.scan.review-period-days}, sent along so safety stock covers the gap. */
    private static final double REVIEW_PERIOD_DAYS = 7.0;

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

        // Priced, so the recommendation goes the EOQ route.
        productRepository.save(Product.builder()
                .sku("WIDGET-1").name("Widget").category("Components")
                .leadTimeDays(7).currentStock(180).onOrder(50).unitCost(12.50)
                .build());
        history("WIDGET-1", 30, 20.0);

        // Unpriced: the same call, minus the costs block.
        productRepository.save(Product.builder()
                .sku("UNPRICED-2").name("Unpriced part").category("Spares")
                .leadTimeDays(21).currentStock(40).onOrder(0)
                .build());
        history("UNPRICED-2", 30, 5.0);

        // One data point, which is below what either endpoint can work from.
        productRepository.save(Product.builder()
                .sku("SPARSE-3").name("Barely sold").category("Spares")
                .leadTimeDays(30).currentStock(2).onOrder(0)
                .build());
        history("SPARSE-3", 1, 1.0);
    }

    /* Reorder recommendation ------------------------------------------------ */

    /**
     * The decision and every intermediate reach the page.
     *
     * <p>The intermediates are the panel's "why": a response that carried the order quantity
     * without the formulas behind it would leave a planner a number to take on trust.
     */
    @Test
    void returnsTheDecisionWithTheStepsBehindIt() throws Exception {
        when(mlServiceClient.forecast(any())).thenReturn(
                MlResponses.forecast("WIDGET-1", HORIZON_DAYS, 21.5));
        when(mlServiceClient.reorderRecommendation(any())).thenReturn(
                MlResponses.reorder("WIDGET-1", 262.0));

        mockMvc.perform(get("/api/products/{id}/reorder-recommendation", "WIDGET-1")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("WIDGET-1"))
                .andExpect(jsonPath("$.decision.order_quantity").value(closeTo(262.0, 1e-6)))
                .andExpect(jsonPath("$.decision.reorder_point").value(closeTo(310.0, 1e-6)))
                .andExpect(jsonPath("$.decision.safety_stock").value(closeTo(95.0, 1e-6)))
                .andExpect(jsonPath("$.decision.inventory_position").value(closeTo(180.0, 1e-6)))
                .andExpect(jsonPath("$.suggested_order").value(true))
                .andExpect(jsonPath("$.eoq").value(closeTo(262.0, 1e-6)))
                .andExpect(jsonPath("$.ordering_strategy").value("eoq"))
                .andExpect(jsonPath("$.intermediates.length()").value(3))
                .andExpect(jsonPath("$.intermediates[1].name").value("safety_stock"))
                .andExpect(jsonPath("$.intermediates[1].formula")
                        .value(containsString("√(lead_time + review_period)")))
                .andExpect(jsonPath("$.intermediates[1].unit").value("units"));
    }

    /**
     * The request carries the product's real position, its costs, and the forecast — not a mean.
     *
     * <p>Captured rather than asserted through the response, because none of it is visible there.
     * A regression that sent an empty forecast list or dropped the review period would still
     * return a perfectly plausible recommendation, computed from the wrong inputs.
     */
    @Test
    void sendsTheStockPositionCostsAndForecastToTheMlService() throws Exception {
        when(mlServiceClient.forecast(any())).thenReturn(
                MlResponses.forecast("WIDGET-1", HORIZON_DAYS, 21.5));
        when(mlServiceClient.reorderRecommendation(any())).thenReturn(
                MlResponses.reorder("WIDGET-1", 262.0));

        mockMvc.perform(get("/api/products/{id}/reorder-recommendation", "WIDGET-1")
                        .with(users.viewer()))
                .andExpect(status().isOk());

        ArgumentCaptor<ReorderRequest> sent = ArgumentCaptor.forClass(ReorderRequest.class);
        verify(mlServiceClient).reorderRecommendation(sent.capture());
        ReorderRequest request = sent.getValue();

        assertThat(request.sku()).isEqualTo("WIDGET-1");
        assertThat(request.currentStock()).isEqualTo(180.0);
        assertThat(request.onOrder()).isEqualTo(50.0);
        assertThat(request.leadTimeDays()).isEqualTo(7.0);
        assertThat(request.reviewPeriodDays()).isEqualTo(REVIEW_PERIOD_DAYS);

        // The forecast, one value per day, rather than a single averaged figure.
        assertThat(request.forecastDailyDemand())
                .hasSize(HORIZON_DAYS)
                .allMatch(value -> value == 21.5);

        // The history goes too, because safety stock is a function of demand variability and
        // the forecast alone carries none.
        assertThat(request.demandHistory()).hasSize(30);
        assertThat(request.demandHistory().get(0).date()).isEqualTo(LocalDate.of(2026, 1, 1));

        assertThat(request.costs()).isNotNull();
        assertThat(request.costs().unitCost()).isEqualTo(12.50);
        assertThat(request.costs().orderingCost()).isEqualTo(50.0);
        assertThat(request.costs().holdingCostRate()).isEqualTo(0.25);

        // The forecast behind it runs over the configured scan horizon, not a caller's choice:
        // the recommendation has no horizon parameter, so this is where that number is decided.
        ArgumentCaptor<MlForecastRequest> forecasted =
                ArgumentCaptor.forClass(MlForecastRequest.class);
        verify(mlServiceClient).forecast(forecasted.capture());
        assertThat(forecasted.getValue().horizonDays()).isEqualTo(HORIZON_DAYS);
        assertThat(forecasted.getValue().sku()).isEqualTo("WIDGET-1");
    }

    /**
     * An unpriced product is sent without costs, not with an invented unit cost.
     *
     * <p>Which is what makes the ML service fall back to its coverage heuristic and say so in
     * {@code method_note}. Supplying a placeholder price would get an EOQ figure back that looked
     * exactly as authoritative as a real one.
     */
    @Test
    void anUnpricedProductIsSentWithoutCostsAndComesBackOnTheCoverageHeuristic() throws Exception {
        when(mlServiceClient.forecast(any())).thenReturn(
                MlResponses.forecast("UNPRICED-2", HORIZON_DAYS, 5.0));
        when(mlServiceClient.reorderRecommendation(any())).thenReturn(
                MlResponses.reorderWithoutCosts("UNPRICED-2"));

        mockMvc.perform(get("/api/products/{id}/reorder-recommendation", "UNPRICED-2")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eoq").doesNotExist())
                .andExpect(jsonPath("$.ordering_strategy").value("coverage"))
                .andExpect(jsonPath("$.method_note").value(containsString("No unit cost")));

        ArgumentCaptor<ReorderRequest> sent = ArgumentCaptor.forClass(ReorderRequest.class);
        verify(mlServiceClient).reorderRecommendation(sent.capture());
        assertThat(sent.getValue().costs()).isNull();
        // The product's own lead time, not the batch default.
        assertThat(sent.getValue().leadTimeDays()).isEqualTo(21.0);
    }

    /**
     * Too little history is a 422 raised before any ML call.
     *
     * <p>Sending one data point and letting the ML service object would spend a round trip to
     * learn something already known here, and would report the refusal as an upstream failure.
     */
    @Test
    void tooLittleHistoryIsRefusedWithoutCallingTheMlService() throws Exception {
        mockMvc.perform(get("/api/products/{id}/reorder-recommendation", "SPARSE-3")
                        .with(users.viewer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Insufficient demand history"));

        verify(mlServiceClient, never()).reorderRecommendation(any());
        verify(mlServiceClient, never()).forecast(any());
    }

    @Test
    void anUnknownProductIsNotFound() throws Exception {
        mockMvc.perform(get("/api/products/{id}/reorder-recommendation", "NO-SUCH-SKU")
                        .with(users.viewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));

        verify(mlServiceClient, never()).reorderRecommendation(any());
    }

    /**
     * An ML outage is a 503, because the backend itself is fine and a retry may work.
     *
     * <p>The panel fetches independently of the chart for this reason: a failure here should cost
     * the reorder box, not the page.
     */
    @Test
    void anUnreachableMlServiceIsReportedAsUnavailable() throws Exception {
        when(mlServiceClient.forecast(any())).thenReturn(
                MlResponses.forecast("WIDGET-1", HORIZON_DAYS, 21.5));
        when(mlServiceClient.reorderRecommendation(any())).thenThrow(
                new MlServiceException("ML service unreachable for the reorder recommendation",
                        new RuntimeException("connect timed out")));

        mockMvc.perform(get("/api/products/{id}/reorder-recommendation", "WIDGET-1")
                        .with(users.viewer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("ML service unavailable"))
                .andExpect(jsonPath("$.upstreamStatus").doesNotExist());
    }

    /* Explain --------------------------------------------------------------- */

    @Test
    void returnsShapContributionsForTheFirstForecastDay() throws Exception {
        when(mlServiceClient.explain(eq("WIDGET-1"), any())).thenReturn(
                MlResponses.explanation("WIDGET-1"));

        mockMvc.perform(get("/api/products/{id}/explain", "WIDGET-1").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("WIDGET-1"))
                .andExpect(jsonPath("$.explained_date").value("2026-03-01"))
                .andExpect(jsonPath("$.step").value(1))
                .andExpect(jsonPath("$.predicted").value(closeTo(118.4, 1e-6)))
                .andExpect(jsonPath("$.base_value").value(closeTo(100.0, 1e-6)))
                .andExpect(jsonPath("$.model_used").value("xgboost"))
                .andExpect(jsonPath("$.contributions.length()").value(3))
                .andExpect(jsonPath("$.contributions[0].feature").value("demand 7 day(s) ago"))
                .andExpect(jsonPath("$.contributions[0].direction").value("increase"))
                .andExpect(jsonPath("$.contributions[0].abs_contribution")
                        .value(closeTo(12.6, 1e-6)))
                .andExpect(jsonPath("$.contributions[2].direction").value("decrease"));
    }

    /** Explain is asked for the SKU and the recorded history, whichever identifier came in. */
    @Test
    void resolvesTheProductBySkuOrIdBeforeAsking() throws Exception {
        when(mlServiceClient.explain(eq("WIDGET-1"), any())).thenReturn(
                MlResponses.explanation("WIDGET-1"));
        Long id = productRepository.findBySku("WIDGET-1").orElseThrow().getId();

        mockMvc.perform(get("/api/products/{id}/explain", id).with(users.viewer()))
                .andExpect(status().isOk());

        ArgumentCaptor<ExplainRequest> sent = ArgumentCaptor.forClass(ExplainRequest.class);
        verify(mlServiceClient).explain(eq("WIDGET-1"), sent.capture());
        ExplainRequest request = sent.getValue();

        assertThat(request.horizonDays()).isEqualTo(HORIZON_DAYS);
        assertThat(request.step()).isEqualTo(1);
        assertThat(request.history()).hasSize(30);
        // Oldest first, so the last point is the most recent day — the one being explained.
        assertThat(request.history().get(29).date()).isEqualTo(LocalDate.of(2026, 1, 30));
    }

    /**
     * A model without SHAP support answers 200 with a note and no contributions.
     *
     * <p>Not an error: the forecast is fine, only unexplainable. The page hides the panel and
     * prints the note, which is why the note has to survive the round trip.
     */
    @Test
    void aModelWithoutShapSupportReturnsANoteRatherThanContributions() throws Exception {
        when(mlServiceClient.explain(eq("UNPRICED-2"), any())).thenReturn(
                MlResponses.noExplanation("UNPRICED-2",
                        "SHAP is only available for the tree model; holt_winters was selected"));

        mockMvc.perform(get("/api/products/{id}/explain", "UNPRICED-2").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contributions.length()").value(0))
                .andExpect(jsonPath("$.note").value(containsString("holt_winters")));
    }

    /**
     * A rejection from the ML service is a 422 carrying its reason, not a blanket 503.
     *
     * <p>The distinction is what the caller does next. 503 invites a retry; this will fail the
     * same way every time, and {@code upstreamError} is the only thing that says why.
     */
    @Test
    void anMlRejectionSurfacesAs422WithTheUpstreamReason() throws Exception {
        when(mlServiceClient.explain(eq("WIDGET-1"), any())).thenThrow(
                new MlServiceException(
                        "ML service rejected the explanation for SKU WIDGET-1",
                        new RuntimeException("422"),
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "{\"detail\":\"SHAP requires the xgboost model\"}"));

        mockMvc.perform(get("/api/products/{id}/explain", "WIDGET-1").with(users.viewer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("ML service rejected the request"))
                .andExpect(jsonPath("$.upstreamStatus").value(422))
                .andExpect(jsonPath("$.upstreamError").value(containsString("xgboost")));
    }

    @Test
    void explainAlsoRefusesTooLittleHistoryBeforeCallingOut() throws Exception {
        mockMvc.perform(get("/api/products/{id}/explain", "SPARSE-3").with(users.viewer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Insufficient demand history"));

        verify(mlServiceClient, never()).explain(any(), any());
    }

    /* Role gating ----------------------------------------------------------- */

    /**
     * Both are reads, so VIEWER is enough — and neither is reachable without a token.
     *
     * <p>Worth pinning rather than assuming: they are the only {@code GET}s in the API that spend
     * an ML call, so there is a standing temptation to restrict them by cost. The product page is
     * what every role opens, and gating them would empty it for most of its users.
     */
    @Test
    void bothEndpointsRequireAuthenticationButOnlyTheViewerRole() throws Exception {
        when(mlServiceClient.forecast(any())).thenReturn(
                MlResponses.forecast("WIDGET-1", HORIZON_DAYS, 21.5));
        when(mlServiceClient.reorderRecommendation(any())).thenReturn(
                MlResponses.reorder("WIDGET-1", 262.0));
        when(mlServiceClient.explain(eq("WIDGET-1"), any())).thenReturn(
                MlResponses.explanation("WIDGET-1"));

        for (String path : new String[] {
                "/api/products/WIDGET-1/reorder-recommendation",
                "/api/products/WIDGET-1/explain"}) {

            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).with(users.viewer())).andExpect(status().isOk());
            mockMvc.perform(get(path).with(users.manager())).andExpect(status().isOk());
            mockMvc.perform(get(path).with(users.admin())).andExpect(status().isOk());
        }
    }

    /** Consecutive days from 2026-01-01, which is what the captured requests are checked against. */
    private void history(String sku, int days, double quantity) {
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < days; i++) {
            demandHistoryRepository.save(DemandHistory.builder()
                    .sku(sku).demandDate(start.plusDays(i)).quantity(quantity).build());
        }
    }
}

package com.supply.demandforecast.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.dto.DashboardSummary;
import com.supply.demandforecast.entity.Alert;
import com.supply.demandforecast.entity.ForecastAccuracy;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.AlertRepository;
import com.supply.demandforecast.repository.ForecastAccuracyRepository;
import com.supply.demandforecast.repository.ProductRepository;
import com.supply.demandforecast.support.WithTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/dashboard/summary}, the four KPI cards.
 *
 * <p>What is really under test is the arithmetic of partial data. Every one of these figures can
 * be computed over a catalogue that is only half measured, and each has a wrong answer that looks
 * perfectly reasonable on a card: an unpriced SKU valued at zero, an unscored one averaged in as
 * 0% accurate, a resolved alert still counted as open. So the assertions are mostly about what
 * the numbers exclude and whether the response says how much it left out.
 *
 * <p>No ML mock — nothing here calls the forecast service. Accuracy is read from what a past scan
 * persisted, which is the point of storing it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class DashboardSummaryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private ForecastAccuracyRepository forecastAccuracyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WithTestUsers users;

    @BeforeEach
    void seed() {
        alertRepository.deleteAll();
        forecastAccuracyRepository.deleteAll();
        productRepository.deleteAll();
        users.reset();

        // 100 × 2.50 plus 40 × 10.00 is 650 of priced stock. UNPRICED-3 holds the most units of
        // the three and contributes nothing, which is the case the coverage counts exist for.
        product("PRICED-1", 100, 2.50);
        product("PRICED-2", 40, 10.00);
        product("UNPRICED-3", 999, null);
    }

    /** Every card at once, over a catalogue that is only partly priced and partly scored. */
    @Test
    void reportsEveryKpiWithTheSampleBehindIt() throws Exception {
        openAlert("PRICED-1", AlertType.STOCKOUT_RISK, Severity.CRITICAL);
        openAlert("PRICED-1", AlertType.OVERSTOCK, Severity.HIGH);
        openAlert("PRICED-2", AlertType.DEMAND_ANOMALY, Severity.HIGH);
        openAlert("PRICED-2", AlertType.STOCKOUT_RISK, Severity.MEDIUM);
        scored("PRICED-1", 8.0);
        scored("PRICED-2", 12.0);

        mockMvc.perform(get("/api/dashboard/summary").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSkus").value(3))
                .andExpect(jsonPath("$.alerts.open").value(4))
                .andExpect(jsonPath("$.alerts.bySeverity.CRITICAL").value(1))
                .andExpect(jsonPath("$.alerts.bySeverity.HIGH").value(2))
                .andExpect(jsonPath("$.alerts.bySeverity.MEDIUM").value(1))
                .andExpect(jsonPath("$.alerts.bySeverity.LOW").value(0))
                .andExpect(jsonPath("$.inventoryValue.amount").value(closeTo(650.0, 1e-6)))
                .andExpect(jsonPath("$.inventoryValue.skusPriced").value(2))
                .andExpect(jsonPath("$.inventoryValue.skusUnpriced").value(1))
                // Mean MAPE of 10 across two SKUs.
                .andExpect(jsonPath("$.forecastAccuracy.averagePercent")
                        .value(closeTo(90.0, 1e-6)))
                .andExpect(jsonPath("$.forecastAccuracy.skusScored").value(2))
                .andExpect(jsonPath("$.forecastAccuracy.lastScoredAt").exists())
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    /**
     * A severity with nothing open is present at zero, not missing.
     *
     * <p>The overview renders a fixed row of four counts. An absent key would render as an empty
     * card, which reads as a page that failed rather than as good news.
     */
    @Test
    void everySeverityHasACountEvenWithNoAlertsAtAll() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.open").value(0))
                .andExpect(jsonPath("$.alerts.bySeverity.length()").value(4))
                .andExpect(jsonPath("$.alerts.bySeverity.LOW").value(0))
                .andExpect(jsonPath("$.alerts.bySeverity.MEDIUM").value(0))
                .andExpect(jsonPath("$.alerts.bySeverity.HIGH").value(0))
                .andExpect(jsonPath("$.alerts.bySeverity.CRITICAL").value(0));
    }

    /**
     * Resolved alerts leave the count.
     *
     * <p>They are kept as an audit trail rather than deleted, so "how many alerts exist" and "how
     * many need attention" diverge permanently — and the card is asking the second question.
     */
    @Test
    void resolvedAlertsAreNotCountedAsOpen() throws Exception {
        openAlert("PRICED-1", AlertType.STOCKOUT_RISK, Severity.CRITICAL);
        Alert settled = openAlert("PRICED-2", AlertType.OVERSTOCK, Severity.HIGH);
        settled.resolve(users.managerUsername(), "Transferred to the east");
        alertRepository.save(settled);

        mockMvc.perform(get("/api/dashboard/summary").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.open").value(1))
                .andExpect(jsonPath("$.alerts.bySeverity.CRITICAL").value(1))
                .andExpect(jsonPath("$.alerts.bySeverity.HIGH").value(0));
    }

    /**
     * Nothing scored yet reads as null, not as zero.
     *
     * <p>Zero would claim every forecast was wrong on a system that has simply never run a scan,
     * which is the difference between "no data" and "bad news". The page prints "not yet scored"
     * off the null.
     */
    @Test
    void anUnscoredCatalogueReportsNoAccuracyRatherThanZero() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forecastAccuracy.averagePercent").doesNotExist())
                .andExpect(jsonPath("$.forecastAccuracy.skusScored").value(0))
                .andExpect(jsonPath("$.forecastAccuracy.lastScoredAt").doesNotExist());
    }

    /**
     * A SKU forecast but never scored is left out of the average, not averaged in as zero.
     *
     * <p>Which is why the accuracy figure ships with its own sample size: 90% over two SKUs from
     * a catalogue of three is a different claim from 90% over all three, and the card can only
     * tell them apart if the count comes with the percentage.
     */
    @Test
    void aSkuWithNoMapeIsExcludedFromTheAverageAndFromItsSampleSize() throws Exception {
        scored("PRICED-1", 8.0);
        scored("PRICED-2", 12.0);
        // Forecast, but with too little history for walk-forward validation to score it.
        forecastAccuracyRepository.save(ForecastAccuracy.builder()
                .sku("UNPRICED-3").selectedModel("naive").scoredAt(Instant.now()).build());

        mockMvc.perform(get("/api/dashboard/summary").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forecastAccuracy.averagePercent")
                        .value(closeTo(90.0, 1e-6)))
                .andExpect(jsonPath("$.forecastAccuracy.skusScored").value(2));
    }

    /**
     * Accuracy floors at zero, because MAPE does not ceiling at 100.
     *
     * <p>A forecast off by four times scores 400, and a card reading -300% accuracy earns a bug
     * report about the arithmetic rather than about the forecast that caused it.
     */
    @Test
    void accuracyIsFlooredAtZeroForAWildlyWrongForecast() throws Exception {
        scored("PRICED-1", 350.0);

        mockMvc.perform(get("/api/dashboard/summary").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forecastAccuracy.averagePercent").value(closeTo(0.0, 1e-6)))
                .andExpect(jsonPath("$.forecastAccuracy.skusScored").value(1));
    }

    /** The timestamp is the most recent scoring, not the oldest and not the time of the read. */
    @Test
    void lastScoredAtIsTheMostRecentScore() throws Exception {
        Instant old = Instant.now().minus(9, ChronoUnit.DAYS);
        forecastAccuracyRepository.save(ForecastAccuracy.builder()
                .sku("PRICED-1").mape(8.0).selectedModel("holt_winters").scoredAt(old).build());
        forecastAccuracyRepository.save(ForecastAccuracy.builder()
                .sku("PRICED-2").mape(12.0).selectedModel("holt_winters")
                .scoredAt(Instant.now()).build());

        // Read back through the record rather than matched as text: how many fractional digits
        // survive H2 and the JSON encoder is not what this test is about.
        DashboardSummary summary = objectMapper.readValue(
                mockMvc.perform(get("/api/dashboard/summary").with(users.viewer()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                DashboardSummary.class);

        assertThat(summary.forecastAccuracy().lastScoredAt())
                .isAfter(old.plus(1, ChronoUnit.DAYS));
    }

    /**
     * An empty catalogue answers with zeros rather than failing.
     *
     * <p>This is the very first request a fresh deployment makes, before anything is uploaded. A
     * 500 here would make a working system look broken on first contact.
     */
    @Test
    void anEmptyCatalogueReturnsZeroesRatherThanAnError() throws Exception {
        productRepository.deleteAll();

        mockMvc.perform(get("/api/dashboard/summary").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSkus").value(0))
                .andExpect(jsonPath("$.inventoryValue.amount").value(closeTo(0.0, 1e-6)))
                .andExpect(jsonPath("$.inventoryValue.skusPriced").value(0))
                .andExpect(jsonPath("$.inventoryValue.skusUnpriced").value(0));
    }

    @Test
    void theSummaryRequiresAuthenticationAndIsReadableByEveryRole() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/dashboard/summary").with(users.viewer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/dashboard/summary").with(users.manager()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/dashboard/summary").with(users.admin()))
                .andExpect(status().isOk());
    }

    /* Helpers -------------------------------------------------------------- */

    private void product(String sku, double currentStock, Double unitCost) {
        productRepository.save(Product.builder()
                .sku(sku).name(sku + " product").category("Components")
                .leadTimeDays(7).currentStock(currentStock).unitCost(unitCost)
                .build());
    }

    private Alert openAlert(String sku, AlertType type, Severity severity) {
        return alertRepository.save(Alert.builder()
                .sku(sku).type(type).severity(severity)
                .message(severity + " " + type + " on " + sku)
                .riskScore(50.0)
                .build());
    }

    private void scored(String sku, double mape) {
        forecastAccuracyRepository.save(ForecastAccuracy.builder()
                .sku(sku).mape(mape).rmse(mape / 2).mae(mape / 3)
                .selectedModel("holt_winters").observations(120)
                .scoredAt(Instant.now())
                .build());
    }
}

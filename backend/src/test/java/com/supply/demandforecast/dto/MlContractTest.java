package com.supply.demandforecast.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.supply.demandforecast.domain.Severity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the hand-mirrored contract with the Python ML service.
 *
 * <p>{@code MlForecastRequest} and {@code ForecastResponse} duplicate the Pydantic models in
 * {@code ml-service/models/schemas.py}. Nothing generates one from the other, so these tests
 * pin the wire format: if a field is renamed on either side, this fails instead of the
 * mismatch surfacing as a 422 at runtime.
 *
 * <p>The mapper below mirrors what Spring Boot auto-configures — {@code JavaTimeModule}
 * registered, timestamp serialization off.
 */
class MlContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void requestSerializesToSnakeCaseFieldsPydanticExpects() throws Exception {
        MlForecastRequest request = MlForecastRequest.of(
                "WIDGET-001", 7,
                List.of(new MlForecastRequest.DemandPoint(LocalDate.of(2026, 7, 1), 12.5)));

        String json = mapper.writeValueAsString(request);

        assertThat(json)
                .contains("\"horizon_days\":7")
                .contains("\"seasonal_periods\":7")
                .contains("\"detect_anomalies\":true")
                .contains("\"sku\":\"WIDGET-001\"")
                .contains("\"date\":\"2026-07-01\"")
                .contains("\"quantity\":12.5");
        // camelCase leaking through would be silently ignored by Pydantic, which does not
        // set extra="forbid" — so the field would fall back to its default, not 422.
        assertThat(json)
                .doesNotContain("horizonDays")
                .doesNotContain("seasonalPeriods")
                .doesNotContain("detectAnomalies")
                .doesNotContain("countryHolidays");
    }

    /**
     * Unset optionals must be omitted, not sent as null.
     *
     * <p>The ML service applies its own defaults for absent fields. An explicit
     * {@code null} overrides them, which for {@code seasonal_periods} means no seasonality
     * where the caller meant "decide for me".
     */
    @Test
    void omitsUnsetOptionalsRatherThanSendingNull() throws Exception {
        MlForecastRequest request = new MlForecastRequest(
                "WIDGET-001", 14, null, null, null,
                List.of(new MlForecastRequest.DemandPoint(LocalDate.of(2026, 7, 1), 3)));

        String json = mapper.writeValueAsString(request);

        assertThat(json).doesNotContain("null");
        assertThat(json).doesNotContain("seasonal_periods").doesNotContain("country_holidays");
        assertThat(json).contains("\"horizon_days\":14");
    }

    @Test
    void deserializesModelSelectionResponse() throws Exception {
        String json = """
                {"sku":"WIDGET-001",
                 "selected_model":"holt-winters-additive(m=7)",
                 "selection_reason":"best walk-forward MAPE over 3 folds",
                 "granularity":"daily",
                 "observations":180,
                 "seasonality_detected":true,
                 "scores":[{"model":"holt-winters-additive(m=7)","mape":8.4,"rmse":1.9,
                            "mae":1.5,"folds":3,"fitted":true,"error":null},
                           {"model":"prophet","mape":null,"rmse":null,"mae":null,
                            "folds":null,"fitted":false,"error":"prophet not installed"}],
                 "points":[{"date":"2026-02-05","predicted":5.0,"lower":4.1,"upper":5.9}],
                 "warnings":[{"code":"recent_spike","message":"last 7 days ran hot",
                              "severity":"MEDIUM"}],
                 "anomalies":[{"date":"2026-01-20","kind":"spike","observed":40.0,
                               "expected":6.0,"deviation":34.0,"z_score":4.05,
                               "severity":"HIGH"}]}
                """;

        ForecastResponse response = mapper.readValue(json, ForecastResponse.class);

        assertThat(response.sku()).isEqualTo("WIDGET-001");
        assertThat(response.selectedModel()).isEqualTo("holt-winters-additive(m=7)");
        assertThat(response.selectionReason()).contains("walk-forward");
        assertThat(response.granularity()).isEqualTo("daily");
        assertThat(response.observations()).isEqualTo(180);
        assertThat(response.seasonalityDetected()).isTrue();

        assertThat(response.scores()).hasSize(2);
        assertThat(response.scores().getFirst().mape()).isEqualTo(8.4);
        // A candidate that failed to fit reports its error and null metrics, and is still
        // listed so the comparison stays auditable.
        assertThat(response.scores().getLast().fitted()).isFalse();
        assertThat(response.scores().getLast().mae()).isNull();
        assertThat(response.scores().getLast().error()).isEqualTo("prophet not installed");

        assertThat(response.points()).hasSize(1);
        assertThat(response.points().getFirst().date()).isEqualTo(LocalDate.of(2026, 2, 5));
        assertThat(response.points().getFirst().predicted()).isEqualTo(5.0);
        assertThat(response.points().getFirst().lower()).isEqualTo(4.1);

        assertThat(response.warnings()).hasSize(1);
        assertThat(response.warnings().getFirst().severity()).isEqualTo(Severity.MEDIUM);

        assertThat(response.anomalies()).hasSize(1);
        assertThat(response.anomalies().getFirst().kind()).isEqualTo("spike");
        assertThat(response.anomalies().getFirst().zScore()).isEqualTo(4.05);
        assertThat(response.anomalies().getFirst().severity()).isEqualTo(Severity.HIGH);
    }

    /**
     * Absent list fields read back as empty, not null.
     *
     * <p>{@code warnings} and {@code anomalies} have Pydantic defaults, so a clean forecast
     * omits them. Callers iterate these without a null guard.
     */
    @Test
    void absentListsReadBackEmpty() throws Exception {
        String json = """
                {"sku":"A","selected_model":"naive-last-value",
                 "selection_reason":"insufficient history for backtesting",
                 "granularity":"daily","observations":2,"seasonality_detected":false,
                 "scores":[],"points":[]}
                """;

        ForecastResponse response = mapper.readValue(json, ForecastResponse.class);

        assertThat(response.warnings()).isEmpty();
        assertThat(response.anomalies()).isEmpty();
        assertThat(response.scores()).isEmpty();
        assertThat(response.points()).isEmpty();
    }

    /**
     * An added response field must not break existing calls.
     *
     * <p>The ML service is versioned separately; a rename still fails loudly above, which is
     * where that failure belongs.
     */
    @Test
    void toleratesFieldsTheMlServiceAddsLater() throws Exception {
        String json = """
                {"sku":"A","selected_model":"naive-last-value","selection_reason":"fallback",
                 "granularity":"daily","observations":2,"seasonality_detected":false,
                 "scores":[],"points":[],"a_field_from_a_future_version":{"nested":1}}
                """;

        ForecastResponse response = mapper.readValue(json, ForecastResponse.class);

        assertThat(response.sku()).isEqualTo("A");
    }
}

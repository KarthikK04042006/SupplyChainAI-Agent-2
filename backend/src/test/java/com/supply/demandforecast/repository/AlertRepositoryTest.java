package com.supply.demandforecast.repository;

import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.entity.Alert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The alert query surface against a real database.
 *
 * <p>Worth its own test rather than folding into the controller tests: the severity ordering
 * is a JPQL {@code case} expression over enum literals and the filters are null-guarded
 * parameters, neither of which is exercised by compiling. The unique index is likewise a
 * database-level claim that only a database can confirm.
 */
@DataJpaTest
@ActiveProfiles("test")
class AlertRepositoryTest {

    @Autowired
    private AlertRepository alertRepository;

    @BeforeEach
    void seed() {
        save("WIDGET-001", AlertType.STOCKOUT_RISK, Severity.MEDIUM, 40);
        save("GADGET-002", AlertType.STOCKOUT_RISK, Severity.CRITICAL, 95);
        save("GIZMO-003", AlertType.OVERSTOCK, Severity.LOW, 10);
        save("DOODAD-004", AlertType.STOCKOUT_RISK, Severity.HIGH, 70);
        save("SLOWMOVE-005", AlertType.OVERSTOCK, Severity.HIGH, 60);
    }

    @Test
    void unfilteredSearchRanksBySeverityThenScore() {
        Page<Alert> page = alertRepository.search(null, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(Alert::getSeverity)
                .containsExactly(Severity.CRITICAL, Severity.HIGH, Severity.HIGH,
                        Severity.MEDIUM, Severity.LOW);

        // Both HIGHs tie on severity, so risk score breaks it — descending.
        assertThat(page.getContent().subList(1, 3))
                .extracting(Alert::getSku)
                .containsExactly("DOODAD-004", "SLOWMOVE-005");
    }

    @Test
    void eachFilterNarrowsIndependentlyAndTheyCompose() {
        assertThat(alertRepository.search(Severity.HIGH, null, null, null, page()))
                .extracting(Alert::getSku)
                .containsExactly("DOODAD-004", "SLOWMOVE-005");

        assertThat(alertRepository.search(null, AlertType.OVERSTOCK, null, null, page()))
                .extracting(Alert::getSku)
                .containsExactly("SLOWMOVE-005", "GIZMO-003");

        assertThat(alertRepository.search(Severity.HIGH, AlertType.OVERSTOCK, null, null, page()))
                .extracting(Alert::getSku)
                .containsExactly("SLOWMOVE-005");

        assertThat(alertRepository.search(null, null, null, "WIDGET-001", page()))
                .extracting(Alert::getSku)
                .containsExactly("WIDGET-001");
    }

    @Test
    void resolvedFilterDistinguishesUnsetFromFalse() {
        Alert widget = alertRepository.findBySkuAndTypeAndResolvedFalse(
                "WIDGET-001", AlertType.STOCKOUT_RISK).orElseThrow();
        widget.resolve("manager", "restocked");
        alertRepository.saveAndFlush(widget);

        // null means "either", which is not the same as false.
        assertThat(alertRepository.search(null, null, null, null, page())).hasSize(5);
        assertThat(alertRepository.search(null, null, false, null, page())).hasSize(4);
        assertThat(alertRepository.search(null, null, true, null, page()))
                .extracting(Alert::getSku)
                .containsExactly("WIDGET-001");
    }

    @Test
    void aSecondOpenAlertForTheSameSkuAndTypeIsRejected() {
        assertThatThrownBy(() -> {
            save("WIDGET-001", AlertType.STOCKOUT_RISK, Severity.CRITICAL, 99);
            alertRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void resolvingFreesTheSlotSoTheProblemCanRecur() {
        Alert open = alertRepository.findBySkuAndTypeAndResolvedFalse(
                "WIDGET-001", AlertType.STOCKOUT_RISK).orElseThrow();
        open.resolve("manager", "restocked");
        alertRepository.saveAndFlush(open);

        save("WIDGET-001", AlertType.STOCKOUT_RISK, Severity.HIGH, 80);
        alertRepository.flush();

        assertThat(alertRepository.search(null, AlertType.STOCKOUT_RISK, null, "WIDGET-001", page()))
                .hasSize(2);
        assertThat(alertRepository.findBySkuAndTypeAndResolvedFalse(
                "WIDGET-001", AlertType.STOCKOUT_RISK))
                .get()
                .extracting(Alert::getSeverity)
                .isEqualTo(Severity.HIGH);
    }

    @Test
    void restatingBumpsOccurrencesWithoutOpeningARow() {
        Alert open = alertRepository.findBySkuAndTypeAndResolvedFalse(
                "GADGET-002", AlertType.STOCKOUT_RISK).orElseThrow();
        open.restate(Severity.MEDIUM, "eased off", 30, 9.0, 120.0);
        alertRepository.saveAndFlush(open);

        assertThat(alertRepository.count()).isEqualTo(5);
        assertThat(open.getOccurrences()).isEqualTo(2);
        assertThat(open.getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void openCountsFeedTheScanSummary() {
        assertThat(alertRepository.countByResolvedFalse()).isEqualTo(5);
        assertThat(alertRepository.countByResolvedFalseAndSeverity(Severity.HIGH)).isEqualTo(2);
        assertThat(alertRepository.findByResolvedFalse()).hasSize(5);
    }

    private static PageRequest page() {
        return PageRequest.of(0, 10);
    }

    private void save(String sku, AlertType type, Severity severity, double riskScore) {
        alertRepository.save(Alert.builder()
                .sku(sku)
                .type(type)
                .severity(severity)
                .message(severity + " " + type + " on " + sku)
                .riskScore(riskScore)
                .daysOfSupply(3.0)
                .inventoryPosition(100.0)
                .build());
    }
}

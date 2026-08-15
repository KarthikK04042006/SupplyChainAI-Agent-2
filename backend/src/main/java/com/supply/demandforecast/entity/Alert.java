package com.supply.demandforecast.entity;

import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A persisted warning about one SKU.
 *
 * <p>Keyed by SKU rather than by {@link Product}'s surrogate id, matching {@link
 * DemandHistory} — a scan can flag a SKU whose product record was never created.
 *
 * <p><strong>One open alert per (sku, type).</strong> The scheduled scan runs repeatedly over
 * the same catalogue, so writing a new row each pass would bury a genuinely new problem under
 * hundreds of restatements of an old one. Instead the scan updates the open alert in place and
 * bumps {@link #occurrences}, which turns "how long has this been wrong, and how many scans
 * have said so" into a readable field. The unique index enforces this at the database level
 * rather than trusting every writer to check first.
 *
 * <p>Resolving does not delete: a resolved row is the audit trail of what was wrong and who
 * decided it no longer was. It also frees the (sku, type) slot, so the same problem recurring
 * later opens a fresh alert instead of silently reviving a closed one. See {@link #activeKey}
 * for how "the slot" is expressed to the database.
 */
@Entity
@Table(
        name = "alerts",
        indexes = {
                @Index(name = "idx_alerts_sku", columnList = "sku"),
                @Index(name = "idx_alerts_resolved", columnList = "resolved"),
                @Index(
                        name = "uk_alerts_open_per_sku_type",
                        columnList = "sku, type, active_key",
                        unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, length = 64)
    private String sku;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AlertType type;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    /** Plain-language justification, taken from the ML service's {@code reason}. */
    @NotBlank
    @Column(nullable = false, length = 512)
    private String message;

    /** Ranks urgency within a type; higher is more urgent. */
    @Column(name = "risk_score", nullable = false)
    private double riskScore;

    /** Cover at the time of the scan, in days. Null when demand is zero. */
    @Column(name = "days_of_supply")
    private Double daysOfSupply;

    /** Inventory position (on hand plus on order) at the time of the scan. */
    @Column(name = "inventory_position")
    private Double inventoryPosition;

    /**
     * How many scans have reported this problem, counting the one that opened it.
     *
     * <p>A count of 40 on a week-old alert is a standing condition; a count of 1 is new.
     */
    @Builder.Default
    @Column(nullable = false)
    private int occurrences = 1;

    @Builder.Default
    @Column(nullable = false)
    private boolean resolved = false;

    /**
     * When this alert was resolved, or null while it is open.
     */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Third column of the unique index: {@code 0} while open, the alert's own id once
     * resolved.
     *
     * <p>The rule wanted is "one open alert per (sku, type), unlimited resolved ones", which
     * is a partial index — and neither MySQL nor H2 has those. Nor can {@code resolved_at}
     * stand in: SQL exempts a row from a unique constraint when any of its columns is null,
     * so indexing on it would permit unlimited <em>open</em> alerts and constrain the
     * resolved ones, precisely backwards.
     *
     * <p>A non-null discriminator gets the intended rule on both databases. Every open row
     * carries {@code 0}, so a second one collides; every resolved row carries its own id,
     * which is unique by construction, so any number may accumulate.
     */
    @Builder.Default
    @Column(name = "active_key", nullable = false)
    private long activeKey = OPEN;

    /** Value of {@link #activeKey} while an alert is open. */
    private static final long OPEN = 0L;

    /** Username of whoever resolved it. Not a foreign key — the record outlives the account. */
    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    /** Optional operator note explaining the resolution. */
    @Column(name = "resolution_note", length = 512)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Last time a scan restated this alert, so a stale one is visible as stale. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void applyDefaults() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /**
     * Folds a fresh scan verdict into an existing open alert.
     *
     * <p>Severity and score are overwritten rather than maximised: the point of the field is
     * what the situation is now, and a stockout risk that has eased from critical to medium
     * should read as medium. The history of how bad it once was is not kept — that would be a
     * second table, and this system does not need one.
     */
    public void restate(Severity newSeverity,
                        String newMessage,
                        double newRiskScore,
                        Double newDaysOfSupply,
                        Double newInventoryPosition) {
        this.severity = newSeverity;
        this.message = newMessage;
        this.riskScore = newRiskScore;
        this.daysOfSupply = newDaysOfSupply;
        this.inventoryPosition = newInventoryPosition;
        this.occurrences++;
    }

    /**
     * Marks this alert resolved.
     *
     * <p>Idempotent by contract: the caller checks {@link #isResolved()} first and reports a
     * conflict, rather than this method silently overwriting who resolved it and when.
     */
    public void resolve(String username, String note) {
        this.resolved = true;
        this.resolvedAt = Instant.now();
        this.resolvedBy = username;
        this.resolutionNote = note;
        this.activeKey = id;
    }
}

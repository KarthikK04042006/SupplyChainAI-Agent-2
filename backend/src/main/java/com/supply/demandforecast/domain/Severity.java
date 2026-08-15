package com.supply.demandforecast.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How urgent a warning, anomaly or alert is.
 *
 * <p>Shared deliberately between the ML wire contract and the persisted {@code Alert}:
 * a "high" risk reported by the ML service and a "high" alert in the database mean the
 * same thing, and giving each layer its own copy would invite them to drift apart.
 *
 * <p>The constants are uppercase so JPA stores the conventional Java form, while
 * {@link #toWire()} emits the lowercase spelling the Python service uses. The two
 * annotations do not overlap: Jackson honours {@code @JsonValue}, JPA honours
 * {@link #name()}.
 */
public enum Severity {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    @JsonValue
    public String toWire() {
        return name().toLowerCase();
    }

    /**
     * Parses the ML service's lowercase spelling.
     *
     * <p>Accepts either case so a hand-written API request is not rejected for
     * capitalising a query parameter.
     */
    @JsonCreator
    public static Severity fromWire(String value) {
        return Severity.valueOf(value.trim().toUpperCase());
    }

    /** True when this severity is at least as urgent as {@code floor}. */
    public boolean atLeast(Severity floor) {
        return ordinal() >= floor.ordinal();
    }
}

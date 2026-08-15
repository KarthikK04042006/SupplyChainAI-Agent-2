package com.supply.demandforecast.service;

import java.util.Map;

/**
 * Field parsing shared by the three CSV importers.
 *
 * <p>Each importer reads a different file, but they all face the same questions: is this column
 * present, is it a number, is a negative quantity meaningful. Answering those in one place is
 * what lets the upload page state one rule per column — otherwise "quantity" could be rejected
 * for being negative in one file and accepted in another.
 *
 * <p>Every failure is an {@link IllegalArgumentException} naming the column and the offending
 * value, because the caller catches it per row and prefixes the line number. A message that did
 * not name the column would leave an operator with "row 412: invalid" and a file to bisect.
 */
final class CsvValues {

    private CsvValues() {
    }

    /** A column that must be present and non-blank. */
    static String required(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required column '%s'".formatted(column));
        }
        return value.trim();
    }

    /**
     * A column that may be absent, blank, or missing from the header entirely.
     *
     * <p>Returns null for all three, which importers read as "leave the stored value alone"
     * rather than as an instruction to clear it.
     */
    static String optional(Map<String, String> row, String column) {
        String value = row.get(column);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** An optional non-negative decimal. */
    static Double optionalDouble(Map<String, String> row, String column) {
        String raw = optional(row, column);
        if (raw == null) {
            return null;
        }

        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "%s '%s' is not a number".formatted(column, raw));
        }
        if (value < 0) {
            throw new IllegalArgumentException(
                    "%s must not be negative, got %s".formatted(column, value));
        }
        return value;
    }

    /** An optional non-negative whole number. */
    static Integer optionalInt(Map<String, String> row, String column) {
        String raw = optional(row, column);
        if (raw == null) {
            return null;
        }

        // Parsed as a double first so "5.0" from a spreadsheet export is accepted; a genuinely
        // fractional lead time is rejected rather than silently truncated to a shorter one.
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "%s '%s' is not a number".formatted(column, raw));
        }
        if (value < 0) {
            throw new IllegalArgumentException(
                    "%s must not be negative, got %s".formatted(column, raw));
        }
        if (value != Math.floor(value)) {
            throw new IllegalArgumentException(
                    "%s must be a whole number, got %s".formatted(column, raw));
        }
        return (int) value;
    }

    /**
     * A required non-negative decimal.
     *
     * <p>The wording of both failures matches {@code DemandImportService}'s original messages,
     * which its tests assert on.
     */
    static double requiredDouble(Map<String, String> row, String column) {
        String raw = required(row, column);

        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "%s '%s' is not a number".formatted(column, raw));
        }
        if (value < 0) {
            throw new IllegalArgumentException(
                    "%s must not be negative, got %s".formatted(column, value));
        }
        return value;
    }
}

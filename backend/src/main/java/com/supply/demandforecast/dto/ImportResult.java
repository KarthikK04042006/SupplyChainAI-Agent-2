package com.supply.demandforecast.dto;

import java.util.List;

/**
 * Outcome of a CSV import, shared by the demand, catalogue, and inventory uploads.
 *
 * <p>One shape for all three so the upload page renders one result panel rather than three. Note
 * what it implies: a file with bad rows still returns 200 with those rows listed, because rejecting
 * the whole upload over one malformed line would cost the caller the rows that were fine.
 *
 * @param rowsParsed  rows that parsed cleanly
 * @param inserted    new rows created
 * @param updated     existing rows the file restated
 * @param rejected    rows that failed validation
 * @param errors      per-row messages, capped to keep the response bounded
 */
public record ImportResult(
        int rowsParsed,
        int inserted,
        int updated,
        int rejected,
        List<String> errors
) {
}

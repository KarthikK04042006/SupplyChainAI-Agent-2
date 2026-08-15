package com.supply.demandforecast.dto;

import jakarta.validation.constraints.Size;

/**
 * Body for {@code PATCH /api/alerts/{id}/resolve}.
 *
 * <p>The note is optional and the body itself may be omitted — resolving with no explanation is
 * a legitimate act, and requiring a note would only teach people to type "ok". Who resolved it
 * comes from the authenticated principal, never from the request, so a caller cannot resolve an
 * alert under someone else's name.
 *
 * @param note why this alert no longer stands, capped to the column width
 */
public record ResolveAlertRequest(@Size(max = 512) String note) {

    public String noteOrNull() {
        return note == null || note.isBlank() ? null : note.trim();
    }
}

package com.supply.demandforecast.domain;

/**
 * What a user is allowed to do.
 *
 * <p>Three levels:
 *
 * <ul>
 *   <li>{@link #VIEWER} — read forecasts, inventory, and alerts. Cannot change anything.
 *   <li>{@link #MANAGER} — everything a viewer can do, plus acting on alerts (resolving
 *       them) and triggering scans on demand.
 *   <li>{@link #ADMIN} — everything, plus managing users and uploading demand data.
 * </ul>
 *
 * <p>Privilege is <em>not</em> inferred from declaration order. Spring Security matches
 * authorities exactly, so an ADMIN does not automatically satisfy a VIEWER requirement; the
 * hierarchy is declared explicitly as a {@code RoleHierarchy} bean in {@code SecurityConfig}.
 * Keeping it there rather than implying it here means the rule is visible at the point it is
 * enforced, and reordering these constants cannot silently change who can do what.
 *
 * <p>Persisted as {@link Enum#name()} via {@code EnumType.STRING}, so ordinal position
 * carries no meaning in the database either.
 */
public enum Role {

    /** Read-only across forecasts, inventory and alerts. */
    VIEWER,

    /** Views forecasts and acts on alerts, but cannot manage users. */
    MANAGER,

    /** Manages users and uploads demand data. */
    ADMIN;

    /**
     * The authority string Spring Security uses, e.g. {@code ROLE_ADMIN}.
     *
     * <p>The {@code ROLE_} prefix is what {@code hasRole()} and {@code RoleHierarchy}
     * expect; adding it here rather than baking it into the constant names keeps the
     * database and JSON readable.
     */
    public String authority() {
        return "ROLE_" + name();
    }
}

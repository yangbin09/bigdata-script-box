package com.bigdata.scriptbox.model;

import java.util.Set;

/**
 * Risk levels for a Script. The string form is the canonical persistence value
 * (also used in {@code script.risk_level} column). Use the helpers in this class
 * rather than raw string literals in callers so future additions stay consistent.
 */
public final class RiskLevel {
    public static final String READ_ONLY = "READ_ONLY";
    public static final String WRITE = "WRITE";
    public static final String DANGEROUS = "DANGEROUS";

    /** All canonical values. Anything else is rejected by {@link #normalize}. */
    public static final Set<String> ALL = Set.of(READ_ONLY, WRITE, DANGEROUS);

    /** Confirmation token the frontend must send when invoking a DANGEROUS script. */
    public static final String CONFIRM_TOKEN = "CONFIRM";

    private RiskLevel() {}

    /**
     * Returns the canonical string for a raw value, or {@link #READ_ONLY} when
     * the input is null/blank/unknown. Used when accepting user-supplied input
     * — we never throw on bad input from the database, but we do throw on bad
     * input from {@code ScriptController.create} where the user picked the
     * value from a select.
     */
    public static String normalize(String raw) {
        if (raw == null) return READ_ONLY;
        String upper = raw.trim().toUpperCase();
        return ALL.contains(upper) ? upper : READ_ONLY;
    }

    /** Strictly validates that a value is one of the canonical levels. */
    public static void requireValid(String raw) {
        if (raw == null || !ALL.contains(raw.trim().toUpperCase())) {
            throw new IllegalArgumentException(
                "invalid risk level: " + raw + " (expected one of " + ALL + ")");
        }
    }
}

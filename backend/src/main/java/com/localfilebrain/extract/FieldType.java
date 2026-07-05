package com.localfilebrain.extract;

/**
 * The type of a single extraction field. Deliberately small and generic — the
 * Structured Extraction Engine stays free of any profession- or
 * country-specific notions; those live only in the (UI-side) template
 * definitions that populate a schema.
 *
 * <ul>
 *   <li>{@link #TEXT}     — free text, preserved as written.
 *   <li>{@link #NUMBER}   — a bare number (no currency).
 *   <li>{@link #CURRENCY} — a monetary amount, formatted with the
 *                           {@link CurrencyDescriptor} supplied in the options.
 *   <li>{@link #DATE}     — a calendar date, preserved verbatim and flagged
 *                           when its day/month order is ambiguous (never
 *                           silently reordered).
 *   <li>{@link #BOOLEAN}  — yes/no.
 * </ul>
 */
public enum FieldType {
    TEXT, NUMBER, CURRENCY, DATE, BOOLEAN;

    /** Lenient parse: unknown / null input falls back to {@link #TEXT}. */
    public static FieldType from(String raw) {
        if (raw == null) return TEXT;
        try { return valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return TEXT; }
    }
}

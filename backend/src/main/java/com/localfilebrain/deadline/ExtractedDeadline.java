package com.localfilebrain.deadline;

/**
 * One deadline/renewal/action as produced by the LLM for a single source
 * document. {@code docId} is the batch-local id we assigned the document in the
 * prompt so the scan service can map a result back to its file when several
 * documents share one LLM call.
 *
 * @param docId       batch-local document id the LLM echoed back (1-based)
 * @param title       short, specific label (e.g. "HDFC car insurance renewal")
 * @param description one-sentence context for the reminder body
 * @param dueDate     resolved ISO date {@code YYYY-MM-DD}, or null if undated
 * @param kind        DEADLINE | RENEWAL | ACTION (normalized)
 * @param confidence  HIGH | MEDIUM | LOW (normalized)
 * @param recurring   NONE | WEEKLY | MONTHLY | QUARTERLY | YEARLY (normalized)
 */
public record ExtractedDeadline(
        int    docId,
        String title,
        String description,
        String dueDate,
        String kind,
        String confidence,
        String recurring) {

    /** Allowed kinds; anything else normalizes to ACTION. */
    public static String normalizeKind(String raw) {
        if (raw == null) return "ACTION";
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "DEADLINE", "RENEWAL", "ACTION" -> s;
            default -> "ACTION";
        };
    }

    /** Allowed confidence levels; anything else normalizes to MEDIUM. */
    public static String normalizeConfidence(String raw) {
        if (raw == null) return "MEDIUM";
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "HIGH", "MEDIUM", "LOW" -> s;
            default -> "MEDIUM";
        };
    }

    /** Allowed recurrence; anything else normalizes to NONE. */
    public static String normalizeRecurring(String raw) {
        if (raw == null) return "NONE";
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY", "NONE" -> s;
            case "ANNUAL", "ANNUALLY"  -> "YEARLY";
            case "DAILY"               -> "WEEKLY"; // we don't expose daily; closest bucket
            default -> "NONE";
        };
    }
}

package com.localfilebrain.extract;

/**
 * One extracted cell: the value, a status carrying confidence/ambiguity
 * metadata, an optional explanatory note, and the {@code evidence} — a short
 * verbatim quote from the source document the value was drawn from. The evidence
 * is what makes an extraction verifiable: it travels all the way to the UI, CSV,
 * and PDF export so a user can confirm a value without reopening the file, and
 * it is what the engine checks the value against (grounding). A flagged value
 * must never render identically to a confident one.
 *
 * <ul>
 *   <li>{@link Status#OK}         — a confident, present, grounded value.
 *   <li>{@link Status#AMBIGUOUS}  — preserved exactly as written but its
 *                                   interpretation is uncertain (e.g. a numeric
 *                                   date whose day/month order can't be
 *                                   determined). {@link #note} explains why. The
 *                                   value is NEVER silently normalized.
 *   <li>{@link Status#UNVERIFIED} — the model returned a value, but it could NOT
 *                                   be located in the document text (the cited
 *                                   evidence isn't in the source, or a factual
 *                                   value doesn't appear there). The value is
 *                                   kept but flagged for review — never silently
 *                                   trusted.
 *   <li>{@link Status#MISSING}    — the field was not found. Value empty; nothing
 *                                   invented.
 * </ul>
 */
public record ExtractedValue(String value, Status status, String note, String evidence) {

    public enum Status { OK, AMBIGUOUS, UNVERIFIED, MISSING }

    public ExtractedValue {
        value    = value    == null ? "" : value;
        status   = status   == null ? Status.OK : status;
        note     = note     == null ? "" : note;
        evidence = evidence == null ? "" : evidence;
    }

    public static ExtractedValue ok(String value)                    { return new ExtractedValue(value, Status.OK, "", ""); }
    public static ExtractedValue ok(String value, String evidence)   { return new ExtractedValue(value, Status.OK, "", evidence); }
    public static ExtractedValue ambiguous(String value, String why) { return new ExtractedValue(value, Status.AMBIGUOUS, why, ""); }
    public static ExtractedValue missing()                           { return new ExtractedValue("", Status.MISSING, "", ""); }
    public static ExtractedValue unverified(String value, String evidence, String note) {
        return new ExtractedValue(value, Status.UNVERIFIED, note, evidence);
    }

    /** Returns a copy of this value with the evidence quote attached (status/note kept). */
    public ExtractedValue withEvidence(String evidence) {
        return new ExtractedValue(value, status, note, evidence);
    }

    public boolean isAmbiguous()  { return status == Status.AMBIGUOUS; }
    public boolean isUnverified() { return status == Status.UNVERIFIED; }
    public boolean isMissing()    { return status == Status.MISSING; }

    /** Any status a user should review before relying on the value. */
    public boolean isFlagged()    { return status == Status.AMBIGUOUS || status == Status.UNVERIFIED; }
}

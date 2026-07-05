package com.localfilebrain.extract;

/**
 * One extracted cell: the value plus a status that carries confidence/ambiguity
 * metadata forward all the way to CSV and PDF export (a production-safety
 * requirement — a flagged value must never render identically to a confident
 * one).
 *
 * <ul>
 *   <li>{@link Status#OK}        — a confident, present value.
 *   <li>{@link Status#AMBIGUOUS} — the value is preserved exactly as written but
 *                                  its interpretation is uncertain (e.g. a
 *                                  numeric date whose day/month order can't be
 *                                  determined). {@link #note} explains why. The
 *                                  value is NEVER silently normalized.
 *   <li>{@link Status#MISSING}   — the field was not found in the document. The
 *                                  value is empty; nothing is invented.
 * </ul>
 */
public record ExtractedValue(String value, Status status, String note) {

    public enum Status { OK, AMBIGUOUS, MISSING }

    public ExtractedValue {
        value  = value == null ? "" : value;
        status = status == null ? Status.OK : status;
        note   = note == null ? "" : note;
    }

    public static ExtractedValue ok(String value)                 { return new ExtractedValue(value, Status.OK, ""); }
    public static ExtractedValue ambiguous(String value, String why) { return new ExtractedValue(value, Status.AMBIGUOUS, why); }
    public static ExtractedValue missing()                        { return new ExtractedValue("", Status.MISSING, ""); }

    public boolean isAmbiguous() { return status == Status.AMBIGUOUS; }
    public boolean isMissing()   { return status == Status.MISSING; }
}

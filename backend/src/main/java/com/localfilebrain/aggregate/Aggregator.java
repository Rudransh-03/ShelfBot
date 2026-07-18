package com.localfilebrain.aggregate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic core of the generic aggregator: given the per-document facts an
 * extractor pulled for a category, it (1) MERGES facts about the same thing
 * across documents — filling each field from whichever doc states it, so a
 * balance in an email beats an invoice's gross — (2) FLAGS genuine conflicts
 * (two docs, same thing, different amounts) instead of silently picking, and
 * (3) leaves the sum/count/list to the caller. No LLM here — pure logic, so it's
 * fully unit-testable.
 */
public final class Aggregator {

    private Aggregator() {}

    // Which status is the most trustworthy when merging: a settled "paid" is a
    // strong signal (owes nothing); a stated PARTIAL balance beats a bare PENDING
    // gross; an unknown/blank status loses to anything definite.
    static int statusRank(String status) {
        if (status == null) return 0;
        String s = status.trim().toLowerCase();
        if (s.contains("paid") || s.contains("received") || s.contains("settled") || s.contains("cleared")) return 5;
        if (s.contains("partial")) return 4;
        if (s.contains("pending") || s.contains("outstanding") || s.contains("overdue")
                || s.contains("unpaid") || s.contains("due") || s.contains("owing")) return 3;
        if (s.contains("prospect") || s.contains("quote") || s.contains("unsigned")) return 1;
        return 0;   // unknown / blank
    }

    /** One merged fact per thing (mergeKey). Each field is filled from the doc that
     *  best states it: status by trust rank, value/balance/date/label by first
     *  stated. So invoice(gross) + email(balance,PARTIAL) + payment(PAID) collapse
     *  into one accurate record. */
    public static List<DocFact> merge(List<DocFact> facts) {
        Map<String, List<DocFact>> groups = new LinkedHashMap<>();
        for (DocFact f : facts) groups.computeIfAbsent(f.mergeKey(), k -> new ArrayList<>()).add(f);

        List<DocFact> out = new ArrayList<>();
        for (List<DocFact> g : groups.values()) {
            DocFact best = g.get(0);
            for (DocFact f : g) if (statusRank(f.status()) > statusRank(best.status())) best = f;
            out.add(new DocFact(
                    bestSubject(g),
                    firstNonBlank(g, DocFact::key),
                    firstNonBlank(g, DocFact::label),
                    firstNonNull(g, DocFact::value),
                    firstNonNull(g, DocFact::balance),
                    best.status() == null || best.status().isBlank()
                            ? firstNonBlank(g, DocFact::status) : best.status(),
                    firstNonBlank(g, DocFact::date),
                    best.sourceName(), best.sourcePath(),
                    firstNonBlank(g, DocFact::note)));
        }
        return out;
    }

    /** Human-readable conflict notes: same thing, two docs, DIFFERENT amounts.
     *  Surfaced to the user rather than silently resolved. */
    public static List<String> conflicts(List<DocFact> facts) {
        Map<String, DocFact> firstWithValue = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        java.util.Set<String> flagged = new java.util.HashSet<>();
        for (DocFact f : facts) {
            if (f.value() == null || f.value() <= 0) continue;
            String k = f.mergeKey();
            DocFact prev = firstWithValue.get(k);
            if (prev == null) { firstWithValue.put(k, f); continue; }
            if (!prev.value().equals(f.value()) && flagged.add(k)) {
                notes.add(prev.sourceName() + " shows " + prev.value()
                        + " but " + f.sourceName() + " shows " + f.value()
                        + " for " + (f.key() == null || f.key().isBlank() ? f.subject() : f.key()));
            }
        }
        return notes;
    }

    /** Sum of what's still owed across merged facts (for "total owed" style ops). */
    public static long sumOwed(List<DocFact> merged) {
        long t = 0;
        for (DocFact f : merged) t += f.owed();
        return t;
    }

    // Generic placeholders an extractor falls back to when a doc doesn't name the
    // party (an email that only says "the client"). A real name from another doc
    // about the same thing should win over these.
    private static final java.util.Set<String> GENERIC = java.util.Set.of(
            "client", "clients", "the client", "a client", "customer", "customers",
            "unnamed", "unknown", "n/a", "party");

    /** The best subject for a merged group: the first REAL (non-placeholder) name,
     *  falling back to a placeholder only if that's all any doc had. */
    static String bestSubject(List<DocFact> g) {
        String fallback = "";
        for (DocFact f : g) {
            String s = f.subject() == null ? "" : f.subject().trim();
            if (s.isBlank()) continue;
            if (GENERIC.contains(s.toLowerCase())) { if (fallback.isBlank()) fallback = s; continue; }
            return s;
        }
        return fallback;
    }

    private interface Getter { String get(DocFact f); }
    private interface NumGetter { Long get(DocFact f); }

    private static String firstNonBlank(List<DocFact> g, Getter get) {
        for (DocFact f : g) { String v = get.get(f); if (v != null && !v.isBlank()) return v; }
        return "";
    }
    private static Long firstNonNull(List<DocFact> g, NumGetter get) {
        for (DocFact f : g) { Long v = get.get(f); if (v != null) return v; }
        return null;
    }
}

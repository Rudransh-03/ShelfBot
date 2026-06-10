package com.localfilebrain.deadline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the prefiltered, context-wrapped excerpts of one batch of documents
 * into structured {@link ExtractedDeadline}s via a single LLM call.
 *
 * <p>The prefilter ({@link DeadlinePrefilter}) is deliberately dumb — it only
 * narrows hundreds of pages down to the few date-bearing chunks. This engine is
 * where the actual understanding happens: it reads each candidate snippet
 * together with its neighbour chunks and a cheap document header (cached TL;DR
 * or the doc's leading text), and asks the model to (a) resolve relative dates
 * against today, (b) write a real title/description, (c) classify the item, and
 * (d) drop the false positives — emitting strict JSON.
 *
 * <p>Several documents are packed into one call (batched by token budget
 * upstream); each carries a batch-local {@code docId} so results can be mapped
 * back to their file. The {@link LlmCall} seam lets the prompt-building and
 * JSON-parsing be unit-tested with a fake model — no network, no key.
 */
public final class DeadlineExtractionEngine {

    private static final Logger log = LoggerFactory.getLogger(DeadlineExtractionEngine.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT = """
            You read excerpts from one or more documents for a personal life-admin
            assistant. In a SINGLE reply you do two jobs and return ONE JSON object.

            Return ONLY a JSON object (no prose, no markdown fences):
            {
              "deadlines": [
                {
                  "doc":         <integer document id, copied from the excerpt header>,
                  "title":       "<= 8 words, specific: who + what (e.g. 'HDFC car insurance renewal')",
                  "description": "one short sentence of context for the reminder body",
                  "date":        "YYYY-MM-DD"  (the actual due/renewal/action date),
                  "kind":        "deadline" | "renewal" | "action",
                  "confidence":  "high" | "medium" | "low",
                  "recurring":   "none" | "weekly" | "monthly" | "quarterly" | "yearly"
                }
              ],
              "documents": [
                {
                  "doc":    <integer document id>,
                  "series": "<the recurring TYPE of document, normalized and issuer-agnostic, e.g. 'GST return', 'bank statement', 'salary slip', 'electricity bill'; null if it is NOT a periodic/recurring document>",
                  "issuer": "<organisation it is from, e.g. 'HDFC Bank', 'GSTN'; null if unclear>",
                  "period": "<the single period the document COVERS: 'YYYY-MM' monthly, 'YYYY-Qn' quarterly, 'YYYY' annual; null if it does not cover one specific recurring period>"
                }
              ]
            }

            Rules for "deadlines":
              - Use ONLY the excerpts. Never invent dates or facts.
              - Resolve relative dates against the provided "Today" date
                (e.g. "within 30 days", "next quarter", "by the 20th").
              - "date" must be a real calendar date in YYYY-MM-DD. If an item has
                no determinable date, OMIT it entirely — undated items can't become
                reminders.
              - Identify WHO/WHAT the deadline belongs to from the document header
                and surrounding text, not just the bare date line.
              - Drop excerpts that are not actually a deadline/obligation
                (a date alone, like an issue date or a year in prose, is not one).
              - De-duplicate: if the same deadline appears multiple times, emit it once.
              - If there are no real deadlines, use an empty "deadlines" array.

            Rules for "documents":
              - Emit exactly ONE entry per document id you were given.
              - "series" is the recurring TYPE, not the title — issuer-agnostic and
                stable across periods, so January and February of the same thing
                share the SAME series string.
              - "period" is what the document is ABOUT/covers and may differ from any
                deadline date. Use null for series/period when the document is not a
                recurring periodic document (e.g. a one-off contract, an ID card).
            """;

    /** Seam over the LLM so prompt-building + parsing can be tested with a fake. */
    @FunctionalInterface
    public interface LlmCall { String call(String systemPrompt, String userPrompt); }

    /**
     * One document's contribution to a batch: a batch-local id, a cheap header
     * (cached TL;DR or leading text), the original filename (weak hint), and the
     * neighbour-wrapped candidate snippets.
     */
    public record DocPayload(int docId, String fileName, String header, List<String> snippets) {}

    /**
     * One document's recurring-series classification, used by the missing-document
     * detector. {@code series}/{@code period} are null when the document isn't a
     * periodic/recurring document. {@code period} is a raw label
     * ('YYYY-MM' | 'YYYY-Qn' | 'YYYY'); canonicalisation happens in the detector.
     */
    public record DocClassification(int docId, String series, String issuer, String period) {}

    /** Both products of the single extraction call. */
    public record BatchResult(List<ExtractedDeadline> deadlines, List<DocClassification> documents) {}

    /**
     * Extracts deadlines for one batch of documents in a single LLM call.
     * Back-compat entry point (deadlines only); see {@link #extractBatchFull}.
     */
    public static List<ExtractedDeadline> extractBatch(List<DocPayload> docs,
                                                       LocalDate today,
                                                       LlmCall llm) {
        return extractBatchFull(docs, today, llm).deadlines();
    }

    /**
     * Extracts BOTH deadlines and per-document series classifications for one
     * batch in a single LLM call. Hallucinated/unknown doc ids are dropped from
     * both lists.
     */
    public static BatchResult extractBatchFull(List<DocPayload> docs,
                                               LocalDate today,
                                               LlmCall llm) {
        if (docs == null || docs.isEmpty()) return new BatchResult(List.of(), List.of());
        String userPrompt = buildPrompt(docs, today);
        String raw = llm.call(SYSTEM_PROMPT, userPrompt);

        java.util.Set<Integer> validIds = new java.util.HashSet<>();
        for (DocPayload d : docs) validIds.add(d.docId());

        com.fasterxml.jackson.databind.JsonNode root = rootJson(raw);

        List<ExtractedDeadline> deadlines = parseDeadlines(root, raw);
        List<ExtractedDeadline> keptDeadlines = new ArrayList<>(deadlines.size());
        for (ExtractedDeadline it : deadlines) {
            if (validIds.contains(it.docId())) keptDeadlines.add(it);
            else log.debug("Dropping extracted item with unknown docId {}: {}", it.docId(), it.title());
        }

        List<DocClassification> docsClass = parseDocuments(root);
        List<DocClassification> keptDocs = new ArrayList<>(docsClass.size());
        for (DocClassification dc : docsClass) {
            if (validIds.contains(dc.docId())) keptDocs.add(dc);
        }

        log.info("Extraction batch: {} doc(s) -> {} deadline(s), {} classified (1 LLM call)",
                docs.size(), keptDeadlines.size(), keptDocs.size());
        return new BatchResult(keptDeadlines, keptDocs);
    }

    static String buildPrompt(List<DocPayload> docs, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        sb.append("Today is ").append(today).append(".\n\n");
        sb.append("Below are excerpts from ").append(docs.size())
          .append(" document(s). Extract deadlines as specified.\n\n");
        for (DocPayload d : docs) {
            sb.append("=== Document id=").append(d.docId());
            if (d.fileName() != null && !d.fileName().isBlank()) {
                sb.append("  (filename hint, may be meaningless: ").append(d.fileName()).append(")");
            }
            sb.append(" ===\n");
            if (d.header() != null && !d.header().isBlank()) {
                sb.append("Document context: ").append(trim(d.header(), 600)).append("\n");
            }
            int n = 1;
            for (String snip : d.snippets()) {
                sb.append("Excerpt ").append(n++).append(": ").append(trim(snip, 1400)).append("\n");
            }
            sb.append("\n");
        }
        sb.append("Return the JSON object now.");
        return sb.toString();
    }

    /**
     * Parses the model's reply into deadlines. Tolerates ```json fences and
     * leading/trailing prose by extracting the outermost JSON array. Items
     * without a usable date are dropped (they can't become reminders); kind /
     * confidence / recurring are normalized.
     */
    /** Back-compat: parse deadlines from a raw reply (bare array or {deadlines:[...]}). */
    static List<ExtractedDeadline> parse(String raw) {
        return parseDeadlines(rootJson(raw), raw);
    }

    /**
     * Isolates the JSON value from a model reply, tolerating code fences and
     * surrounding prose. Returns the wrapper OBJECT for the
     * {@code {"deadlines":[...],"documents":[...]}} shape, or the bare ARRAY for
     * the legacy deadlines-only shape. Null when no JSON value is present.
     *
     * Heuristic: it's a wrapper object only when its {@code '{'} precedes the
     * first {@code '['} and its {@code '}'} follows the last {@code ']'} — an
     * array-of-objects always has {@code '['} before the first {@code '{'}.
     */
    static JsonNode rootJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int ob = raw.indexOf('{'), cb = raw.lastIndexOf('}');
        int oa = raw.indexOf('['), ca = raw.lastIndexOf(']');
        try {
            boolean objectWrapper = ob >= 0 && cb > ob
                    && (oa < 0 || ob < oa) && (ca < 0 || cb > ca);
            if (objectWrapper) return MAPPER.readTree(raw.substring(ob, cb + 1));
            if (oa >= 0 && ca > oa) return MAPPER.readTree(raw.substring(oa, ca + 1));
        } catch (Exception e) {
            log.warn("Extraction reply was not valid JSON; head: {}",
                    raw.substring(0, Math.min(raw.length(), 160)));
        }
        return null;
    }

    private static List<ExtractedDeadline> parseDeadlines(JsonNode root, String raw) {
        List<ExtractedDeadline> out = new ArrayList<>();
        if (root == null) {
            log.warn("Deadline extraction returned no JSON; raw head: {}",
                    raw == null ? "" : raw.substring(0, Math.min(raw.length(), 160)));
            return out;
        }
        JsonNode arr = root.isArray() ? root
                : (root.isObject() ? root.get("deadlines") : null);
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode node : arr) {
            String date = textOrNull(node, "date");
            if (date != null) date = date.trim();
            // Drop undated items — they can't be turned into a reminder.
            if (date == null || date.isBlank() || !looksLikeIsoDate(date)) continue;

            int docId = node.path("doc").asInt(node.path("docId").asInt(0));
            String title = textOrNull(node, "title");
            if (title == null || title.isBlank()) title = "Deadline";
            String desc  = textOrNull(node, "description");

            out.add(new ExtractedDeadline(
                    docId,
                    title.trim(),
                    desc == null ? "" : desc.trim(),
                    date,
                    ExtractedDeadline.normalizeKind(textOrNull(node, "kind")),
                    ExtractedDeadline.normalizeConfidence(textOrNull(node, "confidence")),
                    ExtractedDeadline.normalizeRecurring(textOrNull(node, "recurring"))));
        }
        return out;
    }

    /** Parses per-document series classifications from the wrapper object (empty
     *  for the legacy array shape). Only documents that are actually periodic
     *  (non-null series AND period) are kept. */
    static List<DocClassification> parseDocuments(JsonNode root) {
        List<DocClassification> out = new ArrayList<>();
        if (root == null || !root.isObject()) return out;
        JsonNode arr = root.get("documents");
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode node : arr) {
            int docId = node.path("doc").asInt(node.path("docId").asInt(0));
            if (docId <= 0) continue;
            String series = blankToNull(textOrNull(node, "series"));
            String period = blankToNull(textOrNull(node, "period"));
            if (series == null || period == null) continue; // not a recurring doc
            String issuer = blankToNull(textOrNull(node, "issuer"));
            out.add(new DocClassification(docId, series, issuer, period));
        }
        return out;
    }

    /** Treats blank and the literal strings null/none/n/a as absent. */
    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("none")
                || t.equalsIgnoreCase("n/a")) return null;
        return t;
    }

    /** Cheap sanity check: YYYY-MM-DD with plausible ranges. */
    static boolean looksLikeIsoDate(String s) {
        if (s == null || s.length() != 10) return false;
        if (s.charAt(4) != '-' || s.charAt(7) != '-') return false;
        try {
            LocalDate.parse(s); // rejects 2026-13-40 etc.
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}

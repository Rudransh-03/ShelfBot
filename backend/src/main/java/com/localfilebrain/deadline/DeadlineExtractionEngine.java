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
            You extract concrete deadlines, renewals, and required actions from
            document excerpts, for a personal life-admin assistant that will turn
            each one into a calendar reminder.

            Return ONLY a JSON array (no prose, no markdown fences). Each element:
              {
                "doc":         <integer document id, copied from the excerpt header>,
                "title":       "<= 8 words, specific: who + what (e.g. 'HDFC car insurance renewal')",
                "description": "one short sentence of context for the reminder body",
                "date":        "YYYY-MM-DD"  (the actual due/renewal/action date),
                "kind":        "deadline" | "renewal" | "action",
                "confidence":  "high" | "medium" | "low",
                "recurring":   "none" | "weekly" | "monthly" | "quarterly" | "yearly"
              }

            Rules:
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
              - If there are no real deadlines in any document, return exactly: []
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
     * Extracts deadlines for one batch of documents in a single LLM call.
     * The returned items carry the batch-local {@code docId}; the caller maps
     * those back to absolute paths.
     */
    public static List<ExtractedDeadline> extractBatch(List<DocPayload> docs,
                                                       LocalDate today,
                                                       LlmCall llm) {
        if (docs == null || docs.isEmpty()) return List.of();
        String userPrompt = buildPrompt(docs, today);
        String raw = llm.call(SYSTEM_PROMPT, userPrompt);
        List<ExtractedDeadline> items = parse(raw);
        // Guard against hallucinated doc ids — only keep ids we actually sent.
        java.util.Set<Integer> validIds = new java.util.HashSet<>();
        for (DocPayload d : docs) validIds.add(d.docId());
        List<ExtractedDeadline> kept = new ArrayList<>(items.size());
        for (ExtractedDeadline it : items) {
            if (validIds.contains(it.docId())) kept.add(it);
            else log.debug("Dropping extracted item with unknown docId {}: {}", it.docId(), it.title());
        }
        log.info("Extraction batch: {} doc(s) -> {} deadline(s) (1 LLM call)", docs.size(), kept.size());
        return kept;
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
        sb.append("Return the JSON array now.");
        return sb.toString();
    }

    /**
     * Parses the model's reply into deadlines. Tolerates ```json fences and
     * leading/trailing prose by extracting the outermost JSON array. Items
     * without a usable date are dropped (they can't become reminders); kind /
     * confidence / recurring are normalized.
     */
    static List<ExtractedDeadline> parse(String raw) {
        List<ExtractedDeadline> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;

        String json = isolateJsonArray(raw);
        if (json == null) {
            log.warn("Deadline extraction returned no JSON array; raw head: {}",
                    raw.substring(0, Math.min(raw.length(), 160)));
            return out;
        }

        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return out;
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
        } catch (Exception e) {
            log.warn("Failed to parse deadline JSON ({}): {}", e.getMessage(),
                    json.substring(0, Math.min(json.length(), 200)));
        }
        return out;
    }

    /** Returns the substring from the first '[' to the matching last ']', or null. */
    private static String isolateJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end   = raw.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
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

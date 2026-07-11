package com.localfilebrain.client;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.Client;
import com.localfilebrain.ingestion.IndexMetadataStore.EntityRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates the per-document owner identities captured during the scan into a
 * de-duplicated list of suggested clients for the user to accept.
 *
 * Pure and local (no LLM, no DB) so it's fully testable. Grouping is by a
 * canonical identity key — GSTIN beats PAN beats normalized name — so the same
 * client written several ways (e.g. "Acme Corp" / "ACME CORPORATION") collapses
 * to one suggestion as long as they share a GSTIN/PAN. Already-registered and
 * previously-dismissed identities are filtered out.
 */
public final class EntitySuggester {

    private EntitySuggester() {}

    /**
     * @param key       canonical identity key (also used to dismiss)
     * @param name      best display name
     * @param gstin     GSTIN if known, else null
     * @param pan       PAN if known, else null
     * @param fileCount how many documents point to this identity
     */
    public record Suggestion(String key, String name, String gstin, String pan, int fileCount) {}

    public static List<Suggestion> suggest(List<EntityRow> rows, List<Client> registered, Set<String> dismissed) {
        // Every match token already claimed by a registered client.
        Set<String> claimed = new java.util.HashSet<>();
        if (registered != null) for (Client c : registered) claimed.addAll(c.norms());

        // Group rows by canonical key.
        Map<String, List<EntityRow>> groups = new LinkedHashMap<>();
        if (rows != null) for (EntityRow r : rows) {
            String key = canonicalKey(r);
            if (key == null) continue;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<Suggestion> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            String key = e.getKey();
            if (dismissed != null && dismissed.contains(key)) continue;

            List<EntityRow> g = e.getValue();
            String gstin = firstNonBlank(g, EntityRow::gstin);
            String pan   = firstNonBlank(g, EntityRow::pan);
            String name  = mostCommonName(g);
            // No human-readable name anywhere in the group → never suggest it.
            // A bare GSTIN/PAN as a client "name" produces unrecognizable UI
            // everywhere the client is shown (clarify chips, pickers, lists) —
            // nobody can tell which client "07AABCM4562P1ZK" is.
            if (name == null || looksLikeBareId(name)) continue;

            // Skip when this identity is already a registered client (any of its
            // tokens is claimed).
            if (isClaimed(claimed, gstin) || isClaimed(claimed, pan) || isClaimed(claimed, name)) continue;

            long files = g.stream().map(EntityRow::absolutePath).distinct().count();
            out.add(new Suggestion(key, name, gstin, pan, (int) files));
        }
        out.sort(Comparator.comparingInt(Suggestion::fileCount).reversed());
        return out;
    }

    /** GSTIN (upper) > PAN (upper) > normalized name. Null when the row has nothing usable. */
    static String canonicalKey(EntityRow r) {
        if (notBlank(r.gstin())) return "gstin:" + r.gstin().trim().toUpperCase();
        if (notBlank(r.pan()))   return "pan:"   + r.pan().trim().toUpperCase();
        if (notBlank(r.entityName())) return "name:" + IndexMetadataStore.normToken(r.entityName());
        return null;
    }

    private static boolean isClaimed(Set<String> claimed, String value) {
        return value != null && claimed.contains(IndexMetadataStore.normToken(value));
    }

    private static String firstNonBlank(List<EntityRow> g, java.util.function.Function<EntityRow, String> f) {
        for (EntityRow r : g) { String v = f.apply(r); if (notBlank(v)) return v.trim(); }
        return null;
    }

    /** The most frequently-seen entity name in the group (ties: first seen). */
    private static String mostCommonName(List<EntityRow> g) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EntityRow r : g) if (notBlank(r.entityName())) counts.merge(r.entityName().trim(), 1, Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // A "name" that is actually a bare GSTIN (15 chars) or PAN (10 chars) —
    // extraction sometimes fills the entity field with the id itself.
    private static final java.util.regex.Pattern BARE_ID = java.util.regex.Pattern.compile(
            "(?i)(?:[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]|[A-Z]{5}[0-9]{4}[A-Z])");

    static boolean looksLikeBareId(String name) {
        return BARE_ID.matcher(name.replaceAll("\\s", "")).matches();
    }
}

package com.localfilebrain.client;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.EntityRow;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free, local, LLM-free detection of client identities (GSTIN / PAN) from
 * already-indexed document text. Populates {@code document_entity} so
 * {@link EntitySuggester} can surface "found in your files" client suggestions
 * for EVERY user — not only Pro users who run the (LLM) deadline scan, which was
 * previously the sole source of these identities.
 *
 * <p>High precision by design: GSTIN and PAN have strict, unambiguous shapes, so
 * regex detection has effectively no false positives and costs nothing. Personal
 * (name-only) clients can't be detected this way and remain a manual add.
 *
 * <p>Non-destructive: a file that already has a stored identity (e.g. a richer,
 * named one from the Pro deadline scan) is left untouched — we only ADD
 * identities for files that have none yet.
 */
public final class LocalEntityScanner {

    private static final Logger log = LoggerFactory.getLogger(LocalEntityScanner.class);

    // GSTIN = 2 state digits + 10-char PAN + 1 entity char + 'Z' + 1 checksum.
    private static final Pattern GSTIN =
            Pattern.compile("\\b[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]\\b");
    // Standalone PAN = 5 letters + 4 digits + 1 letter.
    private static final Pattern PAN =
            Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");

    // Identifiers live near the top of tax/business docs; the first ~20k chars
    // of a file is far more than enough and keeps the scan cheap on huge PDFs.
    private static final int MAX_SCAN_CHARS = 20_000;

    private final IndexMetadataStore meta;
    private final VectorStore        vectorStore;

    public LocalEntityScanner(IndexMetadataStore meta, VectorStore vectorStore) {
        this.meta        = meta;
        this.vectorStore = vectorStore;
    }

    /** A detected identity: a GSTIN (with its embedded PAN) or a standalone PAN. */
    public record Hit(String gstin, String pan) {}

    /**
     * Extracts the first GSTIN (and the PAN embedded within it), or — if no
     * GSTIN — the first standalone PAN. Returns null when neither is present.
     */
    static Hit scanText(String text) {
        if (text == null || text.isBlank()) return null;
        String up = text.toUpperCase();
        Matcher g = GSTIN.matcher(up);
        if (g.find()) {
            String gstin = g.group();
            return new Hit(gstin, gstin.substring(2, 12)); // chars 2..11 are the PAN
        }
        Matcher p = PAN.matcher(up);
        if (p.find()) return new Hit(null, p.group());
        return null;
    }

    /**
     * Scans every indexed file (that doesn't already have a stored identity) for
     * a GSTIN/PAN and records what it finds. Returns the number of files an
     * identity was newly stored for.
     */
    public int scanAll() {
        // Files that already carry an identity (e.g. from the Pro deadline scan)
        // — never clobber a richer/named identity with a bare GSTIN/PAN.
        Set<String> alreadyHave = new HashSet<>();
        for (EntityRow r : meta.listAllEntities()) alreadyHave.add(r.absolutePath());

        int found = 0;
        for (FileRecord f : meta.listIndexedFilesBySizeDesc()) {
            String path = f.getAbsolutePath();
            if (alreadyHave.contains(path)) continue;
            try {
                StringBuilder sb = new StringBuilder();
                for (VectorStore.SearchResult c : vectorStore.getChunksForFile(path)) {
                    if (c.text() != null) sb.append(c.text()).append('\n');
                    if (sb.length() >= MAX_SCAN_CHARS) break;
                }
                Hit hit = scanText(sb.toString());
                if (hit != null) {
                    meta.upsertEntity(path, f.getContentHash(), null, hit.gstin(), hit.pan());
                    found++;
                }
            } catch (Exception e) {
                log.debug("local entity scan skipped {}: {}", path, e.getMessage());
            }
        }
        log.info("Local entity scan: identities found in {} file(s)", found);
        return found;
    }
}

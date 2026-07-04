package com.localfilebrain.ingestion;

import com.localfilebrain.model.FileRecord;
import com.localfilebrain.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Free, local, LLM-free classification of each indexed document into a
 * user-friendly TYPE (Invoice, Bank statement, Salary slip, …). Powers the
 * Library's self-sorting filter chips. Cost is zero: it scores a handful of
 * distinctive header phrases over the document's opening text and its file
 * name — no model call, no network — and runs in the same post-index pass as
 * {@code LocalEntityScanner}.
 *
 * <p>Accuracy comes from anchoring on UNAMBIGUOUS header phrases ("TAX
 * INVOICE", "SALARY SLIP", "GOODS AND SERVICES TAX RETURN") and the file name,
 * weighting both. Shared markers like "gstin" (which appear on every business
 * doc) are weighted low so they can't outvote a real header. Anything without a
 * confident match is left as {@link #OTHER} rather than mis-labelled.
 */
public final class DocumentTypeClassifier {

    private static final Logger log = LoggerFactory.getLogger(DocumentTypeClassifier.class);

    public static final String OTHER = "Other";

    // Only the opening of a document carries its identifying header; scanning
    // more would add noise (a contract body mentioning "invoice", say) and cost.
    private static final int SAMPLE_CHARS = 4_000;

    // Minimum score for a confident label; below this we return OTHER.
    private static final int MIN_SCORE = 4;

    // A category may only win if it has an ANCHOR hit: a distinctive multi-word
    // header phrase (content marker of at least this weight) OR any file-name
    // match. This stops a long, unrelated document (e.g. a textbook that happens
    // to mention "settlement" and "agreement") from being classified on an
    // accumulation of generic single words — high precision over recall.
    private static final int ANCHOR_WEIGHT = 8;

    /** A document type and the phrases that signal it, each with a content weight. */
    private record Category(String label, Map<String, Integer> markers) {}

    // Ordered by priority — earlier categories win ties. Strong, distinctive
    // header phrases carry weight 10; supporting terms 2-4; shared/ambiguous
    // terms 1. A file-name hit on any marker adds a flat bonus (names are very
    // reliable for these documents), handled in score().
    private static final List<Category> CATEGORIES = List.of(
            cat("Invoice", m -> {
                m.put("tax invoice", 10); m.put("freelance invoice", 10);
                m.put("proforma invoice", 10); m.put("invoice no", 8);
                m.put("invoice #", 8); m.put("invoice number", 8); m.put("bill to", 5);
                m.put("invoice", 3);
            }),
            cat("Bank statement", m -> {
                m.put("bank statement", 10); m.put("account statement", 10);
                m.put("statement of account", 10); m.put("opening balance", 4);
                m.put("closing balance", 4); m.put("neft", 2); m.put("imps", 2);
            }),
            cat("Salary slip", m -> {
                m.put("salary slip", 10); m.put("pay slip", 10); m.put("payslip", 10);
                m.put("net pay", 5); m.put("gross pay", 4); m.put("earnings", 2);
                m.put("deductions", 2);
            }),
            cat("Tax & GST", m -> {
                m.put("goods and services tax", 10); m.put("gst return", 10);
                m.put("gstr-3b", 10); m.put("gstr 3b", 10); m.put("form gstr", 10);
                m.put("income tax", 8); m.put("form 16", 8); m.put("tds certificate", 8);
                m.put("cgst", 3); m.put("sgst", 3); m.put("taxable value", 2);
                m.put("return period", 2);
            }),
            cat("Purchase order", m -> {
                m.put("purchase order", 10); m.put("po number", 8); m.put("p.o. no", 8);
                m.put("order no", 4);
            }),
            cat("Receipt", m -> {
                m.put("rent receipt", 10); m.put("payment receipt", 10);
                m.put("received with thanks", 8); m.put("received from", 5);
                m.put("receipt", 4);
            }),
            cat("Bill", m -> {
                m.put("electricity bill", 10); m.put("utility bill", 10);
                m.put("water bill", 10); m.put("gas bill", 10); m.put("phone bill", 10);
                m.put("mobile bill", 10); m.put("bill month", 5); m.put("units consumed", 4);
                m.put("amount payable", 3);
            }),
            cat("Notice", m -> {
                m.put("compliance notice", 10); m.put("legal notice", 10);
                m.put("renewal notice", 10); m.put("show cause", 10);
                m.put("licence renewal", 8); m.put("license renewal", 8);
                m.put("notice", 3); m.put("response required", 3);
            }),
            cat("Contract", m -> {
                m.put("supply agreement", 10); m.put("vendor contract", 10);
                m.put("service agreement", 10); m.put("lease agreement", 10);
                m.put("rental agreement", 10); m.put("inter-party settlement", 10);
                m.put("settlement", 6); m.put("contract value", 6); m.put("lease", 5);
                // Generic terms below MIN_SCORE on their own — "agreement" also
                // matches "service level agreement" in unrelated docs, so it must
                // not classify alone; a real contract always has a stronger phrase.
                m.put("agreement", 3); m.put("terms and conditions", 3);
            }),
            cat("ID", m -> {
                m.put("aadhaar", 10); m.put("aadhar", 10); m.put("passport", 10);
                m.put("pan card", 10); m.put("permanent account number", 8);
                m.put("driving licence", 10); m.put("driving license", 10);
                m.put("voter id", 10); m.put("government of india", 6); m.put("uidai", 8);
            }),
            cat("Resume", m -> {
                m.put("curriculum vitae", 10); m.put("work experience", 8);
                m.put("resume", 8); m.put("professional experience", 6);
            }),
            cat("Travel", m -> {
                m.put("travel itinerary", 10); m.put("flight booking", 10);
                m.put("boarding pass", 10); m.put("e-ticket", 10); m.put("itinerary", 6);
                m.put("visa", 6); m.put("pnr", 5); m.put("departure", 3);
            })
    );

    private DocumentTypeClassifier() {}

    /**
     * Classifies a document from its file name and opening text. Returns a
     * type label, or {@link #OTHER} when no category scores confidently.
     */
    public static String classify(String fileName, String text) {
        String content  = text == null ? "" : text.toLowerCase();
        if (content.length() > SAMPLE_CHARS) content = content.substring(0, SAMPLE_CHARS);
        String nameNorm = normalizeName(fileName);

        String best = OTHER;
        int bestScore = 0;
        for (Category c : CATEGORIES) {
            Scored s = score(c, content, nameNorm);
            // Require both a meaningful score AND an anchor hit to classify.
            if (s.hasAnchor() && s.score() >= MIN_SCORE && s.score() > bestScore) {
                bestScore = s.score();
                best = c.label();
            }
        }
        return best;
    }

    private record Scored(int score, boolean hasAnchor) {}

    private static Scored score(Category c, String content, String nameNorm) {
        int score = 0;
        boolean anchor = false;
        for (Map.Entry<String, Integer> e : c.markers().entrySet()) {
            String phrase = e.getKey();
            int w = e.getValue();
            if (content.contains(phrase)) {
                score += w;
                if (w >= ANCHOR_WEIGHT) anchor = true; // distinctive header phrase
            }
            // A file-name match is high-signal for these well-named documents —
            // give it a flat bonus and treat it as an anchor on its own.
            if (nameNorm.contains(phrase)) {
                score += w + 3;
                anchor = true;
            }
        }
        return new Scored(score, anchor);
    }

    /** Lowercases and splits a file name on separators + camelCase / digit
     *  boundaries so "AcmeCorp-GST-Return-Mar2024.pdf" → "acme corp gst return mar 2024 pdf". */
    private static String normalizeName(String fileName) {
        if (fileName == null) return "";
        String spaced = fileName
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([A-Za-z])", "$1 $2")
                .replaceAll("[-_./]+", " ");
        return spaced.toLowerCase();
    }

    // ── Post-index scan loop (mirrors LocalEntityScanner) ────────────────────

    private static final int MAX_SCAN_CHARS = 8_000;

    /**
     * Classifies every indexed file that has no type yet (doc_type IS NULL),
     * reading its opening text from the vector store. Returns the number newly
     * classified. Idempotent and cheap — already-typed files are skipped, so a
     * re-index only reclassifies new/changed files (whose type is cleared on
     * upsert).
     */
    public static int classifyAll(IndexMetadataStore meta, VectorStore vectorStore) {
        int classified = 0;
        for (FileRecord f : meta.listIndexedFilesBySizeDesc()) {
            if (f.getDocType() != null && !f.getDocType().isBlank()) continue;
            String path = f.getAbsolutePath();
            try {
                StringBuilder sb = new StringBuilder();
                for (VectorStore.SearchResult c : vectorStore.getChunksForFile(path)) {
                    if (c.text() != null) sb.append(c.text()).append('\n');
                    if (sb.length() >= MAX_SCAN_CHARS) break;
                }
                String type = classify(f.getFileName(), sb.toString());
                meta.setDocType(path, type);
                classified++;
            } catch (Exception e) {
                log.debug("doc-type classify skipped {}: {}", path, e.getMessage());
            }
        }
        if (classified > 0) log.info("Document-type classification: typed {} file(s)", classified);
        return classified;
    }

    /** Helper to build a category with a marker map in declaration order. */
    private static Category cat(String label, java.util.function.Consumer<Map<String, Integer>> build) {
        Map<String, Integer> m = new LinkedHashMap<>();
        build.accept(m);
        return new Category(label, m);
    }
}

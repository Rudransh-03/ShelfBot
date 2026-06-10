package com.localfilebrain.ingestion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Locates a chunk's page span in a PDF by content-matching, NOT by re-chunking.
 *
 * How it works:
 *   1. At build time, read each PDF page's text via PDFBox and {@link #normalize}
 *      it (lowercase, keep only [a-z0-9]). Normalising away whitespace and
 *      punctuation makes the match robust to the small spacing differences
 *      between Tika's extraction (which produced the canonical chunk text) and
 *      PDFBox's per-page extraction.
 *   2. For a chunk, take a short alphanumeric probe from its head and from its
 *      tail and find which page each falls on. Same page → chunk lives on one
 *      page; different pages → the chunk straddles a page break and we report
 *      the range.
 *
 * It never sees or changes the canonical text, so it cannot affect retrieval or
 * answers. Anything it can't place confidently returns {@code null}, and the
 * chunk is stored with no page — exactly the pre-feature behaviour.
 *
 * Returns {@code null} from {@link #forFile} for non-PDFs and for image-only /
 * scanned PDFs (no embedded text layer to match against — those are OCR'd into
 * the canonical text, which PDFBox can't reproduce).
 */
public final class PdfPageLocator implements PageLocator {

    private static final Logger log = LoggerFactory.getLogger(PdfPageLocator.class);

    // Alphanumeric probe length taken from a chunk's head/tail. Long enough to
    // be effectively unique to one page (so a recurring header/footer won't
    // cause a false early match), short enough to sit within a single page.
    private static final int PROBE     = 60;
    private static final int MIN_PROBE = 16;
    private static final int SHRINK    = 16;

    private final String[] normPages; // normalized text per page; index 0 = page 1

    private PdfPageLocator(String[] normPages) { this.normPages = normPages; }

    /**
     * Builds a locator for the given file, or {@code null} when page citations
     * aren't possible/meaningful (non-PDF, unreadable, or no text layer).
     */
    public static PdfPageLocator forFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (!name.endsWith(".pdf")) return null;
        try (PDDocument doc = PDDocument.load(file.toFile())) {
            int pages = doc.getNumberOfPages();
            if (pages <= 0) return null;
            PDFTextStripper stripper = new PDFTextStripper();
            String[] norm = new String[pages];
            boolean anyText = false;
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                norm[p - 1] = normalize(stripper.getText(doc));
                if (!norm[p - 1].isEmpty()) anyText = true;
            }
            if (!anyText) return null; // scanned / image-only PDF
            return new PdfPageLocator(norm);
        } catch (Throwable e) {
            // Page detection is best-effort — never let it break indexing.
            log.debug("Page locator unavailable for '{}': {}", file.getFileName(), e.toString());
            return null;
        }
    }

    @Override
    public int[] locate(String chunkText) {
        String c = normalize(chunkText);
        if (c.length() < MIN_PROBE) {
            int p = findPage(c, 0);
            return p == 0 ? null : new int[]{p, p};
        }
        String head = c.substring(0, Math.min(PROBE, c.length()));
        String tail = c.substring(Math.max(0, c.length() - PROBE));
        int startPage = locateProbe(head, false); // keep prefix → biases to first page
        int endPage   = locateProbe(tail, true);  // keep suffix → biases to last page

        if (startPage == 0 && endPage == 0) return null;
        if (startPage == 0) startPage = endPage;
        if (endPage   == 0) endPage   = startPage;
        if (endPage < startPage) { int t = startPage; startPage = endPage; endPage = t; }
        return new int[]{startPage, endPage};
    }

    /**
     * Tries the probe, then progressively shorter versions, so a probe that
     * happens to straddle a page break still resolves to where most of it lives.
     * {@code keepSuffix} controls which end is trimmed.
     */
    private int locateProbe(String probe, boolean keepSuffix) {
        for (int len = probe.length(); len >= MIN_PROBE; len -= SHRINK) {
            String sub = keepSuffix ? probe.substring(probe.length() - len)
                                    : probe.substring(0, len);
            int p = findPage(sub, 0);
            if (p != 0) return p;
        }
        return 0;
    }

    /** First page (1-based) whose normalized text contains {@code probe}, else 0. */
    private int findPage(String probe, int unused) {
        if (probe.isEmpty()) return 0;
        for (int i = 0; i < normPages.length; i++) {
            if (normPages[i].contains(probe)) return i + 1;
        }
        return 0;
    }

    /** Lowercase and keep only [a-z0-9] so whitespace/punctuation differences
     *  between extractors don't defeat the substring match. */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toLowerCase(s.charAt(i));
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) sb.append(ch);
        }
        return sb.toString();
    }
}

package com.localfilebrain.util;

import java.security.SecureRandom;

/**
 * Helpers for safely embedding UNTRUSTED, document-derived strings (file names,
 * extracted text) into LLM prompts.
 *
 * <p>Several pipelines feed the user's own file content to the LLM — chat RAG,
 * summaries, deadline extraction, reorg. That content is untrusted: a file can
 * be crafted to contain text that tries to override the system prompt
 * ("ignore previous instructions…"), forge the excerpt delimiters, or fake the
 * trailing user question (indirect prompt injection). We defend in two layers:
 * <ol>
 *   <li>every prompt instructs the model to treat document text as DATA, never
 *       as instructions; and</li>
 *   <li>each untrusted region is wrapped in a per-request random {@link #nonce()}
 *       fence the content cannot predict or forge, so the model can always tell
 *       where untrusted data ends and the real instruction begins.</li>
 * </ol>
 *
 * <p>This class only neutralises the <em>structural</em> pieces — the file-name
 * labels that sit on a delimiter line. Chunk body text is deliberately left
 * byte-for-byte intact so retrieval/citation fidelity is unchanged; the nonce
 * fence plus the system rule are what render the body inert as instructions.
 */
public final class PromptSanitizer {

    private static final SecureRandom RNG = new SecureRandom();

    private PromptSanitizer() {}

    /** A short, unpredictable token used to fence untrusted regions in a prompt. */
    public static String nonce() {
        byte[] b = new byte[9];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Makes a document-derived label (typically a file name) safe to drop on a
     * single prompt line: strips CR/LF and other control characters so it can't
     * forge new lines, collapses runs of the {@code =} / backtick delimiter
     * characters so it can't forge a {@code === fence ===} or code fence, and
     * removes angle brackets so it can't forge a pseudo-tag. Caps the length.
     * Never returns null.
     */
    public static String safeLabel(String s) {
        if (s == null || s.isBlank()) return "unknown";
        String cleaned = s.replaceAll("\\p{Cntrl}", " ") // no newlines / control chars
                          .replaceAll("[=`]{2,}", "=")    // no forged === / ``` fences
                          .replaceAll("[<>]", " ")        // no forged <tags>
                          .trim();
        if (cleaned.isEmpty()) return "unknown";
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200) + "…";
    }

    /**
     * Flattens an untrusted multi-line content preview onto prompt lines safely:
     * collapses CR/LF and other control characters to spaces so the text can't
     * forge new list bullets / section headers, and collapses runs of the
     * {@code =} / backtick fence characters. Unlike {@link #safeLabel} the body
     * characters are otherwise preserved (so the model still judges on real
     * content) and the cap is generous. Returns "" for null/blank.
     */
    public static String safePreview(String s, int maxChars) {
        if (s == null || s.isBlank()) return "";
        String cleaned = s.replaceAll("\\p{Cntrl}", " ")  // one line, no forged structure
                          .replaceAll("[=`]{3,}", "=")     // no forged fences
                          .replaceAll(" {2,}", " ")
                          .trim();
        if (cleaned.length() <= maxChars) return cleaned;
        return cleaned.substring(0, maxChars) + "…";
    }
}

package com.localfilebrain.util;

import java.util.regex.Pattern;

/**
 * Recognises template / sample / boilerplate documents by file name, shared by
 * every pipeline that must not treat their placeholder content as real:
 * <ul>
 *   <li>chat retrieval (a template's chunks are dropped unless the user asks
 *       about templates — see QueryEngine's filter),</li>
 *   <li>the local date scan (a sample invoice's "payment due 01/01/2024" must
 *       never appear in the Timeline or the Needs-attention panel),</li>
 *   <li>the Pro deadline scan (no LLM calls spent extracting fake deadlines
 *       from boilerplate).</li>
 * </ul>
 */
public final class TemplateFiles {

    private TemplateFiles() {}

    /** Matches template-ish markers as separate name segments (delimited by
     *  non-alphanumerics), so "invoice_TEMPLATE.docx" matches but a client
     *  actually named "Sampleton" doesn't. */
    public static final Pattern TEMPLATE_FILENAME = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(template|sample|example|boilerplate|placeholder|" +
            "lorem|starter|demo|blank|_default)(?:[^a-z0-9]|$)"
    );

    /** True when the file name marks a template/sample/boilerplate document. */
    public static boolean isTemplateName(String fileName) {
        return fileName != null && TEMPLATE_FILENAME.matcher(fileName).find();
    }
}

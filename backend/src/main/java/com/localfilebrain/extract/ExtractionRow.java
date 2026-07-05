package com.localfilebrain.extract;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of an extraction result: all fields pulled from a single document,
 * plus that document's identity for the source citation. Extraction is
 * inherently per-document, so the citation is the document itself (the UI
 * renders a clickable source chip that opens the file) — reusing the same
 * "one source = one file" model the rest of the app already uses.
 *
 * @param docId        batch-local id echoed by the model, mapped back to the file
 * @param fileName     display name (source citation)
 * @param absolutePath path the UI opens on click (source citation)
 * @param fields       field-name → extracted value, in schema order
 */
public record ExtractionRow(int docId, String fileName, String absolutePath,
                            Map<String, ExtractedValue> fields) {

    public ExtractionRow {
        fields = fields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fields);
    }

    /** True when at least one field on this row is flagged ambiguous. */
    public boolean hasAmbiguity() {
        return fields.values().stream().anyMatch(ExtractedValue::isAmbiguous);
    }
}

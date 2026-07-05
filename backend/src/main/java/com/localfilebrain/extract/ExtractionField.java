package com.localfilebrain.extract;

/**
 * One column in an extraction schema: what to pull out of each document.
 *
 * @param name        the column label the user sees and the LLM is asked to fill
 *                    (e.g. "Invoice Number", "Vendor / Supplier")
 * @param type        how the extracted value is validated/formatted
 * @param description a short natural-language hint that helps the model find the
 *                    right value; may be blank
 * @param required    advisory only — a required field that comes back empty is
 *                    surfaced as MISSING, never invented
 */
public record ExtractionField(String name, FieldType type, String description, boolean required) {

    public ExtractionField {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("extraction field name is required");
        name = name.trim();
        if (type == null) type = FieldType.TEXT;
        description = description == null ? "" : description.trim();
    }

    public ExtractionField(String name, FieldType type) { this(name, type, "", false); }
}

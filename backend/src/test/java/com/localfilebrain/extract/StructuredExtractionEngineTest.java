package com.localfilebrain.extract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StructuredExtractionEngineTest {

    private static ExtractionField f(String name, FieldType t) { return new ExtractionField(name, t); }

    private static final List<ExtractionField> INVOICE = List.of(
            f("Invoice Number", FieldType.TEXT),
            f("Invoice Date",   FieldType.DATE),
            f("Total Amount",   FieldType.CURRENCY),
            f("Tax Amount",     FieldType.NUMBER),
            f("Paid",           FieldType.BOOLEAN));

    private static StructuredExtractionEngine.DocPayload doc(int id) {
        return new StructuredExtractionEngine.DocPayload(id, "INV-" + id + ".pdf", List.of("body"));
    }

    // ── happy path: one row per doc, values validated per type ────────────────
    @Test
    void extractsRowPerDocument_withTypedValidation() {
        String reply = """
            {"rows":[
              {"doc":1,"fields":{"Invoice Number":"INV-001","Invoice Date":"2025-04-03",
                 "Total Amount":"15,000","Tax Amount":"2,700","Paid":"yes"}}
            ]}""";
        ExtractionOptions opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));

        StructuredExtractionEngine.BatchResult r =
                StructuredExtractionEngine.extractBatch(List.of(doc(1)), INVOICE, opts, (s, u) -> reply);

        assertFalse(r.unreadable());
        assertEquals(1, r.rows().size());
        Map<String, ExtractedValue> fields = r.rows().get(0).fields();
        assertEquals("INV-001", fields.get("Invoice Number").value());
        assertEquals(ExtractedValue.Status.OK, fields.get("Invoice Date").status()); // ISO → unambiguous
        assertEquals("$15,000", fields.get("Total Amount").value());                 // descriptor applied
        assertEquals("2,700", fields.get("Tax Amount").value());                     // number preserved
        assertEquals("Yes", fields.get("Paid").value());
    }

    // ── date safety: ambiguous numeric date preserved + flagged, never reordered
    @Test
    void ambiguousNumericDate_isFlagged_andPreservedVerbatim() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Invoice Date\":\"03/04/2025\"}}]}";
        var r = StructuredExtractionEngine.extractBatch(
                List.of(doc(1)), List.of(f("Invoice Date", FieldType.DATE)),
                ExtractionOptions.defaults(), (s, u) -> reply);
        ExtractedValue d = r.rows().get(0).fields().get("Invoice Date");
        assertTrue(d.isAmbiguous());
        assertEquals("03/04/2025", d.value(), "value must be preserved exactly as written");
        assertFalse(d.note().isBlank());
    }

    @Test
    void dayGreaterThan12_isNotAmbiguous_butStillNotReformatted() {
        // 15 can only be a day → we can tell the order, so it's not flagged, and
        // we still never rewrite it.
        assertFalse(StructuredExtractionEngine.isAmbiguousNumericDate("15/04/2025"));
        assertFalse(StructuredExtractionEngine.isAmbiguousNumericDate("2025-04-03")); // ISO
        assertFalse(StructuredExtractionEngine.isAmbiguousNumericDate("April 3, 2025"));
        assertTrue(StructuredExtractionEngine.isAmbiguousNumericDate("03/04/2025"));
        assertTrue(StructuredExtractionEngine.isAmbiguousNumericDate("3-4-25"));
    }

    // ── currency uses the supplied descriptor (Indian grouping here) ──────────
    @Test
    void currencyField_usesDescriptorGrouping() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Total Amount\":\"1500000\"}}]}";
        var opts = new ExtractionOptions(new CurrencyDescriptor("₹", CurrencyDescriptor.Grouping.INDIAN));
        var r = StructuredExtractionEngine.extractBatch(
                List.of(doc(1)), List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertEquals("₹15,00,000", r.rows().get(0).fields().get("Total Amount").value());
    }

    @Test
    void currencyField_keepsFractionalPart() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Total Amount\":\"$1,250.75\"}}]}";
        var opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));
        var r = StructuredExtractionEngine.extractBatch(
                List.of(doc(1)), List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertEquals("$1,250.75", r.rows().get(0).fields().get("Total Amount").value());
    }

    // ── null / absent field → MISSING, never invented ─────────────────────────
    @Test
    void absentField_isMissing_notInvented() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Invoice Number\":\"INV-1\",\"Total Amount\":null}}]}";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)), INVOICE,
                ExtractionOptions.defaults(), (s, u) -> reply);
        Map<String, ExtractedValue> fields = r.rows().get(0).fields();
        assertTrue(fields.get("Total Amount").isMissing());
        assertTrue(fields.get("Invoice Date").isMissing()); // key not present at all
        assertEquals("INV-1", fields.get("Invoice Number").value());
    }

    // ── unreadable reply → flagged, no rows fabricated ────────────────────────
    @Test
    void unreadableReply_isSignalled() {
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)), INVOICE,
                ExtractionOptions.defaults(), (s, u) -> "sorry, I cannot do that");
        assertTrue(r.unreadable());
        assertTrue(r.rows().isEmpty());
    }

    // ── hallucinated docId is dropped ─────────────────────────────────────────
    @Test
    void unknownDocId_isDropped() {
        String reply = """
            {"rows":[
              {"doc":1,"fields":{"Invoice Number":"real"}},
              {"doc":99,"fields":{"Invoice Number":"ghost"}}
            ]}""";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)),
                List.of(f("Invoice Number", FieldType.TEXT)), ExtractionOptions.defaults(), (s, u) -> reply);
        assertEquals(1, r.rows().size());
        assertEquals("real", r.rows().get(0).fields().get("Invoice Number").value());
    }

    // ── tolerant JSON isolation (code fences / prose around the object) ───────
    @Test
    void toleratesCodeFencesAndProse() {
        String reply = "Here you go:\n```json\n{\"rows\":[{\"doc\":1,\"fields\":{\"Invoice Number\":\"X\"}}]}\n```";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)),
                List.of(f("Invoice Number", FieldType.TEXT)), ExtractionOptions.defaults(), (s, u) -> reply);
        assertEquals(1, r.rows().size());
        assertEquals("X", r.rows().get(0).fields().get("Invoice Number").value());
    }

    // ── HARDENING: an out-of-range amount must not crash / overflow → MISSING ──
    @Test
    void hugeAmount_doesNotOverflow_isMissing() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Total Amount\":\"123456789012345678901234567890\"}}]}";
        var opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)),
                List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertTrue(r.rows().get(0).fields().get("Total Amount").isMissing());
    }

    @Test
    void negativeCurrency_isPreserved() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Total Amount\":\"-1,500\"}}]}";
        var opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)),
                List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertEquals("$-1,500", r.rows().get(0).fields().get("Total Amount").value());
    }

    // ── nullish sentinels the model sometimes emits → MISSING, not literal text ─
    @Test
    void nullishSentinels_becomeMissing() {
        for (String junk : new String[]{"N/A", "none", "not found", "not stated", "null"}) {
            String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Invoice Number\":\"" + junk + "\"}}]}";
            var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)),
                    List.of(f("Invoice Number", FieldType.TEXT)), ExtractionOptions.defaults(), (s, u) -> reply);
            assertTrue(r.rows().get(0).fields().get("Invoice Number").isMissing(), "should be MISSING: " + junk);
        }
    }

    // ── empty fields object → every field MISSING (nothing fabricated) ────────
    @Test
    void emptyFieldsObject_allMissing() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{}}]}";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)), INVOICE,
                ExtractionOptions.defaults(), (s, u) -> reply);
        assertEquals(1, r.rows().size());
        assertTrue(r.rows().get(0).fields().values().stream().allMatch(ExtractedValue::isMissing));
    }

    // ── boolean normalization incl. paid/unpaid; unknown stays as-is ──────────
    @Test
    void booleanVariants() {
        assertEquals("Yes", StructuredExtractionEngine.validateBoolean("paid").value());
        assertEquals("No",  StructuredExtractionEngine.validateBoolean("unpaid").value());
        assertEquals("Yes", StructuredExtractionEngine.validateBoolean("TRUE").value());
        assertEquals("Partially paid", StructuredExtractionEngine.validateBoolean("Partially paid").value());
    }

    // ── number preserves as written; non-numeric → MISSING ────────────────────
    @Test
    void numberFieldPreservesOrMisses() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Tax Amount\":\"twelve\"}}]}";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)),
                List.of(f("Tax Amount", FieldType.NUMBER)), ExtractionOptions.defaults(), (s, u) -> reply);
        assertTrue(r.rows().get(0).fields().get("Tax Amount").isMissing());
    }

    // ── invalid schema (empty) → empty result, no LLM call attempted ──────────
    @Test
    void emptySchema_returnsEmpty_noCall() {
        boolean[] called = {false};
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1)), List.of(),
                ExtractionOptions.defaults(), (s, u) -> { called[0] = true; return "{}"; });
        assertTrue(r.rows().isEmpty());
        assertFalse(r.unreadable());
        assertFalse(called[0], "no LLM call should be made for an empty schema");
    }

    // ── the same descriptor is applied to every doc (no per-doc detection) ────
    @Test
    void currencyDescriptorAppliedUniformly() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Total Amount\":\"$1,000\"}},{\"doc\":2,\"fields\":{\"Total Amount\":\"2000\"}}]}";
        var opts = new ExtractionOptions(new CurrencyDescriptor("₹", CurrencyDescriptor.Grouping.INDIAN));
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1), doc(2)),
                List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertEquals("₹1,000", r.rows().get(0).fields().get("Total Amount").value());
        assertEquals("₹2,000", r.rows().get(1).fields().get("Total Amount").value());
    }
}

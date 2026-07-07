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

    // A realistic invoice body — grounding checks values against this text.
    private static final String INVOICE_BODY =
            "Invoice Number: INV-001\nInvoice Date: 2025-04-03\nTotal Amount: $15,000\n"
          + "Tax Amount: 2,700\nStatus: Paid";

    private static StructuredExtractionEngine.DocPayload doc(int id, String body) {
        return new StructuredExtractionEngine.DocPayload(id, "INV-" + id + ".pdf", List.of(body));
    }

    /** Nested field object: {"value":..,"evidence":..}. */
    private static String field(String name, String value, String evidence) {
        return "\"" + name + "\":{\"value\":" + (value == null ? "null" : "\"" + value + "\"")
                + ",\"evidence\":\"" + (evidence == null ? "" : evidence) + "\"}";
    }

    // ── happy path: one row per doc, typed validation, evidence attached ───────
    @Test
    void extractsRowPerDocument_withTypedValidation_andEvidence() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Invoice Number", "INV-001",   "Invoice Number: INV-001") + ","
                + field("Invoice Date",   "2025-04-03", "Invoice Date: 2025-04-03") + ","
                + field("Total Amount",   "15,000",    "Total Amount: $15,000") + ","
                + field("Tax Amount",     "2,700",     "Tax Amount: 2,700") + ","
                + field("Paid",           "yes",       "Status: Paid")
                + "}}]}";
        ExtractionOptions opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));

        StructuredExtractionEngine.BatchResult r =
                StructuredExtractionEngine.extractBatch(List.of(doc(1, INVOICE_BODY)), INVOICE, opts, (s, u) -> reply);

        assertFalse(r.unreadable());
        assertEquals(1, r.rows().size());
        Map<String, ExtractedValue> fields = r.rows().get(0).fields();
        assertEquals("INV-001", fields.get("Invoice Number").value());
        assertEquals(ExtractedValue.Status.OK, fields.get("Invoice Date").status()); // ISO, grounded → OK
        assertEquals("$15,000", fields.get("Total Amount").value());                 // descriptor applied
        assertEquals("2,700", fields.get("Tax Amount").value());                     // number preserved
        assertEquals("Yes", fields.get("Paid").value());
        // Evidence travels through for verification.
        assertEquals("Invoice Number: INV-001", fields.get("Invoice Number").evidence());
        assertFalse(fields.get("Total Amount").evidence().isBlank());
    }

    // ── date safety: ambiguous date preserved + flagged (still grounded) ──────
    @Test
    void ambiguousNumericDate_isFlagged_andPreservedVerbatim() {
        String body = "Invoice Date: 03/04/2025";
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Invoice Date", "03/04/2025", "Invoice Date: 03/04/2025") + "}}]}";
        var r = StructuredExtractionEngine.extractBatch(
                List.of(doc(1, body)), List.of(f("Invoice Date", FieldType.DATE)),
                ExtractionOptions.defaults(), (s, u) -> reply);
        ExtractedValue d = r.rows().get(0).fields().get("Invoice Date");
        assertTrue(d.isAmbiguous(), "grounded ambiguous date stays AMBIGUOUS, not UNVERIFIED");
        assertEquals("03/04/2025", d.value(), "value must be preserved exactly as written");
        assertFalse(d.note().isBlank());
    }

    @Test
    void dayGreaterThan12_isNotAmbiguous_butStillNotReformatted() {
        assertFalse(StructuredExtractionEngine.isAmbiguousNumericDate("15/04/2025"));
        assertFalse(StructuredExtractionEngine.isAmbiguousNumericDate("2025-04-03")); // ISO
        assertFalse(StructuredExtractionEngine.isAmbiguousNumericDate("April 3, 2025"));
        assertTrue(StructuredExtractionEngine.isAmbiguousNumericDate("03/04/2025"));
        assertTrue(StructuredExtractionEngine.isAmbiguousNumericDate("3-4-25"));
    }

    // ── currency uses the supplied descriptor (Indian grouping here) ──────────
    @Test
    void currencyField_usesDescriptorGrouping() {
        String body = "Grand Total: 1500000 INR";
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Total Amount", "1500000", "Grand Total: 1500000 INR") + "}}]}";
        var opts = new ExtractionOptions(new CurrencyDescriptor("₹", CurrencyDescriptor.Grouping.INDIAN));
        var r = StructuredExtractionEngine.extractBatch(
                List.of(doc(1, body)), List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertEquals("₹15,00,000", r.rows().get(0).fields().get("Total Amount").value());
    }

    @Test
    void currencyField_keepsFractionalPart() {
        String body = "Amount payable: $1,250.75";
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Total Amount", "$1,250.75", "Amount payable: $1,250.75") + "}}]}";
        var opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));
        var r = StructuredExtractionEngine.extractBatch(
                List.of(doc(1, body)), List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertEquals("$1,250.75", r.rows().get(0).fields().get("Total Amount").value());
    }

    // ── null / absent field → MISSING, never invented (flat shape tolerated) ──
    @Test
    void absentField_isMissing_notInvented() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{\"Invoice Number\":\"INV-1\",\"Total Amount\":null}}]}";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, "Invoice Number: INV-1")), INVOICE,
                ExtractionOptions.defaults(), (s, u) -> reply);
        Map<String, ExtractedValue> fields = r.rows().get(0).fields();
        assertTrue(fields.get("Total Amount").isMissing());
        assertTrue(fields.get("Invoice Date").isMissing()); // key not present at all
        assertEquals("INV-1", fields.get("Invoice Number").value());
    }

    // ── GROUNDING: cited evidence not in the document → UNVERIFIED ─────────────
    @Test
    void evidenceNotInSource_isUnverified_valueKept() {
        String body = "Thank you for your business.";
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Total Amount", "99,999", "Total Amount: 99,999") + "}}]}"; // evidence not in body
        var opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));
        var r = StructuredExtractionEngine.extractBatch(
                List.of(doc(1, body)), List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        ExtractedValue v = r.rows().get(0).fields().get("Total Amount");
        assertTrue(v.isUnverified(), "a value whose evidence isn't in the document must be flagged");
        assertEquals("$99,999", v.value(), "the value is still surfaced, just flagged");
        assertFalse(v.note().isBlank());
    }

    // ── GROUNDING: real-looking evidence but the number isn't in it → UNVERIFIED
    @Test
    void factualValueNotBackedByEvidence_isUnverified() {
        String body = "Total Amount: $15,000";
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Total Amount", "51,000", "Total Amount: $15,000") + "}}]}"; // evidence real, number wrong
        var opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));
        var r = StructuredExtractionEngine.extractBatch(
                List.of(doc(1, body)), List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertTrue(r.rows().get(0).fields().get("Total Amount").isUnverified());
    }

    // ── GROUNDING: a synthesized free-text answer with valid evidence → OK ────
    @Test
    void textAnswerWithValidEvidence_isOk_evidenceAttached() {
        String body = "The agreement renews automatically for 12 months unless cancelled.";
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Answer", "Yes — it auto-renews for 12 months",
                        "renews automatically for 12 months") + "}}]}";
        var r = StructuredExtractionEngine.extractBatch(
                List.of(doc(1, body)), List.of(f("Answer", FieldType.TEXT)),
                ExtractionOptions.defaults(), (s, u) -> reply);
        ExtractedValue v = r.rows().get(0).fields().get("Answer");
        assertEquals(ExtractedValue.Status.OK, v.status(), "prose grounded by a real quote is OK");
        assertEquals("renews automatically for 12 months", v.evidence());
    }

    // ── unreadable reply → retried, then flagged; no rows fabricated ──────────
    @Test
    void unreadableReply_isRetriedThenSignalled() {
        int[] calls = {0};
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, INVOICE_BODY)), INVOICE,
                ExtractionOptions.defaults(), (s, u) -> { calls[0]++; return "sorry, I cannot do that"; });
        assertTrue(r.unreadable());
        assertTrue(r.rows().isEmpty());
        assertEquals(3, calls[0], "an unparseable reply is retried up to the attempt cap");
    }

    // ── retry recovers when a later attempt returns valid JSON ────────────────
    @Test
    void retry_recoversAfterOneBadReply() {
        String good = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Invoice Number", "INV-001", "Invoice Number: INV-001") + "}}]}";
        int[] calls = {0};
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, INVOICE_BODY)),
                List.of(f("Invoice Number", FieldType.TEXT)), ExtractionOptions.defaults(),
                (s, u) -> { calls[0]++; return calls[0] == 1 ? "no json here" : good; });
        assertFalse(r.unreadable());
        assertEquals(1, r.rows().size());
        assertEquals("INV-001", r.rows().get(0).fields().get("Invoice Number").value());
        assertEquals(2, calls[0], "second attempt succeeded");
    }

    // ── hallucinated docId is dropped ─────────────────────────────────────────
    @Test
    void unknownDocId_isDropped() {
        String reply = "{\"rows\":["
                + "{\"doc\":1,\"fields\":{" + field("Invoice Number", "real", "Invoice Number: real") + "}},"
                + "{\"doc\":99,\"fields\":{" + field("Invoice Number", "ghost", "x") + "}}]}";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, "Invoice Number: real")),
                List.of(f("Invoice Number", FieldType.TEXT)), ExtractionOptions.defaults(), (s, u) -> reply);
        assertEquals(1, r.rows().size());
        assertEquals("real", r.rows().get(0).fields().get("Invoice Number").value());
    }

    // ── tolerant JSON isolation (code fences / prose around the object) ───────
    @Test
    void toleratesCodeFencesAndProse() {
        String reply = "Here you go:\n```json\n{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Invoice Number", "X", "Invoice Number: X") + "}}]}\n```";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, "Invoice Number: X")),
                List.of(f("Invoice Number", FieldType.TEXT)), ExtractionOptions.defaults(), (s, u) -> reply);
        assertEquals(1, r.rows().size());
        assertEquals("X", r.rows().get(0).fields().get("Invoice Number").value());
    }

    // ── out-of-range amount must not crash / overflow → MISSING ───────────────
    @Test
    void hugeAmount_doesNotOverflow_isMissing() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Total Amount", "123456789012345678901234567890", "x") + "}}]}";
        var opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, INVOICE_BODY)),
                List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertTrue(r.rows().get(0).fields().get("Total Amount").isMissing());
    }

    @Test
    void negativeCurrency_isPreserved() {
        String body = "Adjustment: -1,500";
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{"
                + field("Total Amount", "-1,500", "Adjustment: -1,500") + "}}]}";
        var opts = new ExtractionOptions(new CurrencyDescriptor("$", CurrencyDescriptor.Grouping.WESTERN));
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, body)),
                List.of(f("Total Amount", FieldType.CURRENCY)), opts, (s, u) -> reply);
        assertEquals("$-1,500", r.rows().get(0).fields().get("Total Amount").value());
    }

    // ── nullish sentinels → MISSING, not literal text ─────────────────────────
    @Test
    void nullishSentinels_becomeMissing() {
        for (String junk : new String[]{"N/A", "none", "not found", "not stated", "null"}) {
            String reply = "{\"rows\":[{\"doc\":1,\"fields\":{" + field("Invoice Number", junk, "x") + "}}]}";
            var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, INVOICE_BODY)),
                    List.of(f("Invoice Number", FieldType.TEXT)), ExtractionOptions.defaults(), (s, u) -> reply);
            assertTrue(r.rows().get(0).fields().get("Invoice Number").isMissing(), "should be MISSING: " + junk);
        }
    }

    // ── empty fields object → every field MISSING (nothing fabricated) ────────
    @Test
    void emptyFieldsObject_allMissing() {
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{}}]}";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, INVOICE_BODY)), INVOICE,
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
        String reply = "{\"rows\":[{\"doc\":1,\"fields\":{" + field("Tax Amount", "twelve", "x") + "}}]}";
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, INVOICE_BODY)),
                List.of(f("Tax Amount", FieldType.NUMBER)), ExtractionOptions.defaults(), (s, u) -> reply);
        assertTrue(r.rows().get(0).fields().get("Tax Amount").isMissing());
    }

    // ── invalid schema (empty) → empty result, no LLM call attempted ──────────
    @Test
    void emptySchema_returnsEmpty_noCall() {
        boolean[] called = {false};
        var r = StructuredExtractionEngine.extractBatch(List.of(doc(1, INVOICE_BODY)), List.of(),
                ExtractionOptions.defaults(), (s, u) -> { called[0] = true; return "{}"; });
        assertTrue(r.rows().isEmpty());
        assertFalse(r.unreadable());
        assertFalse(called[0], "no LLM call should be made for an empty schema");
    }

    // ── grounding helper: digit-run match is grouping-insensitive ─────────────
    @Test
    void valueInText_matchesAcrossGrouping() {
        assertTrue(StructuredExtractionEngine.valueInText("₹1,06,200", StructuredExtractionEngine.norm("balance 106200 due")));
        assertTrue(StructuredExtractionEngine.valueInText("$15,000", StructuredExtractionEngine.norm("total $15,000")));
        assertFalse(StructuredExtractionEngine.valueInText("51000", StructuredExtractionEngine.norm("total 15000")));
    }
}

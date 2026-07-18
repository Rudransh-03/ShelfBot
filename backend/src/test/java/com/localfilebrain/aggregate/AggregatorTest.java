package com.localfilebrain.aggregate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The deterministic merge/conflict logic — the exact scenarios the old engine
 *  got wrong: a balance scattered across docs, a paid client, and a real
 *  amount conflict between two docs. */
class AggregatorTest {

    private static DocFact f(String subject, String key, Long value, Long balance, String status, String src) {
        return new DocFact(subject, key, "", value, balance, status, "", src, "/" + src, "");
    }

    @Test
    void mergesScatteredBalanceOverInvoiceGross() {
        // Invoice states only the gross ($4,200, no status); an email states the
        // real partial balance ($3,000). Merged → owes the balance, not the gross.
        var invoice = f("Blue Ridge", "LS-2041", 4200L, null, "", "invoice.pdf");
        var email   = f("Blue Ridge", "LS-2041", null, 3000L, "PARTIAL", "email.pdf");
        List<DocFact> merged = Aggregator.merge(List.of(invoice, email));
        assertEquals(1, merged.size());
        assertEquals(3000L, merged.get(0).owed());
    }

    @Test
    void paidConfirmationBeatsBareInvoice() {
        // Invoice shows $5,000 due (no status); a payment confirmation says PAID.
        // Merged → settled, owes nothing.
        var invoice = f("Summit", "LS-2033", 5000L, null, "", "invoice.pdf");
        var paid    = f("Summit", "LS-2033", null, null, "PAID", "confirmation.pdf");
        List<DocFact> merged = Aggregator.merge(List.of(invoice, paid));
        assertEquals(1, merged.size());
        assertEquals(0L, merged.get(0).owed());
    }

    @Test
    void mergedNamePrefersRealNameOverPlaceholder() {
        // The email only says "client"; the invoice names "Blue Ridge Landscaping
        // LLC". Merged subject must be the real name, not the placeholder.
        var email   = f("client", "LS-2041", null, 3000L, "PARTIAL", "email.pdf");
        var invoice = f("Blue Ridge Landscaping LLC", "LS-2041", 4200L, null, "", "invoice.pdf");
        List<DocFact> merged = Aggregator.merge(List.of(email, invoice));
        assertEquals("Blue Ridge Landscaping LLC", merged.get(0).subject());
        assertEquals(3000L, merged.get(0).owed());
    }

    @Test
    void flagsAmountConflictBetweenTwoDocs() {
        var invoice = f("Blue Ridge", "LS-2041", 4200L, null, "", "invoice.pdf");
        var tracker = f("Blue Ridge", "LS-2041", 4000L, null, "PARTIAL", "tracker.csv");
        List<String> conflicts = Aggregator.conflicts(List.of(invoice, tracker));
        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).contains("4200") && conflicts.get(0).contains("4000"));
    }

    @Test
    void totalSumsOwedAcrossClients() {
        List<DocFact> merged = Aggregator.merge(List.of(
                f("Blue Ridge", "LS-2041", 4200L, 3000L, "PARTIAL", "e.pdf"),
                f("Aurora", "LS-2055", 2750L, null, "PENDING", "a.pdf"),
                f("Summit", "LS-2033", 5000L, null, "PAID", "s.pdf")));   // paid → 0
        assertEquals(3000L + 2750L, Aggregator.sumOwed(merged));
    }
}

package com.localfilebrain.query;

import com.localfilebrain.query.FeeReceivables.FeeRow;
import com.localfilebrain.query.FeeReceivables.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic core of the cross-document fee aggregator — the parts that run
 *  with no DB and no LLM: status/owed logic, client & invoice-id normalization
 *  (the dedup keys), the fee-ledger table parser, and the prose gate. */
class FeeReceivablesTest {

    // ── status → how much is owed ────────────────────────────────────────────

    @Test
    void owedIsFullAmountWhenPending() {
        assertEquals(29500, FeeReceivables.owedFor(Status.PENDING, 29500, 0));
    }

    @Test
    void owedIsRemainingBalanceWhenPartial() {
        // Acme: billed 40,000, paid 25,000 → 15,000 still owed.
        assertEquals(15000, FeeReceivables.owedFor(Status.PARTIAL, 40000, 15000));
    }

    @Test
    void statedBalanceWinsEvenWhenLabelledPending() {
        // Silverline live miss: model returned PENDING, amount 60,000,
        // balanceDue 30,000 — the owed figure is the remaining 30,000.
        assertEquals(30000, FeeReceivables.owedFor(Status.PENDING, 60000, 30000));
        // No balance given → the whole billed amount is owed.
        assertEquals(29500, FeeReceivables.owedFor(Status.PENDING, 29500, 0));
    }

    @Test
    void partialBalanceRowBeatsBareInvoiceInDedup() {
        // Same invoice in two docs: an email states the real PARTIAL balance
        // ($3,000), the invoice PDF shows only the gross with no payment status.
        // The PARTIAL row must win so the client is reported as owing $3,000, not
        // the invoice's face value with unknown status.
        var partial = new FeeReceivables.FeeRow("Blue Ridge", "LS-2041", 4200, 3000,
                Status.PARTIAL, "email.pdf", "/e", "", "");
        var invoice = new FeeReceivables.FeeRow("Blue Ridge", "LS-2041", 4200, 0,
                Status.UNKNOWN, "invoice.pdf", "/i", "", "");
        assertTrue(FeeReceivables.rowRank(partial) > FeeReceivables.rowRank(invoice));
        // PENDING (full owed) also beats a bare UNKNOWN invoice.
        var pending = new FeeReceivables.FeeRow("X", "N-1", 900, 900, Status.PENDING, "s", "/s", "", "");
        assertTrue(FeeReceivables.rowRank(pending) > FeeReceivables.rowRank(invoice));
    }

    @Test
    void settledOrProspectOwesNothing() {
        assertEquals(0, FeeReceivables.owedFor(Status.PAID, 53100, 0));
        assertEquals(0, FeeReceivables.owedFor(Status.RECEIVED, 25000, 0));
        assertEquals(0, FeeReceivables.owedFor(Status.PROSPECT, 35000, 0));
        assertEquals(0, FeeReceivables.owedFor(Status.UNKNOWN, 21240, 0));
    }

    @Test
    void statusWordsNormalize() {
        assertEquals(Status.PENDING, FeeReceivables.normStatus("PENDING"));
        assertEquals(Status.PENDING, FeeReceivables.normStatus("outstanding"));
        assertEquals(Status.PAID, FeeReceivables.normStatus("paid"));
        assertEquals(Status.RECEIVED, FeeReceivables.normStatus("RECEIVED"));
        assertEquals(Status.PARTIAL, FeeReceivables.normStatus("partly paid / PARTIAL"));
        assertEquals(Status.PROSPECT, FeeReceivables.normStatus("PROSPECT"));
        // A compliance status is NOT a fee status.
        assertEquals(Status.UNKNOWN, FeeReceivables.normStatus("NOT FILED (missing)"));
    }

    // ── dedup keys ───────────────────────────────────────────────────────────

    @Test
    void invoiceIdNormalizesAcrossFormats() {
        // Tracker "MA-015" and invoice title "MA/2026-27/015" must collide.
        assertEquals(FeeReceivables.normId("MA-015"), FeeReceivables.normId("MA/2026-27/015"));
        assertEquals("MA015", FeeReceivables.normId("MA-015"));
    }

    @Test
    void invoiceIdDerivableFromFileName() {
        assertEquals("MA015", FeeReceivables.feeIdFromName("MA_Fee_Invoice_2026-27_015_GuptaHardware.pdf"));
        assertEquals("MA016", FeeReceivables.feeIdFromName("MA_Fee_Invoice_2026-27_016_AnjaliRao.pdf"));
        assertNull(FeeReceivables.feeIdFromName("acme_partial_payment_note.txt"));
    }

    @Test
    void clientNameNormalizesPastHonorificsAndSuffixes() {
        assertEquals(FeeReceivables.normClient("Dr Anjali Rao"), FeeReceivables.normClient("anjali rao"));
        assertEquals(FeeReceivables.normClient("Gupta Hardware"), FeeReceivables.normClient("M/s Gupta Hardware"));
        // Distinct clients stay distinct.
        assertNotEquals(FeeReceivables.normClient("Gupta Hardware"), FeeReceivables.normClient("Gupta Textiles"));
    }

    // ── fee-ledger table parsing (the consolidated tracker) ─────────────────

    private static final String TRACKER = String.join("\n",
            "Client,Invoice,Amount,Raised,Status,Notes",
            "Zenlite Interiors LLP,MA-013,44250,10-04-2026,PAID,neft 18/04",
            "Meridian Exports Pvt Ltd,MA-014,53100,05-06-2026,PAID,debit 05/06",
            "Gupta Hardware,MA-015,29500,05-06-2026,PENDING,promised by 10 July",
            "Dr Anjali Rao,MA-016,17700,20-06-2026,PENDING,after ITR filing",
            "Kumar Constructions,QUOTE,35000,,PROSPECT,not signed yet",
            "Zenlite Interiors LLP,CASH-ADHOC,25000,03-07-2026,RECEIVED,cash - not in books",
            "Gupta Textiles,MA-017,31860,28-06-2026,PAID,neft 04/07");

    @Test
    void parsesFeeTrackerRowsWithStatusAndAmount() {
        List<FeeRow> rows = FeeReceivables.parseTracker(TRACKER, null);
        assertNotNull(rows, "a fee ledger with Status+Amount columns must parse");
        // Two PENDING rows, total still owed 47,200 from the tracker alone.
        long owed = rows.stream().filter(r -> r.owed() > 0).mapToLong(FeeRow::owed).sum();
        assertEquals(47200, owed);
        // Prospect and received rows contribute nothing owed.
        assertTrue(rows.stream().anyMatch(r -> r.status() == Status.PROSPECT && r.owed() == 0));
        assertTrue(rows.stream().anyMatch(r -> r.status() == Status.RECEIVED && r.owed() == 0));
    }

    @Test
    void complianceTrackerIsNotAFeeLedger() {
        // firm_wide_pending_tracker: Status column but NO Amount column → null,
        // so it never gets summed as if it were fees.
        String compliance = String.join("\n",
                "Client,Item,Due Date,Status",
                "Meridian Exports,DRC-01A reply,18-07-2026,PENDING",
                "Zenlite Interiors,GSTR-3B May 2026,20-07-2026,NOT FILED (missing)");
        assertNull(FeeReceivables.parseTracker(compliance, null));
    }

    // ── prose gate (which messy docs reach the LLM) ─────────────────────────

    @Test
    void proseFeeNoteIsAFirmReceivable() {
        assertTrue(FeeReceivables.looksLikeFirmReceivable(
                "Acme Corporation - our professional fee bill Rs. 40,000: received Rs. 25,000; "
              + "BALANCE Rs. 15,000 still outstanding."));
    }

    @Test
    void vendorBillToTheFirmIsNotAReceivable() {
        // The firm is the PAYER here — not money owed TO the firm.
        assertFalse(FeeReceivables.looksLikeFirmReceivable(
                "VENDOR INVOICE From: Deepak Sharma Stationers To: Malhotra & Associates. "
              + "Item: printer paper. Amount: Rs. 4,500."));
    }

    @Test
    void clientOwnSalesInvoiceIsNotAFirmReceivable() {
        // A client's sales invoice to their own customer, no professional-fee /
        // MA-id / owed signal → not summed as a fee owed to the firm.
        assertFalse(FeeReceivables.looksLikeFirmReceivable(
                "TAX INVOICE. Rohan Mehta to ABC Traders. Goods supplied. Amount Rs. 82,000."));
    }
}

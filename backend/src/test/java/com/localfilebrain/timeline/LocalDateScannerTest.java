package com.localfilebrain.timeline;

import com.localfilebrain.ingestion.IndexMetadataStore.NewDate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link LocalDateScanner#extractEvents} — the free, local extraction of
 * obligation dates that powers the Timeline. High precision is the contract:
 * a date is an event ONLY when an obligation trigger (due / expires / renewal /
 * filing / response / deadline) sits just before it.
 */
class LocalDateScannerTest {

    @Test
    void paymentDue_dmyFormat() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Total: ₹1,20,000. Payment due by 15 March 2026 via NEFT.");
        assertEquals(1, out.size());
        assertEquals("2026-03-15", out.get(0).eventDate());
        assertEquals("Payment due", out.get(0).title());
    }

    @Test
    void expiry_numericIndianFormat_parsedAsDayFirst() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "FSSAI licence valid until 28/02/2026. Renew before expiry.");
        assertEquals(1, out.size());
        assertEquals("2026-02-28", out.get(0).eventDate());
        assertEquals("Expires", out.get(0).title());
    }

    @Test
    void filingDue_isoFormat() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "GSTR-3B filing due date: 2026-04-20 for the March period.");
        assertEquals(1, out.size());
        assertEquals("2026-04-20", out.get(0).eventDate());
        assertEquals("Filing due", out.get(0).title());
    }

    @Test
    void responseDue_mdyFormat() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "You are required to respond by April 4, 2026 to this notice.");
        assertEquals(1, out.size());
        assertEquals("2026-04-04", out.get(0).eventDate());
        assertEquals("Response due", out.get(0).title());
    }

    @Test
    void plainDatesWithoutTrigger_ignored() {
        // Issue dates, birthdays, transaction dates — no obligation, no event.
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Invoice issued on 01 March 2026. Statement period 2026-01-01 to "
              + "2026-01-31. Born 12/05/1994.");
        assertTrue(out.isEmpty());
    }

    @Test
    void impossibleNumericDate_skippedNotCrashed() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Deadline reference 99/99/2026 is not a date.");
        assertTrue(out.isEmpty());
    }

    @Test
    void ambiguousNumeric_fallsBackToMonthFirstWhenDayFirstImpossible() {
        // 03/25/2026 can't be DD/MM (month 25) → parsed as MM/DD.
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Renewal due 03/25/2026 for the service contract.");
        assertEquals(1, out.size());
        assertEquals("2026-03-25", out.get(0).eventDate());
    }

    @Test
    void duplicateDateAndTitle_dedupedWithinFile() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Payment due 15 March 2026. Reminder: payment due 15/03/2026.");
        assertEquals(1, out.size());
    }

    @Test
    void multipleDistinctObligations_allCaptured() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Licence expires 28 February 2026. Renewal due by 15 February 2026.");
        assertEquals(2, out.size());
    }

    @Test
    void excerptCarriesSurroundingContext() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "The registration certificate expires on 30 June 2026 unless renewed.");
        assertEquals(1, out.size());
        assertTrue(out.get(0).sourceExcerpt().contains("expires on 30 June 2026"));
    }

    @Test
    void nullAndBlank_returnEmpty() {
        assertTrue(LocalDateScanner.extractEvents(null).isEmpty());
        assertTrue(LocalDateScanner.extractEvents("   ").isEmpty());
    }

    @Test
    void ordinalDate_parsed() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Rent payment due by 5th March 2026 as per the agreement.");
        assertEquals(1, out.size());
        assertEquals("2026-03-05", out.get(0).eventDate());
    }

    @Test
    void ofForm_parsed() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "The licence expires on the 21st of June 2026.");
        assertEquals(1, out.size());
        assertEquals("2026-06-21", out.get(0).eventDate());
    }

    @Test
    void completedObligation_notRecorded() {
        // The renewal already happened — this date is history, not an action item.
        assertTrue(LocalDateScanner.extractEvents(
                "Insurance renewal was completed on 04 January 2026.").isEmpty());
        assertTrue(LocalDateScanner.extractEvents(
                "Payment due amount was paid on 10/01/2026 via NEFT.").isEmpty());
        assertTrue(LocalDateScanner.extractEvents(
                "GST filing due for Dec — already filed 2026-01-11.").isEmpty());
    }

    @Test
    void futureObligationPhrasing_notSuppressedByGuard() {
        // "filed on or before" is an obligation, not a completion.
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Return must be filed on or before 20 April 2026. Filing due accordingly.");
        assertEquals(1, out.size());
        assertEquals("2026-04-20", out.get(0).eventDate());
    }

    @Test
    void completedAndOpenObligations_onlyOpenOneKept() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Renewal was completed on 04 January 2026. Next renewal due by 04 January 2027.");
        assertEquals(1, out.size());
        assertEquals("2027-01-04", out.get(0).eventDate());
        assertEquals("Renewal", out.get(0).title());
    }

    @Test
    void recordKeepingLabels_notObligations() {
        // An ITR acknowledgment's "Date of filing :" records a COMPLETED filing —
        // even with an obligation trigger word earlier in the window, it must
        // not surface as overdue (live false positive from Gupta_ITR3_Ack).
        assertTrue(LocalDateScanner.extractEvents(
                "Due date service used. Ack No : 458219340280626 Date of filing : 28-06-2026 Gross income").isEmpty());
        assertTrue(LocalDateScanner.extractEvents(
                "Filing deadline met. Filed on : 28-06-2026 as acknowledged.").isEmpty());
        assertTrue(LocalDateScanner.extractEvents(
                "Renewal receipt. Payment date : 12/05/2026 confirmed.").isEmpty());
    }

    @Test
    void dueOrLastDateOfFiling_stillObligations() {
        // "due/last date of filing" is a deadline, not a record — the label
        // guard must not suppress it.
        List<NewDate> due = LocalDateScanner.extractEvents(
                "The due date of filing : 31 July 2026 for AY 2026-27.");
        assertEquals(1, due.size());
        assertEquals("2026-07-31", due.get(0).eventDate());

        List<NewDate> last = LocalDateScanner.extractEvents(
                "Last date of filing : 31 July 2026. Penalties apply after.");
        assertEquals(1, last.size());
        assertEquals("2026-07-31", last.get(0).eventDate());
    }

    @Test
    void excerpt_isSentenceBounded_noMidWordDebris() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Ack No : 458219340280626 processed successfully. GST payment due by 15 July 2026 for the June period. Gross totals follow.");
        assertEquals(1, out.size());
        // The stored context is the clean sentence — no ack-number lead-in, no
        // mid-word tail like "Gro".
        assertEquals("GST payment due by 15 July 2026 for the June period.",
                out.get(0).sourceExcerpt());
    }

    @Test
    void excerpt_abbreviationsDontEndTheSentence() {
        List<NewDate> out = LocalDateScanner.extractEvents(
                "Electricity bill total Rs. 2,340 payment due by 3 July 2026 to Acme Power.");
        assertEquals(1, out.size());
        assertEquals("Electricity bill total Rs. 2,340 payment due by 3 July 2026 to Acme Power.",
                out.get(0).sourceExcerpt());
    }

    @Test
    void absurdYears_rejected() {
        assertTrue(LocalDateScanner.extractEvents(
                "Warranty expires 31/12/9999 (lifetime).").isEmpty());
        assertTrue(LocalDateScanner.extractEvents(
                "Deed deadline dated 15 March 1897.").isEmpty());
    }

    // ── extractPrimaryDate: the document's OWN date for period questions ─────

    @Test
    void primaryDate_fileNameMonthYearWins() {
        // The name says January — even if the pay-out happened in February.
        assertEquals("2024-01-01", LocalDateScanner.extractPrimaryDate(
                "SharmaBakery-Salary-Slip-Jan2024.pdf",
                "Salary slip. Pay date: 01 February 2024. Net pay 62,000."));
        assertEquals("2024-02-01", LocalDateScanner.extractPrimaryDate(
                "VermaTextiles-Bank-Statement-Feb2024.pdf", "statement text"));
        assertEquals("2026-04-01", LocalDateScanner.extractPrimaryDate(
                "report_2026-04.pdf", "quarterly report"));
    }

    @Test
    void primaryDate_contentCueBeatsOtherDates() {
        assertEquals("2024-03-05", LocalDateScanner.extractPrimaryDate(
                "invoice330.pdf",
                "TAX INVOICE\nInvoice date: 05 March 2024\nPayment due by: 04 April 2024"));
        assertEquals("2024-03-12", LocalDateScanner.extractPrimaryDate(
                "notice.pdf",
                "Compliance notice issued 12 March 2024. Response required by 27 March 2024."));
    }

    @Test
    void primaryDate_bareMonthYearInContent() {
        assertEquals("2024-03-01", LocalDateScanner.extractPrimaryDate(
                "bill.pdf", "ELECTRICITY BILL\nBill month: March 2024\nUnits: 320"));
    }

    @Test
    void primaryDate_fallsBackToFirstDate() {
        assertEquals("2024-01-10", LocalDateScanner.extractPrimaryDate(
                "freelance.pdf", "FREELANCE INVOICE #100\n10 January 2024\nAmount: 25,000"));
    }

    @Test
    void primaryDate_noneFound_returnsNull() {
        assertNull(LocalDateScanner.extractPrimaryDate("notes.md", "ideas for the weekend"));
        assertNull(LocalDateScanner.extractPrimaryDate(null, null));
    }
}

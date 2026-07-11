package com.localfilebrain.query;

import com.localfilebrain.ingestion.IndexMetadataStore.DateRow;
import com.localfilebrain.ingestion.IndexMetadataStore.DeadlineRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the extracted-obligations chat context: for action-flavored questions
 * the model receives Rudo's own dated-obligation list (deadlines + timeline
 * rows), so a due date can never be missed just because its document didn't
 * make the 40-chunk retrieval pool (live miss: both 2026 notices absent while
 * a stale 2024 one answered alone).
 */
class ObligationsContextTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 5);

    private static DeadlineRow deadline(String path, String iso, String title, String status) {
        return new DeadlineRow(9, path, path.substring(path.lastIndexOf('/') + 1),
                "hash", title, "desc", iso, "PAYMENT", "HIGH", null,
                status, false, "…", "t", "t");
    }

    private static DateRow date(String path, String iso, String title) {
        return new DateRow(1, path, path.substring(path.lastIndexOf('/') + 1),
                iso, title, "…", "Notice");
    }

    @Test
    void actionFlavoredDetection() {
        assertTrue(QueryEngine.isActionFlavored("which notices need a response and by when?"));
        assertTrue(QueryEngine.isActionFlavored("what should I chase this week?"));
        assertTrue(QueryEngine.isActionFlavored("anything urgent or overdue?"));
        assertFalse(QueryEngine.isActionFlavored("who is Suresh Gupta?"));
        assertFalse(QueryEngine.isActionFlavored("summarize the Meridian tax audit extract"));
    }

    @Test
    void mergesDeadlinesAndDates_deadlineWinsDuplicates_sortedSoonestFirst() {
        String block = QueryEngine.obligationsBlock(
                List.of(deadline("/a/drc.pdf", "2026-07-18", "Meridian DRC-01A reply", "PENDING")),
                List.of(date("/a/drc.pdf", "2026-07-18", "Due"),          // duplicate — deadline wins
                        date("/a/gupta.pdf", "2026-07-10", "Payment due")),
                null, java.util.Map.of("/a/drc.pdf", "Notice"), TODAY);
        assertNotNull(block);
        String[] lines = block.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("2026-07-10"), "soonest first");
        assertTrue(lines[1].contains("Meridian DRC-01A reply"), "deadline title wins the dupe");
        assertTrue(lines[1].contains("drc.pdf"));
        assertTrue(lines[1].contains("a Notice document"), "doc type tag present");
    }

    @Test
    void windowExcludesAncientAndFarFuture_scopeExcludesOtherClients() {
        String block = QueryEngine.obligationsBlock(
                List.of(deadline("/a/old.pdf", "2024-03-27", "Acme notice reply", "PENDING"),
                        deadline("/a/far.pdf", "2027-09-01", "Far renewal", "PENDING"),
                        deadline("/b/out-of-scope.pdf", "2026-07-20", "Other client", "PENDING"),
                        deadline("/a/done.pdf", "2026-07-12", "Handled", "DONE"),
                        deadline("/a/in.pdf", "2026-07-15", "Internet bill", "PENDING")),
                List.of(),
                Set.of("/a/old.pdf", "/a/far.pdf", "/a/done.pdf", "/a/in.pdf"), null, TODAY);
        assertNotNull(block);
        assertTrue(block.contains("Internet bill"));
        assertFalse(block.contains("Acme notice reply"), "2024 is ancient history");
        assertFalse(block.contains("Far renewal"), "beyond the ahead-window");
        assertFalse(block.contains("Other client"), "out of scope");
        assertFalse(block.contains("Handled"), "non-PENDING excluded");
    }

    @Test
    void perItemDetailDetection_forListOverride() {
        // These want a date/amount per item — a bare file list can't answer them.
        assertTrue(QueryEngine.asksPerItemDetail("which notices need a response and by when?"));
        assertTrue(QueryEngine.asksPerItemDetail("which policies expire this year?"));
        // These are genuine enumerations and must STAY on the inventory path.
        assertFalse(QueryEngine.asksPerItemDetail("which documents are from May 2026?"));
        assertFalse(QueryEngine.asksPerItemDetail("which of my fee invoices are unpaid?"));
        assertFalse(QueryEngine.asksPerItemDetail("list all my contracts"));
    }

    @Test
    void subjectQualifiers_keepEntityWords_dropKindAndTemporalWords() {
        assertEquals(Set.of("meridian"),
                QueryEngine.subjectQualifiers("Meridian GSTR-3B returns"));
        assertEquals(Set.of("rohan", "mehta"),
                QueryEngine.subjectQualifiers("Rohan Mehta invoices"));
        assertTrue(QueryEngine.subjectQualifiers("fee invoices from June 2026").isEmpty(),
                "months/kind/fee words must never be enforced against file names");
        assertTrue(QueryEngine.subjectQualifiers("documents").isEmpty());
        assertTrue(QueryEngine.subjectQualifiers(null).isEmpty());
    }

    @Test
    void emptyInputs_yieldNull() {
        assertNull(QueryEngine.obligationsBlock(List.of(), List.of(), null, null, TODAY));
        assertNull(QueryEngine.obligationsBlock(null, null, null, null, TODAY));
    }
}

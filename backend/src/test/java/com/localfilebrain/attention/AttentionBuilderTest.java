package com.localfilebrain.attention;

import com.localfilebrain.attention.AttentionBuilder.Item;
import com.localfilebrain.attention.AttentionBuilder.Result;
import com.localfilebrain.deadline.MissingDocumentDetector.MissingDoc;
import com.localfilebrain.ingestion.IndexMetadataStore.DateRow;
import com.localfilebrain.ingestion.IndexMetadataStore.DeadlineRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link AttentionBuilder} — the deterministic merge behind the
 * "Needs attention" panel. Accuracy contract: nothing invented (input rows
 * only), deadlines beat timeline duplicates, strictly-today window (due later
 * is the Deadlines tab's job), and dismissed items never resurface.
 */
class AttentionBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 4);

    private static DateRow date(String path, String iso, String title) {
        return new DateRow(1, path, path.substring(path.lastIndexOf('/') + 1),
                iso, title, "…excerpt…", "Invoice");
    }

    private static DeadlineRow deadline(String path, String iso, String title, String status) {
        return new DeadlineRow(9, path, path.substring(path.lastIndexOf('/') + 1),
                "hash", title, "desc", iso, "PAYMENT", "HIGH", null,
                status, false, "…excerpt…", "t", "t");
    }

    @Test
    void bucketsAndOrder_dueTodayFirstThenOverdueRecentFirst() {
        Result r = AttentionBuilder.build(
                List.of(
                        date("/a/overdue-old.pdf", "2026-06-01", "Filing due"),
                        date("/a/overdue-new.pdf", "2026-07-01", "Expires"),
                        date("/a/due-today.pdf",   "2026-07-04", "Due")),
                List.of(), List.of(), Set.of(), TODAY);
        assertEquals(3, r.total());
        assertEquals(1, r.dueToday());
        assertEquals(2, r.overdue());
        assertEquals("/a/due-today.pdf",   r.items().get(0).path());
        assertEquals("DUE_TODAY",          r.items().get(0).bucket());
        assertEquals("/a/overdue-new.pdf", r.items().get(1).path()); // recent miss first
        assertEquals("/a/overdue-old.pdf", r.items().get(2).path());
    }

    @Test
    void anythingDueLaterThanToday_excluded() {
        // Tomorrow onwards is the Deadlines tab's job — this panel is strictly
        // "what needs me today".
        Result r = AttentionBuilder.build(
                List.of(date("/a/due-tomorrow.pdf", "2026-07-05", "Payment due"),
                        date("/a/renewal.pdf",      "2026-12-31", "Renewal")),
                List.of(deadline("/a/next-week.pdf", "2026-07-10", "Pay invoice", "PENDING")),
                List.of(), Set.of(), TODAY);
        assertEquals(0, r.total());
    }

    @Test
    void ancientOverdue_excluded() {
        // A first index of an old archive extracts dates years in the past —
        // history, not action items.
        Result r = AttentionBuilder.build(
                List.of(date("/a/archive.pdf", "2024-03-14", "Payment due"),
                        date("/a/recent.pdf",  "2026-06-20", "Payment due")),
                List.of(deadline("/a/old-deadline.pdf", "2023-01-01", "Ancient", "PENDING")),
                List.of(), Set.of(), TODAY);
        assertEquals(1, r.total());
        assertEquals("/a/recent.pdf", r.items().get(0).path());
    }

    @Test
    void deadlineWinsOverTimelineDuplicate() {
        Result r = AttentionBuilder.build(
                List.of(date("/a/inv.pdf", "2026-07-04", "Payment due")),
                List.of(deadline("/a/inv.pdf", "2026-07-04", "Pay Acme invoice", "PENDING")),
                List.of(), Set.of(), TODAY);
        assertEquals(1, r.total());
        Item it = r.items().get(0);
        assertEquals("deadline", it.kind());
        assertEquals("deadline:9", it.id());
        assertEquals("Pay Acme invoice", it.title());
    }

    @Test
    void handledDeadlines_excluded_butTimelineDateStillShows() {
        // DONE/DISMISSED deadlines don't count — but they also don't suppress
        // the independent timeline date (only PENDING ones enter the dedup set).
        Result r = AttentionBuilder.build(
                List.of(date("/a/inv.pdf", "2026-07-04", "Payment due")),
                List.of(deadline("/a/inv.pdf", "2026-07-04", "Pay invoice", "DONE"),
                        deadline("/a/other.pdf", "2026-07-03", "Renew licence", "DISMISSED")),
                List.of(), Set.of(), TODAY);
        assertEquals(1, r.total());
        assertEquals("date", r.items().get(0).kind());
    }

    @Test
    void dismissedItems_neverResurface() {
        DateRow due = date("/a/inv.pdf", "2026-07-04", "Payment due");
        MissingDoc gap = new MissingDoc("GST return", "Sharma Bakery", "February 2024",
                "monthly", "HIGH", 3);

        Result before = AttentionBuilder.build(List.of(due), List.of(), List.of(gap),
                Set.of(), TODAY);
        assertEquals(2, before.total());
        String dateId    = before.items().get(0).id();
        String missingId = before.items().get(1).id();
        assertEquals("date:/a/inv.pdf|2026-07-04", dateId);
        assertEquals("missing:GST return|February 2024", missingId);

        Result after = AttentionBuilder.build(List.of(due), List.of(), List.of(gap),
                Set.of(dateId, missingId), TODAY);
        assertEquals(0, after.total());
    }

    @Test
    void undatedOrBadDatedDeadlines_skipped() {
        Result r = AttentionBuilder.build(List.of(),
                List.of(deadline("/a/x.pdf", null, "No date", "PENDING"),
                        deadline("/a/y.pdf", "soonish", "Bad date", "PENDING")),
                List.of(), Set.of(), TODAY);
        assertEquals(0, r.total());
    }

    @Test
    void missingDocs_appendedLast() {
        Result r = AttentionBuilder.build(
                List.of(date("/a/due.pdf", "2026-07-04", "Due")),
                List.of(),
                List.of(new MissingDoc("GST return", "Sharma Bakery", "February 2024",
                        "monthly", "HIGH", 3)),
                Set.of(), TODAY);
        assertEquals(2, r.total());
        assertEquals(1, r.missing());
        Item last = r.items().get(1);
        assertEquals("missing", last.kind());
        assertTrue(last.title().contains("GST return"));
        assertTrue(last.title().contains("February 2024"));
    }

    @Test
    void emptyInputs_emptyResult() {
        Result r = AttentionBuilder.build(List.of(), List.of(), List.of(), Set.of(), TODAY);
        assertEquals(0, r.total());
        Result rn = AttentionBuilder.build(null, null, null, null, TODAY);
        assertEquals(0, rn.total());
    }
}

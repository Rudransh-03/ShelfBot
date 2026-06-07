package com.localfilebrain.deadline;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.DeadlineRow;
import com.localfilebrain.ingestion.IndexMetadataStore.NewDeadline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DeadlineMaintenanceTest {

    @TempDir Path tmp;
    private IndexMetadataStore store;

    @BeforeEach void setUp() { store = new IndexMetadataStore(tmp.resolve("meta.db")); }
    @AfterEach  void tearDown() { store.close(); }

    // ── nextOccurrence ────────────────────────────────────────────────────────

    @Test
    void nextOccurrence_rollsYearlyPastFirstFutureOccurrence() {
        // 14 Mar each year; today 7 Jun 2026 → this year's Mar already passed → next is 2027-03-14.
        assertEquals(LocalDate.of(2027, 3, 14),
                DeadlineMaintenance.nextOccurrence(LocalDate.of(2024, 3, 14), "YEARLY", LocalDate.of(2026, 6, 7)));
    }

    @Test
    void nextOccurrence_monthlyAndQuarterly() {
        assertEquals(LocalDate.of(2026, 6, 15),
                DeadlineMaintenance.nextOccurrence(LocalDate.of(2026, 1, 15), "MONTHLY", LocalDate.of(2026, 6, 7)));
        assertEquals(LocalDate.of(2026, 7, 1),
                DeadlineMaintenance.nextOccurrence(LocalDate.of(2026, 1, 1), "QUARTERLY", LocalDate.of(2026, 6, 7)));
    }

    @Test
    void nextOccurrence_nonRecurringUnchanged_andTodayKept() {
        LocalDate due = LocalDate.of(2020, 1, 1);
        assertEquals(due, DeadlineMaintenance.nextOccurrence(due, "NONE", LocalDate.of(2026, 6, 7)));
        // Due exactly today stays today (not rolled).
        LocalDate today = LocalDate.of(2026, 6, 7);
        assertEquals(today, DeadlineMaintenance.nextOccurrence(today, "YEARLY", today));
    }

    // ── purgeAndRoll over the store ───────────────────────────────────────────

    private void insert(String path, String dueDate, String recurring) {
        store.replaceDeadlinesForFile(path, "f.txt", "hash-" + path,
                List.of(new NewDeadline("t", "d", dueDate, "DEADLINE", "HIGH", recurring, "src")));
    }

    @Test
    void purgeAndRoll_deletesOneTimePast_rollsRecurring_keepsFuture() {
        LocalDate today = LocalDate.of(2026, 6, 7);
        insert("/one-time-past", "2024-03-14", "NONE");    // → deleted
        insert("/recurring-past", "2024-03-14", "YEARLY"); // → rolled to 2027-03-14
        insert("/future", "2026-12-01", "NONE");           // → untouched
        insert("/today", today.toString(), "NONE");        // → kept (not strictly past)

        DeadlineMaintenance.purgeAndRoll(store, today);

        Map<String, DeadlineRow> byPath = store.listDeadlines("all").stream()
                .collect(Collectors.toMap(DeadlineRow::absolutePath, Function.identity()));

        assertFalse(byPath.containsKey("/one-time-past"), "past one-time must be deleted");
        assertTrue(byPath.containsKey("/recurring-past"), "recurring must survive");
        assertEquals("2027-03-14", byPath.get("/recurring-past").dueDate(), "recurring rolled to next occurrence");
        assertEquals("2026-12-01", byPath.get("/future").dueDate(), "future untouched");
        assertTrue(byPath.containsKey("/today"), "today's deadline kept");
    }
}

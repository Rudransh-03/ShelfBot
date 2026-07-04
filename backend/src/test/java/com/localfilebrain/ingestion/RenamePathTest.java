package com.localfilebrain.ingestion;

import com.localfilebrain.model.FileRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link IndexMetadataStore#renamePath} — the SQLite half of reorg "index
 * healing". After a move, EVERY path-keyed record must follow the file: the
 * index row itself, the cached summary (an LLM artifact the user must never
 * re-pay for), timeline dates, scan markers, and the doc type.
 */
class RenamePathTest {

    @TempDir Path tmp;
    private IndexMetadataStore meta;

    @BeforeEach void setUp() { meta = new IndexMetadataStore(tmp.resolve("meta.db")); }
    @AfterEach  void tearDown() { if (meta != null) meta.close(); }

    private static final String OLD = "/d/Downloads/inv.pdf";
    private static final String NEW = "/d/Downloads/Invoices/inv.pdf";

    private void seed() {
        meta.upsert(FileRecord.builder()
                .absolutePath(OLD).fileName("inv.pdf").fileExtension("pdf")
                .fileSizeBytes(10).lastModifiedMs(1).contentHash("h1")
                .status(FileRecord.Status.INDEXED).chunkCount(1).tokenCount(5)
                .lastIndexedAt(Instant.now()).build());
        meta.setDocType(OLD, "Invoice");
        meta.setPrimaryDate(OLD, "2026-06-25");
        meta.putSummary(OLD, "h1", "A one-page brief.", 2);
        meta.replaceDatesForFile(OLD, "inv.pdf", "v3:h1",
                List.of(new IndexMetadataStore.NewDate("2026-07-10", "Payment due", "due by…")));
    }

    @Test
    void everyPathKeyedRecordFollowsTheFile() {
        seed();
        meta.renamePath(OLD, NEW);

        // Old path is gone; new path carries the full record.
        assertTrue(meta.findByPath(OLD).isEmpty());
        FileRecord moved = meta.findByPath(NEW).orElseThrow();
        assertEquals("Invoice",    moved.getDocType());
        assertEquals("2026-06-25", moved.getPrimaryDate());

        // The cached summary followed — no LLM re-spend after a reorg.
        assertTrue(meta.getSummary(NEW).isPresent());
        assertEquals("A one-page brief.", meta.getSummary(NEW).get().summary());
        assertTrue(meta.getSummary(OLD).isEmpty());

        // Timeline dates re-pointed, and the scan marker too (no re-scan).
        List<IndexMetadataStore.DateRow> dates = meta.listTimeline();
        assertEquals(1, dates.size());
        assertEquals(NEW, dates.get(0).absolutePath());
        assertTrue(meta.isDateScanned(NEW, "v3:h1"));
        assertFalse(meta.isDateScanned(OLD, "v3:h1"));
    }

    @Test
    void renameOfUnknownPath_isANoOp() {
        seed();
        meta.renamePath("/nope/missing.pdf", "/nope/moved.pdf");
        assertTrue(meta.findByPath(OLD).isPresent()); // untouched
    }
}

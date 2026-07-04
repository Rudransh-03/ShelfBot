package com.localfilebrain.deadline;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.NewDeadline;
import com.localfilebrain.model.DocumentChunk;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.storage.VectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistence rules of the deadline scan around a misbehaving model, tested
 * through the {@link DeadlineExtractionEngine.LlmCall} seam (no network).
 *
 * Guards the failure that once zeroed a user's whole Deadlines tab: one
 * oversized batch → truncated JSON reply → parsed as "no deadlines" → every
 * batched file stamped scanned-with-zero AND its previously-extracted
 * deadlines wiped by the replace. Two invariants prevent it:
 *   1. an unreadable reply stops the scan WITHOUT stamping or wiping anything;
 *   2. batches are capped by document count so a single reply always fits the
 *      output-token ceiling in the first place.
 */
class DeadlineScanServiceTest {

    @TempDir Path tmp;
    private IndexMetadataStore meta;
    private VectorStore vs;

    @BeforeEach void setUp() {
        meta = new IndexMetadataStore(tmp.resolve("meta.db"));
        vs   = new VectorStore(tmp.resolve("idx"));
    }

    @AfterEach void tearDown() { if (vs != null) vs.close(); if (meta != null) meta.close(); }

    /** Passes the prefilter (explicit date + "due" trigger) so the file reaches the LLM batch. */
    private static final String DATED_TEXT =
            "TAX INVOICE. Payment due by 15 March 2026. Total: 5,000.";

    private void indexFile(String path, String text) {
        String name = Path.of(path).getFileName().toString();
        vs.upsert(List.of(DocumentChunk.builder()
                        .chunkId(path + "::0").sourceFilePath(path).fileName(name)
                        .fileExtension("txt").text(text).chunkIndex(0).totalChunks(1).build()),
                List.of(new float[]{1f, 0f, 0f, 0f}));
        meta.upsert(FileRecord.builder()
                .absolutePath(path).fileName(name).fileExtension("txt")
                .fileSizeBytes(1).lastModifiedMs(0).contentHash("h")
                .status(FileRecord.Status.INDEXED).chunkCount(1).tokenCount(0)
                .lastIndexedAt(Instant.now()).build());
    }

    @Test
    void unreadableReply_stampsNothing_andKeepsExistingDeadlines() {
        String path = "/d/RealCo-Invoice-9.txt";
        indexFile(path, DATED_TEXT);

        // A deadline extracted at an older content hash — the file has since
        // changed (hash "h"), so it's due for a re-scan.
        meta.replaceDeadlinesForFile(path, "RealCo-Invoice-9.txt", "old-hash",
                List.of(new NewDeadline("Pay RealCo invoice", "Invoice total 5,000",
                        "2026-03-15", "DEADLINE", "HIGH", "NONE", "Payment due by 15 March 2026")));
        assertEquals(1, meta.listDeadlines(null).size());

        DeadlineScanService svc = new DeadlineScanService(meta, vs);
        DeadlineScanService.ScanResult r =
                svc.scan((sys, user) -> "I'm sorry, I can't help with that.", 25, null);

        assertEquals(DeadlineScanService.Stop.ERROR, r.stop(),
                "an unreadable reply must stop the scan, not complete it");
        assertFalse(meta.isDeadlineScanned(path, "h"),
                "the file must NOT be stamped scanned — it has to retry next pass");
        assertEquals(1, meta.listDeadlines(null).size(),
                "previously-extracted deadlines must survive the failed scan");
        assertEquals("Pay RealCo invoice", meta.listDeadlines(null).get(0).title());
    }

    @Test
    void readableEmptyReply_stillStampsFilesScanned() {
        String path = "/d/RealCo-Invoice-9.txt";
        indexFile(path, DATED_TEXT);

        DeadlineScanService svc = new DeadlineScanService(meta, vs);
        DeadlineScanService.ScanResult r =
                svc.scan((sys, user) -> "{\"deadlines\":[],\"documents\":[]}", 25, null);

        assertEquals(DeadlineScanService.Stop.COMPLETE, r.stop());
        assertTrue(meta.isDeadlineScanned(path, "h"),
                "a genuinely deadline-free file must be stamped so it's never re-paid for");
    }

    @Test
    void batchesAreCappedByDocumentCount() {
        int files = 25; // > 2 full batches at the 10-doc cap
        for (int i = 1; i <= files; i++) {
            indexFile("/d/Invoice-" + i + ".txt", DATED_TEXT);
        }

        List<String> prompts = new ArrayList<>();
        DeadlineScanService svc = new DeadlineScanService(meta, vs);
        DeadlineScanService.ScanResult r = svc.scan((sys, user) -> {
            prompts.add(user);
            return "{\"deadlines\":[],\"documents\":[]}";
        }, 25, null);

        assertEquals(DeadlineScanService.Stop.COMPLETE, r.stop());
        assertEquals(3, prompts.size(), "25 docs at a 10-doc cap = 3 LLM calls");
        int totalDocs = 0;
        for (String p : prompts) {
            int docs = countOccurrences(p, "=== Document id=");
            assertTrue(docs <= 10, "a single call carried " + docs + " docs (cap is 10)");
            totalDocs += docs;
        }
        assertEquals(files, totalDocs, "every pending file must be sent exactly once");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int idx = haystack.indexOf(needle); idx >= 0; idx = haystack.indexOf(needle, idx + 1)) n++;
        return n;
    }
}

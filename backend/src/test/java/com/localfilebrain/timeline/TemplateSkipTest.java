package com.localfilebrain.timeline;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.model.DocumentChunk;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.storage.VectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Template/sample documents must never feed the Timeline: their placeholder
 * dates ("payment due 01/01/2024" in an invoice template) are not real
 * obligations, and before this guard they surfaced in the Timeline and the
 * Needs-attention panel like genuine deadlines.
 */
class TemplateSkipTest {

    @TempDir Path tmp;
    private IndexMetadataStore meta;
    private VectorStore vs;

    @BeforeEach void setUp() {
        meta = new IndexMetadataStore(tmp.resolve("meta.db"));
        vs   = new VectorStore(tmp.resolve("idx"));
    }

    @AfterEach void tearDown() { if (vs != null) vs.close(); if (meta != null) meta.close(); }

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
    void templateDates_neverReachTheTimeline() {
        String obligation = "TAX INVOICE. Payment due by 15 March 2026. Total: 5,000.";
        indexFile("/d/invoice_TEMPLATE.txt", obligation);   // template — must be skipped
        indexFile("/d/RealCo-Invoice-9.txt", obligation);   // real — must be scanned

        new LocalDateScanner(meta, vs).scanAll();

        List<IndexMetadataStore.DateRow> rows = meta.listTimeline();
        assertEquals(1, rows.size(), "only the real invoice's date may surface");
        assertEquals("/d/RealCo-Invoice-9.txt", rows.get(0).absolutePath());

        // The template is marked scanned (won't be re-visited every pass).
        assertTrue(meta.isDateScanned("/d/invoice_TEMPLATE.txt", "v6:h"));
    }
}

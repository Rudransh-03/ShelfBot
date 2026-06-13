package com.localfilebrain.storage;

import com.localfilebrain.model.DocumentChunk;
import com.localfilebrain.storage.VectorStore.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the per-client isolation guarantee at the index level: a filtered query
 * returns ONLY chunks from the allowed file set, even when an excluded file is a
 * strictly closer vector match. No embeddings or LLM involved — vectors are
 * supplied directly, so this is the hard, deterministic proof that scope is
 * enforced by the search itself, not by any prompt.
 */
class VectorStoreScopeTest {

    @TempDir Path tmp;

    private static DocumentChunk chunk(String path, float[] v, String text) {
        return DocumentChunk.builder()
                .chunkId(path + "::chunk-0").sourceFilePath(path).fileName(path.substring(path.lastIndexOf('/') + 1))
                .fileExtension("pdf").text(text).mimeType("application/pdf")
                .chunkIndex(0).totalChunks(1).build();
    }

    @Test
    void filteredQueryNeverReturnsAnExcludedFile_evenIfItScoresHigher() {
        String aPath = "/clientA/a.pdf";   // allowed
        String bPath = "/clientB/b.pdf";   // must stay invisible
        try (VectorStore vs = new VectorStore(tmp.resolve("idx"))) {
            vs.upsert(List.of(chunk(aPath, new float[]{1f, 0f, 0f, 0f}, "client A february gst")),
                      List.of(new float[]{1f, 0f, 0f, 0f}));
            vs.upsert(List.of(chunk(bPath, new float[]{0f, 1f, 0f, 0f}, "client B february gst")),
                      List.of(new float[]{0f, 1f, 0f, 0f}));

            float[] q = new float[]{0.15f, 1f, 0f, 0f}; // closest to B

            // Unfiltered: B wins (it's the closer match) — establishes the trap.
            List<SearchResult> all = vs.query(q, 10);
            assertEquals(bPath, all.get(0).sourceFilePath(), "B should be the top unfiltered match");

            // Filtered to A: B must NOT appear at all, despite scoring higher.
            List<SearchResult> scoped = vs.query(q, 10, Set.of(aPath));
            assertFalse(scoped.isEmpty(), "A's chunk should still be retrievable");
            assertTrue(scoped.stream().allMatch(r -> r.sourceFilePath().equals(aPath)),
                    "filtered query leaked a chunk from outside the allowed set");

            // Empty scope (a client with no documents) → nothing.
            assertTrue(vs.query(q, 10, Set.of()).isEmpty(), "empty scope must return nothing");

            // Null scope → unrestricted (backward compatible).
            assertEquals(2, vs.query(q, 10, null).size());
        }
    }
}

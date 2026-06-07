package com.localfilebrain.storage;

import com.localfilebrain.model.DocumentChunk;
import com.localfilebrain.storage.VectorStore.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the contract that {@link VectorStore#replaceBySourceFile} the
 * concurrency fix relies on: re-indexing a file leaves EXACTLY the new chunk
 * set — no stale tail from a previous (longer) version and no duplicates.
 *
 * Because chunk ids are deterministic ({@code path::chunk-i}), the old
 * delete-then-upsert path couldn't produce same-id duplicates, but two threads
 * re-indexing the same file could interleave delete/add and leave stale
 * higher-index chunks. {@code replaceBySourceFile} is a single synchronized
 * delete+add, and the {@code IngestionPipeline} per-file lock serialises the
 * whole index-one-file unit — so this "fully replaces" property holds even
 * under concurrent re-index.
 */
class VectorStoreReplaceTest {

    @TempDir Path tmp;

    private static List<DocumentChunk> chunks(String path, int n) {
        List<DocumentChunk> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(DocumentChunk.builder()
                    .chunkId(path + "::chunk-" + i)
                    .sourceFilePath(path)
                    .fileName("doc.txt")
                    .fileExtension("txt")
                    .text("chunk " + i)
                    .mimeType("text/plain")
                    .fileLastModifiedMs(0)
                    .chunkIndex(i)
                    .totalChunks(n)
                    .build());
        }
        return out;
    }

    private static List<float[]> vecs(int n) {
        List<float[]> v = new ArrayList<>();
        for (int i = 0; i < n; i++) v.add(new float[]{ 1f, 0f, 0f, (float) (i + 1) });
        return v;
    }

    @Test
    void replaceFullyReplaces_noStaleOrDuplicateChunks() {
        try (VectorStore vs = new VectorStore(tmp.resolve("idx"))) {
            String path = "/x/doc.txt";
            vs.replaceBySourceFile(path, chunks(path, 8), vecs(8));
            assertEquals(8, vs.getChunksForFile(path).size());

            // Re-index to a SHORTER version — the old tail (chunk-3..7) must be gone,
            // not lingering alongside the new set.
            vs.replaceBySourceFile(path, chunks(path, 3), vecs(3));
            List<SearchResult> after = vs.getChunksForFile(path);
            assertEquals(3, after.size(), "replace must drop the stale tail, leaving exactly the new set");
            assertTrue(after.stream().allMatch(c -> c.chunkIndex() < 3), "no stale higher-index chunks remain");
        }
    }

    @Test
    void concurrentReplaceSameFile_leavesOneCleanSet() throws Exception {
        try (VectorStore vs = new VectorStore(tmp.resolve("idx2"))) {
            String path = "/x/doc.txt";
            int threads = 4, iterations = 30;
            vs.replaceBySourceFile(path, chunks(path, 5), vecs(5)); // seed

            CountDownLatch start = new CountDownLatch(1);
            List<Thread> ts = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                // Half the threads write a 5-chunk version, half a 3-chunk version,
                // hammering the same file — the final state must be exactly one of
                // those sets (contiguous from 0), never an accumulation.
                final int n = (t % 2 == 0) ? 5 : 3;
                Thread th = new Thread(() -> {
                    try { start.await(); } catch (InterruptedException e) { return; }
                    for (int k = 0; k < iterations; k++) vs.replaceBySourceFile(path, chunks(path, n), vecs(n));
                });
                ts.add(th); th.start();
            }
            start.countDown();
            for (Thread th : ts) th.join();

            List<SearchResult> after = vs.getChunksForFile(path);
            int size = after.size();
            assertTrue(size == 3 || size == 5, "final chunk set must be one full version, got " + size);
            assertTrue(after.stream().allMatch(c -> c.chunkIndex() < size), "no stale chunks above the set size");
        }
    }
}

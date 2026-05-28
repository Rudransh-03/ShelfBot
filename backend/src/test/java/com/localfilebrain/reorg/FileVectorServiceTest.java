package com.localfilebrain.reorg;

import com.localfilebrain.embedding.EmbeddingClient;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FileVectorService}. Validates:
 *   - Pure-math mean-pool + L2-normalize.
 *   - End-to-end: chunks in Lucene → mean-pooled file vector → cached in DB.
 *   - Cache invalidation on file re-upsert.
 *   - Cache invalidation when embedding model changes.
 */
final class FileVectorServiceTest {

    @TempDir
    Path tmp;

    private VectorStore        vectorStore;
    private IndexMetadataStore metadataStore;

    @BeforeEach
    void setup() {
        vectorStore   = new VectorStore(tmp.resolve("idx"));
        metadataStore = new IndexMetadataStore(tmp.resolve("meta.db"));
    }

    @AfterEach
    void teardown() throws Exception {
        vectorStore.close();
        metadataStore.close();
    }

    // -------------------------------------------------------------------------
    // Pure math
    // -------------------------------------------------------------------------

    @Test
    void meanPool_singleVector_returnsNormalizedCopy() {
        float[] v = {3f, 4f, 0f};   // magnitude 5
        float[] out = FileVectorService.meanPoolAndNormalize(List.of(v));
        assertEquals(0.6f, out[0], 1e-5);
        assertEquals(0.8f, out[1], 1e-5);
        assertEquals(0.0f, out[2], 1e-5);
    }

    @Test
    void meanPool_twoVectors_averagesThenNormalizes() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0f, 1f, 0f};
        // mean = (0.5, 0.5, 0); magnitude = sqrt(0.5)
        // normalized = (0.7071, 0.7071, 0)
        float[] out = FileVectorService.meanPoolAndNormalize(List.of(a, b));
        assertEquals(0.7071f, out[0], 1e-4);
        assertEquals(0.7071f, out[1], 1e-4);
        assertEquals(0.0f,    out[2], 1e-4);
        // Sanity: norm == 1
        double norm = Math.sqrt((double)out[0]*out[0] + (double)out[1]*out[1] + (double)out[2]*out[2]);
        assertEquals(1.0, norm, 1e-5);
    }

    @Test
    void meanPool_rejectsInconsistentDim() {
        assertThrows(IllegalArgumentException.class,
                () -> FileVectorService.meanPoolAndNormalize(
                        List.of(new float[]{1, 2, 3}, new float[]{1, 2})));
    }

    @Test
    void meanPool_rejectsEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> FileVectorService.meanPoolAndNormalize(List.of()));
    }

    // -------------------------------------------------------------------------
    // End-to-end through Lucene + SQLite
    // -------------------------------------------------------------------------

    @Test
    void getFileVector_computesFromChunksAndCaches() {
        String path = "/fake/resume.pdf";
        registerInMetadata(path);
        upsertChunks(path, List.of(
                new float[]{1, 0, 0, 0},
                new float[]{0, 1, 0, 0}));

        FakeEmbeddingClient embed = new FakeEmbeddingClient("bge-test", 4);
        FileVectorService svc = new FileVectorService(vectorStore, metadataStore, embed);

        Optional<float[]> v = svc.getFileVector(path);
        assertTrue(v.isPresent());
        assertEquals(4, v.get().length);
        // Mean of (1,0,0,0) and (0,1,0,0) = (0.5,0.5,0,0), normalized = (0.7071,0.7071,0,0)
        assertEquals(0.7071f, v.get()[0], 1e-4);
        assertEquals(0.7071f, v.get()[1], 1e-4);

        // Now the row must be cached in SQLite
        Optional<IndexMetadataStore.CachedFileVector> cached = metadataStore.getFileVector(path);
        assertTrue(cached.isPresent());
        assertEquals("bge-test", cached.get().modelId());
        assertEquals(4, cached.get().dim());
        assertEquals(2, cached.get().chunkCount());

        // Second call should hit the cache (we can't observe that directly,
        // but the returned value must match bit-for-bit).
        Optional<float[]> v2 = svc.getFileVector(path);
        assertTrue(v2.isPresent());
        assertArrayEquals(v.get(), v2.get(), 1e-6f);
    }

    @Test
    void getFileVector_fallsBackToFilenameEmbedding_whenNoChunks() {
        FakeEmbeddingClient embed = new FakeEmbeddingClient("bge-test", 4);
        FileVectorService svc = new FileVectorService(vectorStore, metadataStore, embed);

        // No chunks indexed for this path → falls back to embedding the
        // filename so the file still gets a placeable vector.
        Optional<float[]> v = svc.getFileVector("/does/not/exist.pdf");
        assertTrue(v.isPresent());
        assertEquals(4, v.get().length);

        // Sentinel: filename-derived rows store chunk_count = 0.
        Optional<IndexMetadataStore.CachedFileVector> cached = metadataStore.getFileVector("/does/not/exist.pdf");
        assertTrue(cached.isPresent());
        assertEquals(0, cached.get().chunkCount(),
                "filename-derived cache row must use chunk_count=0 as sentinel");
    }

    @Test
    void weightedSumAndNormalize_blendsAccordingToWeights() {
        // pure-math regression for the fusion helper
        float[] a = {1, 0, 0, 0};
        float[] b = {0, 1, 0, 0};
        float[] out = FileVectorService.weightedSumAndNormalize(a, 0.7f, b, 0.3f);
        // raw weighted sum = (0.7, 0.3, 0, 0); magnitude sqrt(0.49 + 0.09) = sqrt(0.58)
        double norm = Math.sqrt(0.58);
        assertEquals((float)(0.7 / norm), out[0], 1e-4);
        assertEquals((float)(0.3 / norm), out[1], 1e-4);
        // result is unit length
        double n = 0; for (float f : out) n += f * f;
        assertEquals(1.0, n, 1e-5);
    }

    @Test
    void getFileVector_fusesContentAndFilenameEmbedding() {
        // When the file has indexed chunks AND the filename embedding is
        // non-zero, the cached vector must be the 0.7/0.3 fusion — that's
        // the fix that lets "resume.pdf + resume_template.docx" cluster
        // even when their contents diverge.
        String path = "/fake/Rudransh_Resume.pdf";
        registerInMetadata(path);
        upsertChunks(path, List.of(new float[]{1, 0, 0, 0}));   // content vec

        // Embedding client that returns a specific filename vector.
        EmbeddingClient embed = new EmbeddingClient() {
            @Override public List<float[]> embedBatch(List<String> texts) {
                // For any input, return the same filename-vector signal.
                return texts.stream().map(t -> new float[]{0, 1, 0, 0}).toList();
            }
            @Override public int dimensions() { return 4; }
            @Override public String modelId() { return "bge-test"; }
        };

        FileVectorService svc = new FileVectorService(vectorStore, metadataStore, embed);
        float[] fused = svc.getFileVector(path).orElseThrow();

        // Fused should be normalize(0.7*content + 0.3*filename)
        //   = normalize(0.7, 0.3, 0, 0)
        double norm = Math.sqrt(0.49 + 0.09);
        assertEquals((float)(0.7 / norm), fused[0], 1e-3);
        assertEquals((float)(0.3 / norm), fused[1], 1e-3);
        assertEquals(0f, fused[2], 1e-5);

        // chunk_count > 0 sentinel still holds — this is a content-derived
        // (now fused) vector, NOT a filename-only fallback.
        var cached = metadataStore.getFileVector(path).orElseThrow();
        assertEquals(1, cached.chunkCount(),
                "fused-vector rows must still report chunk_count > 0");
    }

    @Test
    void getFileVector_returnsEmpty_whenEmbeddingClientFailsOnFallback() {
        // Embedding client that throws on embedBatch — simulates a model
        // load failure or runtime error during the filename fallback path.
        EmbeddingClient throwing = new EmbeddingClient() {
            @Override public List<float[]> embedBatch(List<String> texts) {
                throw new RuntimeException("embed failed");
            }
            @Override public int dimensions() { return 4; }
            @Override public String modelId() { return "bge-test"; }
        };
        FileVectorService svc = new FileVectorService(vectorStore, metadataStore, throwing);
        assertTrue(svc.getFileVector("/does/not/exist.pdf").isEmpty());
    }

    @Test
    void cache_invalidatedOnFileUpsert() {
        String path = "/fake/notes.pdf";
        registerInMetadata(path);
        upsertChunks(path, List.of(new float[]{1, 0, 0, 0}));

        FakeEmbeddingClient embed = new FakeEmbeddingClient("bge-test", 4);
        FileVectorService svc = new FileVectorService(vectorStore, metadataStore, embed);
        svc.getFileVector(path);
        assertTrue(metadataStore.getFileVector(path).isPresent(), "vector should be cached after first call");

        // Simulate re-index: metadataStore.upsert() must wipe the cache.
        registerInMetadata(path);
        assertTrue(metadataStore.getFileVector(path).isEmpty(),
                "re-upsert must invalidate cached file vector");
    }

    @Test
    void cache_invalidatedOnModelChange() {
        String path = "/fake/letter.pdf";
        registerInMetadata(path);
        upsertChunks(path, List.of(new float[]{1, 0, 0, 0}));

        // First compute under model A.
        FakeEmbeddingClient embedA = new FakeEmbeddingClient("bge-A", 4);
        FileVectorService svcA = new FileVectorService(vectorStore, metadataStore, embedA);
        Optional<float[]> vA = svcA.getFileVector(path);
        assertTrue(vA.isPresent());

        // Now switch to model B. Cached row carries model_id = "bge-A".
        FakeEmbeddingClient embedB = new FakeEmbeddingClient("bge-B", 4);
        FileVectorService svcB = new FileVectorService(vectorStore, metadataStore, embedB);
        Optional<float[]> vB = svcB.getFileVector(path);
        assertTrue(vB.isPresent());

        // After the call, cache must now reflect model B (stale A row was dropped).
        Optional<IndexMetadataStore.CachedFileVector> cached = metadataStore.getFileVector(path);
        assertTrue(cached.isPresent());
        assertEquals("bge-B", cached.get().modelId());
    }

    @Test
    void cache_invalidatedOnFileDelete() {
        String path = "/fake/old.pdf";
        registerInMetadata(path);
        upsertChunks(path, List.of(new float[]{1, 0, 0, 0}));

        FakeEmbeddingClient embed = new FakeEmbeddingClient("bge-test", 4);
        new FileVectorService(vectorStore, metadataStore, embed).getFileVector(path);
        assertTrue(metadataStore.getFileVector(path).isPresent());

        metadataStore.delete(path);
        assertTrue(metadataStore.getFileVector(path).isEmpty(),
                "delete() must invalidate cached file vector");
    }

    @Test
    void getFileVectors_batchPreservesOrderAndIncludesFilenameFallback() {
        String a = "/fake/a.pdf";
        String b = "/fake/b.pdf";   // no chunks — falls back to filename embedding
        String c = "/fake/c.pdf";
        registerInMetadata(a);
        registerInMetadata(c);
        upsertChunks(a, List.of(new float[]{1, 0, 0, 0}));
        upsertChunks(c, List.of(new float[]{0, 0, 1, 0}));

        FakeEmbeddingClient embed = new FakeEmbeddingClient("bge-test", 4);
        FileVectorService svc = new FileVectorService(vectorStore, metadataStore, embed);

        Map<String, float[]> out = svc.getFileVectors(List.of(a, b, c));
        // All three now get a vector — b through the filename fallback.
        assertEquals(3, out.size());
        assertTrue(out.containsKey(a));
        assertTrue(out.containsKey(b));
        assertTrue(out.containsKey(c));
        // Order preserved (LinkedHashMap)
        List<String> keys = List.copyOf(out.keySet());
        assertEquals(a, keys.get(0));
        assertEquals(b, keys.get(1));
        assertEquals(c, keys.get(2));
        // b should be the filename-derived one (chunk_count = 0 sentinel)
        assertEquals(0, metadataStore.getFileVector(b).orElseThrow().chunkCount());
        // a and c should have real chunk-derived vectors (chunk_count = 1)
        assertEquals(1, metadataStore.getFileVector(a).orElseThrow().chunkCount());
        assertEquals(1, metadataStore.getFileVector(c).orElseThrow().chunkCount());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void registerInMetadata(String absolutePath) {
        metadataStore.upsert(FileRecord.builder()
                .absolutePath(absolutePath)
                .fileName(Path.of(absolutePath).getFileName().toString())
                .fileExtension("pdf")
                .fileSizeBytes(1)
                .lastModifiedMs(0)
                .contentHash("h")
                .status(FileRecord.Status.INDEXED)
                .chunkCount(1)
                .tokenCount(0)
                .lastIndexedAt(Instant.now())
                .build());
    }

    private void upsertChunks(String absolutePath, List<float[]> vectors) {
        java.util.List<DocumentChunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            chunks.add(DocumentChunk.builder()
                    .chunkId(absolutePath + "#" + i)
                    .sourceFilePath(absolutePath)
                    .fileName(Path.of(absolutePath).getFileName().toString())
                    .fileExtension("pdf")
                    .text("chunk text " + i)
                    .mimeType("application/pdf")
                    .fileLastModifiedMs(0)
                    .chunkIndex(i)
                    .totalChunks(vectors.size())
                    .build());
        }
        vectorStore.upsert(chunks, vectors);
    }

    /**
     * EmbeddingClient stub that returns zero vectors. With zero filename
     * embeddings, the {@code fuseWithFilenameIfAvailable} step is a no-op
     * (0.7 × content + 0.3 × zero, then renormalize, yields the original
     * content vector unchanged) — so these tests can keep asserting on
     * the content-only behaviour. A separate dedicated test exercises
     * fusion with a non-zero filename embedding.
     */
    private static final class FakeEmbeddingClient implements EmbeddingClient {
        private final String model;
        private final int dim;
        FakeEmbeddingClient(String model, int dim) { this.model = model; this.dim = dim; }
        @Override public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(t -> new float[dim]).toList();
        }
        @Override public int dimensions() { return dim; }
        @Override public String modelId() { return model; }
    }
}

package com.localfilebrain.reorg;

import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.localfilebrain.storage.VectorStore.SearchResult;

/**
 * Derives a single per-file embedding by mean-pooling the chunk-level
 * embeddings that Lucene already holds, then L2-normalizes the result so
 * cosine similarity reduces to a dot product downstream.
 *
 * Caching: file vectors live in {@code file_vectors} in the SQLite metadata
 * DB. Lookups are O(1). The cache is automatically invalidated by
 * {@link IndexMetadataStore#upsert} (any re-index) and {@link
 * IndexMetadataStore#delete} (file removed). This class additionally
 * discards rows whose stored {@code model_id} no longer matches the active
 * embedding backend — handles the case where the user swaps embedding
 * providers between runs.
 *
 * Lazy by design: vectors are computed only when a reorg actually needs
 * them, so the live indexing path is untouched (no extra DB writes per
 * ingested file).
 */
public final class FileVectorService {

    private static final Logger log = LoggerFactory.getLogger(FileVectorService.class);

    private final VectorStore        vectorStore;
    private final IndexMetadataStore metadataStore;
    private final EmbeddingClient    embeddingClient;

    public FileVectorService(VectorStore vectorStore,
                             IndexMetadataStore metadataStore,
                             EmbeddingClient embeddingClient) {
        this.vectorStore     = vectorStore;
        this.metadataStore   = metadataStore;
        this.embeddingClient = embeddingClient;
    }

    /**
     * Returns the L2-normalized file vector for {@code absolutePath}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Valid cache hit (model + dim match) → return cached.</li>
     *   <li>Stale cache (model/dim differs) → drop and recompute.</li>
     *   <li>File has indexed chunks → mean-pool chunk vectors, cache.</li>
     *   <li>File has no chunks (images, binaries, unsupported types) →
     *       embed the filename string as the file's vector. This keeps
     *       every file in {@code targetDir} comparable in the same
     *       embedding space during clustering. The cached row uses
     *       {@code chunk_count = 0} to mark the filename-derived case so
     *       downstream code can tell content-based vs filename-only
     *       vectors apart if it ever needs to.</li>
     * </ol>
     *
     * Returns {@link Optional#empty()} only on a true error (embedding
     * client failure). Normal cases always produce a vector.
     */
    public Optional<float[]> getFileVector(String absolutePath) {
        Optional<IndexMetadataStore.CachedFileVector> cached = metadataStore.getFileVector(absolutePath);
        if (cached.isPresent()) {
            IndexMetadataStore.CachedFileVector c = cached.get();
            if (c.modelId().equals(embeddingClient.modelId()) && c.dim() == embeddingClient.dimensions()) {
                return Optional.of(c.vec());
            }
            log.debug("Discarding stale cached vector for '{}' (model {} != {})",
                    absolutePath, c.modelId(), embeddingClient.modelId());
            metadataStore.deleteFileVector(absolutePath);
        }

        // Primary path: mean-pool chunk embeddings from Lucene, then FUSE
        // with the filename embedding. The content vector captures topic
        // similarity; the filename adds a strong signal for cases where
        // content differs but the user obviously meant them together
        // (e.g. resume.pdf + resume_template.docx). Without this fusion,
        // those pairs cluster weakly and never clear the confidence cutoff.
        List<float[]> chunkVectors = vectorStore.getChunkVectorsForFile(absolutePath);
        if (!chunkVectors.isEmpty()) {
            float[] contentVec = meanPoolAndNormalize(chunkVectors);
            float[] fused = fuseWithFilenameIfAvailable(absolutePath, contentVec);
            metadataStore.putFileVector(
                    absolutePath,
                    fused.length,
                    embeddingClient.modelId(),
                    chunkVectors.size(),     // > 0 sentinel: content-derived (now fused)
                    fused);
            return Optional.of(fused);
        }

        // Fallback: no indexed chunks (image, binary, unsupported type).
        // Embed the filename so the file is still placeable in cluster space.
        return embedFromFilename(absolutePath);
    }

    /**
     * Weights: 0.7 content + 0.3 filename. Chosen so content still
     * dominates when both signals are strong, but a hand-picked filename
     * pair still gets meaningful similarity even when the content of the
     * two files differs (template vs. instantiation). Returns {@code
     * contentVec} unchanged if the filename embedding fails for any
     * reason — we never want to lose the content signal we already have.
     */
    private float[] fuseWithFilenameIfAvailable(String absolutePath, float[] contentVec) {
        try {
            String name = java.nio.file.Paths.get(absolutePath).getFileName().toString();
            List<float[]> result = embeddingClient.embedBatch(List.of(name));
            if (result.isEmpty()) return contentVec;
            float[] filenameVec = normalize(result.get(0));
            if (filenameVec.length != contentVec.length) return contentVec;
            return weightedSumAndNormalize(contentVec, 0.7f, filenameVec, 0.3f);
        } catch (Exception e) {
            log.debug("Filename fusion failed for '{}'; using content-only vector ({})",
                    absolutePath, e.getMessage());
            return contentVec;
        }
    }

    /**
     * Computes {@code normalize(wa*a + wb*b)}. Both inputs assumed L2-
     * normalized so weighted summing is in the right space. Package-private
     * for unit testing.
     */
    static float[] weightedSumAndNormalize(float[] a, float wa, float[] b, float wb) {
        final int dim = a.length;
        float[] out = new float[dim];
        for (int i = 0; i < dim; i++) out[i] = wa * a[i] + wb * b[i];
        return normalize(out);
    }

    private Optional<float[]> embedFromFilename(String absolutePath) {
        String name = java.nio.file.Paths.get(absolutePath).getFileName().toString();
        // Embed the bare filename — for "IMG_2847.jpg" or "tax_return_2024.pdf"
        // BGE will still produce a meaningful vector based on the tokens it
        // recognizes (digits, words, the extension hint).
        try {
            List<float[]> result = embeddingClient.embedBatch(List.of(name));
            if (result.isEmpty()) return Optional.empty();
            float[] raw = result.get(0);
            // BGE outputs are already L2-normalized but normalize defensively
            // so callers can always assume unit vectors.
            float[] normalized = normalize(raw);
            metadataStore.putFileVector(
                    absolutePath,
                    normalized.length,
                    embeddingClient.modelId(),
                    0,                      // 0 = filename-derived sentinel
                    normalized);
            return Optional.of(normalized);
        } catch (Exception e) {
            log.warn("Filename-embedding fallback failed for '{}': {}", absolutePath, e.getMessage());
            return Optional.empty();
        }
    }

    /** L2-normalize a single vector (no mean-pool). */
    static float[] normalize(float[] v) {
        double normSq = 0;
        for (float f : v) normSq += (double) f * f;
        double norm = Math.sqrt(normSq);
        if (norm < 1e-9) return v.clone();
        float invNorm = (float) (1.0 / norm);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] * invNorm;
        return out;
    }

    /**
     * Returns true if this file's cached vector was derived ONLY from its
     * filename (no chunks were indexed in Lucene). Callers use this to
     * apply extra-strict gating during clustering — filename-only vectors
     * have weaker semantic grounding and need filename-token overlap to
     * legitimately cluster together.
     *
     * <p>Must be called AFTER {@link #getFileVector(String)} so the row
     * exists in the cache.
     */
    public boolean isFilenameOnly(String absolutePath) {
        return metadataStore.getFileVector(absolutePath)
                .map(c -> c.chunkCount() == 0)
                .orElse(false);
    }

    /**
     * Returns a short content preview (first {@code maxChars} of the
     * file's indexed text, joined across chunks in document order)
     * suitable for showing the LLM. Used by the reorg pipeline so the
     * model can judge a file by its actual content rather than guessing
     * from the filename alone — the BGE-on-OCR noise floor makes cosine
     * similarity alone unreliable for cross-format grouping.
     *
     * <p>Returns {@link Optional#empty()} for filename-only files (Pages,
     * .key, etc. — Tika can't extract them), which the caller should
     * surface to the LLM as "no preview available."
     */
    public Optional<String> getContentPreview(String absolutePath, int maxChars) {
        if (maxChars <= 0) return Optional.empty();
        List<SearchResult> chunks = vectorStore.getChunksForFile(absolutePath);
        if (chunks.isEmpty()) return Optional.empty();
        StringBuilder sb = new StringBuilder(maxChars + 64);
        for (SearchResult c : chunks) {
            if (sb.length() >= maxChars) break;
            if (c.text() == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.text());
        }
        // Collapse whitespace runs so the preview is dense.
        String collapsed = sb.toString().replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) return Optional.empty();
        if (collapsed.length() > maxChars) {
            collapsed = collapsed.substring(0, maxChars).trim() + "…";
        }
        return Optional.of(collapsed);
    }

    /**
     * Batch lookup: same semantics as {@link #getFileVector}, applied to a
     * list of paths. Files with no indexed chunks are silently omitted from
     * the result map. Preserves input order via {@link LinkedHashMap}.
     */
    public Map<String, float[]> getFileVectors(List<String> absolutePaths) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String path : absolutePaths) {
            getFileVector(path).ifPresent(v -> out.put(path, v));
        }
        return out;
    }

    /**
     * Pure function: mean-pool a list of vectors (all same dim), then
     * L2-normalize. Exposed package-private for unit testing — the math is
     * the load-bearing part of this class.
     */
    static float[] meanPoolAndNormalize(List<float[]> vectors) {
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("Cannot pool empty vector list");
        }
        final int dim = vectors.get(0).length;

        // Mean-pool: sum positions across vectors, divide by n.
        float[] sum = new float[dim];
        for (float[] v : vectors) {
            if (v.length != dim) {
                throw new IllegalArgumentException(
                        "Inconsistent dim in pool: expected " + dim + " got " + v.length);
            }
            for (int i = 0; i < dim; i++) sum[i] += v[i];
        }
        float invN = 1f / vectors.size();
        for (int i = 0; i < dim; i++) sum[i] *= invN;

        // L2-normalize so cosine ≡ dot product downstream.
        double normSq = 0;
        for (float f : sum) normSq += (double) f * f;
        double norm = Math.sqrt(normSq);
        if (norm < 1e-9) {
            // Degenerate: all-zero pooled vector (would happen only if every
            // chunk vector cancelled out, which doesn't occur for BGE outputs).
            // Return as-is rather than dividing by zero.
            return sum;
        }
        float invNorm = (float) (1.0 / norm);
        for (int i = 0; i < dim; i++) sum[i] *= invNorm;
        return sum;
    }
}

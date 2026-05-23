package com.localfilebrain.embedding;

import java.util.List;

/**
 * Pluggable embedding backend. Two implementations exist:
 *
 *   • {@link OpenAIEmbeddingClient}   — cloud, text-embedding-3-small (1536-dim)
 *   • {@link LocalEmbeddingClient}    — on-device, bge-small-en-v1.5 (384-dim)
 *
 * The pipeline and query engine talk to this interface; the choice is made
 * once at startup from {@code embedding.provider} in config.properties and
 * stays consistent for the life of the process.
 *
 * Switching providers is a destructive operation — vectors stored under one
 * model live in a different space (and often different dimension) than the
 * other. The drift-detection logic in {@code Main} wipes the Lucene index
 * when {@link #dimensions()} no longer matches what was previously written.
 */
public interface EmbeddingClient extends AutoCloseable {

    /** Returns the embedding for each input text, in the same order. */
    List<float[]> embedBatch(List<String> texts);

    /** Vector dimensionality produced by this backend. Constant for the model. */
    int dimensions();

    /**
     * Stable identifier for the underlying model. Persisted alongside the
     * vector index so we can detect — and recover from — a provider switch.
     */
    String modelId();

    @Override
    default void close() { /* no-op for backends that hold no resources */ }
}

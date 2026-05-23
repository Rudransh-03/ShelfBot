package com.localfilebrain.embedding;

import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the configured {@link EmbeddingClient}. Centralised so the pipeline
 * and the query engine produce the *same* backend (mixing the two would
 * mean the index was written with one model and queried with another —
 * vectors live in different spaces and you'd get garbage results).
 */
public final class EmbeddingClientFactory {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClientFactory.class);

    private EmbeddingClientFactory() {}

    public static EmbeddingClient create(AppConfig config) {
        return create(config, null);
    }

    /**
     * @param tokenStore  used when the OpenAI embedding backend is selected
     *                    AND the app is in proxy mode (the default). May be
     *                    null for the local backend.
     */
    public static EmbeddingClient create(AppConfig config, AuthTokenStore tokenStore) {
        String provider = config.getEmbeddingProvider();
        if ("openai".equals(provider)) {
            log.info("Embedding provider: openai (text-embedding-3-small)");
            return new OpenAIEmbeddingClient(
                    config,
                    tokenStore,
                    config.getEmbeddingBatchSize()
            );
        }
        log.info("Embedding provider: local ({})", LocalEmbeddingClient.MODEL_NAME);
        return new LocalEmbeddingClient(config.getLocalEmbeddingModelPath());
    }
}

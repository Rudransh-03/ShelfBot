package com.localfilebrain.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls OpenAI's text-embedding-3-small API to convert text chunks into vectors.
 *
 * Cost: $0.02 per million tokens. A typical 1800-char chunk is ~450 tokens.
 * Batching: sends up to batchSize chunks per HTTP request to minimize round trips.
 *
 * Model: text-embedding-3-small → 1536-dimensional vectors.
 * API docs: https://platform.openai.com/docs/api-reference/embeddings
 */
public final class OpenAIEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIEmbeddingClient.class);

    private static final String ENDPOINT = "https://api.openai.com/v1/embeddings";
    private static final String MODEL    = "text-embedding-3-small";

    private final String     apiKey;
    private final int        batchSize;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OpenAIEmbeddingClient(String apiKey, int batchSize) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key must not be blank");
        }
        this.apiKey     = apiKey;
        this.batchSize  = batchSize;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Embeds a list of texts in batches.
     * Returns a list of float arrays, one per input text, in the same order.
     *
     * @param texts list of strings to embed
     * @return list of embedding vectors (each is float[1536])
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        List<float[]> allEmbeddings = new ArrayList<>(texts.size());

        // Process in batches to stay within API limits and avoid timeouts
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            log.debug("Embedding batch {}/{} ({} texts)",
                    (i / batchSize) + 1, (int) Math.ceil((double) texts.size() / batchSize), batch.size());

            List<float[]> batchResult = callAPI(batch);
            allEmbeddings.addAll(batchResult);

            // Brief pause between batches to avoid hitting rate limits
            if (i + batchSize < texts.size()) {
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        return allEmbeddings;
    }

    // -------------------------------------------------------------------------
    // Internal: single API call for one batch
    // -------------------------------------------------------------------------

    private List<float[]> callAPI(List<String> texts) {
        try {
            // Build request body: {"model": "...", "input": ["text1", "text2", ...]}
            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            ArrayNode inputArray = body.putArray("input");
            for (String text : texts) {
                // Truncate at 8000 chars (~2000 tokens) as a safety net — well within the 8191 token limit
                inputArray.add(text.length() > 8000 ? text.substring(0, 8000) : text);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new EmbeddingException("OpenAI API error " + response.statusCode() + ": " + response.body());
            }

            return parseEmbeddings(response.body(), texts.size());

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to call OpenAI embedding API: " + e.getMessage(), e);
        }
    }

    private List<float[]> parseEmbeddings(String responseBody, int expectedCount) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.get("data");

        if (data == null || !data.isArray()) {
            throw new EmbeddingException("Unexpected response format from OpenAI: " + responseBody);
        }

        // OpenAI returns results sorted by index field — collect them in order
        List<float[]> embeddings = new ArrayList<>(expectedCount);
        for (int i = 0; i < data.size(); i++) {
            embeddings.add(null); // placeholder
        }

        for (JsonNode item : data) {
            int index        = item.get("index").asInt();
            JsonNode embNode = item.get("embedding");
            float[] vector   = new float[embNode.size()];
            for (int j = 0; j < embNode.size(); j++) {
                vector[j] = (float) embNode.get(j).asDouble();
            }
            embeddings.set(index, vector);
        }

        if (embeddings.size() != expectedCount) {
            throw new EmbeddingException("Expected " + expectedCount + " embeddings, got " + embeddings.size());
        }

        return embeddings;
    }

    // -------------------------------------------------------------------------
    // Typed exception
    // -------------------------------------------------------------------------

    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
        public EmbeddingException(String message, Throwable cause) { super(message, cause); }
    }
}

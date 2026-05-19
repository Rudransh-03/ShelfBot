package com.localfilebrain.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Calls Ollama's local embedding API to convert text into vectors.
 *
 * Ollama runs locally at http://localhost:11434 (default).
 * Recommended model: nomic-embed-text (768-dimensional vectors)
 *   Pull with: ollama pull nomic-embed-text
 *
 * Unlike OpenAI, Ollama does not support batch embedding in one call.
 * We call it once per chunk — still fast since it's local, no network latency.
 *
 * Cost: zero. Runs entirely on the user's machine.
 */
public final class OllamaEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);

    private final String     baseUrl;
    private final String     model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaEmbeddingClient(String baseUrl, String model) {
        this.baseUrl    = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model      = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Embeds a list of texts. Returns one float[] per input, in the same order.
     * Each call to Ollama is sequential — Ollama processes one at a time.
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0 && i % 10 == 0) {
                log.debug("Embedding progress: {}/{}", i, texts.size());
            }
            results.add(embedSingle(texts.get(i)));
        }
        return results;
    }

    private float[] embedSingle(String text) {
        try {
            // Truncate at 8000 chars as safety net
            String input = text.length() > 8000 ? text.substring(0, 8000) : text;

            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", input);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new EmbeddingException("Ollama embedding error (" + response.statusCode() + "): " + response.body());
            }

            JsonNode root      = mapper.readTree(response.body());
            JsonNode embedding = root.get("embedding");

            if (embedding == null || !embedding.isArray()) {
                throw new EmbeddingException("Unexpected Ollama response format: " + response.body());
            }

            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            return vector;

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to call Ollama embedding API: " + e.getMessage(), e);
        }
    }

    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
        public EmbeddingException(String message, Throwable cause) { super(message, cause); }
    }
}

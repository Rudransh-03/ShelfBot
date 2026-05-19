package com.localfilebrain.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class OpenAIEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIEmbeddingClient.class);

    private static final String ENDPOINT = "https://api.openai.com/v1/embeddings";
    private static final String MODEL    = "text-embedding-3-small";

    private final String       apiKey;
    private final int          batchSize;
    private final ObjectMapper mapper;

    public OpenAIEmbeddingClient(String apiKey, int batchSize) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key must not be blank");
        }
        this.apiKey    = apiKey;
        this.batchSize = batchSize;
        this.mapper    = new ObjectMapper();
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        List<float[]> allEmbeddings = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            log.debug("Embedding batch {}/{} ({} texts)",
                    (i / batchSize) + 1, (int) Math.ceil((double) texts.size() / batchSize), batch.size());

            allEmbeddings.addAll(callAPI(batch));

            if (i + batchSize < texts.size()) {
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        return allEmbeddings;
    }

    private List<float[]> callAPI(List<String> texts) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            ArrayNode inputArray = body.putArray("input");
            for (String text : texts) {
                inputArray.add(text.length() > 8000 ? text.substring(0, 8000) : text);
            }

            byte[] requestBytes = mapper.writeValueAsBytes(body);

            HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(requestBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBytes);
            }

            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            if (status != 200) {
                throw new EmbeddingException("OpenAI API error " + status + ": " + responseBody);
            }

            return parseEmbeddings(responseBody, texts.size());

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

        List<float[]> embeddings = new ArrayList<>(expectedCount);
        for (int i = 0; i < data.size(); i++) embeddings.add(null);

        for (JsonNode item : data) {
            int index        = item.get("index").asInt();
            JsonNode embNode = item.get("embedding");
            float[] vector   = new float[embNode.size()];
            for (int j = 0; j < embNode.size(); j++) vector[j] = (float) embNode.get(j).asDouble();
            embeddings.set(index, vector);
        }

        if (embeddings.size() != expectedCount) {
            throw new EmbeddingException("Expected " + expectedCount + " embeddings, got " + embeddings.size());
        }

        return embeddings;
    }

    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
        public EmbeddingException(String message, Throwable cause) { super(message, cause); }
    }
}

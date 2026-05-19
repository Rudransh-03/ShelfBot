package com.localfilebrain.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localfilebrain.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ChromaDBClient {

    private static final Logger log = LoggerFactory.getLogger(ChromaDBClient.class);

    private static final String TENANT   = "default_tenant";
    private static final String DATABASE = "default_database";

    private final String       baseUrl;
    private final String       collectionName;
    private final ObjectMapper mapper;

    // Base path for all collection-level operations
    private final String collectionsBase;

    // Cached collection ID — fetched once on first use
    private String collectionId;

    public ChromaDBClient(String baseUrl, String collectionName) {
        this.baseUrl         = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collectionName  = collectionName;
        this.collectionsBase = this.baseUrl + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE + "/collections";
        this.mapper          = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Ensures the collection exists. Creates it if absent.
     * Call once at pipeline startup.
     */
    public void ensureCollection() {
        try {
            // Try GET first
            HttpResp res = get(collectionsBase + "/" + collectionName);

            if (res.statusCode() == 200) {
                JsonNode node = mapper.readTree(res.body());
                this.collectionId = node.get("id").asText();
                log.info("ChromaDB collection '{}' found", collectionName);
                return;
            }

            // Not found — create it
            ObjectNode body = mapper.createObjectNode();
            body.put("name", collectionName);
            ObjectNode metadata = body.putObject("metadata");
            metadata.put("hnsw:space", "cosine");

            HttpResp createRes = post(collectionsBase, mapper.writeValueAsString(body));

            if (createRes.statusCode() != 200 && createRes.statusCode() != 201) {
                throw new ChromaDBException("Failed to create collection (" + createRes.statusCode() + "): " + createRes.body());
            }

            JsonNode created = mapper.readTree(createRes.body());
            this.collectionId = created.get("id").asText();
            log.info("ChromaDB collection '{}' created (id={})", collectionName, collectionId);

        } catch (ChromaDBException e) {
            throw e;
        } catch (Exception e) {
            throw new ChromaDBException("Failed to ensure ChromaDB collection: " + e.getMessage(), e);
        }
    }

    /**
     * Upserts chunks + embeddings into ChromaDB.
     */
    public void upsert(List<DocumentChunk> chunks, List<float[]> embeddings) {
        if (chunks.isEmpty()) return;
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "chunks.size() " + chunks.size() + " != embeddings.size() " + embeddings.size());
        }

        ensureCollectionIdLoaded();

        try {
            ObjectNode body = mapper.createObjectNode();

            ArrayNode ids = body.putArray("ids");
            chunks.forEach(c -> ids.add(c.getChunkId()));

            ArrayNode embArray = body.putArray("embeddings");
            for (float[] vec : embeddings) {
                ArrayNode vecNode = embArray.addArray();
                for (float v : vec) vecNode.add(v);
            }

            ArrayNode docs = body.putArray("documents");
            chunks.forEach(c -> docs.add(c.getText()));

            ArrayNode metas = body.putArray("metadatas");
            for (DocumentChunk chunk : chunks) {
                ObjectNode meta = metas.addObject();
                meta.put("fileName",           chunk.getFileName());
                meta.put("sourceFilePath",     chunk.getSourceFilePath());
                meta.put("chunkIndex",         chunk.getChunkIndex());
                meta.put("totalChunks",        chunk.getTotalChunks());
                meta.put("mimeType",           chunk.getMimeType());
                meta.put("fileLastModifiedMs", chunk.getFileLastModifiedMs());
                meta.put("charCount",          chunk.getCharCount());
            }

            String url = collectionsBase + "/" + collectionId + "/upsert";
            HttpResp response = post(url, mapper.writeValueAsString(body));

            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new ChromaDBException("Upsert failed (" + response.statusCode() + "): " + response.body());
            }

            log.debug("Upserted {} chunks into ChromaDB", chunks.size());

        } catch (ChromaDBException e) {
            throw e;
        } catch (Exception e) {
            throw new ChromaDBException("Failed to upsert into ChromaDB: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes all chunks for a given source file path.
     */
    public void deleteBySourceFile(String absoluteFilePath) {
        ensureCollectionIdLoaded();
        try {
            ObjectNode body = mapper.createObjectNode();
            ObjectNode where = body.putObject("where");
            ObjectNode condition = where.putObject("sourceFilePath");
            condition.put("$eq", absoluteFilePath);

            String url = collectionsBase + "/" + collectionId + "/delete";
            HttpResp response = post(url, mapper.writeValueAsString(body));

            if (response.statusCode() != 200) {
                log.warn("ChromaDB delete returned {}: {}", response.statusCode(), response.body());
            } else {
                log.debug("Deleted chunks for: {}", absoluteFilePath);
            }
        } catch (Exception e) {
            log.warn("Failed to delete old chunks for '{}': {}", absoluteFilePath, e.getMessage());
        }
    }

    /**
     * Returns total chunk count in the collection.
     */
    public int count() {
        ensureCollectionIdLoaded();
        try {
            HttpResp response = get(collectionsBase + "/" + collectionId + "/count");
            if (response.statusCode() == 200) {
                return mapper.readTree(response.body()).asInt();
            }
        } catch (Exception e) {
            log.warn("Could not fetch ChromaDB count: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Query ChromaDB for the top-k most similar chunks.
     */
    public QueryResult query(float[] queryEmbedding, int topK) {
        ensureCollectionIdLoaded();
        try {
            ObjectNode body = mapper.createObjectNode();

            ArrayNode queryEmbeddings = body.putArray("query_embeddings");
            ArrayNode vecNode = queryEmbeddings.addArray();
            for (float v : queryEmbedding) vecNode.add(v);

            body.put("n_results", topK);

            ArrayNode include = body.putArray("include");
            include.add("documents");
            include.add("metadatas");
            include.add("distances");

            String url = collectionsBase + "/" + collectionId + "/query";
            HttpResp response = post(url, mapper.writeValueAsString(body));

            if (response.statusCode() != 200) {
                throw new ChromaDBException("Query failed (" + response.statusCode() + "): " + response.body());
            }

            return parseQueryResult(response.body());

        } catch (ChromaDBException e) {
            throw e;
        } catch (Exception e) {
            throw new ChromaDBException("Failed to query ChromaDB: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Query result parsing
    // -------------------------------------------------------------------------

    private QueryResult parseQueryResult(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);

        JsonNode documents = root.path("documents").path(0);
        JsonNode metadatas = root.path("metadatas").path(0);
        JsonNode distances = root.path("distances").path(0);

        java.util.List<QueryResult.Match> matches = new java.util.ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            String text       = documents.get(i).asText();
            double distance   = distances.get(i).asDouble();
            JsonNode meta     = metadatas.get(i);
            String fileName   = meta.path("fileName").asText();
            String sourcePath = meta.path("sourceFilePath").asText();
            int chunkIndex    = meta.path("chunkIndex").asInt();

            matches.add(new QueryResult.Match(text, fileName, sourcePath, chunkIndex, distance));
        }

        return new QueryResult(matches);
    }

    // -------------------------------------------------------------------------
    // HTTP helpers — uses HttpURLConnection (blocking I/O, no NIO issues)
    // -------------------------------------------------------------------------

    private HttpResp get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        try {
            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new HttpResp(status, body);
        } finally {
            conn.disconnect();
        }
    }

    private HttpResp post(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setDoOutput(true);
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        try {
            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new HttpResp(status, body);
        } finally {
            conn.disconnect();
        }
    }

    private void ensureCollectionIdLoaded() {
        if (collectionId == null) ensureCollection();
    }

    // -------------------------------------------------------------------------
    // Simple HTTP response wrapper
    // -------------------------------------------------------------------------

    private static final class HttpResp {
        private final int    status;
        private final String body;
        HttpResp(int status, String body) { this.status = status; this.body = body; }
        int    statusCode() { return status; }
        String body()       { return body; }
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    public record QueryResult(java.util.List<Match> matches) {
        public record Match(
                String text,
                String fileName,
                String sourceFilePath,
                int    chunkIndex,
                double distance
        ) {}
    }

    // -------------------------------------------------------------------------
    // Typed exception
    // -------------------------------------------------------------------------

    public static class ChromaDBException extends RuntimeException {
        public ChromaDBException(String message) { super(message); }
        public ChromaDBException(String message, Throwable cause) { super(message, cause); }
    }
}

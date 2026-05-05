package com.localfilebrain.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localfilebrain.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class ChromaDBClient {

    private static final Logger log = LoggerFactory.getLogger(ChromaDBClient.class);

    private static final String TENANT   = "default_tenant";
    private static final String DATABASE = "default_database";

    private final String     baseUrl;
    private final String     collectionName;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    // Base path for all collection-level operations
    private final String collectionsBase;

    // Cached collection ID — fetched once on first use
    private String collectionId;

    public ChromaDBClient(String baseUrl, String collectionName) {
        this.baseUrl        = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collectionName = collectionName;
        this.collectionsBase = this.baseUrl + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE + "/collections";
        this.httpClient     = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
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
            HttpResponse<String> res = get(collectionsBase + "/" + collectionName);

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

            HttpResponse<String> createRes = post(collectionsBase, mapper.writeValueAsString(body));

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
     * Existing chunk IDs are overwritten — safe for re-indexing.
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
            HttpResponse<String> response = post(url, mapper.writeValueAsString(body));

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
     * Called before re-indexing a changed file to remove stale chunks.
     */
    public void deleteBySourceFile(String absoluteFilePath) {
        ensureCollectionIdLoaded();
        try {
            ObjectNode body = mapper.createObjectNode();
            ObjectNode where = body.putObject("where");
            ObjectNode condition = where.putObject("sourceFilePath");
            condition.put("$eq", absoluteFilePath);

            String url = collectionsBase + "/" + collectionId + "/delete";
            HttpResponse<String> response = post(url, mapper.writeValueAsString(body));

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
            HttpResponse<String> response = get(collectionsBase + "/" + collectionId + "/count");
            if (response.statusCode() == 200) {
                return mapper.readTree(response.body()).asInt();
            }
        } catch (Exception e) {
            log.warn("Could not fetch ChromaDB count: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Query ChromaDB for the top-k most similar chunks to a given embedding vector.
     * Used in Stage 3 querying.
     *
     * @param queryEmbedding the question's embedding vector
     * @param topK           number of results to return
     * @return QueryResult containing texts and metadatas of matching chunks
     */
    public QueryResult query(float[] queryEmbedding, int topK) {
        ensureCollectionIdLoaded();
        try {
            ObjectNode body = mapper.createObjectNode();

            // query_embeddings is an array of arrays (we send one query vector)
            ArrayNode queryEmbeddings = body.putArray("query_embeddings");
            ArrayNode vecNode = queryEmbeddings.addArray();
            for (float v : queryEmbedding) vecNode.add(v);

            body.put("n_results", topK);

            // Ask ChromaDB to return documents and metadatas alongside distances
            ArrayNode include = body.putArray("include");
            include.add("documents");
            include.add("metadatas");
            include.add("distances");

            String url = collectionsBase + "/" + collectionId + "/query";
            HttpResponse<String> response = post(url, mapper.writeValueAsString(body));

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

        // ChromaDB returns arrays-of-arrays (one per query vector — we sent 1)
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
    // HTTP helpers
    // -------------------------------------------------------------------------

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void ensureCollectionIdLoaded() {
        if (collectionId == null) ensureCollection();
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



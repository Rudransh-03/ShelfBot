package com.localfilebrain.storage;

import com.localfilebrain.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Local vector store built on SQLite + pure-Java cosine similarity.
 *
 * Replaces ChromaDB. Zero external dependencies, zero native extensions,
 * works on every OS. Single SQLite file stores everything.
 *
 * Schema:
 *   TABLE chunks:
 *     chunk_id          TEXT PRIMARY KEY   — e.g. "/docs/file.pdf::chunk-0"
 *     source_file_path  TEXT NOT NULL
 *     file_name         TEXT NOT NULL
 *     chunk_index       INTEGER NOT NULL
 *     total_chunks      INTEGER NOT NULL
 *     mime_type         TEXT NOT NULL
 *     file_last_modified_ms INTEGER NOT NULL
 *     char_count        INTEGER NOT NULL
 *     text              TEXT NOT NULL      — raw chunk text
 *     embedding         BLOB NOT NULL      — float[] serialized as bytes
 *     indexed_at        TEXT NOT NULL
 *
 * Similarity search: loads all embedding BLOBs into memory, computes cosine
 * similarity in Java, returns top-K. For 50,000 chunks (768-dim) this is
 * ~150MB RAM and ~200ms — acceptable for personal document search.
 *
 * Thread safety: single connection, synchronized methods.
 */
public final class VectorStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    private final Connection connection;

    public VectorStore(Path dbPath) {
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            // WAL mode for better concurrent read performance
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
            }
            initSchema();
            log.info("VectorStore opened: {}", dbPath.toAbsolutePath());
        } catch (SQLException e) {
            throw new VectorStoreException("Failed to open VectorStore at: " + dbPath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Schema
    // -------------------------------------------------------------------------

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS chunks (
                    chunk_id              TEXT    PRIMARY KEY,
                    source_file_path      TEXT    NOT NULL,
                    file_name             TEXT    NOT NULL,
                    chunk_index           INTEGER NOT NULL,
                    total_chunks          INTEGER NOT NULL,
                    mime_type             TEXT    NOT NULL,
                    file_last_modified_ms INTEGER NOT NULL,
                    char_count            INTEGER NOT NULL,
                    text                  TEXT    NOT NULL,
                    embedding             BLOB    NOT NULL,
                    indexed_at            TEXT    NOT NULL
                )
                """);
            stmt.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_source_file ON chunks(source_file_path)
                """);
        }
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Upserts a batch of chunks with their embeddings.
     * If a chunk_id already exists it is overwritten — safe for re-indexing.
     */
    public synchronized void upsert(List<DocumentChunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks and embeddings must have the same size");
        }

        String sql = """
            INSERT INTO chunks
              (chunk_id, source_file_path, file_name, chunk_index, total_chunks,
               mime_type, file_last_modified_ms, char_count, text, embedding, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(chunk_id) DO UPDATE SET
              source_file_path      = excluded.source_file_path,
              file_name             = excluded.file_name,
              chunk_index           = excluded.chunk_index,
              total_chunks          = excluded.total_chunks,
              mime_type             = excluded.mime_type,
              file_last_modified_ms = excluded.file_last_modified_ms,
              char_count            = excluded.char_count,
              text                  = excluded.text,
              embedding             = excluded.embedding,
              indexed_at            = excluded.indexed_at
            """;

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (int i = 0; i < chunks.size(); i++) {
                    DocumentChunk chunk = chunks.get(i);
                    float[]       vec   = embeddings.get(i);

                    ps.setString(1,  chunk.getChunkId());
                    ps.setString(2,  chunk.getSourceFilePath());
                    ps.setString(3,  chunk.getFileName());
                    ps.setInt   (4,  chunk.getChunkIndex());
                    ps.setInt   (5,  chunk.getTotalChunks());
                    ps.setString(6,  chunk.getMimeType());
                    ps.setLong  (7,  chunk.getFileLastModifiedMs());
                    ps.setInt   (8,  chunk.getCharCount());
                    ps.setString(9,  chunk.getText());
                    ps.setBytes (10, floatsToBytes(vec));
                    ps.setString(11, Instant.now().toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            try { connection.rollback(); connection.setAutoCommit(true); } catch (SQLException ignored) {}
            throw new VectorStoreException("Failed to upsert chunks", e);
        }
    }

    /**
     * Deletes all chunks for a given source file.
     * Called before re-indexing a changed file.
     */
    public synchronized void deleteBySourceFile(String absoluteFilePath) {
        String sql = "DELETE FROM chunks WHERE source_file_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absoluteFilePath);
            int deleted = ps.executeUpdate();
            log.debug("Deleted {} chunks for: {}", deleted, absoluteFilePath);
        } catch (SQLException e) {
            log.warn("Failed to delete chunks for '{}': {}", absoluteFilePath, e.getMessage());
        }
    }

    /**
     * Returns total chunk count in the store.
     */
    public synchronized int count() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs   = stmt.executeQuery("SELECT COUNT(*) FROM chunks")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("Could not count chunks: {}", e.getMessage());
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Vector similarity search
    // -------------------------------------------------------------------------

    /**
     * Returns the top-K chunks most similar to the query embedding.
     * Uses cosine similarity computed in pure Java over all stored embeddings.
     */
    public synchronized List<SearchResult> query(float[] queryEmbedding, int topK) {
        // Load all embeddings + metadata from SQLite
        String sql = "SELECT chunk_id, source_file_path, file_name, chunk_index, text, embedding FROM chunks";

        List<CandidateRow> candidates = new ArrayList<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs   = stmt.executeQuery(sql)) {

            while (rs.next()) {
                byte[]  blob      = rs.getBytes("embedding");
                float[] embedding = bytesToFloats(blob);
                double  score     = cosineSimilarity(queryEmbedding, embedding);

                candidates.add(new CandidateRow(
                        rs.getString("chunk_id"),
                        rs.getString("source_file_path"),
                        rs.getString("file_name"),
                        rs.getInt("chunk_index"),
                        rs.getString("text"),
                        score
                ));
            }
        } catch (SQLException e) {
            throw new VectorStoreException("Failed to query vector store", e);
        }

        // Sort by similarity descending, take top-K
        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));

        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, candidates.size()); i++) {
            CandidateRow c = candidates.get(i);
            // Convert similarity score to distance (1 - similarity) for consistency
            // with the threshold check in QueryEngine (lower = more similar)
            results.add(new SearchResult(
                    c.chunkId(), c.sourceFilePath(), c.fileName(),
                    c.chunkIndex(), c.text(), 1.0 - c.score()
            ));
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // Math
    // -------------------------------------------------------------------------

    /**
     * Cosine similarity between two vectors. Returns 1.0 for identical, 0.0 for orthogonal.
     */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    private static byte[] floatsToBytes(float[] floats) {
        ByteBuffer buf = ByteBuffer.allocate(floats.length * Float.BYTES);
        FloatBuffer fb = buf.asFloatBuffer();
        fb.put(floats);
        return buf.array();
    }

    private static float[] bytesToFloats(byte[] bytes) {
        FloatBuffer fb = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] floats = new float[fb.limit()];
        fb.get(floats);
        return floats;
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    public record SearchResult(
            String chunkId,
            String sourceFilePath,
            String fileName,
            int    chunkIndex,
            String text,
            double distance   // 0 = identical, 1 = completely different
    ) {}

    private record CandidateRow(
            String chunkId,
            String sourceFilePath,
            String fileName,
            int    chunkIndex,
            String text,
            double score      // cosine similarity: higher = more similar
    ) {}

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.debug("VectorStore connection closed");
            }
        } catch (SQLException e) {
            log.warn("Error closing VectorStore", e);
        }
    }

    public static class VectorStoreException extends RuntimeException {
        public VectorStoreException(String message, Throwable cause) { super(message, cause); }
    }
}

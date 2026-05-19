package com.localfilebrain.ingestion;

import com.localfilebrain.model.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/**
 * Local SQLite-backed metadata store that tracks which files have been indexed,
 * their content hashes, timestamps, and chunk counts.
 *
 * Schema:
 *   TABLE file_index:
 *     absolute_path TEXT PRIMARY KEY
 *     file_name     TEXT NOT NULL
 *     file_extension TEXT NOT NULL
 *     file_size_bytes INTEGER NOT NULL
 *     last_modified_ms INTEGER NOT NULL
 *     content_hash TEXT NOT NULL       ← SHA-256, used for change detection
 *     status TEXT NOT NULL             ← INDEXED | FAILED | IN_PROGRESS
 *     chunk_count INTEGER DEFAULT 0
 *     last_indexed_at TEXT             ← ISO-8601 instant
 *     error_message TEXT               ← populated on failure
 *
 * Thread safety: single-connection, synchronized methods. For multi-threaded
 * use in Stage 2, replace with a connection pool.
 */
public final class IndexMetadataStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IndexMetadataStore.class);

    private final Connection connection;

    public IndexMetadataStore(Path dbPath) {
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            initSchema();
            log.info("Metadata store opened: {}", dbPath.toAbsolutePath());
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to open metadata DB at: " + dbPath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Schema initialization
    // -------------------------------------------------------------------------

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS file_index (
                    absolute_path    TEXT    PRIMARY KEY,
                    file_name        TEXT    NOT NULL,
                    file_extension   TEXT    NOT NULL,
                    file_size_bytes  INTEGER NOT NULL,
                    last_modified_ms INTEGER NOT NULL,
                    content_hash     TEXT    NOT NULL,
                    status           TEXT    NOT NULL DEFAULT 'INDEXED',
                    chunk_count      INTEGER NOT NULL DEFAULT 0,
                    last_indexed_at  TEXT,
                    error_message    TEXT
                )
                """);

            // Index for fast lookup by status (used during re-index to find FAILEDs)
            stmt.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_status ON file_index(status)
                """);

            log.debug("Schema initialized");
        }
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Returns the stored record for a file, or empty if not seen before.
     */
    public synchronized Optional<FileRecord> findByPath(String absolutePath) {
        String sql = "SELECT * FROM file_index WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to query file: " + absolutePath, e);
        }
        return Optional.empty();
    }

    /**
     * Step 1 check: returns true if the file is INDEXED and its stored last-modified
     * timestamp matches the current one. Zero file I/O — just a SQLite lookup.
     */
    public synchronized boolean isUpToDateByTimestamp(String absolutePath, long currentLastModifiedMs) {
        String sql = "SELECT last_modified_ms, status FROM file_index WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long storedTs = rs.getLong("last_modified_ms");
                    String status = rs.getString("status");
                    return currentLastModifiedMs == storedTs
                        && FileRecord.Status.INDEXED.name().equals(status);
                }
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to check timestamp for: " + absolutePath, e);
        }
        return false;
    }

    /**
     * Step 2 check: returns true if the file is INDEXED and its stored SHA-256 hash
     * matches the current one. Called only when the timestamp has changed.
     */
    public synchronized boolean isUpToDateByHash(String absolutePath, String currentHash) {
        String sql = "SELECT content_hash, status FROM file_index WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("content_hash");
                    String status     = rs.getString("status");
                    return currentHash.equals(storedHash)
                        && FileRecord.Status.INDEXED.name().equals(status);
                }
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to check hash for: " + absolutePath, e);
        }
        return false;
    }

    /**
     * Updates only the stored last_modified_ms for a file.
     * Called when a file's timestamp changed but content hash didn't —
     * so Step 1 (timestamp check) fires correctly on the next run.
     */
    public synchronized void updateTimestamp(String absolutePath, long newLastModifiedMs) {
        String sql = "UPDATE file_index SET last_modified_ms = ? WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, newLastModifiedMs);
            ps.setString(2, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to update timestamp for: " + absolutePath, e);
        }
    }

    /**
     * Returns the total number of indexed files.
     */
    public synchronized int countIndexed() {
        String sql = "SELECT COUNT(*) FROM file_index WHERE status = 'INDEXED'";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to count indexed files", e);
        }
    }

    /**
     * Returns the total number of failed files.
     */
    public synchronized int countFailed() {
        String sql = "SELECT COUNT(*) FROM file_index WHERE status = 'FAILED'";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to count failed files", e);
        }
    }

    /**
     * Returns the sum of all chunk_count values for INDEXED files.
     */
    public synchronized int getTotalChunks() {
        String sql = "SELECT COALESCE(SUM(chunk_count), 0) FROM file_index WHERE status = 'INDEXED'";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to sum chunk counts", e);
        }
    }

    /**
     * Returns the most recent last_indexed_at timestamp across all INDEXED files.
     */
    public synchronized Optional<String> getLastIndexedAt() {
        String sql = "SELECT last_indexed_at FROM file_index WHERE status = 'INDEXED' "
                   + "AND last_indexed_at IS NOT NULL ORDER BY last_indexed_at DESC LIMIT 1";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? Optional.ofNullable(rs.getString("last_indexed_at")) : Optional.empty();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to get last indexed timestamp", e);
        }
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Inserts or replaces the full record for a file.
     */
    public synchronized void upsert(FileRecord record) {
        String sql = """
            INSERT INTO file_index
              (absolute_path, file_name, file_extension, file_size_bytes,
               last_modified_ms, content_hash, status, chunk_count, last_indexed_at, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(absolute_path) DO UPDATE SET
              file_name        = excluded.file_name,
              file_extension   = excluded.file_extension,
              file_size_bytes  = excluded.file_size_bytes,
              last_modified_ms = excluded.last_modified_ms,
              content_hash     = excluded.content_hash,
              status           = excluded.status,
              chunk_count      = excluded.chunk_count,
              last_indexed_at  = excluded.last_indexed_at,
              error_message    = excluded.error_message
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, record.getAbsolutePath());
            ps.setString(2, record.getFileName());
            ps.setString(3, record.getFileExtension());
            ps.setLong  (4, record.getFileSizeBytes());
            ps.setLong  (5, record.getLastModifiedMs());
            ps.setString(6, record.getContentHash());
            ps.setString(7, record.getStatus().name());
            ps.setInt   (8, record.getChunkCount());
            ps.setString(9, record.getLastIndexedAt() != null ? record.getLastIndexedAt().toString() : null);
            ps.setString(10, record.getErrorMessage());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to upsert record for: " + record.getAbsolutePath(), e);
        }
    }

    /**
     * Marks a file as failed with an error message.
     * Preserves all other existing fields.
     */
    public synchronized void markFailed(String absolutePath, String errorMessage) {
        String sql = """
            UPDATE file_index
            SET status = 'FAILED', error_message = ?, last_indexed_at = ?
            WHERE absolute_path = ?
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, truncate(errorMessage, 1000));
            ps.setString(2, Instant.now().toString());
            ps.setString(3, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to mark file as failed: " + absolutePath, e);
        }
    }

    /**
     * Removes a file's record (used when a file is deleted from disk).
     */
    public synchronized void delete(String absolutePath) {
        String sql = "DELETE FROM file_index WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to delete record for: " + absolutePath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FileRecord mapRow(ResultSet rs) throws SQLException {
        String statusStr = rs.getString("status");
        FileRecord.Status status;
        try {
            status = FileRecord.Status.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            status = FileRecord.Status.FAILED;
        }

        String lastIndexedStr = rs.getString("last_indexed_at");
        Instant lastIndexed   = lastIndexedStr != null ? Instant.parse(lastIndexedStr) : null;

        return FileRecord.builder()
            .absolutePath(rs.getString("absolute_path"))
            .fileName(rs.getString("file_name"))
            .fileExtension(rs.getString("file_extension"))
            .fileSizeBytes(rs.getLong("file_size_bytes"))
            .lastModifiedMs(rs.getLong("last_modified_ms"))
            .contentHash(rs.getString("content_hash"))
            .status(status)
            .chunkCount(rs.getInt("chunk_count"))
            .lastIndexedAt(lastIndexed)
            .errorMessage(rs.getString("error_message"))
            .build();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.debug("Metadata store connection closed");
            }
        } catch (SQLException e) {
            log.warn("Error closing metadata store connection", e);
        }
    }

    // -------------------------------------------------------------------------
    // Typed exception
    // -------------------------------------------------------------------------

    public static class MetadataStoreException extends RuntimeException {
        public MetadataStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

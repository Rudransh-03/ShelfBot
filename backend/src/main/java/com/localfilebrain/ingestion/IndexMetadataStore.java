package com.localfilebrain.ingestion;

import com.localfilebrain.model.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
            // WAL mode gives us: (1) durable single-statement writes — important
            // for the reorg undo log, which must be on disk BEFORE the move,
            // and (2) concurrent reads alongside writes, so a long-running
            // indexing job doesn't block the reorg history endpoint. The
            // PRAGMA is per-database-file and persists.
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA synchronous=NORMAL");
            }
            initSchema();
            log.info("Metadata store opened (WAL): {}", dbPath.toAbsolutePath());
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
                    token_count      INTEGER NOT NULL DEFAULT 0,
                    last_indexed_at  TEXT,
                    error_message    TEXT
                )
                """);

            // Index for fast lookup by status (used during re-index to find FAILEDs)
            stmt.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_status ON file_index(status)
                """);

            // Cache of mean-pooled file-level embeddings, derived on demand
            // from the per-chunk vectors in Lucene. Stored as a raw byte BLOB
            // (4 bytes per dim, little-endian) — SQLite handles arbitrary
            // binary blobs natively. Invalidated on file upsert / delete and
            // on embedding-model change (via model_id mismatch on read).
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS file_vectors (
                    absolute_path TEXT    PRIMARY KEY,
                    dim           INTEGER NOT NULL,
                    model_id      TEXT    NOT NULL,
                    chunk_count   INTEGER NOT NULL,
                    vec           BLOB    NOT NULL,
                    computed_at   TEXT    NOT NULL
                )
                """);

            // Cached one-page summary per indexed file. Generated on demand
            // when the user clicks "Summarise" in the Library view. The
            // content_hash column is the staleness check — when the file's
            // current hash differs from the stored one the cached row is
            // ignored and regenerated.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS file_summaries (
                    absolute_path TEXT    PRIMARY KEY,
                    content_hash  TEXT    NOT NULL,
                    summary       TEXT    NOT NULL,
                    llm_calls     INTEGER NOT NULL DEFAULT 1,
                    generated_at  TEXT    NOT NULL
                )
                """);

            // Cross-document deadline intelligence. document_deadlines holds the
            // extracted items (one row per deadline/renewal/action); deadline_scan
            // is the per-file "scanned at this content hash" marker so the scan
            // is incremental and never re-pays for an unchanged file (even one
            // that yielded zero deadlines); deadline_usage is a client-side daily
            // counter that paces LLM calls so a big library scan can't burn the
            // whole day's budget in one go. All three are invalidated for a file
            // on re-index / delete (see upsert/delete), exactly like the summary
            // cache, so a changed document is re-scanned next pass.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS document_deadlines (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    absolute_path  TEXT    NOT NULL,
                    file_name      TEXT    NOT NULL,
                    content_hash   TEXT    NOT NULL,
                    title          TEXT    NOT NULL,
                    description    TEXT,
                    due_date       TEXT,
                    kind           TEXT    NOT NULL DEFAULT 'ACTION',
                    confidence     TEXT    NOT NULL DEFAULT 'MEDIUM',
                    recurring      TEXT    NOT NULL DEFAULT 'NONE',
                    status         TEXT    NOT NULL DEFAULT 'PENDING',
                    reminder_set   INTEGER NOT NULL DEFAULT 0,
                    source_excerpt TEXT,
                    created_at     TEXT    NOT NULL,
                    updated_at     TEXT    NOT NULL
                )
                """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_deadlines_path ON document_deadlines(absolute_path)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_deadlines_status ON document_deadlines(status)");
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS deadline_scan (
                    absolute_path TEXT    PRIMARY KEY,
                    content_hash  TEXT    NOT NULL,
                    item_count    INTEGER NOT NULL DEFAULT 0,
                    scanned_at    TEXT    NOT NULL
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS deadline_usage (
                    date       TEXT    PRIMARY KEY,
                    call_count INTEGER NOT NULL DEFAULT 0
                )
                """);

            // Undo log for executed reorg batches. One row per file move.
            // Written BEFORE the move actually happens so a crash mid-move
            // still leaves a recoverable record. The created_destination_dir
            // column lets undo know whether to delete the (possibly empty)
            // destination folder we created — null when we moved into a
            // folder that already existed.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reorg_undo_log (
                    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
                    batch_id                TEXT    NOT NULL,
                    sequence                INTEGER NOT NULL,
                    executed_at             TEXT    NOT NULL,
                    from_path               TEXT    NOT NULL,
                    to_path                 TEXT    NOT NULL,
                    created_destination_dir TEXT
                )
                """);
            stmt.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_undo_batch
                    ON reorg_undo_log(batch_id)
                """);

            // Lightweight migration: pre-existing DBs from before token tracking
            // won't have the column. Adding it is idempotent — SQLite throws
            // "duplicate column" if it already exists, which we swallow.
            try {
                stmt.executeUpdate("ALTER TABLE file_index ADD COLUMN token_count INTEGER NOT NULL DEFAULT 0");
                log.info("Added token_count column to existing file_index table");
            } catch (SQLException ignored) {
                // Column already exists — nothing to do.
            }

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
     * Returns the sum of token_count across all INDEXED files — used to
     * enforce the per-user indexing budget.
     */
    public synchronized long sumIndexedTokens() {
        String sql = "SELECT COALESCE(SUM(token_count), 0) FROM file_index WHERE status = 'INDEXED'";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to sum token counts", e);
        }
    }

    /**
     * Returns the stored token count for a specific file (0 if unknown).
     * Used during re-index to compute the delta against the user budget.
     */
    public synchronized long getTokenCountForFile(String absolutePath) {
        String sql = "SELECT token_count FROM file_index WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to read tokens for: " + absolutePath, e);
        }
    }

    /**
     * Returns every INDEXED file, sorted by file size descending. Used by
     * the Library view to show a per-file management list.
     */
    public synchronized List<FileRecord> listIndexedFilesBySizeDesc() {
        String sql = "SELECT * FROM file_index WHERE status = 'INDEXED' "
                   + "ORDER BY file_size_bytes DESC, file_name ASC";
        List<FileRecord> out = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) out.add(mapRow(rs));
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to list indexed files", e);
        }
        return out;
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
               last_modified_ms, content_hash, status, chunk_count, token_count,
               last_indexed_at, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(absolute_path) DO UPDATE SET
              file_name        = excluded.file_name,
              file_extension   = excluded.file_extension,
              file_size_bytes  = excluded.file_size_bytes,
              last_modified_ms = excluded.last_modified_ms,
              content_hash     = excluded.content_hash,
              status           = excluded.status,
              chunk_count      = excluded.chunk_count,
              token_count      = excluded.token_count,
              last_indexed_at  = excluded.last_indexed_at,
              error_message    = excluded.error_message
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1,  record.getAbsolutePath());
            ps.setString(2,  record.getFileName());
            ps.setString(3,  record.getFileExtension());
            ps.setLong  (4,  record.getFileSizeBytes());
            ps.setLong  (5,  record.getLastModifiedMs());
            ps.setString(6,  record.getContentHash());
            ps.setString(7,  record.getStatus().name());
            ps.setInt   (8,  record.getChunkCount());
            ps.setLong  (9,  record.getTokenCount());
            ps.setString(10, record.getLastIndexedAt() != null ? record.getLastIndexedAt().toString() : null);
            ps.setString(11, record.getErrorMessage());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to upsert record for: " + record.getAbsolutePath(), e);
        }
        // Any re-upsert means the file's content has been re-processed and its
        // cached file-level vector (if any) is now stale. Cheaper to wipe than
        // to detect staleness on read.
        deleteFileVector(record.getAbsolutePath());
        // The file was re-processed (possibly with different content), so
        // any cached summary is stale — drop it. The summary will be
        // regenerated on next /api/files/summary request.
        deleteSummary(record.getAbsolutePath());
        // Same staleness logic for extracted deadlines: a changed file may
        // have entirely different dates, so drop its items and its scan marker
        // — the deadline scan will re-extract this file on its next pass.
        clearDeadlinesForFile(record.getAbsolutePath());
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
     * Wipes every row whose status is INDEXED. Used to recover from a drift
     * state where the metadata DB claims files are indexed but the vector
     * store has been wiped or is otherwise empty — without this the scanner
     * would honour the metadata and refuse to re-process anything.
     *
     * FAILED rows are preserved so we don't keep retrying known-bad files.
     */
    public synchronized int clearAllIndexedRecords() {
        try (Statement stmt = connection.createStatement()) {
            int n = stmt.executeUpdate("DELETE FROM file_index WHERE status = 'INDEXED'");
            // File-level vector cache is downstream of indexed chunks — if the
            // indexed set is wiped, the cache is meaningless.
            stmt.executeUpdate("DELETE FROM file_vectors");
            log.warn("Cleared {} INDEXED rows from metadata store (drift recovery)", n);
            return n;
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to clear indexed records", e);
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
        deleteFileVector(absolutePath);
        deleteSummary(absolutePath);
        clearDeadlinesForFile(absolutePath);
    }

    // -------------------------------------------------------------------------
    // Document summary cache
    // -------------------------------------------------------------------------

    /** Cached one-page summary for a file. */
    public record CachedSummary(String absolutePath,
                                String contentHash,
                                String summary,
                                int llmCalls,
                                String generatedAt) {}

    /**
     * Returns the cached summary for a file (regardless of staleness — the
     * caller compares {@code contentHash} against the current file row to
     * decide whether to use it).
     */
    public synchronized Optional<CachedSummary> getSummary(String absolutePath) {
        String sql = "SELECT absolute_path, content_hash, summary, llm_calls, generated_at "
                   + "FROM file_summaries WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new CachedSummary(
                        rs.getString("absolute_path"),
                        rs.getString("content_hash"),
                        rs.getString("summary"),
                        rs.getInt("llm_calls"),
                        rs.getString("generated_at")));
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to read summary for: " + absolutePath, e);
        }
    }

    /** Inserts or replaces the cached summary. */
    public synchronized void putSummary(String absolutePath,
                                        String contentHash,
                                        String summary,
                                        int llmCalls) {
        String sql = """
            INSERT INTO file_summaries (absolute_path, content_hash, summary, llm_calls, generated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(absolute_path) DO UPDATE SET
              content_hash = excluded.content_hash,
              summary      = excluded.summary,
              llm_calls    = excluded.llm_calls,
              generated_at = excluded.generated_at
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.setString(2, contentHash);
            ps.setString(3, summary);
            ps.setInt   (4, llmCalls);
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to put summary for: " + absolutePath, e);
        }
    }

    /** Removes the cached summary. No-op if none stored. */
    public synchronized void deleteSummary(String absolutePath) {
        String sql = "DELETE FROM file_summaries WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to delete summary for '{}': {}", absolutePath, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // File-level vector cache (for reorg / clustering)
    // -------------------------------------------------------------------------

    /**
     * Stores the mean-pooled, L2-normalized file vector for {@code absolutePath}.
     * Overwrites any existing entry. The {@code modelId} pin lets readers
     * detect — and discard — vectors computed under a different embedding
     * model.
     */
    public synchronized void putFileVector(String absolutePath,
                                           int dim,
                                           String modelId,
                                           int chunkCount,
                                           float[] vec) {
        if (vec.length != dim) {
            throw new IllegalArgumentException("vec.length=" + vec.length + " != dim=" + dim);
        }
        byte[] blob = floatsToBytes(vec);
        String sql = """
            INSERT INTO file_vectors (absolute_path, dim, model_id, chunk_count, vec, computed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(absolute_path) DO UPDATE SET
              dim         = excluded.dim,
              model_id    = excluded.model_id,
              chunk_count = excluded.chunk_count,
              vec         = excluded.vec,
              computed_at = excluded.computed_at
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.setInt   (2, dim);
            ps.setString(3, modelId);
            ps.setInt   (4, chunkCount);
            ps.setBytes (5, blob);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to put file vector for: " + absolutePath, e);
        }
    }

    /**
     * Returns the cached file vector, or empty if none exists. Callers must
     * check the {@code modelId} field against the current embedding backend
     * — a mismatch means the cached vector lives in the wrong embedding
     * space and should be discarded.
     */
    public synchronized Optional<CachedFileVector> getFileVector(String absolutePath) {
        String sql = "SELECT dim, model_id, chunk_count, vec FROM file_vectors WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                int dim       = rs.getInt("dim");
                String model  = rs.getString("model_id");
                int chunkCnt  = rs.getInt("chunk_count");
                byte[] blob   = rs.getBytes("vec");
                float[] vec   = bytesToFloats(blob, dim);
                return Optional.of(new CachedFileVector(absolutePath, dim, model, chunkCnt, vec));
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to read file vector for: " + absolutePath, e);
        }
    }

    /** Removes the cached file vector for one file. No-op if none stored. */
    public synchronized void deleteFileVector(String absolutePath) {
        String sql = "DELETE FROM file_vectors WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Cache invalidation is best-effort — log and continue.
            log.warn("Failed to delete file vector for '{}': {}", absolutePath, e.getMessage());
        }
    }

    /** Cached file-level embedding row. */
    public record CachedFileVector(String absolutePath,
                                   int dim,
                                   String modelId,
                                   int chunkCount,
                                   float[] vec) {}

    // -------------------------------------------------------------------------
    // Reorg undo log
    // -------------------------------------------------------------------------

    /**
     * Records one move in the undo log. Must be called BEFORE the actual
     * filesystem move so a crash between the DB write and the move still
     * leaves a recoverable trail.
     *
     * @param createdDestinationDir absolute path of a directory the executor
     *        created for this move (so undo knows it can clean up); null if
     *        the destination directory already existed.
     */
    public synchronized void appendUndoEntry(String batchId,
                                             int sequence,
                                             String fromPath,
                                             String toPath,
                                             String createdDestinationDir) {
        String sql = """
            INSERT INTO reorg_undo_log
              (batch_id, sequence, executed_at, from_path, to_path, created_destination_dir)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, batchId);
            ps.setInt   (2, sequence);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, fromPath);
            ps.setString(5, toPath);
            if (createdDestinationDir != null) ps.setString(6, createdDestinationDir);
            else                               ps.setNull  (6, Types.VARCHAR);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to append undo entry for batch " + batchId, e);
        }
    }

    /**
     * Returns all rows in a batch, ordered for replay-in-reverse (largest
     * sequence first). Used by the undo executor to reverse moves in the
     * opposite order they were applied.
     */
    public synchronized List<UndoEntry> getUndoBatchReversed(String batchId) {
        String sql = """
            SELECT batch_id, sequence, executed_at, from_path, to_path, created_destination_dir
            FROM reorg_undo_log
            WHERE batch_id = ?
            ORDER BY sequence DESC
            """;
        List<UndoEntry> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new UndoEntry(
                            rs.getString("batch_id"),
                            rs.getInt("sequence"),
                            rs.getString("executed_at"),
                            rs.getString("from_path"),
                            rs.getString("to_path"),
                            rs.getString("created_destination_dir")));
                }
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to read undo batch " + batchId, e);
        }
        return out;
    }

    /** Removes all rows for a batch — called after a successful undo. */
    public synchronized void deleteUndoBatch(String batchId) {
        String sql = "DELETE FROM reorg_undo_log WHERE batch_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, batchId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to delete undo batch " + batchId, e);
        }
    }

    /**
     * Recent batches, newest first. Powers the "Undo last reorganization"
     * UI affordance. Each summary carries enough info to render a one-line
     * label without re-reading individual rows.
     */
    public synchronized List<UndoBatchSummary> listRecentUndoBatches(int limit) {
        String sql = """
            SELECT batch_id,
                   COUNT(*)           AS move_count,
                   MAX(executed_at)   AS executed_at
            FROM reorg_undo_log
            GROUP BY batch_id
            ORDER BY executed_at DESC
            LIMIT ?
            """;
        List<UndoBatchSummary> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new UndoBatchSummary(
                            rs.getString("batch_id"),
                            rs.getInt("move_count"),
                            rs.getString("executed_at")));
                }
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to list undo batches", e);
        }
        return out;
    }

    /** A single recorded move in the undo log. */
    public record UndoEntry(String batchId,
                            int sequence,
                            String executedAt,
                            String fromPath,
                            String toPath,
                            String createdDestinationDir) {}

    /** Lightweight summary of an undo batch — for the "recent reorgs" UI list. */
    public record UndoBatchSummary(String batchId, int moveCount, String executedAt) {}

    private static byte[] floatsToBytes(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) buf.putFloat(f);
        return buf.array();
    }

    private static float[] bytesToFloats(byte[] b, int expectedDim) {
        if (b.length != expectedDim * Float.BYTES) {
            throw new MetadataStoreException(
                    "Corrupt file_vectors row: blob length " + b.length +
                    " ≠ expected " + (expectedDim * Float.BYTES),
                    null);
        }
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[expectedDim];
        for (int i = 0; i < expectedDim; i++) out[i] = buf.getFloat();
        return out;
    }

    // -------------------------------------------------------------------------
    // Cross-document deadlines
    // -------------------------------------------------------------------------

    /** A stored deadline/renewal/action row. */
    public record DeadlineRow(long id,
                              String absolutePath,
                              String fileName,
                              String contentHash,
                              String title,
                              String description,
                              String dueDate,
                              String kind,
                              String confidence,
                              String recurring,
                              String status,
                              boolean reminderSet,
                              String sourceExcerpt,
                              String createdAt,
                              String updatedAt) {}

    /** Input for inserting a freshly-extracted deadline (id/timestamps assigned by the store). */
    public record NewDeadline(String title,
                              String description,
                              String dueDate,
                              String kind,
                              String confidence,
                              String recurring,
                              String sourceExcerpt) {}

    /**
     * Returns true if {@code absolutePath} has already been scanned for
     * deadlines at its current {@code contentHash}. Lets the scan skip files
     * that are unchanged since their last scan — even ones that yielded zero
     * deadlines (so we never re-pay an LLM call for a deadline-free file).
     */
    public synchronized boolean isDeadlineScanned(String absolutePath, String contentHash) {
        String sql = "SELECT content_hash FROM deadline_scan WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && contentHash.equals(rs.getString("content_hash"));
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to check deadline scan for: " + absolutePath, e);
        }
    }

    /**
     * Atomically replaces a file's extracted deadlines and stamps it scanned at
     * the given content hash. Preserves nothing of the prior rows — a file is
     * only re-scanned when its content changed, so old items are stale by then.
     * Done in a single transaction so a crash can't leave a "scanned" marker
     * without its rows (or vice-versa).
     */
    public synchronized void replaceDeadlinesForFile(String absolutePath,
                                                     String fileName,
                                                     String contentHash,
                                                     List<NewDeadline> items) {
        String now = Instant.now().toString();
        boolean priorAutoCommit = true;
        try {
            priorAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM document_deadlines WHERE absolute_path = ?")) {
                del.setString(1, absolutePath);
                del.executeUpdate();
            }

            String ins = """
                INSERT INTO document_deadlines
                  (absolute_path, file_name, content_hash, title, description, due_date,
                   kind, confidence, recurring, status, reminder_set, source_excerpt,
                   created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                """;
            try (PreparedStatement ps = connection.prepareStatement(ins)) {
                for (NewDeadline d : items) {
                    ps.setString(1,  absolutePath);
                    ps.setString(2,  fileName);
                    ps.setString(3,  contentHash);
                    ps.setString(4,  d.title());
                    ps.setString(5,  d.description());
                    ps.setString(6,  d.dueDate());
                    ps.setString(7,  d.kind());
                    ps.setString(8,  d.confidence());
                    ps.setString(9,  d.recurring());
                    ps.setString(10, d.sourceExcerpt());
                    ps.setString(11, now);
                    ps.setString(12, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            try (PreparedStatement scan = connection.prepareStatement("""
                INSERT INTO deadline_scan (absolute_path, content_hash, item_count, scanned_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(absolute_path) DO UPDATE SET
                  content_hash = excluded.content_hash,
                  item_count   = excluded.item_count,
                  scanned_at   = excluded.scanned_at
                """)) {
                scan.setString(1, absolutePath);
                scan.setString(2, contentHash);
                scan.setInt   (3, items.size());
                scan.setString(4, now);
                scan.executeUpdate();
            }

            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw new MetadataStoreException("Failed to store deadlines for: " + absolutePath, e);
        } finally {
            try { connection.setAutoCommit(priorAutoCommit); } catch (SQLException ignored) {}
        }
    }

    /** Removes a file's extracted deadlines and its scan marker (invalidation). */
    public synchronized void clearDeadlinesForFile(String absolutePath) {
        try (PreparedStatement a = connection.prepareStatement(
                "DELETE FROM document_deadlines WHERE absolute_path = ?");
             PreparedStatement b = connection.prepareStatement(
                "DELETE FROM deadline_scan WHERE absolute_path = ?")) {
            a.setString(1, absolutePath); a.executeUpdate();
            b.setString(1, absolutePath); b.executeUpdate();
        } catch (SQLException e) {
            // Best-effort invalidation — log and continue.
            log.warn("Failed to clear deadlines for '{}': {}", absolutePath, e.getMessage());
        }
    }

    /**
     * Lists deadlines, optionally filtered by status (PENDING | DONE |
     * DISMISSED). Null/blank/"all" returns every row. Ordered so the soonest
     * dated item leads and undated items sort last.
     */
    public synchronized List<DeadlineRow> listDeadlines(String statusFilter) {
        boolean filtered = statusFilter != null && !statusFilter.isBlank()
                && !"all".equalsIgnoreCase(statusFilter);
        String sql = "SELECT * FROM document_deadlines "
                   + (filtered ? "WHERE status = ? " : "")
                   + "ORDER BY (due_date IS NULL), due_date ASC, created_at ASC";
        List<DeadlineRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (filtered) ps.setString(1, statusFilter.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapDeadline(rs));
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to list deadlines", e);
        }
        return out;
    }

    /** Single deadline by id. */
    public synchronized Optional<DeadlineRow> getDeadline(long id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM document_deadlines WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapDeadline(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to get deadline " + id, e);
        }
    }

    /** Count of PENDING deadlines — drives the sidebar badge. */
    public synchronized int countPendingDeadlines() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM document_deadlines WHERE status = 'PENDING'")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to count pending deadlines", e);
        }
    }

    /**
     * Partial update of a deadline. Any null argument leaves that column
     * unchanged; {@code reminderSet} is a {@link Boolean} so null means "don't
     * touch". Returns true if a row was updated. {@code updated_at} is always
     * refreshed when at least one field changes.
     */
    public synchronized boolean updateDeadline(long id,
                                               String status,
                                               Boolean reminderSet,
                                               String title,
                                               String description,
                                               String dueDate,
                                               String recurring) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (status      != null) { sets.add("status = ?");       args.add(status.toUpperCase()); }
        if (reminderSet != null) { sets.add("reminder_set = ?"); args.add(reminderSet ? 1 : 0); }
        if (title       != null) { sets.add("title = ?");        args.add(title); }
        if (description != null) { sets.add("description = ?");  args.add(description); }
        if (dueDate     != null) { sets.add("due_date = ?");     args.add(dueDate); }
        if (recurring   != null) { sets.add("recurring = ?");    args.add(recurring.toUpperCase()); }
        if (sets.isEmpty()) return false;
        sets.add("updated_at = ?"); args.add(Instant.now().toString());

        String sql = "UPDATE document_deadlines SET " + String.join(", ", sets) + " WHERE id = ?";
        args.add(id);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to update deadline " + id, e);
        }
    }

    /** Deletes one deadline. Returns true if a row was removed. */
    public synchronized boolean deleteDeadline(long id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM document_deadlines WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to delete deadline " + id, e);
        }
    }

    /**
     * Deletes one-time (non-recurring) deadlines whose due date is strictly
     * before {@code todayIso} (YYYY-MM-DD) — they're past and no longer
     * actionable. Recurring ones are NOT deleted here (they're rolled forward
     * separately). ISO dates compare correctly as strings. Returns rows removed.
     */
    public synchronized int deleteOneTimePastDeadlines(String todayIso) {
        String sql = "DELETE FROM document_deadlines "
                   + "WHERE due_date IS NOT NULL AND due_date < ? "
                   + "AND (recurring IS NULL OR recurring = 'NONE')";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, todayIso);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to purge past one-time deadlines", e);
        }
    }

    /** Recurring deadlines whose due date is strictly before {@code todayIso} — to be rolled forward. */
    public synchronized List<DeadlineRow> listRecurringPastDeadlines(String todayIso) {
        String sql = "SELECT * FROM document_deadlines "
                   + "WHERE due_date IS NOT NULL AND due_date < ? "
                   + "AND recurring IS NOT NULL AND recurring != 'NONE'";
        List<DeadlineRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, todayIso);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapDeadline(rs));
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to list recurring past deadlines", e);
        }
        return out;
    }

    /** Today's deadline-extraction LLM call count for the given day key (0 if none). */
    public synchronized int getDeadlineCallsToday(String dayKey) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT call_count FROM deadline_usage WHERE date = ?")) {
            ps.setString(1, dayKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to read deadline usage", e);
        }
    }

    /** Atomically increments the day's deadline-call counter and returns the new value. */
    public synchronized int incrementDeadlineCallsToday(String dayKey) {
        String sql = """
            INSERT INTO deadline_usage (date, call_count) VALUES (?, 1)
            ON CONFLICT(date) DO UPDATE SET call_count = call_count + 1
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, dayKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to bump deadline usage", e);
        }
        return getDeadlineCallsToday(dayKey);
    }

    private DeadlineRow mapDeadline(ResultSet rs) throws SQLException {
        return new DeadlineRow(
                rs.getLong("id"),
                rs.getString("absolute_path"),
                rs.getString("file_name"),
                rs.getString("content_hash"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("due_date"),
                rs.getString("kind"),
                rs.getString("confidence"),
                rs.getString("recurring"),
                rs.getString("status"),
                rs.getInt("reminder_set") != 0,
                rs.getString("source_excerpt"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
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

        long tokenCount = 0L;
        try {
            tokenCount = rs.getLong("token_count");
        } catch (SQLException ignored) {
            // Older row without the column — leave at 0.
        }

        return FileRecord.builder()
            .absolutePath(rs.getString("absolute_path"))
            .fileName(rs.getString("file_name"))
            .fileExtension(rs.getString("file_extension"))
            .fileSizeBytes(rs.getLong("file_size_bytes"))
            .lastModifiedMs(rs.getLong("last_modified_ms"))
            .contentHash(rs.getString("content_hash"))
            .status(status)
            .chunkCount(rs.getInt("chunk_count"))
            .tokenCount(tokenCount)
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

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

            // Per-document recurring-series classification, produced by the same
            // LLM call as the deadline scan (no extra call). One row per indexed
            // file that the model judged to be a periodic document (e.g. a GST
            // return or a monthly statement). The Missing-Document detector reads
            // this whole table locally to spot gaps in a series. Invalidated for a
            // file on re-index / delete alongside its deadlines.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS document_series (
                    absolute_path TEXT    PRIMARY KEY,
                    file_name     TEXT    NOT NULL,
                    content_hash  TEXT    NOT NULL,
                    series        TEXT    NOT NULL,
                    issuer        TEXT,
                    period        TEXT    NOT NULL,
                    detected_at   TEXT    NOT NULL
                )
                """);

            // ── Per-client workspaces (data isolation) ──────────────────────
            // clients: the user's client list. client_identifiers: the literal
            // tokens (GSTIN/PAN/name/alias) that identify a client inside a
            // document or a question — matched on word boundaries. file_client:
            // which client each indexed file belongs to; `pinned=1` means the
            // user assigned it by hand (sticky — auto-tagging never overrides it).
            // Isolation is enforced by filtering retrieval to a client's paths,
            // so no cross-client chunk can ever surface.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS clients (
                    id         TEXT PRIMARY KEY,
                    name       TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS client_identifiers (
                    client_id TEXT NOT NULL,
                    value     TEXT NOT NULL,   -- as entered (display)
                    norm      TEXT NOT NULL,   -- normalized for matching
                    PRIMARY KEY (client_id, norm),
                    FOREIGN KEY (client_id) REFERENCES clients(id) ON DELETE CASCADE
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS file_client (
                    absolute_path TEXT PRIMARY KEY,
                    client_id     TEXT NOT NULL,
                    pinned        INTEGER NOT NULL DEFAULT 0,
                    assigned_at   TEXT NOT NULL,
                    FOREIGN KEY (client_id) REFERENCES clients(id) ON DELETE CASCADE
                )
                """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_file_client_client ON file_client(client_id)");

            // Per-document OWNER identity captured during the scan (same LLM call
            // as deadlines/series). Aggregated locally into suggested clients the
            // user can accept. Invalidated with the file on re-index/delete.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS document_entity (
                    absolute_path TEXT PRIMARY KEY,
                    content_hash  TEXT NOT NULL,
                    entity_name   TEXT,
                    gstin         TEXT,
                    pan           TEXT,
                    detected_at   TEXT NOT NULL
                )
                """);
            // Suggestions the user explicitly dismissed, by canonical key, so they
            // don't keep reappearing.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dismissed_suggestions (
                    entity_key   TEXT PRIMARY KEY,
                    dismissed_at TEXT NOT NULL
                )
                """);
            // "Needs attention" items the user cleared, by stable item key
            // ("date:<path>|<date>" / "missing:<series>|<period>"), so a
            // handled item never resurfaces. Deadline-backed items are NOT
            // stored here — clearing those sets the deadline row DISMISSED.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS attention_dismissed (
                    item_key     TEXT PRIMARY KEY,
                    dismissed_at TEXT NOT NULL
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

            // Obligation dates (due / expiry / renewal) extracted by the free
            // local LocalDateScanner. Powers the Timeline view.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS document_dates (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    absolute_path  TEXT    NOT NULL,
                    file_name      TEXT    NOT NULL,
                    content_hash   TEXT    NOT NULL,
                    event_date     TEXT    NOT NULL,
                    title          TEXT    NOT NULL,
                    source_excerpt TEXT,
                    created_at     TEXT    NOT NULL
                )
                """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dates_path ON document_dates(absolute_path)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dates_date ON document_dates(event_date)");
            // Per-file "scanned for dates at this hash" marker so the local date
            // scan is incremental — even a file that yielded zero dates is never
            // re-scanned until its content changes.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS date_scan (
                    absolute_path TEXT    PRIMARY KEY,
                    content_hash  TEXT    NOT NULL,
                    scanned_at    TEXT    NOT NULL
                )
                """);

            // Cache of fee-receivable tuples extracted from a prose document by
            // the LLM, keyed by content hash. A document is sent to the LLM at
            // most once per content version; every later "unpaid fees" question
            // reuses the cached JSON for free. Cleared naturally when the file
            // changes (hash mismatch) or is deleted.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS fee_extract (
                    absolute_path TEXT    PRIMARY KEY,
                    content_hash  TEXT    NOT NULL,
                    rows_json     TEXT    NOT NULL,
                    extracted_at  TEXT    NOT NULL
                )
                """);

            // ── Generic aggregation layer (replaces the fee-specific cache) ──────
            // The set of aggregation CATEGORIES the app has learned to extract
            // (grows as new "count/list-everything" questions appear). Keeping it
            // small and coarse keeps the match-or-new step reliable.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS agg_category (
                    name        TEXT PRIMARY KEY,   -- canonical id, e.g. "client_fee_owed"
                    label       TEXT NOT NULL,      -- human phrase, e.g. "money a client owes me"
                    field_spec  TEXT NOT NULL,      -- what the per-doc extractor pulls (LLM instruction)
                    filter_terms TEXT,              -- cheap content keywords; blank = no keyword filter (scan all)
                    created_at  TEXT NOT NULL
                )
                """);
            // Per-(document, category) extracted facts, keyed by content hash so a
            // doc is read at most once per category per version. Accumulates —
            // asking a NEW category never clears another's rows.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS doc_facts (
                    absolute_path TEXT NOT NULL,
                    category      TEXT NOT NULL,
                    content_hash  TEXT NOT NULL,
                    facts_json    TEXT NOT NULL,     -- extracted DocFact records (may be [])
                    extracted_at  TEXT NOT NULL,
                    PRIMARY KEY (absolute_path, category)
                )
                """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_doc_facts_cat ON doc_facts(category)");

            // One universal "fact sheet" per document — everything worth knowing,
            // extracted ONCE (LLM reads each doc a single time, ever) and reused to
            // answer every corpus-wide question. content_hash carries a version
            // suffix so a prompt change re-extracts; keyed by path (one row/doc).
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS doc_sheet (
                    absolute_path TEXT NOT NULL,
                    content_hash  TEXT NOT NULL,     -- content hash + "#" + sheet version
                    sheet_json    TEXT NOT NULL,     -- the universal fact sheet
                    extracted_at  TEXT NOT NULL,
                    PRIMARY KEY (absolute_path)
                )
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
            // Same idempotent migration for the auto-classified document type.
            try {
                stmt.executeUpdate("ALTER TABLE file_index ADD COLUMN doc_type TEXT");
                log.info("Added doc_type column to existing file_index table");
            } catch (SQLException ignored) {
                // Column already exists — nothing to do.
            }
            // And for the document's primary date (its own issue/period date,
            // ISO yyyy-MM-dd) — extracted free+locally by the date scan; makes
            // "documents from February 2024" questions deterministic.
            try {
                stmt.executeUpdate("ALTER TABLE file_index ADD COLUMN primary_date TEXT");
                log.info("Added primary_date column to existing file_index table");
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

    /** Files that failed to index, with their error messages (most recent first). */
    public synchronized List<FileRecord> listFailedFiles() {
        String sql = "SELECT * FROM file_index WHERE status = 'FAILED' "
                   + "ORDER BY last_indexed_at DESC, file_name ASC";
        List<FileRecord> out = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) out.add(mapRow(rs));
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to list failed files", e);
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
        // Same for the (free, local) timeline dates, auto doc-type, and primary
        // date: re-processed content gets re-scanned / re-classified next pass.
        clearDatesForFile(record.getAbsolutePath());
        clearDocType(record.getAbsolutePath());
        try { setPrimaryDate(record.getAbsolutePath(), null); } catch (Exception ignored) { }
    }

    // -------------------------------------------------------------------------
    // Document type (auto-classification → Library filter chips)
    // -------------------------------------------------------------------------

    /** Stores the auto-classified type for a file (e.g. "Invoice"). */
    public synchronized void setDocType(String absolutePath, String docType) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE file_index SET doc_type = ? WHERE absolute_path = ?")) {
            ps.setString(1, docType);
            ps.setString(2, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to set doc_type for: " + absolutePath, e);
        }
    }

    /** Clears a file's type so it is re-classified after re-processing. */
    public synchronized void clearDocType(String absolutePath) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE file_index SET doc_type = NULL WHERE absolute_path = ?")) {
            ps.setString(1, absolutePath);
            ps.executeUpdate();
        } catch (SQLException ignored) { /* best-effort */ }
    }

    // Every table that keys rows by a file's absolute path. When a reorg moves a
    // file, ALL of these must follow it — otherwise chips can't open the file,
    // and cached summaries / LLM-extracted deadlines would be re-paid for.
    private static final String[] PATH_KEYED_TABLES = {
            "file_index", "file_vectors", "file_summaries", "document_deadlines",
            "deadline_scan", "document_series", "file_client", "document_entity",
            "document_dates", "date_scan"
    };

    /**
     * Re-points every record of {@code oldPath} at {@code newPath} — used after
     * a reorg move (and its undo) so the index follows the file instead of
     * dangling at the old location. Atomic across all tables; the file name is
     * unchanged (reorg moves between folders, it never renames).
     */
    public synchronized void renamePath(String oldPath, String newPath) {
        try {
            connection.setAutoCommit(false);
            for (String table : PATH_KEYED_TABLES) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE " + table + " SET absolute_path = ? WHERE absolute_path = ?")) {
                    ps.setString(1, newPath);
                    ps.setString(2, oldPath);
                    ps.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw new MetadataStoreException(
                    "Failed to rename path " + oldPath + " -> " + newPath, e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** Stores the file's primary date (ISO yyyy-MM-dd, or null when none found). */
    public synchronized void setPrimaryDate(String absolutePath, String isoDate) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE file_index SET primary_date = ? WHERE absolute_path = ?")) {
            ps.setString(1, isoDate);
            ps.setString(2, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to set primary_date for: " + absolutePath, e);
        }
    }

    /** Indexed-file counts grouped by auto type, for the Library filter chips. */
    public synchronized java.util.LinkedHashMap<String, Integer> docTypeCounts() {
        java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
        String sql = "SELECT COALESCE(doc_type, 'Other') AS t, COUNT(*) AS n "
                   + "FROM file_index WHERE status = 'INDEXED' GROUP BY t ORDER BY n DESC";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.put(rs.getString("t"), rs.getInt("n"));
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to count doc types", e);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Timeline dates (free local extraction → Timeline view)
    // -------------------------------------------------------------------------

    /** A freshly-extracted obligation date (id/timestamp assigned by the store). */
    public record NewDate(String eventDate, String title, String sourceExcerpt) {}

    /** One stored timeline date row. */
    public record DateRow(long id, String absolutePath, String fileName,
                          String eventDate, String title, String sourceExcerpt,
                          String docType) {}

    /** True if {@code absolutePath} was already date-scanned at {@code contentHash}. */
    public synchronized boolean isDateScanned(String absolutePath, String contentHash) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT content_hash FROM date_scan WHERE absolute_path = ?")) {
            ps.setString(1, absolutePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && contentHash != null && contentHash.equals(rs.getString("content_hash"));
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to check date scan for: " + absolutePath, e);
        }
    }

    /** Replaces a file's stored dates and records the scan marker, atomically. */
    public synchronized void replaceDatesForFile(String absolutePath, String fileName,
                                                 String contentHash, List<NewDate> events) {
        String now = Instant.now().toString();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM document_dates WHERE absolute_path = ?")) {
                del.setString(1, absolutePath);
                del.executeUpdate();
            }
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO document_dates (absolute_path, file_name, content_hash, "
                  + "event_date, title, source_excerpt, created_at) VALUES (?,?,?,?,?,?,?)")) {
                for (NewDate e : events) {
                    ins.setString(1, absolutePath);
                    ins.setString(2, fileName);
                    ins.setString(3, contentHash);
                    ins.setString(4, e.eventDate());
                    ins.setString(5, e.title());
                    ins.setString(6, truncate(e.sourceExcerpt(), 300));
                    ins.setString(7, now);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            try (PreparedStatement mk = connection.prepareStatement(
                    "INSERT INTO date_scan (absolute_path, content_hash, scanned_at) VALUES (?,?,?) "
                  + "ON CONFLICT(absolute_path) DO UPDATE SET content_hash = excluded.content_hash, "
                  + "scanned_at = excluded.scanned_at")) {
                mk.setString(1, absolutePath);
                mk.setString(2, contentHash);
                mk.setString(3, now);
                mk.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw new MetadataStoreException("Failed to store dates for: " + absolutePath, e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /** Drops a file's stored dates + scan marker (called on re-process / delete). */
    public synchronized void clearDatesForFile(String absolutePath) {
        try (PreparedStatement a = connection.prepareStatement(
                "DELETE FROM document_dates WHERE absolute_path = ?");
             PreparedStatement b = connection.prepareStatement(
                "DELETE FROM date_scan WHERE absolute_path = ?")) {
            a.setString(1, absolutePath); a.executeUpdate();
            b.setString(1, absolutePath); b.executeUpdate();
        } catch (SQLException ignored) { /* best-effort */ }
    }

    /** All timeline dates, chronological, joined to each file's auto doc type. */
    public synchronized List<DateRow> listTimeline() {
        String sql = "SELECT d.id, d.absolute_path, d.file_name, d.event_date, d.title, "
                   + "d.source_excerpt, f.doc_type "
                   + "FROM document_dates d LEFT JOIN file_index f ON f.absolute_path = d.absolute_path "
                   + "ORDER BY d.event_date ASC";
        List<DateRow> out = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new DateRow(
                        rs.getLong("id"), rs.getString("absolute_path"), rs.getString("file_name"),
                        rs.getString("event_date"), rs.getString("title"),
                        rs.getString("source_excerpt"), rs.getString("doc_type")));
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to list timeline", e);
        }
        return out;
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
        clearDatesForFile(absolutePath); // drop its timeline dates too
        unassignFile(absolutePath); // the file is gone — drop its client assignment
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
    // Fee-extract cache (LLM-extracted receivable tuples, keyed by content hash)
    // -------------------------------------------------------------------------

    /** The cached extracted-rows JSON for {@code absolutePath} IF it was stored
     *  at exactly {@code contentHash} (i.e. the file hasn't changed since);
     *  empty otherwise, so a stale cache is never used. */
    public synchronized Optional<String> getFeeExtract(String absolutePath, String contentHash) {
        String sql = "SELECT rows_json FROM fee_extract WHERE absolute_path = ? AND content_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.setString(2, contentHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("rows_json")) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("Failed to read fee_extract for '{}': {}", absolutePath, e.getMessage());
            return Optional.empty();
        }
    }

    /** Inserts or replaces the cached fee-receivable rows for a file. */
    public synchronized void putFeeExtract(String absolutePath, String contentHash, String rowsJson) {
        String sql = """
            INSERT INTO fee_extract (absolute_path, content_hash, rows_json, extracted_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(absolute_path) DO UPDATE SET
              content_hash = excluded.content_hash,
              rows_json    = excluded.rows_json,
              extracted_at = excluded.extracted_at
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.setString(2, contentHash);
            ps.setString(3, rowsJson);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to put fee_extract for '{}': {}", absolutePath, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Generic aggregation: category registry + per-(doc,category) facts cache
    // -------------------------------------------------------------------------

    /** A learned aggregation category (what to extract + how to cheaply filter). */
    public record AggCategory(String name, String label, String fieldSpec, String filterTerms) {}

    /** All learned categories (the small list the match-or-new step compares against). */
    public synchronized List<AggCategory> listCategories() {
        List<AggCategory> out = new ArrayList<>();
        String sql = "SELECT name, label, field_spec, filter_terms FROM agg_category ORDER BY created_at";
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                out.add(new AggCategory(rs.getString("name"), rs.getString("label"),
                        rs.getString("field_spec"), rs.getString("filter_terms")));
        } catch (SQLException e) {
            log.warn("Failed to list categories: {}", e.getMessage());
        }
        return out;
    }

    /** Registers (or updates) a category. Idempotent on {@code name}. */
    public synchronized void putCategory(String name, String label, String fieldSpec, String filterTerms) {
        String sql = """
            INSERT INTO agg_category (name, label, field_spec, filter_terms, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET
              label = excluded.label, field_spec = excluded.field_spec, filter_terms = excluded.filter_terms
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name); ps.setString(2, label); ps.setString(3, fieldSpec);
            ps.setString(4, filterTerms == null ? "" : filterTerms); ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to put category '{}': {}", name, e.getMessage());
        }
    }

    /** Cached facts for (doc, category) IF stored at exactly {@code contentHash};
     *  empty when absent or stale (so a changed doc is re-extracted). */
    public synchronized Optional<String> getDocFacts(String absolutePath, String category, String contentHash) {
        String sql = "SELECT facts_json FROM doc_facts WHERE absolute_path = ? AND category = ? AND content_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath); ps.setString(2, category); ps.setString(3, contentHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("facts_json")) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("Failed to read doc_facts for '{}'/{}: {}", absolutePath, category, e.getMessage());
            return Optional.empty();
        }
    }

    /** Inserts/replaces the extracted facts for one (doc, category). */
    public synchronized void putDocFacts(String absolutePath, String category, String contentHash, String factsJson) {
        String sql = """
            INSERT INTO doc_facts (absolute_path, category, content_hash, facts_json, extracted_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(absolute_path, category) DO UPDATE SET
              content_hash = excluded.content_hash, facts_json = excluded.facts_json, extracted_at = excluded.extracted_at
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath); ps.setString(2, category); ps.setString(3, contentHash);
            ps.setString(4, factsJson); ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to put doc_facts for '{}'/{}: {}", absolutePath, category, e.getMessage());
        }
    }

    /** The cached universal fact sheet for one doc, only if it matches this hash
     *  (content + sheet version). Empty when absent or stale — caller re-extracts. */
    public synchronized Optional<String> getSheet(String absolutePath, String contentHash) {
        String sql = "SELECT sheet_json FROM doc_sheet WHERE absolute_path = ? AND content_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath); ps.setString(2, contentHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("sheet_json")) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("Failed to read doc_sheet for '{}': {}", absolutePath, e.getMessage());
            return Optional.empty();
        }
    }

    /** Inserts/replaces the universal fact sheet for one doc. */
    public synchronized void putSheet(String absolutePath, String contentHash, String sheetJson) {
        String sql = """
            INSERT INTO doc_sheet (absolute_path, content_hash, sheet_json, extracted_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(absolute_path) DO UPDATE SET
              content_hash = excluded.content_hash, sheet_json = excluded.sheet_json, extracted_at = excluded.extracted_at
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath); ps.setString(2, contentHash);
            ps.setString(3, sheetJson); ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to put doc_sheet for '{}': {}", absolutePath, e.getMessage());
        }
    }

    /** Enriches an existing sheet's JSON in place (same doc, same content hash) —
     *  used to persist lazily-added fields like amount roles without re-extracting. */
    public synchronized void updateSheetJson(String absolutePath, String sheetJson) {
        String sql = "UPDATE doc_sheet SET sheet_json = ? WHERE absolute_path = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sheetJson); ps.setString(2, absolutePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to update doc_sheet for '{}': {}", absolutePath, e.getMessage());
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

    /** Removes a file's extracted deadlines, its scan marker, and its series
     *  classification (invalidation — all produced by the same scan pass). */
    public synchronized void clearDeadlinesForFile(String absolutePath) {
        try (PreparedStatement a = connection.prepareStatement(
                "DELETE FROM document_deadlines WHERE absolute_path = ?");
             PreparedStatement b = connection.prepareStatement(
                "DELETE FROM deadline_scan WHERE absolute_path = ?");
             PreparedStatement c = connection.prepareStatement(
                "DELETE FROM document_series WHERE absolute_path = ?");
             PreparedStatement e = connection.prepareStatement(
                "DELETE FROM document_entity WHERE absolute_path = ?")) {
            a.setString(1, absolutePath); a.executeUpdate();
            b.setString(1, absolutePath); b.executeUpdate();
            c.setString(1, absolutePath); c.executeUpdate();
            e.setString(1, absolutePath); e.executeUpdate();
        } catch (SQLException e) {
            // Best-effort invalidation — log and continue.
            log.warn("Failed to clear deadlines for '{}': {}", absolutePath, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Document series (for the Missing-Document detector)
    // -------------------------------------------------------------------------

    /** A stored recurring-document classification. {@code issuer} may be null. */
    public record SeriesRow(String absolutePath, String fileName, String contentHash,
                            String series, String issuer, String period) {}

    /**
     * Upserts one file's series classification. Called from the deadline scan
     * flush for documents the model judged periodic; non-recurring documents get
     * no row. A no-op delete-then-skip when {@code series} or {@code period} is
     * blank keeps the table clean if a re-scan reclassifies a doc as non-periodic.
     */
    public synchronized void upsertSeries(String absolutePath, String fileName, String contentHash,
                                          String series, String issuer, String period) {
        if (series == null || series.isBlank() || period == null || period.isBlank()) {
            // Reclassified as non-recurring (or unknown) — ensure no stale row remains.
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM document_series WHERE absolute_path = ?")) {
                del.setString(1, absolutePath); del.executeUpdate();
            } catch (SQLException e) {
                log.warn("Failed to clear series for '{}': {}", absolutePath, e.getMessage());
            }
            return;
        }
        String sql = """
            INSERT INTO document_series
              (absolute_path, file_name, content_hash, series, issuer, period, detected_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(absolute_path) DO UPDATE SET
              file_name = excluded.file_name, content_hash = excluded.content_hash,
              series = excluded.series, issuer = excluded.issuer,
              period = excluded.period, detected_at = excluded.detected_at
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.setString(2, fileName);
            ps.setString(3, contentHash);
            ps.setString(4, series.trim());
            ps.setString(5, issuer == null ? null : issuer.trim());
            ps.setString(6, period.trim());
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to store series for: " + absolutePath, e);
        }
    }

    /** All recurring-document classifications across the library (for gap detection). */
    public synchronized List<SeriesRow> listAllSeries() {
        List<SeriesRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT absolute_path, file_name, content_hash, series, issuer, period FROM document_series");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new SeriesRow(
                        rs.getString("absolute_path"), rs.getString("file_name"),
                        rs.getString("content_hash"), rs.getString("series"),
                        rs.getString("issuer"), rs.getString("period")));
            }
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to list document series", e);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Per-document owner identity (for client suggestions)
    // -------------------------------------------------------------------------

    /** A document's captured owner identity. Any field may be null. */
    public record EntityRow(String absolutePath, String entityName, String gstin, String pan) {}

    /** Stores (or clears, when all three are blank) a file's owner identity. */
    public synchronized void upsertEntity(String absolutePath, String contentHash,
                                          String entityName, String gstin, String pan) {
        boolean empty = (entityName == null || entityName.isBlank())
                && (gstin == null || gstin.isBlank()) && (pan == null || pan.isBlank());
        if (empty) {
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM document_entity WHERE absolute_path = ?")) {
                del.setString(1, absolutePath); del.executeUpdate();
            } catch (SQLException e) { log.warn("Failed to clear entity for '{}': {}", absolutePath, e.getMessage()); }
            return;
        }
        String sql = """
            INSERT INTO document_entity (absolute_path, content_hash, entity_name, gstin, pan, detected_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(absolute_path) DO UPDATE SET
              content_hash = excluded.content_hash, entity_name = excluded.entity_name,
              gstin = excluded.gstin, pan = excluded.pan, detected_at = excluded.detected_at
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, absolutePath);
            ps.setString(2, contentHash);
            ps.setString(3, entityName);
            ps.setString(4, gstin);
            ps.setString(5, pan);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new MetadataStoreException("Failed to store entity for: " + absolutePath, e); }
    }

    public synchronized List<EntityRow> listAllEntities() {
        List<EntityRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT absolute_path, entity_name, gstin, pan FROM document_entity");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new EntityRow(
                    rs.getString("absolute_path"), rs.getString("entity_name"),
                    rs.getString("gstin"), rs.getString("pan")));
        } catch (SQLException e) { throw new MetadataStoreException("Failed to list entities", e); }
        return out;
    }

    public synchronized void dismissSuggestion(String entityKey) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO dismissed_suggestions (entity_key, dismissed_at) VALUES (?, ?)")) {
            ps.setString(1, entityKey); ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new MetadataStoreException("Failed to dismiss suggestion", e); }
    }

    public synchronized java.util.Set<String> dismissedSuggestionKeys() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT entity_key FROM dismissed_suggestions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) { throw new MetadataStoreException("Failed to list dismissed suggestions", e); }
        return out;
    }

    /** Permanently clears one "Needs attention" item (date/missing kinds) by its stable key. */
    public synchronized void dismissAttentionItem(String itemKey) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO attention_dismissed (item_key, dismissed_at) VALUES (?, ?)")) {
            ps.setString(1, itemKey); ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new MetadataStoreException("Failed to dismiss attention item", e); }
    }

    public synchronized java.util.Set<String> dismissedAttentionKeys() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT item_key FROM attention_dismissed");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) { throw new MetadataStoreException("Failed to list dismissed attention items", e); }
        return out;
    }

    // -------------------------------------------------------------------------
    // Per-client workspaces
    // -------------------------------------------------------------------------

    /** A client and its match tokens (normalized + display forms). */
    public record Client(String id, String name, List<String> identifiers, List<String> norms) {}

    /** Normalizes an identifier/match token: lowercase, trimmed, single-spaced.
     *  Centralized so document-tagging and question-matching agree exactly. */
    public static String normToken(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    // Legal-form words stripped to derive a short alias, so a client registered
    // as "Sharma Bakery Private Limited" also matches the user typing
    // "Sharma Bakery". Without this, matching required the full legal name.
    private static final java.util.Set<String> LEGAL_SUFFIXES = java.util.Set.of(
            "private", "pvt", "limited", "ltd", "llp", "llc", "inc", "incorporated",
            "corporation", "corp", "company", "co", "plc", "gmbh", "sons");

    /** A short alias for a company name with legal-form suffixes stripped
     *  (e.g. "Acme Corporation" → "Acme"), or null when nothing was stripped. */
    static String shortName(String name) {
        if (name == null) return null;
        String[] toks = name.trim().split("\\s+");
        int end = toks.length;
        while (end > 1) {
            String t = toks[end - 1].toLowerCase().replaceAll("[^a-z]", "");
            if (!LEGAL_SUFFIXES.contains(t)) break;
            end--;
        }
        if (end == toks.length || end == 0) return null; // nothing stripped
        return String.join(" ", java.util.Arrays.copyOfRange(toks, 0, end));
    }

    public synchronized String createClient(String name) {
        String id  = java.util.UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String safe = (name == null || name.isBlank()) ? "Client" : name.trim();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO clients (id, name, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, id); ps.setString(2, safe); ps.setString(3, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataStoreException("Failed to create client", e);
        }
        // A client is identified by its own name out of the box, plus a
        // legal-suffix-stripped short alias so "Sharma Bakery Private Limited"
        // also matches "Sharma Bakery".
        addClientIdentifier(id, safe);
        String alias = shortName(safe);
        if (alias != null && !normToken(alias).equals(normToken(safe))) addClientIdentifier(id, alias);
        return id;
    }

    public synchronized void renameClient(String id, String name) {
        if (name == null || name.isBlank()) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE clients SET name = ? WHERE id = ?")) {
            ps.setString(1, name.trim()); ps.setString(2, id); ps.executeUpdate();
        } catch (SQLException e) { throw new MetadataStoreException("Failed to rename client", e); }
    }

    /** Deletes a client and all its identifiers + file assignments. */
    public synchronized void deleteClient(String id) {
        try {
            for (String sql : new String[]{
                    "DELETE FROM file_client WHERE client_id = ?",
                    "DELETE FROM client_identifiers WHERE client_id = ?",
                    "DELETE FROM clients WHERE id = ?"}) {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, id); ps.executeUpdate();
                }
            }
        } catch (SQLException e) { throw new MetadataStoreException("Failed to delete client", e); }
    }

    /** Adds a match token to a client. Blank or too-short (<3 chars normalized)
     *  tokens are rejected — they'd cause false matches. Idempotent. */
    public synchronized boolean addClientIdentifier(String clientId, String value) {
        if (value == null) return false;
        String norm = normToken(value);
        if (norm.length() < 3) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO client_identifiers (client_id, value, norm) VALUES (?, ?, ?)")) {
            ps.setString(1, clientId); ps.setString(2, value.trim()); ps.setString(3, norm);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) { throw new MetadataStoreException("Failed to add identifier", e); }
    }

    public synchronized void removeClientIdentifier(String clientId, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM client_identifiers WHERE client_id = ? AND norm = ?")) {
            ps.setString(1, clientId); ps.setString(2, normToken(value)); ps.executeUpdate();
        } catch (SQLException e) { throw new MetadataStoreException("Failed to remove identifier", e); }
    }

    public synchronized List<Client> listClients() {
        Map<String, Client> byId = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, name FROM clients ORDER BY name COLLATE NOCASE");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byId.put(rs.getString("id"),
                        new Client(rs.getString("id"), rs.getString("name"),
                                new ArrayList<>(), new ArrayList<>()));
            }
        } catch (SQLException e) { throw new MetadataStoreException("Failed to list clients", e); }
        if (byId.isEmpty()) return List.of();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT client_id, value, norm FROM client_identifiers");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Client c = byId.get(rs.getString("client_id"));
                if (c != null) { c.identifiers().add(rs.getString("value")); c.norms().add(rs.getString("norm")); }
            }
        } catch (SQLException e) { throw new MetadataStoreException("Failed to list identifiers", e); }
        return new ArrayList<>(byId.values());
    }

    /** For each client id, how many of their documents are still indexed. A client
     *  whose files were all deleted returns 0 ("no docs left") even if a stale
     *  mapping lingers — the roster uses this to stop showing gone clients. */
    public synchronized Map<String, Integer> clientLiveDocCounts() {
        Map<String, Integer> out = new java.util.HashMap<>();
        String sql = "SELECT fc.client_id, COUNT(*) c FROM file_client fc "
                   + "JOIN file_index fi ON fc.absolute_path = fi.absolute_path "
                   + "WHERE fi.status = 'INDEXED' GROUP BY fc.client_id";
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getString("client_id"), rs.getInt("c"));
        } catch (SQLException e) {
            log.warn("clientLiveDocCounts failed: {}", e.getMessage());
        }
        return out;
    }

    public synchronized int countClients() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM clients");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { throw new MetadataStoreException("Failed to count clients", e); }
    }

    /** Whether a client id exists (guards stale focus / bad input). */
    public synchronized boolean clientExists(String clientId) {
        if (clientId == null) return false;
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM clients WHERE id = ?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new MetadataStoreException("Failed to check client", e); }
    }

    /** Assigns a file to a client. {@code pinned} marks a manual (sticky) assignment. */
    public synchronized void assignFileToClient(String absolutePath, String clientId, boolean pinned) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO file_client (absolute_path, client_id, pinned, assigned_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(absolute_path) DO UPDATE SET
                  client_id = excluded.client_id, pinned = excluded.pinned,
                  assigned_at = excluded.assigned_at
                """)) {
            ps.setString(1, absolutePath); ps.setString(2, clientId);
            ps.setInt(3, pinned ? 1 : 0); ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new MetadataStoreException("Failed to assign file", e); }
    }

    /** Removes a file's client assignment. */
    public synchronized void unassignFile(String absolutePath) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM file_client WHERE absolute_path = ?")) {
            ps.setString(1, absolutePath); ps.executeUpdate();
        } catch (SQLException e) { log.warn("Failed to unassign '{}': {}", absolutePath, e.getMessage()); }
    }

    public synchronized boolean isFilePinned(String absolutePath) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT pinned FROM file_client WHERE absolute_path = ?")) {
            ps.setString(1, absolutePath);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt("pinned") == 1; }
        } catch (SQLException e) { throw new MetadataStoreException("Failed to check pin", e); }
    }

    /** All file paths belonging to a client. */
    public synchronized List<String> pathsForClient(String clientId) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT absolute_path FROM file_client WHERE client_id = ?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getString(1)); }
        } catch (SQLException e) { throw new MetadataStoreException("Failed to list client paths", e); }
        return out;
    }

    /** Every assigned path (any client) — used to derive the "unassigned" set. */
    public synchronized java.util.Set<String> allAssignedPaths() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT absolute_path FROM file_client");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) { throw new MetadataStoreException("Failed to list assigned paths", e); }
        return out;
    }

    /** All indexed file paths (INDEXED status). */
    public synchronized List<String> listIndexedPaths() {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT absolute_path FROM file_index WHERE status = 'INDEXED'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) { throw new MetadataStoreException("Failed to list indexed paths", e); }
        return out;
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
    /**
     * Purges past one-time deadlines with status-aware retention:
     * handled items (DONE/DISMISSED) go as soon as their date is past — pure
     * clutter — but a PENDING past deadline is a MISSED obligation (the most
     * attention-worthy thing the app knows) and survives until
     * {@code pendingCutoffIso}. Beyond that it's pre-app archive history
     * (a first scan of old documents extracts hundreds of long-past dates)
     * or something the user has ignored for months — either way, clutter.
     */
    public synchronized int deleteOneTimePastDeadlines(String todayIso, String pendingCutoffIso) {
        String sql = "DELETE FROM document_deadlines "
                   + "WHERE due_date IS NOT NULL AND due_date < ? "
                   + "AND (recurring IS NULL OR recurring = 'NONE') "
                   + "AND (status IN ('DONE','DISMISSED') OR due_date < ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, todayIso);
            ps.setString(2, pendingCutoffIso);
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

        String docType = null;
        try {
            docType = rs.getString("doc_type");
        } catch (SQLException ignored) {
            // Older row without the column — leave null (unclassified).
        }

        String primaryDate = null;
        try {
            primaryDate = rs.getString("primary_date");
        } catch (SQLException ignored) {
            // Older row without the column — leave null (unknown).
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
            .docType(docType)
            .primaryDate(primaryDate)
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

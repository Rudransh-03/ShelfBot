package com.localfilebrain.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Local, on-device persistence for chat threads — the ChatGPT-style
 * "conversations in a sidebar" feature. Deliberately a SEPARATE SQLite
 * file from the index metadata DB so heavy indexing writes never contend
 * with chat reads/writes, and so nothing about chat content ever leaves
 * the device (matches Rudo's local-first/privacy pitch — chat is never
 * sent to or stored on the licensing proxy).
 *
 * Two tables:
 *   conversations(id, title, created_at, updated_at)
 *   messages(id, conversation_id, role, content, created_at)
 */
public final class ChatStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatStore.class);

    private final Connection connection;

    public ChatStore(Path dbPath) {
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA synchronous=NORMAL");
                pragma.execute("PRAGMA foreign_keys=ON");
            }
            initSchema();
            log.info("Chat store opened (WAL): {}", dbPath.toAbsolutePath());
        } catch (SQLException e) {
            throw new ChatStoreException("Failed to open chat DB at: " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id         TEXT PRIMARY KEY,
                    title      TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS messages (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    role            TEXT NOT NULL,
                    content         TEXT NOT NULL,
                    sources         TEXT,
                    created_at      TEXT NOT NULL,
                    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
                )
                """);
            stmt.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_messages_conversation
                    ON messages(conversation_id, id)
                """);
            // Migration for DBs created before source persistence — adding the
            // column is idempotent; SQLite throws "duplicate column" if it
            // already exists, which we swallow.
            try {
                stmt.executeUpdate("ALTER TABLE messages ADD COLUMN sources TEXT");
            } catch (SQLException ignored) {
                // Column already exists.
            }
            log.debug("Chat schema initialized");
        }
    }

    // -------------------------------------------------------------------------
    // Conversations
    // -------------------------------------------------------------------------

    /** Creates a new conversation with the given title; returns its generated id. */
    public synchronized String createConversation(String title) {
        String id  = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String safeTitle = (title == null || title.isBlank()) ? "New chat" : title.trim();
        String sql = "INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, safeTitle);
            ps.setString(3, now);
            ps.setString(4, now);
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            throw new ChatStoreException("Failed to create conversation", e);
        }
    }

    /** All conversations, most-recently-updated first. */
    public synchronized List<Conversation> listConversations() {
        String sql = "SELECT id, title, created_at, updated_at FROM conversations ORDER BY updated_at DESC";
        List<Conversation> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Conversation(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")));
            }
            return out;
        } catch (SQLException e) {
            throw new ChatStoreException("Failed to list conversations", e);
        }
    }

    /**
     * Conversations whose title OR any message body contains {@code query}
     * (case-insensitive substring), most-recently-updated first. Blank query
     * falls back to the full list.
     */
    public synchronized List<Conversation> searchConversations(String query) {
        if (query == null || query.isBlank()) return listConversations();
        String like = "%" + query.trim() + "%";
        String sql = """
            SELECT c.id, c.title, c.created_at, c.updated_at
            FROM conversations c
            WHERE c.title LIKE ?
               OR EXISTS (
                   SELECT 1 FROM messages m
                   WHERE m.conversation_id = c.id AND m.content LIKE ?
               )
            ORDER BY c.updated_at DESC
            """;
        List<Conversation> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, like);
            ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Conversation(
                            rs.getString("id"),
                            rs.getString("title"),
                            rs.getString("created_at"),
                            rs.getString("updated_at")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new ChatStoreException("Failed to search conversations", e);
        }
    }

    /**
     * Search results enriched with a message snippet around the match — the
     * shape the command-palette search modal renders (title + preview + date).
     */
    public synchronized List<SearchHit> searchWithSnippets(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        List<SearchHit> hits = new ArrayList<>();
        for (Conversation c : searchConversations(query)) {
            if (hits.size() >= limit) break;
            hits.add(new SearchHit(c.id(), c.title(), c.updatedAt(), bestSnippet(c.id(), query)));
        }
        return hits;
    }

    /** Snippet from the first message that matches; else the opening message. */
    private String bestSnippet(String conversationId, String query) {
        String like = "%" + query.trim() + "%";
        String matchSql = "SELECT content FROM messages WHERE conversation_id = ? AND content LIKE ? ORDER BY id ASC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(matchSql)) {
            ps.setString(1, conversationId);
            ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return buildSnippet(rs.getString("content"), query);
            }
        } catch (SQLException ignored) { /* fall back to opening message */ }

        String firstSql = "SELECT content FROM messages WHERE conversation_id = ? ORDER BY id ASC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(firstSql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return buildSnippet(rs.getString("content"), null);
            }
        } catch (SQLException ignored) { /* no messages */ }
        return "";
    }

    /** A ~one-line window around the first match (or just the head if no match). */
    private static String buildSnippet(String content, String query) {
        if (content == null) return "";
        String flat = content.replaceAll("\\s+", " ").trim();
        if (query == null || query.isBlank()) {
            return flat.length() > 120 ? flat.substring(0, 120).trim() + "…" : flat;
        }
        int idx = flat.toLowerCase().indexOf(query.trim().toLowerCase());
        if (idx < 0) {
            return flat.length() > 120 ? flat.substring(0, 120).trim() + "…" : flat;
        }
        int start = Math.max(0, idx - 40);
        int end   = Math.min(flat.length(), idx + query.trim().length() + 70);
        String s = flat.substring(start, end).trim();
        if (start > 0)            s = "…" + s;
        if (end < flat.length())  s = s + "…";
        return s;
    }

    public synchronized boolean exists(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return false;
        String sql = "SELECT 1 FROM conversations WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new ChatStoreException("Failed to check conversation", e);
        }
    }

    public synchronized void renameConversation(String conversationId, String title) {
        String safeTitle = (title == null || title.isBlank()) ? "Untitled" : title.trim();
        String sql = "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, safeTitle);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ChatStoreException("Failed to rename conversation", e);
        }
    }

    /** Deletes a conversation and (via ON DELETE CASCADE) its messages. */
    public synchronized void deleteConversation(String conversationId) {
        String sql = "DELETE FROM conversations WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ChatStoreException("Failed to delete conversation", e);
        }
    }

    // -------------------------------------------------------------------------
    // Messages
    // -------------------------------------------------------------------------

    /** Appends a message (no sources) and bumps the parent's updated_at. */
    public synchronized void addMessage(String conversationId, String role, String content) {
        addMessage(conversationId, role, content, null);
    }

    /**
     * Appends a message, optionally carrying serialized source chips (a JSON
     * string the UI re-hydrates on reload), and bumps the parent's updated_at.
     */
    public synchronized void addMessage(String conversationId, String role, String content, String sourcesJson) {
        String now = Instant.now().toString();
        String insert = "INSERT INTO messages (conversation_id, role, content, sources, created_at) VALUES (?, ?, ?, ?, ?)";
        String touch  = "UPDATE conversations SET updated_at = ? WHERE id = ?";
        try {
            try (PreparedStatement ps = connection.prepareStatement(insert)) {
                ps.setString(1, conversationId);
                ps.setString(2, role);
                ps.setString(3, content == null ? "" : content);
                ps.setString(4, sourcesJson);
                ps.setString(5, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(touch)) {
                ps.setString(1, now);
                ps.setString(2, conversationId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new ChatStoreException("Failed to add message", e);
        }
    }

    /** All messages for a conversation, oldest first. */
    public synchronized List<Message> getMessages(String conversationId) {
        String sql = "SELECT role, content, sources, created_at FROM messages WHERE conversation_id = ? ORDER BY id ASC";
        List<Message> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Message(
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("sources"),
                            rs.getString("created_at")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new ChatStoreException("Failed to read messages", e);
        }
    }

    /**
     * Returns the last {@code maxExchanges} user→assistant pairs for a
     * conversation, oldest first — the slice used to rehydrate the LLM's
     * conversation context. Stray/unpaired messages are skipped so a
     * mid-thread failure can't corrupt the history.
     */
    public synchronized List<Exchange> getRecentExchanges(String conversationId, int maxExchanges) {
        List<Message> msgs = getMessages(conversationId);
        List<Exchange> exchanges = new ArrayList<>();
        String pendingQuestion = null;
        for (Message m : msgs) {
            if ("user".equals(m.role())) {
                pendingQuestion = m.content();
            } else if ("assistant".equals(m.role()) && pendingQuestion != null) {
                exchanges.add(new Exchange(pendingQuestion, m.content()));
                pendingQuestion = null;
            }
        }
        if (exchanges.size() > maxExchanges) {
            return new ArrayList<>(exchanges.subList(exchanges.size() - maxExchanges, exchanges.size()));
        }
        return exchanges;
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            log.warn("Error closing chat store: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    public record Conversation(String id, String title, String createdAt, String updatedAt) {}
    public record Message(String role, String content, String sources, String createdAt) {}
    public record Exchange(String question, String answer) {}
    public record SearchHit(String id, String title, String updatedAt, String snippet) {}

    public static final class ChatStoreException extends RuntimeException {
        public ChatStoreException(String message, Throwable cause) { super(message, cause); }
    }
}

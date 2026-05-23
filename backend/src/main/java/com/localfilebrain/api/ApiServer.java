package com.localfilebrain.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IngestionPipeline;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.model.IngestionResult;
import com.localfilebrain.query.QueryEngine;
import com.localfilebrain.storage.VectorStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Embedded HTTP REST API server powering the ShelfBot desktop UI.
 *
 * Routes:
 *   GET  /api/health            – liveness probe
 *   GET  /api/status            – indexed file stats
 *   POST /api/index             – start async indexing job
 *   GET  /api/index             – poll current indexing job status
 *   POST /api/query             – semantic search + LLM answer
 *   DELETE /api/conversation    – clear conversation history
 *   GET  /api/config            – read config values
 *   POST /api/config            – update files root path(s)
 *                                  Accepts either {"rootPath": "..."} or {"rootPaths": ["...", "..."]}
 *   GET  /api/files             – list indexed files (sorted by size desc)
 *   DELETE /api/files           – remove an indexed file (frees its token budget)
 *                                  Body: {"path": "/absolute/path/to/file"}
 */
public final class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    private final HttpServer         server;
    private final ObjectMapper       mapper  = new ObjectMapper();
    private volatile AppConfig       config;
    private final IndexMetadataStore metadataStore;
    private final VectorStore        vectorStore;
    private final EmbeddingClient    embeddingClient;
    private final AuthTokenStore     tokenStore;

    // QueryEngine is created lazily so the server still starts even when the
    // OpenAI key is not yet configured.
    private volatile QueryEngine queryEngine;

    // ── Indexing job state ────────────────────────────────────────────────────
    private final AtomicBoolean                    indexingRunning = new AtomicBoolean(false);
    private final AtomicReference<IndexingStatus>  indexingStatus  = new AtomicReference<>(null);
    private final AtomicReference<IndexingProgress> currentProgress = new AtomicReference<>(null);
    private final ExecutorService                bgExecutor      = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shelfbot-indexing");
        t.setDaemon(true);
        return t;
    });

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public ApiServer(int port,
                     AppConfig config,
                     IndexMetadataStore metadataStore,
                     VectorStore vectorStore,
                     EmbeddingClient embeddingClient,
                     AuthTokenStore tokenStore,
                     QueryEngine queryEngine) throws IOException {
        this.config          = config;
        this.metadataStore   = metadataStore;
        this.vectorStore     = vectorStore;
        this.embeddingClient = embeddingClient;
        this.tokenStore      = tokenStore;
        this.queryEngine     = queryEngine;   // may be null if config incomplete

        this.server = HttpServer.create(new InetSocketAddress("localhost", port), 64);
        this.server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "shelfbot-http");
            t.setDaemon(true);
            return t;
        }));

        registerRoutes();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    public void start() {
        server.start();
        log.info("ShelfBot API server listening on port {}", server.getAddress().getPort());
    }

    public void stop() {
        server.stop(2);
        bgExecutor.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route registration
    // ─────────────────────────────────────────────────────────────────────────

    private void registerRoutes() {
        server.createContext("/api/health",       this::handleHealth);
        server.createContext("/api/status",       this::handleStatus);
        server.createContext("/api/index",        this::handleIndex);
        server.createContext("/api/query",        this::handleQuery);
        server.createContext("/api/conversation", this::handleConversation);
        server.createContext("/api/config",       this::handleConfig);
        server.createContext("/api/files",        this::handleFiles);
        server.createContext("/api/auth",         this::handleAuth);
    }

    /**
     * Auth-token bridge between the Electron UI and the Java backend.
     *   GET    /api/auth          → { authenticated, email? }
     *   POST   /api/auth          → { token, email } stores the JWT
     *   DELETE /api/auth          → clears the token (logout)
     */
    private void handleAuth(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        if (isMethod(ex, "GET")) {
            sendJson(ex, 200, map(
                "authenticated", tokenStore.isAuthenticated(),
                "email",         tokenStore.getUserEmail()
            ));
            return;
        }

        if (isMethod(ex, "POST")) {
            try {
                Map<?, ?> body  = readJson(ex);
                String    token = (String) body.get("token");
                String    email = (String) body.get("email");
                if (token == null || token.isBlank()) {
                    sendError(ex, 400, "token is required");
                    return;
                }
                tokenStore.setToken(token, email);
                // Reset the lazy QueryEngine so the next query picks up the
                // new token instead of capturing a stale "Not signed in".
                this.queryEngine = null;
                sendJson(ex, 200, map("ok", true, "email", email));
            } catch (Exception e) {
                sendError(ex, 500, e.getMessage());
            }
            return;
        }

        if (isMethod(ex, "DELETE")) {
            tokenStore.clear();
            this.queryEngine = null;
            sendJson(ex, 200, map("ok", true));
            return;
        }

        methodNotAllowed(ex);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Handlers
    // ─────────────────────────────────────────────────────────────────────────

    /** GET /api/health */
    private void handleHealth(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "GET")) { methodNotAllowed(ex); return; }
        sendJson(ex, 200, map("status", "ok", "app", "ShelfBot", "version", "1.0.0"));
    }

    /** GET /api/status */
    private void handleStatus(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "GET")) { methodNotAllowed(ex); return; }
        try {
            int          indexed      = metadataStore.countIndexed();
            int          failed       = metadataStore.countFailed();
            int          totalChunks  = metadataStore.getTotalChunks();
            long         tokensUsed   = metadataStore.sumIndexedTokens();
            String       lastIndexed  = metadataStore.getLastIndexedAt().orElse("");
            List<String> rootPaths    = safeRootPaths();
            String       rootPath     = rootPaths.isEmpty() ? "" : rootPaths.get(0);
            List<String> accessIssues = findUnreadableRoots(rootPaths);

            String embeddingModel = embeddingClient != null ? embeddingClient.modelId() : "unknown";

            sendJson(ex, 200, map(
                "indexedFiles",       indexed,
                "failedFiles",        failed,
                "totalChunks",        totalChunks,
                "lastIndexed",        lastIndexed,
                "rootPath",           rootPath,
                "rootPaths",          rootPaths,
                "tokensUsed",         tokensUsed,
                "tokensLimit",        IngestionPipeline.MAX_TOKENS_TOTAL,
                "tokensLimitPerFile", IngestionPipeline.MAX_TOKENS_PER_FILE,
                "accessIssues",       accessIssues,
                "platform",           System.getProperty("os.name", "").toLowerCase(),
                "embeddingModel",     embeddingModel,
                "apiMode",            config.getApiMode(),
                "authenticated",      tokenStore.isAuthenticated(),
                "userEmail",          tokenStore.getUserEmail()
            ));
        } catch (Exception e) {
            log.error("status error", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    /** POST /api/index  – start job   GET /api/index – poll status */
    private void handleIndex(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        if (isMethod(ex, "GET")) {
            IndexingStatus s = indexingStatus.get();
            Map<String, Object> body;
            if (s == null) {
                body = map("running", false, "hasRun", false);
            } else {
                body = s.toMap();
            }
            // While a job is running, attach the latest per-file progress
            // snapshot so the UI can render a real bar without a separate poll.
            IndexingProgress prog = currentProgress.get();
            if (prog != null && (s == null || s.running)) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("processed",   prog.processed);
                p.put("total",       prog.total);
                p.put("failed",      prog.failed);
                p.put("currentFile", prog.currentFile);
                body.put("progress", p);
            }
            sendJson(ex, 200, body);
            return;
        }

        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        if (!indexingRunning.compareAndSet(false, true)) {
            sendJson(ex, 409, map("error", "Indexing already in progress"));
            return;
        }

        indexingStatus.set(new IndexingStatus(true, null, null));
        currentProgress.set(null);

        bgExecutor.submit(() -> {
            try {
                AppConfig fresh = AppConfig.load();
                IngestionPipeline pipeline = new IngestionPipeline(fresh, metadataStore, vectorStore, embeddingClient);
                // Note: embeddingClient already wraps tokenStore via Main, so
                // proxy-mode embedding calls see the active JWT automatically.
                IngestionResult result = pipeline.run((processed, total, failed, currentFile) ->
                        currentProgress.set(new IndexingProgress(processed, total, failed, currentFile))
                );
                indexingStatus.set(new IndexingStatus(false, result, null));
            } catch (Throwable t) {
                log.error("Indexing job failed", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                indexingStatus.set(new IndexingStatus(false, null, msg));
            } finally {
                currentProgress.set(null);
                indexingRunning.set(false);
            }
        });

        sendJson(ex, 202, map("started", true, "message", "Indexing started in background"));
    }

    /** POST /api/query */
    private void handleQuery(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        try {
            Map<?, ?> req      = readJson(ex);
            String    question = (String) req.get("question");

            if (question == null || question.isBlank()) {
                sendError(ex, 400, "question is required");
                return;
            }

            QueryEngine engine = getOrInitQueryEngine();
            QueryEngine.QueryResult result = engine.query(question);

            sendJson(ex, 200, map(
                "answer",  result.answer(),
                "sources", result.sourceFiles(),
                "found",   result.found()
            ));
        } catch (AppConfig.ConfigurationException e) {
            sendError(ex, 503, "OpenAI API key not configured: " + e.getMessage());
        } catch (Exception e) {
            log.error("Query failed", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    /** DELETE /api/conversation */
    private void handleConversation(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "DELETE")) { methodNotAllowed(ex); return; }

        try {
            QueryEngine engine = getOrInitQueryEngine();
            engine.clearHistory();
            sendJson(ex, 200, map("cleared", true));
        } catch (Exception e) {
            sendError(ex, 503, "QueryEngine not available: " + e.getMessage());
        }
    }

    /** GET /api/config    POST /api/config */
    private void handleConfig(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        if (isMethod(ex, "GET")) {
            try {
                List<String> rootPaths = safeRootPaths();
                String       rootPath  = rootPaths.isEmpty() ? "" : rootPaths.get(0);
                sendJson(ex, 200, map(
                    "rootPath",        rootPath,
                    "rootPaths",       rootPaths,
                    "vectorIndexPath", config.getVectorIndexPath().toAbsolutePath().toString(),
                    "metadataDbPath",  config.getMetadataDbPath().toAbsolutePath().toString()
                ));
            } catch (Exception e) {
                sendError(ex, 500, e.getMessage());
            }
            return;
        }

        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        try {
            Map<?, ?> req = readJson(ex);

            // Accept either {"rootPaths": ["...", "..."]} (preferred, multi-root)
            // or {"rootPath": "..."} (legacy, single).
            Object       rootPathsRaw = req.get("rootPaths");
            String       singlePath   = (String) req.get("rootPath");
            List<String> cleaned      = new ArrayList<>();

            if (rootPathsRaw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s && !s.isBlank()) {
                        cleaned.add(s.trim());
                    }
                }
            } else if (singlePath != null && !singlePath.isBlank()) {
                cleaned.add(singlePath.trim());
            }

            if (cleaned.isEmpty()) {
                sendError(ex, 400, "rootPath or rootPaths is required");
                return;
            }

            updateConfigRootPaths(cleaned);

            // Reload config so future status calls reflect the new paths
            this.config = AppConfig.load();
            // Reset QueryEngine so it re-initialises with the updated config on next query
            this.queryEngine = null;

            sendJson(ex, 200, map(
                "updated",   true,
                "rootPath",  cleaned.get(0),
                "rootPaths", cleaned
            ));
        } catch (Exception e) {
            log.error("Config update failed", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    /** GET /api/files – sorted by size desc.   DELETE /api/files – remove one file by path. */
    private void handleFiles(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        if (isMethod(ex, "GET")) {
            try {
                List<FileRecord> records = metadataStore.listIndexedFilesBySizeDesc();
                List<Map<String, Object>> rows = new ArrayList<>(records.size());
                for (FileRecord r : records) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("path",          r.getAbsolutePath());
                    row.put("name",          r.getFileName());
                    row.put("extension",     r.getFileExtension());
                    row.put("sizeBytes",     r.getFileSizeBytes());
                    row.put("chunkCount",    r.getChunkCount());
                    row.put("tokenCount",    r.getTokenCount());
                    row.put("lastIndexedAt", r.getLastIndexedAt() != null ? r.getLastIndexedAt().toString() : null);
                    rows.add(row);
                }
                sendJson(ex, 200, map("files", rows, "count", rows.size()));
            } catch (Exception e) {
                log.error("list files error", e);
                sendError(ex, 500, e.getMessage());
            }
            return;
        }

        if (!isMethod(ex, "DELETE")) { methodNotAllowed(ex); return; }

        try {
            Map<?, ?> req  = readJson(ex);
            String    path = (String) req.get("path");

            if (path == null || path.isBlank()) {
                sendError(ex, 400, "path is required");
                return;
            }

            // Drop the file's chunks from the vector store first; metadata
            // row last so that a failure midway leaves the metadata consistent
            // with what's actually in the index.
            try {
                vectorStore.deleteBySourceFile(path);
            } catch (Exception e) {
                log.warn("Vector store delete failed for '{}': {} (continuing to remove metadata)", path, e.getMessage());
            }

            metadataStore.delete(path);

            sendJson(ex, 200, map("deleted", true, "path", path));
        } catch (Exception e) {
            log.error("delete file error", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Lazy / re-initialised QueryEngine. Thread-safe via double-checked locking. */
    private QueryEngine getOrInitQueryEngine() {
        if (queryEngine != null) return queryEngine;
        synchronized (this) {
            if (queryEngine == null) {
                AppConfig fresh = AppConfig.load();
                queryEngine = new QueryEngine(fresh, vectorStore, embeddingClient, tokenStore);
            }
        }
        return queryEngine;
    }

    /**
     * Returns the configured roots that we cannot actually read. This is the
     * symptom of macOS Full Disk Access being denied for the bundled Java
     * process (the watcher silently sees nothing, scans return 0 files).
     * Cheap: opens a directory stream and closes it immediately.
     */
    private List<String> findUnreadableRoots(List<String> roots) {
        List<String> issues = new ArrayList<>();
        for (String pathStr : roots) {
            try {
                Path p = Paths.get(pathStr);
                if (!Files.isDirectory(p)) {
                    issues.add(pathStr);
                    continue;
                }
                try (var stream = Files.newDirectoryStream(p)) {
                    stream.iterator(); // touch it
                }
            } catch (Exception e) {
                issues.add(pathStr);
            }
        }
        return issues;
    }

    private List<String> safeRootPaths() {
        try {
            List<String> result = new ArrayList<>();
            for (Path p : config.getFilesRootPaths()) {
                result.add(p.toAbsolutePath().toString());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Persists the configured roots to {@code config.properties}.
     *
     * For a single path we write the legacy {@code files.root.path} key (so older
     * builds of the CLI can still read it) and remove any stale multi-key.
     * For multiple paths we write {@code files.root.paths} as a comma-separated
     * list and remove the legacy single key to keep the file unambiguous.
     */
    private void updateConfigRootPaths(List<String> newPaths) throws IOException {
        Path configFile = Paths.get("config.properties");
        Properties props = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                props.load(in);
            }
        }
        if (newPaths.size() == 1) {
            props.setProperty("files.root.path", newPaths.get(0));
            props.remove("files.root.paths");
        } else {
            props.setProperty("files.root.paths", String.join(",", newPaths));
            props.remove("files.root.path");
        }
        try (OutputStream out = Files.newOutputStream(configFile)) {
            props.store(out, "ShelfBot configuration — updated by UI");
        }
    }

    // ── HTTP utilities ────────────────────────────────────────────────────────

    /** Handles OPTIONS preflight and adds CORS headers to every response. */
    private boolean preflight(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private boolean isMethod(HttpExchange ex, String method) {
        return method.equalsIgnoreCase(ex.getRequestMethod());
    }

    private void methodNotAllowed(HttpExchange ex) throws IOException {
        sendError(ex, 405, "Method not allowed");
    }

    private Map<?, ?> readJson(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        return mapper.readValue(body, Map.class);
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, map("error", message != null ? message : "Unknown error"));
    }

    @SafeVarargs
    private static <K, V> Map<K, V> map(Object... pairs) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length - 1; i += 2) m.put(pairs[i], pairs[i + 1]);
        //noinspection unchecked
        return (Map<K, V>) m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Indexing job status DTO
    // ─────────────────────────────────────────────────────────────────────────

    /** Snapshot of the in-flight indexing job, streamed via {@code progress} on /api/index GET. */
    public static final class IndexingProgress {
        final int processed;
        final int total;
        final int failed;
        final String currentFile;

        IndexingProgress(int processed, int total, int failed, String currentFile) {
            this.processed   = processed;
            this.total       = total;
            this.failed      = failed;
            this.currentFile = currentFile;
        }
    }

    public static final class IndexingStatus {
        final boolean       running;
        final IngestionResult result;
        final String        error;

        IndexingStatus(boolean running, IngestionResult result, String error) {
            this.running = running;
            this.result  = result;
            this.error   = error;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("running", running);
            m.put("hasRun",  result != null || error != null);
            if (error  != null) m.put("error",  error);
            if (result != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("totalFilesScanned",  result.getTotalFilesScanned());
                r.put("filesSkipped",       result.getFilesSkipped());
                r.put("filesProcessed",     result.getFilesProcessed());
                r.put("filesFailed",        result.getFilesFailed());
                r.put("totalChunksCreated", result.getTotalChunksCreated());
                r.put("durationMs",         result.getDurationMs());
                r.put("failedFilePaths",    result.getFailedFilePaths());
                m.put("result", r);
            }
            return m;
        }
    }
}

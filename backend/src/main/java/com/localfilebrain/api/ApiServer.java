package com.localfilebrain.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IngestionPipeline;
import com.localfilebrain.model.IngestionResult;
import com.localfilebrain.query.QueryEngine;
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
 *   POST /api/config            – update files.root.path
 */
public final class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    private final HttpServer         server;
    private final ObjectMapper       mapper  = new ObjectMapper();
    private volatile AppConfig       config;
    private final IndexMetadataStore metadataStore;

    // QueryEngine is created lazily so the server still starts even when the
    // OpenAI key is not yet configured.
    private volatile QueryEngine queryEngine;

    // ── Indexing job state ────────────────────────────────────────────────────
    private final AtomicBoolean                  indexingRunning = new AtomicBoolean(false);
    private final AtomicReference<IndexingStatus> indexingStatus  = new AtomicReference<>(null);
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
                     QueryEngine queryEngine) throws IOException {
        this.config        = config;
        this.metadataStore = metadataStore;
        this.queryEngine   = queryEngine;   // may be null if config incomplete

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
            int    indexed     = metadataStore.countIndexed();
            int    failed      = metadataStore.countFailed();
            int    totalChunks = metadataStore.getTotalChunks();
            String lastIndexed = metadataStore.getLastIndexedAt().orElse("");
            String rootPath    = safeRootPath();

            sendJson(ex, 200, map(
                "indexedFiles", indexed,
                "failedFiles",  failed,
                "totalChunks",  totalChunks,
                "lastIndexed",  lastIndexed,
                "rootPath",     rootPath
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
            if (s == null) {
                sendJson(ex, 200, map("running", false, "hasRun", false));
            } else {
                sendJson(ex, 200, s.toMap());
            }
            return;
        }

        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        if (!indexingRunning.compareAndSet(false, true)) {
            sendJson(ex, 409, map("error", "Indexing already in progress"));
            return;
        }

        indexingStatus.set(new IndexingStatus(true, null, null));

        bgExecutor.submit(() -> {
            try {
                AppConfig fresh = AppConfig.load();
                IngestionPipeline pipeline = new IngestionPipeline(fresh, metadataStore);
                IngestionResult result = pipeline.run();
                indexingStatus.set(new IndexingStatus(false, result, null));
            } catch (Throwable t) {
                log.error("Indexing job failed", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                indexingStatus.set(new IndexingStatus(false, null, msg));
            } finally {
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
                sendJson(ex, 200, map(
                    "rootPath",      safeRootPath(),
                    "chromaUrl",     config.getChromaDbUrl(),
                    "collection",    config.getChromaDbCollection(),
                    "metadataDbPath", config.getMetadataDbPath().toString()
                ));
            } catch (Exception e) {
                sendError(ex, 500, e.getMessage());
            }
            return;
        }

        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        try {
            Map<?, ?> req     = readJson(ex);
            String    newPath = (String) req.get("rootPath");

            if (newPath == null || newPath.isBlank()) {
                sendError(ex, 400, "rootPath is required");
                return;
            }

            updateConfigRootPath(newPath.trim());

            // Reload config so safeRootPath() and future status calls reflect the new path
            this.config = AppConfig.load();
            // Reset QueryEngine so it re-initialises with the updated config on next query
            this.queryEngine = null;

            sendJson(ex, 200, map("updated", true, "rootPath", newPath.trim()));
        } catch (Exception e) {
            log.error("Config update failed", e);
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
                queryEngine = new QueryEngine(fresh);
            }
        }
        return queryEngine;
    }

    private String safeRootPath() {
        try {
            return config.getFilesRootPath().toAbsolutePath().toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void updateConfigRootPath(String newPath) throws IOException {
        Path configFile = Paths.get("config.properties");
        Properties props = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                props.load(in);
            }
        }
        props.setProperty("files.root.path", newPath);
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

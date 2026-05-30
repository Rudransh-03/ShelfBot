package com.localfilebrain.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IngestionPipeline;
import com.localfilebrain.ingestion.TextExtractor;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.model.IngestionResult;
import com.localfilebrain.query.QueryEngine;
import com.localfilebrain.reorg.DirectoryAnalyzer;
import com.localfilebrain.reorg.DirectoryReorgPlan;
import com.localfilebrain.reorg.FileVectorService;
import com.localfilebrain.reorg.MoveExecutor;
import com.localfilebrain.reorg.ProxyReorgLlmClient;
import com.localfilebrain.reorg.ReorgExecutionResult;
import com.localfilebrain.reorg.ReorgPlanBuilder;
import com.localfilebrain.reorg.ReorgProposal;
import com.localfilebrain.reorg.ReorgToolLoop;
import com.localfilebrain.reorg.ReorgToolLoopResult;
import com.localfilebrain.reorg.ScopeError;
import com.localfilebrain.reorg.UndoExecutor;
import com.localfilebrain.storage.VectorStore;
import com.localfilebrain.summarize.SummarizationEngine;
import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.util.PathNormalizer;
import com.localfilebrain.model.FileRecord;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
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
 *   POST /api/reorg/execute     – run a vetted list of moves; returns batchId for undo
 *                                  Body: {"targetDir": "...", "moves": [{from, to, destinationIsNew, ...}]}
 *   POST /api/reorg/undo        – reverse a batch
 *                                  Body: {"batchId": "..."}
 *   GET  /api/reorg/history     – recent batches (?limit=N)
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
    // One-shot detector — instantiating TextExtractor probes Tesseract; we
    // keep the result to report in /api/status without re-checking per call.
    private final boolean            ocrAvailable = new TextExtractor().isOcrAvailable();

    // QueryEngine is created lazily so the server still starts even when the
    // OpenAI key is not yet configured.
    private volatile QueryEngine queryEngine;

    // ── Indexing job state ────────────────────────────────────────────────────
    private final AtomicBoolean                    indexingRunning = new AtomicBoolean(false);
    private final AtomicReference<IndexingStatus>  indexingStatus  = new AtomicReference<>(null);
    private final AtomicReference<IndexingProgress> currentProgress = new AtomicReference<>(null);
    // Live per-file status for the "View status" panel, keyed by absolute path.
    // Files are added when a worker picks them up and removed when they finish,
    // so this only ever holds the files currently being indexed (never skipped
    // or completed ones).
    private final Map<String, Map<String, Object>> fileStatuses = new ConcurrentHashMap<>();
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
        server.createContext("/api/query/stream", this::handleQueryStream);
        server.createContext("/api/conversation", this::handleConversation);
        server.createContext("/api/config",       this::handleConfig);
        server.createContext("/api/files/summary", this::handleFileSummary);
        server.createContext("/api/files",        this::handleFiles);
        server.createContext("/api/auth",         this::handleAuth);
        server.createContext("/api/reorg/preview", this::handleReorgPreview);
        server.createContext("/api/reorg/execute", this::handleReorgExecute);
        server.createContext("/api/reorg/undo",    this::handleReorgUndo);
        server.createContext("/api/reorg/history", this::handleReorgHistory);
    }

    /**
     * Auth-token bridge between the Electron UI and the Java backend.
     *   GET    /api/auth   → { authenticated }
     *   POST   /api/auth   → { token } stores the JWT for outgoing OpenAI calls
     *   DELETE /api/auth   → clears the token
     */
    private void handleAuth(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        if (isMethod(ex, "GET")) {
            sendJson(ex, 200, map("authenticated", tokenStore.isAuthenticated()));
            return;
        }

        if (isMethod(ex, "POST")) {
            try {
                Map<?, ?> body  = readJson(ex);
                String    token = (String) body.get("token");
                if (token == null || token.isBlank()) {
                    sendError(ex, 400, "token is required");
                    return;
                }
                tokenStore.setToken(token);
                // Reset the lazy QueryEngine so the next query picks up the
                // new token instead of capturing a stale "Not signed in".
                this.queryEngine = null;
                sendJson(ex, 200, map("ok", true));
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
                "ocrAvailable",       ocrAvailable
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
            // Live per-file status for the "View status" panel. Snapshot the
            // map, attach each file's path, and sort by name so the list is
            // stable between polls. Empty when nothing is in flight.
            if (s == null || s.running) {
                List<Map<String, Object>> active = new ArrayList<>();
                for (Map.Entry<String, Map<String, Object>> e : fileStatuses.entrySet()) {
                    Map<String, Object> m = new LinkedHashMap<>(e.getValue());
                    m.put("path", e.getKey());
                    active.add(m);
                }
                active.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
                body.put("activeFiles", active);
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
        fileStatuses.clear();

        bgExecutor.submit(() -> {
            try {
                AppConfig fresh = AppConfig.load();
                IngestionPipeline pipeline = new IngestionPipeline(fresh, metadataStore, vectorStore, embeddingClient);
                // Note: embeddingClient already wraps tokenStore via Main, so
                // proxy-mode embedding calls see the active JWT automatically.
                // The per-file callbacks fire from multiple worker threads, so
                // everything here writes only thread-safe structures.
                IngestionResult result = pipeline.run(new IngestionPipeline.ProgressListener() {
                    @Override public void onProgress(int processed, int total, int failed, String currentFile) {
                        currentProgress.set(new IndexingProgress(processed, total, failed, currentFile));
                    }
                    @Override public void onFileStart(String fileId, String fileName) {
                        Map<String, Object> m = new ConcurrentHashMap<>();
                        m.put("name", fileName);
                        m.put("stage", "starting");
                        m.put("done", 0);
                        m.put("total", 0);
                        fileStatuses.put(fileId, m);
                    }
                    @Override public void onFileStage(String fileId, String stage, int done, int total) {
                        Map<String, Object> m = fileStatuses.get(fileId);
                        if (m != null) {
                            m.put("stage", stage);
                            m.put("done", done);
                            m.put("total", total);
                        }
                    }
                    @Override public void onFileEnd(String fileId) {
                        fileStatuses.remove(fileId);
                    }
                });
                indexingStatus.set(new IndexingStatus(false, result, null));
            } catch (Throwable t) {
                log.error("Indexing job failed", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                indexingStatus.set(new IndexingStatus(false, null, msg));
            } finally {
                currentProgress.set(null);
                fileStatuses.clear();
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

    /**
     * POST /api/query/stream — Server-Sent Events.
     *
     * Body: { question: "..." }
     *
     * Emits a stream of events:
     *   event: token   data: "<plain text fragment>"        (many of these)
     *   event: done    data: {"sources":[…], "found": true} (one, at end)
     *   event: error   data: "<message>"                    (one, on failure)
     *
     * The Electron renderer subscribes via EventSource (or a raw fetch
     * reader) and appends each token to the live AI bubble. Identical
     * retrieval logic to /api/query — only the response shape differs.
     */
    private void handleQueryStream(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        // Read + validate request body before opening the stream.
        String question;
        try {
            Map<?, ?> req = readJson(ex);
            question = (String) req.get("question");
            if (question == null || question.isBlank()) {
                sendError(ex, 400, "question is required");
                return;
            }
        } catch (Exception e) {
            sendError(ex, 400, "Invalid JSON body: " + e.getMessage());
            return;
        }

        // Switch the response into SSE mode. Once headers are sent we own
        // the body and can write events incrementally.
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Content-Type",  "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        ex.getResponseHeaders().set("Connection",    "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0); // 0 = chunked / open-ended

        OutputStream out = ex.getResponseBody();

        try {
            QueryEngine engine = getOrInitQueryEngine();
            QueryEngine.QueryResult result = engine.queryStream(question, token -> {
                try {
                    writeSseEvent(out, "token", token);
                } catch (IOException ignored) { /* client disconnected — outer try cleans up */ }
            });

            // Final summary event with sources + found flag, encoded as JSON.
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("found",   result.found());
            summary.put("sources", result.sourceFiles());
            writeSseEvent(out, "done", mapper.writeValueAsString(summary));

        } catch (Exception e) {
            try {
                writeSseEvent(out, "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (IOException ignored) {}
            log.warn("query stream failed: {}", e.getMessage());
        } finally {
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Writes a single SSE event. Multi-line data values are split into
     * multiple data: lines (per the SSE spec), which the browser
     * reassembles by joining with newlines on the client side.
     */
    private void writeSseEvent(OutputStream out, String event, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event).append('\n');
        // SSE 'data:' must not contain embedded \n; split into multiple lines.
        for (String line : data.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n'); // blank line terminates the event
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
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
            String    rawPath = (String) req.get("path");

            if (rawPath == null || rawPath.isBlank()) {
                sendError(ex, 400, "path is required");
                return;
            }

            // Canonicalize so the row keyed by the on-disk-real path is the
            // one we delete — the UI may send any case variant macOS happens
            // to display.
            String path = PathNormalizer.canonical(rawPath);

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

    /**
     * POST /api/files/summary  body: { path, force? }
     *
     * Returns the one-page brief for an indexed file. Cache logic:
     *   - Path is canonicalized so case-only differences hit the same row.
     *   - If a cached summary exists AND its content_hash matches the file's
     *     current hash, return it immediately ({@code cached: true}).
     *   - Otherwise (or when force=true), generate via {@link SummarizationEngine},
     *     persist, and return ({@code cached: false}).
     *
     * Status codes:
     *   200 OK         — body has summary
     *   400 Bad Request — missing path
     *   404 Not Found  — file isn't indexed
     *   401 Unauthorized — proxy says you aren't signed in
     *   429 Too Many   — daily LLM quota reached
     *   503 Service Unavailable — backend isn't configured (no LLM client)
     */
    private void handleFileSummary(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        try {
            Map<?, ?> body = readJson(ex);
            String rawPath = (String) body.get("path");
            if (rawPath == null || rawPath.isBlank()) {
                sendError(ex, 400, "path is required");
                return;
            }
            boolean force = Boolean.TRUE.equals(body.get("force"));
            String path = PathNormalizer.canonical(rawPath);

            FileRecord record = metadataStore.findByPath(path).orElse(null);
            if (record == null || record.getStatus() != FileRecord.Status.INDEXED) {
                sendError(ex, 404, "File is not indexed: " + path);
                return;
            }

            // Cache hit path — return immediately when the file hasn't changed
            // since the summary was generated.
            if (!force) {
                var cached = metadataStore.getSummary(path);
                if (cached.isPresent()
                        && cached.get().contentHash().equals(record.getContentHash())) {
                    sendJson(ex, 200, map(
                            "path",        path,
                            "fileName",    record.getFileName(),
                            "summary",     cached.get().summary(),
                            "llmCalls",    cached.get().llmCalls(),
                            "generatedAt", cached.get().generatedAt(),
                            "cached",      true));
                    return;
                }
            }

            if (embeddingClient == null) {
                sendError(ex, 503, "Backend is still starting up. Try again in a moment.");
                return;
            }
            if (!tokenStore.isAuthenticated()) {
                sendError(ex, 401, "Please sign in to ShelfBot before generating summaries.");
                return;
            }

            GPT4oMiniClient llm = new GPT4oMiniClient(config, tokenStore);
            SummarizationEngine engine = new SummarizationEngine(llm, vectorStore);
            SummarizationEngine.Result result = engine.summarize(path, record.getFileName());

            metadataStore.putSummary(path, record.getContentHash(), result.summary(), result.llmCalls());

            sendJson(ex, 200, map(
                    "path",        path,
                    "fileName",    record.getFileName(),
                    "summary",     result.summary(),
                    "llmCalls",    result.llmCalls(),
                    "generatedAt", java.time.Instant.now().toString(),
                    "cached",      false));

        } catch (com.localfilebrain.llm.GPT4oMiniClient.LLMException e) {
            String msg = e.getMessage() == null ? "Summary failed" : e.getMessage();
            int status = msg.toLowerCase().contains("session") ? 401
                       : msg.toLowerCase().contains("limit")   ? 429
                       : 502;
            sendError(ex, status, msg);
        } catch (Exception e) {
            log.warn("/api/files/summary failed", e);
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
    // ─────────────────────────────────────────────────────────────────────────
    // Reorg execute / undo
    // ─────────────────────────────────────────────────────────────────────────
    //
    // The preview / proposal endpoint (which runs analyzer + LLM tool loop +
    // plan builder) is added alongside the UI in Step 8. These endpoints
    // are the action surface: the UI sends a vetted-by-user list of moves
    // here to execute, and can reverse them later via /undo.
    //
    // Both endpoints are auth-token-free at the API level — the UI is the
    // only caller (this server only binds to localhost) and the dangerous
    // LLM-cost path goes through the proxy separately. The filesystem
    // moves themselves are gated by the safety belts inside MoveExecutor.

    /**
     * POST /api/reorg/preview  body: { targetDir }
     *
     * End-to-end dry-run: runs the directory analyzer (Steps 1-3), then the
     * LLM tool loop (Steps 4-5) via the proxy, then the plan builder (Step 6),
     * and returns the user-approvable {@link ReorgProposal} as JSON. If the
     * directory is too varied to handle in one pass, the analyzer's scope
     * error is surfaced INSTEAD of the proposal so the UI can render the
     * "try these subdirs instead" suggestions without ever spending an LLM
     * call.
     */
    private void handleReorgPreview(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        try {
            Map<?, ?> body = readJson(ex);
            String targetDirStr = (String) body.get("targetDir");
            if (targetDirStr == null || targetDirStr.isBlank()) {
                sendError(ex, 400, "targetDir is required");
                return;
            }
            Path targetDir = Paths.get(targetDirStr);
            if (!Files.isDirectory(targetDir)) {
                sendError(ex, 400, "targetDir is not a directory: " + targetDir);
                return;
            }

            // Refuse to reorganize a folder that contains ShelfBot's own
            // working data — moving Lucene segment files or the metadata
            // DB around would corrupt the index. Compare on normalized
            // absolute paths so the user can't sneak past with "./" tricks.
            Path absTarget = targetDir.toAbsolutePath().normalize();
            Path indexPath = config.getVectorIndexPath().toAbsolutePath().normalize();
            Path dbPath    = config.getMetadataDbPath().toAbsolutePath().normalize();
            if (indexPath.startsWith(absTarget) || dbPath.startsWith(absTarget)) {
                sendError(ex, 400,
                        "That folder contains ShelfBot's own data files. "
                      + "Pick a different folder to reorganize.");
                return;
            }

            // Stage 0: just-in-time indexing of the target folder's loose files.
            //
            // ShelfBot's library indexer only covers configured root paths,
            // so picking a folder for reorg that isn't in the library would
            // leave every file as a filename-only embedding — BGE-small on
            // bare filenames is too noisy to ground the clustering or the
            // LLM's content-aware judgments. Index now so the analyzer has
            // real content vectors to work with. indexOne is idempotent
            // (timestamp + hash check) so files already in the library are
            // a no-op; the cost is paid only for new files.
            jitIndexLooseFiles(targetDir);

            // Stage 1-3: local analysis (no LLM cost).
            FileVectorService  fvs      = new FileVectorService(vectorStore, metadataStore, embeddingClient);
            DirectoryAnalyzer  analyzer = new DirectoryAnalyzer(fvs);
            DirectoryReorgPlan plan     = analyzer.analyze(targetDir);

            // Scope guard short-circuit: don't spend any LLM call if we
            // already know the directory is too varied.
            if (plan.scopeError().isPresent()) {
                sendJson(ex, 200, map(
                        "kind",       "scopeError",
                        "scopeError", serializeScopeError(plan.scopeError().get()),
                        "summary",    summarizePlan(plan)));
                return;
            }

            // No-op path: nothing to do means no LLM call needed.
            if (plan.newClusters().isEmpty() && plan.loners().isEmpty()
                    && plan.assignedToExisting().isEmpty()) {
                sendJson(ex, 200, map(
                        "kind",     "proposal",
                        "proposal", serializeProposal(new ReorgProposal(
                                plan.targetDir(),
                                List.of(), List.of(), List.of(),
                                0, 0, java.util.Optional.empty())),
                        "summary",  summarizePlan(plan)));
                return;
            }

            // Stage 4-5: LLM tool loop via the auth proxy.
            if (!tokenStore.isAuthenticated()) {
                sendError(ex, 401, "Please sign in to ShelfBot before running a reorganization.");
                return;
            }
            ProxyReorgLlmClient llmClient = new ProxyReorgLlmClient(config, tokenStore);
            ReorgToolLoop       loop      = new ReorgToolLoop(llmClient);
            ReorgToolLoopResult llmResult = loop.run(plan);

            // Stage 6: assemble final proposal.
            ReorgPlanBuilder builder  = new ReorgPlanBuilder();
            ReorgProposal    proposal = builder.build(plan, llmResult);

            sendJson(ex, 200, map(
                    "kind",     "proposal",
                    "proposal", serializeProposal(proposal),
                    "summary",  summarizePlan(plan)));

        } catch (com.localfilebrain.reorg.ReorgLlmClient.LlmHttpException e) {
            // Proxy unreachable or returned a non-friendly error. Surface the
            // proxy's user-facing message verbatim if it had one; otherwise
            // a generic but actionable hint.
            log.warn("/api/reorg/preview proxy failure: {}", e.getMessage());
            String msg = e.getMessage();
            if (msg == null || msg.isBlank() || msg.contains("Connection refused")
                    || msg.contains("ConnectException")) {
                msg = "Can't reach the ShelfBot service right now. Check your internet connection and try again in a moment.";
            }
            sendError(ex, 502, msg);
        } catch (Exception e) {
            log.warn("/api/reorg/preview failed", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    /** POST /api/reorg/execute  body: { targetDir, moves: [{from, to, destinationIsNew, source, reason, confidence}] } */
    private void handleReorgExecute(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        try {
            Map<?, ?> body = readJson(ex);
            String targetDirStr = (String) body.get("targetDir");
            if (targetDirStr == null || targetDirStr.isBlank()) {
                sendError(ex, 400, "targetDir is required");
                return;
            }
            Path targetDir = Paths.get(targetDirStr);
            if (!Files.isDirectory(targetDir)) {
                sendError(ex, 400, "targetDir is not a directory: " + targetDir);
                return;
            }

            // Same guard as preview — refuse if targetDir hosts ShelfBot's
            // own data. A buggy client could still post a payload here
            // without first calling preview.
            Path absTargetExec = targetDir.toAbsolutePath().normalize();
            Path indexPathExec = config.getVectorIndexPath().toAbsolutePath().normalize();
            Path dbPathExec    = config.getMetadataDbPath().toAbsolutePath().normalize();
            if (indexPathExec.startsWith(absTargetExec) || dbPathExec.startsWith(absTargetExec)) {
                sendError(ex, 400,
                        "That folder contains ShelfBot's own data files. "
                      + "Pick a different folder to reorganize.");
                return;
            }

            Object movesRaw = body.get("moves");
            if (!(movesRaw instanceof List<?> movesList)) {
                sendError(ex, 400, "moves must be an array");
                return;
            }
            List<ReorgProposal.ProposedMove> moves = new ArrayList<>(movesList.size());
            for (Object m : movesList) {
                if (!(m instanceof Map<?, ?> mm)) {
                    sendError(ex, 400, "each move must be a JSON object");
                    return;
                }
                String from = (String) mm.get("from");
                String to   = (String) mm.get("to");
                if (from == null || to == null) {
                    sendError(ex, 400, "each move requires from and to");
                    return;
                }
                Boolean newDest = (Boolean) mm.get("destinationIsNew");
                Object  sourceRaw = mm.get("source");
                Object  reasonRaw = mm.get("reason");
                Object  confRaw   = mm.get("confidence");
                String  source = sourceRaw instanceof String s ? s : "NEW_CLUSTER";
                String  reason = reasonRaw instanceof String r ? r : "";
                float   confidence = confRaw instanceof Number n ? n.floatValue() : 0f;
                moves.add(new ReorgProposal.ProposedMove(
                        Paths.get(from), Paths.get(to),
                        Boolean.TRUE.equals(newDest),
                        parseSource(source),
                        reason,
                        confidence));
            }

            MoveExecutor executor = new MoveExecutor(metadataStore);
            ReorgExecutionResult result = executor.execute(targetDir, moves);
            sendJson(ex, 200, serializeExecution(result));
        } catch (Exception e) {
            log.warn("/api/reorg/execute failed", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    /** POST /api/reorg/undo  body: { batchId } */
    private void handleReorgUndo(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        try {
            Map<?, ?> body = readJson(ex);
            String batchId = (String) body.get("batchId");
            if (batchId == null || batchId.isBlank()) {
                sendError(ex, 400, "batchId is required");
                return;
            }
            UndoExecutor undoer = new UndoExecutor(metadataStore);
            UndoExecutor.UndoResult r = undoer.undo(batchId);
            sendJson(ex, 200, serializeUndo(r));
        } catch (Exception e) {
            log.warn("/api/reorg/undo failed", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    /** GET /api/reorg/history?limit=N — recent batches for the "Undo last reorg" UI. */
    private void handleReorgHistory(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "GET")) { methodNotAllowed(ex); return; }

        int limit = 20;
        String q = ex.getRequestURI().getQuery();
        if (q != null) {
            for (String kv : q.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "limit".equals(kv.substring(0, eq))) {
                    try { limit = Math.max(1, Math.min(100, Integer.parseInt(kv.substring(eq + 1)))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        var batches = metadataStore.listRecentUndoBatches(limit);
        List<Map<String, Object>> out = new ArrayList<>(batches.size());
        for (var b : batches) {
            out.add(this.<String, Object>map(
                    "batchId",    b.batchId(),
                    "moveCount",  b.moveCount(),
                    "executedAt", b.executedAt()));
        }
        sendJson(ex, 200, map("batches", out));
    }

    /**
     * JSON shape for a {@link ReorgProposal} — mirrors the record fields with
     * paths as strings (UI doesn't speak java.nio.file.Path).
     */
    private Map<String, Object> serializeProposal(ReorgProposal p) {
        List<Map<String, Object>> moves = new ArrayList<>(p.moves().size());
        for (var m : p.moves()) {
            moves.add(this.<String, Object>map(
                    "from",             m.from().toString(),
                    "to",               m.to().toString(),
                    "destinationFile",  m.destinationFile().toString(),
                    "destinationIsNew", m.destinationIsNew(),
                    "source",           m.source().name(),
                    "reason",           m.reason(),
                    "confidence",       m.confidence()));
        }
        List<Map<String, Object>> dropped = new ArrayList<>(p.dropped().size());
        for (var d : p.dropped()) {
            dropped.add(this.<String, Object>map(
                    "file",       d.file().toString(),
                    "reason",     d.reason(),
                    "confidence", d.confidence()));
        }
        List<String> leftAlone = new ArrayList<>(p.leftAlone().size());
        for (var pth : p.leftAlone()) leftAlone.add(pth.toString());

        return this.<String, Object>map(
                "targetDir",          p.targetDir().toString(),
                "moves",              moves,
                "dropped",            dropped,
                "leftAlone",          leftAlone,
                "llmCallsAttempted",  p.llmCallsAttempted(),
                "llmCallsSuccessful", p.llmCallsSuccessful(),
                "stoppedReason",      p.stoppedReason().orElse(null),
                "hasAnyChanges",      p.hasAnyChanges());
    }

    private Map<String, Object> serializeScopeError(ScopeError e) {
        List<Map<String, Object>> suggestions = new ArrayList<>(e.suggestions().size());
        for (var s : e.suggestions()) {
            Map<String, Object> entry = this.<String, Object>map(
                    "label",     s.label(),
                    "scopePath", s.scopePath() == null ? null : s.scopePath().toString());
            if (s.familyFilter() != null) {
                entry.put("familyFilter", this.<String, Object>map(
                        "family",    s.familyFilter().family().name(),
                        "fileCount", s.familyFilter().fileCount()));
            }
            suggestions.add(entry);
        }
        return this.<String, Object>map(
                "title",             e.title(),
                "detail",            e.detail(),
                "suggestions",       suggestions,
                "decisionsRequired", e.decisionsRequired(),
                "decisionBudget",    e.decisionBudget());
    }

    /** Compact stats the UI shows above the move list ("3 already in place / 4 to organize"). */
    private Map<String, Object> summarizePlan(DirectoryReorgPlan plan) {
        return this.<String, Object>map(
                "looseFiles",          plan.totalLooseFiles(),
                "decisions",           plan.totalDecisions(),
                "assignedToExisting",  plan.assignedToExisting().size(),
                "newClusters",         plan.newClusters().size(),
                "loners",              plan.loners().size(),
                "existingSubdirCount", plan.existingSubdirs().size());
    }

    /**
     * Indexes any top-level loose files of {@code targetDir} that aren't
     * already up-to-date in the metadata store. Uses the shared
     * VectorStore + EmbeddingClient + IndexMetadataStore so the rest of
     * the app (live watcher, query path) sees the fresh chunks
     * immediately and the next reorg call short-circuits.
     *
     * <p>Failures (Tika can't read .pages, file too large, budget tripped)
     * are logged and swallowed — we never want indexing problems to
     * prevent the reorg preview from running. Files we couldn't index
     * fall through to the existing filename-only path inside the analyzer.
     */
    private void jitIndexLooseFiles(Path targetDir) {
        java.util.Set<String> supportedExts = new java.util.HashSet<>();
        for (String e : config.getSupportedExtensions()) supportedExts.add(e.toLowerCase());

        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(targetDir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                int dot = name.lastIndexOf('.');
                if (dot <= 0 || dot == name.length() - 1) continue;
                String ext = name.substring(dot + 1).toLowerCase();
                if (!supportedExts.contains(ext)) continue;
                candidates.add(p);
            }
        } catch (IOException e) {
            log.warn("JIT-index: failed to scan {}: {}", targetDir, e.getMessage());
            return;
        }

        if (candidates.isEmpty()) return;

        IngestionPipeline pipeline = new IngestionPipeline(
                config, metadataStore, vectorStore, embeddingClient);
        int newlyIndexed = 0;
        for (Path file : candidates) {
            try {
                int chunks = pipeline.indexOne(file);
                if (chunks > 0) newlyIndexed++;
            } catch (Exception e) {
                log.debug("JIT-index: '{}' failed: {}", file.getFileName(), e.getMessage());
            }
        }
        if (newlyIndexed > 0) {
            log.info("JIT-indexed {} new file(s) under {}", newlyIndexed, targetDir);
        }
    }

    private static ReorgProposal.ProposedMove.Source parseSource(String s) {
        try { return ReorgProposal.ProposedMove.Source.valueOf(s); }
        catch (IllegalArgumentException e) { return ReorgProposal.ProposedMove.Source.NEW_CLUSTER; }
    }

    private Map<String, Object> serializeExecution(ReorgExecutionResult r) {
        List<Map<String, Object>> outcomes = new ArrayList<>(r.outcomes().size());
        for (var o : r.outcomes()) {
            outcomes.add(this.<String, Object>map(
                    "from",       o.from().toString(),
                    "to",         o.to().toString(),
                    "resolvedTo", o.resolvedTo() == null ? null : o.resolvedTo().toString(),
                    "status",     o.status().name(),
                    "reason",     o.reason()));
        }
        return this.<String, Object>map(
                "batchId",      r.batchId(),
                "outcomes",     outcomes,
                "successCount", r.successCount(),
                "skippedCount", r.skippedCount(),
                "failedCount",  r.failedCount());
    }

    private Map<String, Object> serializeUndo(UndoExecutor.UndoResult r) {
        List<Map<String, Object>> outcomes = new ArrayList<>(r.outcomes().size());
        for (var o : r.outcomes()) {
            outcomes.add(this.<String, Object>map(
                    "fromPath", o.fromPath(),
                    "toPath",   o.toPath(),
                    "status",   o.status().name(),
                    "reason",   o.reason()));
        }
        return this.<String, Object>map(
                "batchId",      r.batchId(),
                "outcomes",     outcomes,
                "successCount", r.successCount(),
                "skippedCount", r.skippedCount(),
                "failedCount",  r.failedCount());
    }

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

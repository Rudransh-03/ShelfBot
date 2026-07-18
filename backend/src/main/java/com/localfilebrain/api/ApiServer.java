package com.localfilebrain.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localfilebrain.attention.AttentionBuilder;
import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.config.RegionProfile;
import com.localfilebrain.deadline.DeadlineScanService;
import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.chat.ChatStore;
import com.localfilebrain.client.ClientResolver;
import com.localfilebrain.client.EntitySuggester;
import com.localfilebrain.client.LocalEntityScanner;
import com.localfilebrain.client.MembershipEngine;
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
    // Per-launch shared secret. The Electron main process generates it, hands it
    // to this backend via the SHELFBOT_LOCAL_TOKEN env var, and to the renderer
    // over IPC. Every /api/* request (except the liveness probe + CORS preflight)
    // must echo it in the X-Shelfbot-Token header. This is what stops a malicious
    // web page open in the user's ordinary browser from reaching this localhost
    // API and reading their private documents/chat — the page can hit the port,
    // but it cannot know this secret. Null/blank ⇒ enforcement disabled, which is
    // the case for headless dev/test runs (no env var set) so they keep working.
    private final String             localToken = System.getenv("SHELFBOT_LOCAL_TOKEN");
    // One-shot detector — instantiating TextExtractor probes Tesseract; we
    // keep the result to report in /api/status without re-checking per call.
    private final boolean            ocrAvailable = new TextExtractor().isOcrAvailable();

    // QueryEngine is created lazily so the server still starts even when the
    // OpenAI key is not yet configured.
    private volatile QueryEngine queryEngine;

    // Local, on-device chat persistence (ChatGPT-style threads). Lazily opened
    // so server startup never depends on it.
    private volatile ChatStore chatStore;

    // Serializes the rehydrate→query→persist sequence so concurrent requests
    // can't interleave on the engine's swapped-in conversation history.
    private final Object queryLock = new Object();

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

    // ── Deadline-scan job state (independent of indexing) ──────────────────────
    private final AtomicBoolean                     deadlineScanRunning  = new AtomicBoolean(false);
    private final AtomicReference<DeadlineScanState> deadlineScanStatus  = new AtomicReference<>(null);
    private final AtomicReference<DeadlineProgress>  deadlineProgress    = new AtomicReference<>(null);
    private final ExecutorService                   deadlineExecutor     = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shelfbot-deadline-scan");
        t.setDaemon(true);
        return t;
    });

    // ── Extraction job state (Extract Mode / Bulk Q&A; independent of the above) ─
    private final AtomicBoolean                     extractionRunning = new AtomicBoolean(false);
    // Set by DELETE /api/extract; polled by the running job between batches.
    private final AtomicBoolean                     extractionCancel  = new AtomicBoolean(false);
    // Holds the last run's status; contains rows once finished (see handleExtract).
    private final AtomicReference<Map<String, Object>> extractionResult = new AtomicReference<>(null);
    private final AtomicReference<int[]>            extractionProgress = new AtomicReference<>(null); // {processed,total}
    private final ExecutorService                   extractionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shelfbot-extract");
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
        deadlineExecutor.shutdown();
        extractionExecutor.shutdown();
        ChatStore cs = chatStore;
        if (cs != null) cs.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route registration
    // ─────────────────────────────────────────────────────────────────────────

    private void registerRoutes() {
        register("/api/health",       this::handleHealth);
        register("/api/status",       this::handleStatus);
        register("/api/index",        this::handleIndex);
        register("/api/query",        this::handleQuery);
        register("/api/query/stream", this::handleQueryStream);
        register("/api/conversations", this::handleConversations);
        register("/api/config",       this::handleConfig);
        register("/api/files/summary", this::handleFileSummary);
        register("/api/files",        this::handleFiles);
        register("/api/extract",        this::handleExtract);
        register("/api/deadlines/scan", this::handleDeadlineScan);
        register("/api/deadlines",      this::handleDeadlines);
        register("/api/missing",        this::handleMissing);
        register("/api/timeline",       this::handleTimeline);
        register("/api/attention",      this::handleAttention);
        register("/api/attention/dismiss", this::handleAttentionDismiss);
        register("/api/clients",         this::handleClients);
        register("/api/auth",         this::handleAuth);
        register("/api/reorg/preview", this::handleReorgPreview);
        register("/api/reorg/execute", this::handleReorgExecute);
        register("/api/reorg/undo",    this::handleReorgUndo);
        register("/api/reorg/history", this::handleReorgHistory);
    }

    /**
     * Registers a route with a last-resort safety net. Each handler already does
     * its own try/catch, but if anything slips through, the bare
     * {@code com.sun.net.httpserver} closes the socket with NO response — the UI
     * fetch then hangs/errors opaquely. This wrapper guarantees a 500 JSON reply
     * (best-effort — a no-op if the handler already started the response, e.g. a
     * mid-stream SSE failure) and logs the cause server-side.
     */
    private void register(String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, ex -> {
            try {
                handler.handle(ex);
            } catch (BadRequestException bre) {
                try { sendError(ex, 400, bre.getMessage()); }
                catch (Exception ignored) { /* response already begun */ }
            } catch (Throwable t) {
                log.error("Unhandled error in {} {}", ex.getRequestMethod(),
                        ex.getRequestURI().getPath(), t);
                try { sendError(ex, 500, "Internal server error"); }
                catch (Exception ignored) { /* response already begun — can't help it */ }
            }
        });
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
                // Free, local client-identity detection (GSTIN/PAN) over the
                // freshly-indexed text, so "found in your files" suggestions
                // appear for everyone without needing the Pro deadline scan.
                try { new LocalEntityScanner(metadataStore, vectorStore).scanAll(); }
                catch (Exception e) { log.warn("post-index local entity scan failed: {}", e.getMessage()); }
                // Free, local document-type classification (Library filter chips)
                // and obligation-date extraction (Timeline). Both reuse the
                // already-indexed text — no LLM call, no added run-cost.
                try { com.localfilebrain.ingestion.DocumentTypeClassifier.classifyAll(metadataStore, vectorStore); }
                catch (Exception e) { log.warn("post-index doc-type classification failed: {}", e.getMessage()); }
                try { new com.localfilebrain.timeline.LocalDateScanner(metadataStore, vectorStore).scanAll(); }
                catch (Exception e) { log.warn("post-index local date scan failed: {}", e.getMessage()); }
                // Refresh client membership after indexing so newly-added files
                // get auto-filed to their client and changed files lose any stale
                // tag (manual pins are preserved). No-op when no clients exist.
                if (metadataStore.countClients() > 0) {
                    try { recomputeMembership(); }
                    catch (Exception e) { log.warn("post-index membership recompute failed: {}", e.getMessage()); }
                }
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

            getOrInitQueryEngine(); // surface config errors before creating a thread
            String convId = resolveConversation(req.get("conversationId"), question);

            ScopeDecision sd = resolveScope(req.get("clientId"), convId, question);
            if (sd.clarify) {
                ChatStore cs = chatStore();
                cs.addMessage(convId, "user", question);
                cs.addMessage(convId, "assistant", sd.message, null);
                sendJson(ex, 200, map(
                    "answer",         sd.message,
                    "sources",        List.of(),
                    "found",          true,
                    "conversationId", convId,
                    "clarify",        sd.options
                ));
                return;
            }

            QueryEngine.QueryResult result =
                    answerInConversation(convId, question, e -> e.query(question, sd.allowedPaths));

            sendJson(ex, 200, map(
                "answer",         result.answer(),
                "sources",        result.sourceFiles(),
                "found",          result.found(),
                "conversationId", convId,
                "scope",          sd.label
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
        Object conversationIdRaw = null;
        Object clientIdRaw = null;
        try {
            Map<?, ?> req = readJson(ex);
            question = (String) req.get("question");
            conversationIdRaw = req.get("conversationId");
            clientIdRaw = req.get("clientId");
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
            getOrInitQueryEngine(); // surface config errors before creating a thread
            String convId = resolveConversation(conversationIdRaw, question);

            ScopeDecision sd = resolveScope(clientIdRaw, convId, question);
            if (sd.clarify) {
                // Ambiguous client → emit the clarifying question as one token and
                // close with the options; no retrieval happens.
                ChatStore cs = chatStore();
                cs.addMessage(convId, "user", question);
                cs.addMessage(convId, "assistant", sd.message, null);
                writeSseEvent(out, "token", sd.message);
                Map<String, Object> cl = new LinkedHashMap<>();
                cl.put("found", true);
                cl.put("sources", List.of());
                cl.put("conversationId", convId);
                cl.put("clarify", sd.options);
                writeSseEvent(out, "done", mapper.writeValueAsString(cl));
                return;
            }

            QueryEngine.QueryResult result = answerInConversation(convId, question, engine ->
                engine.queryStream(question, token -> {
                    try {
                        writeSseEvent(out, "token", token);
                    } catch (IOException ignored) { /* client disconnected — outer try cleans up */ }
                }, sd.allowedPaths));

            // Final summary event with sources + found flag + the thread id
            // (lets the UI adopt a freshly-created conversation), encoded as JSON.
            // The full answer text rides along as a safety net: if any path ever
            // resolves without streaming tokens, the UI can still render the
            // reply instead of leaving a blank bubble.
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("found",          result.found());
            summary.put("answer",         result.answer());
            summary.put("sources",        result.sourceFiles());
            summary.put("conversationId", convId);
            summary.put("scope",          sd.label);
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

    /**
     * REST for chat threads — all local, on-device (never sent to the proxy):
     *   GET    /api/conversations         list threads (most-recent first)
     *   POST   /api/conversations         create { title? } → { id, title }
     *   GET    /api/conversations/{id}     thread messages
     *   POST   /api/conversations/{id}     rename { title }
     *   DELETE /api/conversations/{id}     delete thread + its messages
     */
    private void handleConversations(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        try {
            ChatStore cs = chatStore();
            String base = "/api/conversations";
            String path = ex.getRequestURI().getPath();
            String id = path.length() > base.length() ? path.substring(base.length() + 1) : "";
            if (id.endsWith("/")) id = id.substring(0, id.length() - 1);

            if (id.isEmpty()) {
                if (isMethod(ex, "GET")) {
                    String q = queryParam(ex, "q");
                    if (q != null && !q.isBlank()) {
                        // Command-palette search: results carry a match snippet.
                        sendJson(ex, 200, map("results", cs.searchWithSnippets(q, 30)));
                    } else {
                        sendJson(ex, 200, map("conversations", cs.listConversations()));
                    }
                } else if (isMethod(ex, "POST")) {
                    Object t = readJson(ex).get("title");
                    String title = (t instanceof String s && !s.isBlank()) ? s.trim() : "New chat";
                    String newId = cs.createConversation(title);
                    sendJson(ex, 200, map("id", newId, "title", title));
                } else {
                    methodNotAllowed(ex);
                }
                return;
            }

            if (!cs.exists(id)) { sendError(ex, 404, "conversation not found"); return; }
            if (isMethod(ex, "GET")) {
                sendJson(ex, 200, map("id", id, "messages", cs.getMessages(id)));
            } else if (isMethod(ex, "DELETE")) {
                cs.deleteConversation(id);
                sendJson(ex, 200, map("deleted", true));
            } else if (isMethod(ex, "POST")) {
                Object t = readJson(ex).get("title");
                cs.renameConversation(id, t instanceof String s ? s : null);
                sendJson(ex, 200, map("renamed", true));
            } else {
                methodNotAllowed(ex);
            }
        } catch (Exception e) {
            log.warn("/api/conversations failed", e);
            sendError(ex, 500, e.getMessage());
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
                    "region",          config.getRegion().code(),
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

            // Region is a standalone setting — a request may carry only the region
            // (from the Settings dropdown / onboarding), only paths, or both.
            String region = req.get("region") instanceof String r && !r.isBlank()
                    ? RegionProfile.of(r).code() : null;   // normalise (US/UK/EU/IN)

            if (cleaned.isEmpty() && region == null) {
                sendError(ex, 400, "rootPath, rootPaths, or region is required");
                return;
            }

            if (!cleaned.isEmpty()) updateConfigRootPaths(cleaned);
            if (region != null)     updateConfigProperty("user.region", region);

            // Reload config so future calls reflect the change (this also re-syncs
            // the active market via RegionProfile.setActive inside AppConfig.load).
            this.config = AppConfig.load();
            // Reset QueryEngine so it re-initialises with the updated config on next query
            this.queryEngine = null;

            sendJson(ex, 200, map(
                "updated",   true,
                "rootPath",  cleaned.isEmpty() ? "" : cleaned.get(0),
                "rootPaths", cleaned,
                "region",    config.getRegion().code()
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
                // ?status=failed → the files that couldn't be indexed, with the
                // reason, so the UI can show the user which/why (default: indexed).
                boolean wantFailed = "failed".equalsIgnoreCase(queryParam(ex, "status"));
                List<FileRecord> records = wantFailed
                        ? metadataStore.listFailedFiles()
                        : metadataStore.listIndexedFilesBySizeDesc();
                List<Map<String, Object>> rows = new ArrayList<>(records.size());
                for (FileRecord r : records) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("path",          r.getAbsolutePath());
                    row.put("name",          r.getFileName());
                    row.put("extension",     r.getFileExtension());
                    row.put("sizeBytes",     r.getFileSizeBytes());
                    row.put("lastIndexedAt", r.getLastIndexedAt() != null ? r.getLastIndexedAt().toString() : null);
                    if (wantFailed) {
                        row.put("reason", r.getErrorMessage());
                    } else {
                        row.put("chunkCount", r.getChunkCount());
                        row.put("tokenCount", r.getTokenCount());
                        row.put("docType", r.getDocType() != null ? r.getDocType() : "Other");
                    }
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
    // Deadlines (cross-document intelligence)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/deadlines/scan — start an incremental scan of indexed files for
     * deadlines (one batched LLM call per group of date-bearing documents).
     * GET  /api/deadlines/scan — poll the running/last scan's status + progress.
     *
     * The scan is paced by a per-(UTC)-day call budget and stops cleanly on the
     * upstream daily quota, so it's safe to call after every index — it simply
     * resumes where it left off on the next pass.
     */
    /**
     * POST /api/extract — start a structured extraction over selected documents
     * (Extract Mode and Bulk Q&A). Reuses the deadline engine's batching model
     * via {@link ExtractionService}. Body:
     *   {
     *     "paths":   ["/abs/a.pdf", ...]   // OR
     *     "folder":  "/abs/dir",           // OR
     *     "clientId":"...",                // scope; else all indexed files
     *     "fields":  [{ "name","type","description","required" }],
     *     "options": { "currency": { "symbol":"₹", "grouping":"INDIAN|WESTERN" } }
     *   }
     * GET  /api/extract — poll running/last-run status; carries the result rows
     * once finished. Single-flight (409 if a run is already going).
     */
    private void handleExtract(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        if (isMethod(ex, "GET")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("running", extractionRunning.get());
            Map<String, Object> result = extractionResult.get();
            body.put("hasRun", result != null);
            if (result != null) body.putAll(result);
            int[] p = extractionProgress.get();
            if (p != null && extractionRunning.get()) {
                body.put("progress", map("processed", p[0], "total", p[1]));
            }
            sendJson(ex, 200, body);
            return;
        }

        // DELETE /api/extract — request cancellation of the running job. The job
        // stops at the next batch boundary and returns the rows done so far.
        if (isMethod(ex, "DELETE")) {
            if (extractionRunning.get()) extractionCancel.set(true);
            sendJson(ex, 200, map("cancelling", extractionRunning.get()));
            return;
        }

        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        if (!tokenStore.isAuthenticated()) {
            sendError(ex, 401, "Please sign in to Rudo before running an extraction.");
            return;
        }

        final List<com.localfilebrain.extract.ExtractionField> schema;
        final com.localfilebrain.extract.ExtractionOptions options;
        final List<com.localfilebrain.extract.ExtractionService.FileRef> targets;
        try {
            Map<?, ?> req = readJson(ex);
            schema  = parseSchema(req.get("fields"));
            if (schema.isEmpty()) { sendError(ex, 400, "At least one extraction field is required."); return; }
            options = parseExtractOptions(req.get("options"));
            targets = resolveExtractTargets(req);
            if (targets.isEmpty()) { sendError(ex, 400, "No indexed documents match the selection."); return; }
            if (targets.size() > 1000) {
                sendError(ex, 400, "Too many documents selected (" + targets.size()
                        + "). Please narrow the selection to 1000 or fewer.");
                return;
            }
        } catch (IllegalArgumentException bad) {
            sendError(ex, 400, bad.getMessage());
            return;
        } catch (Exception e) {
            sendError(ex, 400, "Invalid request: " + e.getMessage());
            return;
        }

        if (!extractionRunning.compareAndSet(false, true)) {
            sendJson(ex, 409, map("error", "An extraction is already in progress"));
            return;
        }
        extractionResult.set(null);
        extractionCancel.set(false);
        extractionProgress.set(new int[]{0, targets.size()});

        extractionExecutor.submit(() -> {
            try {
                AppConfig fresh = AppConfig.load();
                GPT4oMiniClient llm = new GPT4oMiniClient(fresh, tokenStore);
                com.localfilebrain.extract.ExtractionService svc =
                        new com.localfilebrain.extract.ExtractionService(vectorStore);
                com.localfilebrain.extract.ExtractionService.Result r = svc.run(
                        targets, schema, options,
                        (sys, user) -> llm.oneShot(sys, user,
                                com.localfilebrain.extract.ExtractionService.EXTRACTION_MAX_TOKENS),
                        (processed, total) -> extractionProgress.set(new int[]{processed, total}),
                        extractionCancel::get);
                extractionResult.set(buildExtractionResult(schema, r));
                log.info("Extraction finished: {} row(s), {} truncated batch(es), cancelled={}",
                        r.rows().size(), r.truncatedBatches(), r.cancelled());
            } catch (Throwable t) {
                log.error("Extraction failed", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                extractionResult.set(map("error", msg, "rows", List.of()));
            } finally {
                extractionProgress.set(null);
                extractionCancel.set(false);
                extractionRunning.set(false);
            }
        });

        sendJson(ex, 202, map("started", true, "total", targets.size()));
    }

    /** Parses the schema array into typed {@link com.localfilebrain.extract.ExtractionField}s.
     *  Field names are de-duplicated (case-insensitive, first wins) so a duplicate
     *  column can't desync the result columns from each row's per-field map. */
    private List<com.localfilebrain.extract.ExtractionField> parseSchema(Object raw) {
        List<com.localfilebrain.extract.ExtractionField> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object name = m.get("name");
            if (name == null || String.valueOf(name).isBlank()) continue;
            String nm = String.valueOf(name).trim();
            if (!seen.add(nm.toLowerCase())) continue; // skip duplicate column names
            com.localfilebrain.extract.FieldType type =
                    com.localfilebrain.extract.FieldType.from(m.get("type") == null ? null : String.valueOf(m.get("type")));
            String desc = m.get("description") == null ? "" : String.valueOf(m.get("description"));
            boolean required = Boolean.TRUE.equals(m.get("required"));
            out.add(new com.localfilebrain.extract.ExtractionField(nm, type, desc, required));
        }
        return out;
    }

    /** Parses {@code options.currency}; absent → neutral (never silently ₹). */
    private com.localfilebrain.extract.ExtractionOptions parseExtractOptions(Object raw) {
        if (raw instanceof Map<?, ?> opts && opts.get("currency") instanceof Map<?, ?> cur) {
            String symbol = cur.get("symbol") == null ? "" : String.valueOf(cur.get("symbol"));
            com.localfilebrain.extract.CurrencyDescriptor.Grouping grouping =
                    "INDIAN".equalsIgnoreCase(String.valueOf(cur.get("grouping")))
                            ? com.localfilebrain.extract.CurrencyDescriptor.Grouping.INDIAN
                            : com.localfilebrain.extract.CurrencyDescriptor.Grouping.WESTERN;
            return new com.localfilebrain.extract.ExtractionOptions(
                    new com.localfilebrain.extract.CurrencyDescriptor(symbol, grouping));
        }
        return com.localfilebrain.extract.ExtractionOptions.defaults();
    }

    /** Resolves the target files from paths | folder | clientId | (all indexed). */
    private List<com.localfilebrain.extract.ExtractionService.FileRef> resolveExtractTargets(Map<?, ?> req) {
        // path → display name, over all indexed files.
        LinkedHashMap<String, String> nameByPath = new LinkedHashMap<>();
        for (var fr : metadataStore.listIndexedFilesBySizeDesc()) {
            nameByPath.put(fr.getAbsolutePath(), fr.getFileName());
        }

        java.util.List<String> chosen = new ArrayList<>();
        if (req.get("paths") instanceof List<?> list && !list.isEmpty()) {
            for (Object o : list) { String p = String.valueOf(o); if (nameByPath.containsKey(p)) chosen.add(p); }
        } else if (req.get("folder") instanceof String folder && !folder.isBlank()) {
            // Separator-agnostic prefix match (handles both '/' and '\\') so a
            // parent folder selects its files on macOS and Windows alike.
            String base = folder.replaceAll("[/\\\\]+$", "");
            for (String p : nameByPath.keySet()) {
                if (p.equals(base) || p.startsWith(base + "/") || p.startsWith(base + "\\")) chosen.add(p);
            }
        } else if (req.get("clientId") instanceof String cid && !cid.isBlank()) {
            for (String p : metadataStore.pathsForClient(cid)) if (nameByPath.containsKey(p)) chosen.add(p);
        } else {
            chosen.addAll(nameByPath.keySet());
        }

        List<com.localfilebrain.extract.ExtractionService.FileRef> out = new ArrayList<>(chosen.size());
        for (String p : chosen) {
            out.add(new com.localfilebrain.extract.ExtractionService.FileRef(nameByPath.get(p), p));
        }
        return out;
    }

    /** Serializes an extraction result for the poll response (columns + rows,
     *  each cell carrying its value/status/note so ambiguity survives to export). */
    private Map<String, Object> buildExtractionResult(
            List<com.localfilebrain.extract.ExtractionField> schema,
            com.localfilebrain.extract.ExtractionService.Result r) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (var f : schema) columns.add(map("name", f.name(), "type", f.type().name().toLowerCase()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : r.rows()) {
            List<Map<String, Object>> cells = new ArrayList<>();
            for (var f : schema) {
                var v = row.fields().get(f.name());
                cells.add(map(
                        "name",     f.name(),
                        "value",    v == null ? "" : v.value(),
                        "status",   v == null ? "MISSING" : v.status().name(),
                        "note",     v == null ? "" : v.note(),
                        "evidence", v == null ? "" : v.evidence()));
            }
            rows.add(map(
                    "fileName",     row.fileName(),
                    "absolutePath", row.absolutePath(),
                    // "ambiguous" now means "needs review" (ambiguous OR unverified)
                    // so the UI's review indicator lights up for both.
                    "ambiguous",    row.hasFlagged(),
                    "cells",        cells));
        }
        return map("done", true, "columns", columns, "rows", rows,
                "count", rows.size(), "truncatedBatches", r.truncatedBatches(),
                "cancelled", r.cancelled());
    }

    private void handleDeadlineScan(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;

        if (isMethod(ex, "GET")) {
            Map<String, Object> body = new LinkedHashMap<>();
            DeadlineScanState s = deadlineScanStatus.get();
            body.put("running", deadlineScanRunning.get());
            body.put("hasRun",  s != null);
            if (s != null) {
                body.put("stop",         s.stop);
                body.put("message",      s.message);
                body.put("filesScanned", s.filesScanned);
                body.put("found",        s.found);
            }
            DeadlineProgress p = deadlineProgress.get();
            if (p != null && deadlineScanRunning.get()) {
                body.put("progress", map(
                        "processed",   p.processed,
                        "total",       p.total,
                        "found",       p.found,
                        "currentFile", p.currentFile));
            }
            sendJson(ex, 200, body);
            return;
        }

        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }

        if (!tokenStore.isAuthenticated()) {
            sendError(ex, 401, "Please sign in to ShelfBot before scanning for deadlines.");
            return;
        }
        if (!deadlineScanRunning.compareAndSet(false, true)) {
            sendJson(ex, 409, map("error", "A deadline scan is already in progress"));
            return;
        }
        deadlineProgress.set(null);

        deadlineExecutor.submit(() -> {
            try {
                AppConfig fresh = AppConfig.load();
                GPT4oMiniClient llm = new GPT4oMiniClient(fresh, tokenStore);
                DeadlineScanService svc = new DeadlineScanService(metadataStore, vectorStore);
                DeadlineScanService.ScanResult r = svc.scan(
                        llm, fresh.getDeadlineDailyCallBudget(),
                        (processed, total, found, currentFile) ->
                                deadlineProgress.set(new DeadlineProgress(processed, total, found, currentFile)));
                deadlineScanStatus.set(new DeadlineScanState(
                        r.stop().name(), r.message(), r.filesScanned(), r.deadlinesFound()));
                log.info("Deadline scan finished: {} ({} files, {} found, {} LLM calls)",
                        r.stop(), r.filesScanned(), r.deadlinesFound(), r.llmCallsUsed());
            } catch (Throwable t) {
                log.error("Deadline scan failed", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                deadlineScanStatus.set(new DeadlineScanState("ERROR", msg, 0, 0));
            } finally {
                deadlineProgress.set(null);
                deadlineScanRunning.set(false);
            }
        });

        sendJson(ex, 202, map("started", true));
    }

    /**
     * GET    /api/deadlines           list items (+ computed buckets) and counts.
     *                                  Optional ?status=pending|done|dismissed|all (default all).
     * POST   /api/deadlines/{id}       partial update {status?, reminderSet?, title?, description?, dueDate?, recurring?}
     * DELETE /api/deadlines/{id}       remove one item.
     */
    /**
     * GET /api/missing — gaps in recurring document series (e.g. "GST return for
     * February 2024 may be missing"), computed locally from the series
     * classifications captured during the deadline scan. No LLM call here.
     */
    private void handleMissing(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "GET")) { methodNotAllowed(ex); return; }
        try {
            List<IndexMetadataStore.SeriesRow> series = metadataStore.listAllSeries();
            List<com.localfilebrain.deadline.MissingDocumentDetector.MissingDoc> missing =
                    com.localfilebrain.deadline.MissingDocumentDetector.detect(series);

            List<Map<String, Object>> items = new ArrayList<>(missing.size());
            for (var d : missing) {
                items.add(map(
                        "series",       d.series(),
                        "issuer",       d.issuer(),
                        "period",       d.periodLabel(),
                        "cadence",      d.cadence(),
                        "confidence",   d.confidence(),
                        "presentCount", d.presentCount(),
                        "message",      missingMessage(d)));
            }
            sendJson(ex, 200, map("items", items, "count", items.size()));
        } catch (Exception e) {
            log.warn("/api/missing failed", e);
            sendError(ex, 500, "Failed to compute missing documents");
        }
    }

    /** Human-readable, deliberately tentative phrasing for a missing-doc alert. */
    private static String missingMessage(
            com.localfilebrain.deadline.MissingDocumentDetector.MissingDoc d) {
        String who = (d.issuer() == null || d.issuer().isBlank()) ? "" : " from " + d.issuer();
        return "Looks like your " + d.series() + who + " for " + d.periodLabel() + " may be missing.";
    }

    /**
     * GET /api/timeline — every obligation date Rudo found across the user's
     * documents (due dates, expiries, renewals), in chronological order, each
     * with a computed bucket (OVERDUE | DUE_SOON | UPCOMING) and the file's auto
     * type. Free + local: reads the {@code document_dates} table the local date
     * scan fills at index time — no LLM call.
     */
    private void handleTimeline(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "GET")) { methodNotAllowed(ex); return; }
        try {
            List<IndexMetadataStore.DateRow> rows = metadataStore.listTimeline();
            java.time.LocalDate today = java.time.LocalDate.now();
            List<Map<String, Object>> items = new ArrayList<>(rows.size());
            int overdue = 0, dueSoon = 0, upcoming = 0;
            for (IndexMetadataStore.DateRow r : rows) {
                Long daysUntil = null;
                String bucket = "UPCOMING";
                try {
                    java.time.LocalDate d = java.time.LocalDate.parse(r.eventDate());
                    long days = java.time.temporal.ChronoUnit.DAYS.between(today, d);
                    daysUntil = days;
                    bucket = days < 0 ? "OVERDUE" : days <= 7 ? "DUE_SOON" : "UPCOMING";
                } catch (Exception ignored) { /* keep UPCOMING */ }
                switch (bucket) {
                    case "OVERDUE"  -> overdue++;
                    case "DUE_SOON" -> dueSoon++;
                    default         -> upcoming++;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",        r.id());
                m.put("path",      r.absolutePath());
                m.put("fileName",  r.fileName());
                m.put("date",      r.eventDate());
                m.put("title",     r.title());
                m.put("excerpt",   r.sourceExcerpt());
                m.put("docType",   r.docType() != null ? r.docType() : "Other");
                m.put("daysUntil", daysUntil);
                m.put("bucket",    bucket);
                items.add(m);
            }
            sendJson(ex, 200, map("items", items, "counts", map(
                    "total", rows.size(), "overdue", overdue, "dueSoon", dueSoon, "upcoming", upcoming)));
        } catch (Exception e) {
            log.warn("/api/timeline failed", e);
            sendError(ex, 500, "Failed to load timeline");
        }
    }

    /**
     * GET /api/attention — strictly TODAY's action items behind the sidebar
     * button: obligations due today, overdue obligations, and likely-missing
     * recurring documents (anything due later lives only in Deadlines). Merged
     * deterministically by {@link AttentionBuilder} from data the extraction
     * layers already wrote — local, free, and incapable of hallucinating.
     */
    private void handleAttention(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "GET")) { methodNotAllowed(ex); return; }
        try {
            List<com.localfilebrain.deadline.MissingDocumentDetector.MissingDoc> missing =
                    com.localfilebrain.deadline.MissingDocumentDetector.detect(metadataStore.listAllSeries());
            AttentionBuilder.Result r = AttentionBuilder.build(
                    metadataStore.listTimeline(),
                    metadataStore.listDeadlines("PENDING"),
                    missing,
                    metadataStore.dismissedAttentionKeys(),
                    java.time.LocalDate.now());

            List<Map<String, Object>> items = new ArrayList<>(r.items().size());
            for (AttentionBuilder.Item it : r.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",        it.id());
                m.put("kind",      it.kind());
                m.put("bucket",    it.bucket());
                m.put("title",     it.title());
                m.put("detail",    it.detail());
                m.put("date",      it.date());
                m.put("daysUntil", it.daysUntil());
                m.put("fileName",  it.fileName());
                m.put("path",      it.path());
                m.put("docType",   it.docType());
                items.add(m);
            }
            sendJson(ex, 200, map("items", items, "counts", map(
                    "total", r.total(), "overdue", r.overdue(),
                    "dueToday", r.dueToday(), "missing", r.missing())));
        } catch (Exception e) {
            log.warn("/api/attention failed", e);
            sendError(ex, 500, "Failed to load attention items");
        }
    }

    /**
     * POST /api/attention/dismiss {id} — permanently clears one attention item
     * so the panel can't grow forever. Deadline-backed items ("deadline:&lt;n&gt;")
     * are dismissed on their own row (also clearing them from the Deadlines
     * tab); date/missing items are keyed into {@code attention_dismissed}.
     */
    private void handleAttentionDismiss(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!isMethod(ex, "POST")) { methodNotAllowed(ex); return; }
        try {
            Map<?, ?> body = readJson(ex);
            String id = strOrNull(body.get("id"));
            if (id == null || id.isBlank()) { sendError(ex, 400, "id is required"); return; }
            if (id.startsWith("deadline:")) {
                long deadlineId;
                try { deadlineId = Long.parseLong(id.substring("deadline:".length())); }
                catch (NumberFormatException e) { sendError(ex, 400, "bad deadline id"); return; }
                boolean ok = metadataStore.updateDeadline(
                        deadlineId, "DISMISSED", null, null, null, null, null);
                if (!ok) { sendError(ex, 404, "deadline not found"); return; }
            } else {
                metadataStore.dismissAttentionItem(id);
            }
            sendJson(ex, 200, map("dismissed", true));
        } catch (Exception e) {
            log.warn("/api/attention/dismiss failed", e);
            sendError(ex, 500, "Failed to dismiss attention item");
        }
    }

    private void handleDeadlines(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        try {
            String base = "/api/deadlines";
            String path = ex.getRequestURI().getPath();
            String id   = path.length() > base.length() ? path.substring(base.length() + 1) : "";
            if (id.endsWith("/")) id = id.substring(0, id.length() - 1);

            if (id.isEmpty()) {
                if (!isMethod(ex, "GET")) { methodNotAllowed(ex); return; }
                String status = queryParam(ex, "status");
                List<IndexMetadataStore.DeadlineRow> rows = metadataStore.listDeadlines(status);
                java.time.LocalDate today = java.time.LocalDate.now();

                List<Map<String, Object>> items = new ArrayList<>(rows.size());
                int overdue = 0, dueSoon = 0, upcoming = 0, noDate = 0, pending = 0, withReminder = 0;
                for (IndexMetadataStore.DeadlineRow r : rows) {
                    Map<String, Object> m = serializeDeadline(r, today);
                    items.add(m);
                    if ("PENDING".equals(r.status())) pending++;
                    if (r.reminderSet()) withReminder++;
                    switch (String.valueOf(m.get("bucket"))) {
                        case "OVERDUE"  -> overdue++;
                        case "DUE_SOON" -> dueSoon++;
                        case "UPCOMING" -> upcoming++;
                        case "NO_DATE"  -> noDate++;
                        default -> { }
                    }
                }
                sendJson(ex, 200, map(
                        "items",  items,
                        "counts", map(
                                "total",        rows.size(),
                                "pending",      pending,
                                "overdue",      overdue,
                                "dueSoon",      dueSoon,
                                "upcoming",     upcoming,
                                "noDate",       noDate,
                                "withReminder", withReminder)));
                return;
            }

            long deadlineId;
            try { deadlineId = Long.parseLong(id); }
            catch (NumberFormatException e) { sendError(ex, 400, "invalid deadline id"); return; }

            if (isMethod(ex, "DELETE")) {
                boolean ok = metadataStore.deleteDeadline(deadlineId);
                if (!ok) { sendError(ex, 404, "deadline not found"); return; }
                sendJson(ex, 200, map("deleted", true));
                return;
            }

            if (isMethod(ex, "POST")) {
                Map<?, ?> body = readJson(ex);
                String status      = strOrNull(body.get("status"));
                Boolean reminder   = body.get("reminderSet") instanceof Boolean b ? b : null;
                String title       = strOrNull(body.get("title"));
                String description = strOrNull(body.get("description"));
                String dueDate     = strOrNull(body.get("dueDate"));
                String recurring   = strOrNull(body.get("recurring"));
                if (status != null) {
                    String up = status.toUpperCase();
                    if (!up.equals("PENDING") && !up.equals("DONE") && !up.equals("DISMISSED")) {
                        sendError(ex, 400, "status must be PENDING, DONE, or DISMISSED");
                        return;
                    }
                }
                boolean ok = metadataStore.updateDeadline(
                        deadlineId, status, reminder, title, description, dueDate, recurring);
                if (!ok) { sendError(ex, 404, "deadline not found or nothing to update"); return; }
                sendJson(ex, 200, map("updated", true));
                return;
            }

            methodNotAllowed(ex);
        } catch (Exception e) {
            log.warn("/api/deadlines failed", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    /** Maps a stored deadline to JSON, adding computed daysUntil + bucket. */
    private Map<String, Object> serializeDeadline(IndexMetadataStore.DeadlineRow r,
                                                  java.time.LocalDate today) {
        Long daysUntil = null;
        String bucket  = "NO_DATE";
        if (r.dueDate() != null && !r.dueDate().isBlank()) {
            try {
                java.time.LocalDate due = java.time.LocalDate.parse(r.dueDate());
                long d = java.time.temporal.ChronoUnit.DAYS.between(today, due);
                daysUntil = d;
                bucket = d < 0 ? "OVERDUE" : d <= 7 ? "DUE_SOON" : "UPCOMING";
            } catch (Exception ignored) { /* leave as NO_DATE */ }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            r.id());
        m.put("path",          r.absolutePath());
        m.put("fileName",      r.fileName());
        m.put("title",         r.title());
        m.put("description",   r.description());
        m.put("dueDate",       r.dueDate());
        m.put("kind",          r.kind());
        m.put("confidence",    r.confidence());
        m.put("recurring",     r.recurring());
        m.put("status",        r.status());
        m.put("reminderSet",   r.reminderSet());
        m.put("sourceExcerpt", r.sourceExcerpt());
        m.put("daysUntil",     daysUntil);
        m.put("bucket",        bucket);
        return m;
    }

    private static String strOrNull(Object o) {
        return (o instanceof String s && !s.isBlank()) ? s.trim() : null;
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
                queryEngine = new QueryEngine(fresh, vectorStore, embeddingClient, tokenStore, metadataStore);
            }
        }
        return queryEngine;
    }

    /** Lazy chat-thread store, opened beside the index metadata DB. */
    private ChatStore chatStore() {
        if (chatStore != null) return chatStore;
        synchronized (this) {
            if (chatStore == null) {
                // Lives in the per-user data root (resolved by AppConfig), never
                // beside the working / install directory.
                chatStore = new ChatStore(config.getChatDbPath().toAbsolutePath());
            }
        }
        return chatStore;
    }

    /**
     * Resolves the conversation a query belongs to. Reuses the id the client
     * sent if it still exists; otherwise opens a fresh thread titled from the
     * first question. Returns the id to hand back to the UI.
     */
    // ─────────────────────────────────────────────────────────────────────────
    // Per-client isolation: scope resolution
    // ─────────────────────────────────────────────────────────────────────────

    private static final String UNASSIGNED = "__unassigned__";
    private static final String ALL_SCOPE  = "__all__";

    /** The outcome of resolving which client a question is about. */
    private record ScopeDecision(
            boolean clarify,
            java.util.Set<String> allowedPaths,   // null = search everything
            String clientId,                       // focus to persist (null/sentinel ok)
            String label,                          // "Answering about: X" (null when unscoped)
            List<Map<String, Object>> options,     // clarify chips
            String message) {                      // clarify question text
        static ScopeDecision unscoped() { return new ScopeDecision(false, null, null, null, null, null); }
        static ScopeDecision scoped(java.util.Set<String> paths, String clientId, String label) {
            return new ScopeDecision(false, paths, clientId, label, null, null);
        }
        static ScopeDecision clarify(List<Map<String, Object>> options, String message) {
            return new ScopeDecision(true, null, null, null, options, message);
        }
    }

    /**
     * Decides the retrieval scope for this turn and updates the conversation's
     * remembered client. Order: explicit UI/clarify override → automatic
     * resolution from the question + carried focus. Returns a clarify decision
     * when the target client is ambiguous or unspecified (never guesses).
     */
    private ScopeDecision resolveScope(Object clientIdRaw, String convId, String question) {
        List<IndexMetadataStore.Client> clients = metadataStore.listClients();
        if (clients.isEmpty()) return ScopeDecision.unscoped(); // feature dormant — behaves as before

        ChatStore cs = chatStore();
        String focus = cs.getFocus(convId);

        // 1. Explicit choice from the UI picker or a clarify chip.
        if (clientIdRaw instanceof String cid && !cid.isBlank()) {
            if (ALL_SCOPE.equals(cid)) { cs.setFocus(convId, null); return ScopeDecision.unscoped(); }
            if (UNASSIGNED.equals(cid)) {
                cs.setFocus(convId, UNASSIGNED);
                return ScopeDecision.scoped(unassignedPaths(), UNASSIGNED, "Personal / unfiled");
            }
            if (metadataStore.clientExists(cid)) {
                cs.setFocus(convId, cid);
                return ScopeDecision.scoped(new java.util.HashSet<>(metadataStore.pathsForClient(cid)),
                        cid, clientName(clients, cid));
            }
            // Unknown id → ignore and fall through to automatic resolution.
        }

        // 2. Automatic resolution. Only a real (still-existing) client counts as focus.
        String realFocus = (focus != null && metadataStore.clientExists(focus)) ? focus : null;
        ClientResolver.Resolution r = ClientResolver.resolve(question, realFocus, clients);
        switch (r.kind()) {
            case NONE -> {
                // Carry an explicit "Personal / unfiled" choice across plain follow-ups.
                if (UNASSIGNED.equals(focus)) {
                    return ScopeDecision.scoped(unassignedPaths(), UNASSIGNED, "Personal / unfiled");
                }
                // A general question RELEASES any stale client focus, so it can't
                // haunt the NEXT turn. Without this, a chat that touched client A
                // then asked something general kept A in focus, and a later bare
                // follow-up ("what's the penalty if they miss it?") wrongly
                // re-scoped to A and dead-ended (the AOC-4 bug).
                if (focus != null) cs.setFocus(convId, null);
                return ScopeDecision.unscoped();
            }
            case SCOPED -> {
                cs.setFocus(convId, r.clientId());
                return ScopeDecision.scoped(new java.util.HashSet<>(metadataStore.pathsForClient(r.clientId())),
                        r.clientId(), clientName(clients, r.clientId()));
            }
            case CLARIFY -> {
                // Duplicate registrations of the SAME entity ("Meridian Exports
                // Pvt Ltd" vs "Meridian Exports Private Limited") are not real
                // ambiguity — and their files are typically conflicted-out of
                // both, so scoping to either would find nothing. Answer
                // generally instead of interrogating the user.
                List<String> candidateNames = new ArrayList<>();
                for (String id : r.candidateIds()) candidateNames.add(clientName(clients, id));
                if (com.localfilebrain.client.ClientMatcher.sameEntityNames(candidateNames)) {
                    log.info("Clarify skipped: candidates {} are the same entity — answering generally",
                            candidateNames);
                    // The question named a (duplicate-registered) entity and we
                    // answered generally — release any prior, unrelated focus so it
                    // can't leak into the next follow-up.
                    if (focus != null) cs.setFocus(convId, null);
                    return ScopeDecision.unscoped();
                }
                return ScopeDecision.clarify(clarifyOptions(clients, r.candidateIds()), clarifyMessage());
            }
        }
        return ScopeDecision.unscoped();
    }

    /** Indexed files that belong to no client. */
    private java.util.Set<String> unassignedPaths() {
        java.util.Set<String> assigned = metadataStore.allAssignedPaths();
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String p : metadataStore.listIndexedPaths()) if (!assigned.contains(p)) out.add(p);
        return out;
    }

    private static String clientName(List<IndexMetadataStore.Client> clients, String id) {
        for (var c : clients) if (c.id().equals(id)) return c.name();
        return "this client";
    }

    private static String clarifyMessage() {
        return "That could be about more than one of your clients — which one do you mean?";
    }

    private List<Map<String, Object>> clarifyOptions(List<IndexMetadataStore.Client> clients, List<String> candidateIds) {
        List<Map<String, Object>> opts = new ArrayList<>();
        for (String id : candidateIds) opts.add(map("id", id, "name", clientName(clients, id)));
        opts.add(map("id", ALL_SCOPE, "name", "All documents"));
        return opts;
    }

    /** GET list · POST create · POST recompute · POST assign · POST {id} edit · DELETE {id}. */
    private void handleClients(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        try {
            String base = "/api/clients";
            String path = ex.getRequestURI().getPath();
            String sub  = path.length() > base.length() ? path.substring(base.length() + 1) : "";
            if (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);

            if (sub.isEmpty() && isMethod(ex, "GET")) { sendJson(ex, 200, map("clients", listClientsJson())); return; }

            if (sub.isEmpty() && isMethod(ex, "POST")) {
                Map<?, ?> body = readJson(ex);
                String name = String.valueOf(body.get("name"));
                String id = metadataStore.createClient(name);
                // Optional identifiers supplied at creation.
                Object ids = body.get("identifiers");
                if (ids instanceof List<?> list) for (Object v : list) metadataStore.addClientIdentifier(id, String.valueOf(v));
                recomputeMembership();
                sendJson(ex, 200, map("id", id));
                return;
            }

            if ("recompute".equals(sub) && isMethod(ex, "POST")) {
                MembershipEngine.Result r = recomputeMembership();
                sendJson(ex, 200, map("assigned", r.assigned(), "conflicted", r.conflicted(), "unmatched", r.unmatched()));
                return;
            }

            if ("assign".equals(sub) && isMethod(ex, "POST")) {
                Map<?, ?> body = readJson(ex);
                String p = (String) body.get("path");
                Object cidObj = body.get("clientId");
                if (p == null || p.isBlank()) { sendError(ex, 400, "path required"); return; }
                if (cidObj == null) { metadataStore.unassignFile(p); }
                else { metadataStore.assignFileToClient(p, String.valueOf(cidObj), true); } // manual = pinned
                sendJson(ex, 200, map("ok", true));
                return;
            }

            if ("suggestions".equals(sub) && isMethod(ex, "GET")) {
                List<EntitySuggester.Suggestion> sugg = EntitySuggester.suggest(
                        metadataStore.listAllEntities(), metadataStore.listClients(),
                        metadataStore.dismissedSuggestionKeys());
                List<Map<String, Object>> items = new ArrayList<>();
                for (var s : sugg) items.add(map(
                        "key", s.key(), "name", s.name(), "gstin", s.gstin(),
                        "pan", s.pan(), "fileCount", s.fileCount()));
                sendJson(ex, 200, map("suggestions", items));
                return;
            }

            // On-demand free/local detection of client identities (GSTIN/PAN)
            // across already-indexed files. Lets the user populate suggestions
            // without re-indexing or running the Pro deadline scan.
            if ("scan".equals(sub) && isMethod(ex, "POST")) {
                int found = new LocalEntityScanner(metadataStore, vectorStore).scanAll();
                List<EntitySuggester.Suggestion> sugg = EntitySuggester.suggest(
                        metadataStore.listAllEntities(), metadataStore.listClients(),
                        metadataStore.dismissedSuggestionKeys());
                sendJson(ex, 200, map("found", found, "suggestions", sugg.size()));
                return;
            }

            if ("accept".equals(sub) && isMethod(ex, "POST")) {
                Map<?, ?> body = readJson(ex);
                String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
                if (name == null || name.isBlank()) { sendError(ex, 400, "name required"); return; }
                String id = metadataStore.createClient(name); // name auto-added as identifier
                if (body.get("gstin") != null) metadataStore.addClientIdentifier(id, String.valueOf(body.get("gstin")));
                if (body.get("pan")   != null) metadataStore.addClientIdentifier(id, String.valueOf(body.get("pan")));
                // Caller can defer the (whole-library) recompute when accepting
                // many suggestions at once, then recompute a single time.
                if (!Boolean.FALSE.equals(body.get("recompute"))) recomputeMembership();
                sendJson(ex, 200, map("id", id));
                return;
            }

            if ("dismiss".equals(sub) && isMethod(ex, "POST")) {
                Map<?, ?> body = readJson(ex);
                String key = body.get("key") == null ? null : String.valueOf(body.get("key"));
                if (key == null || key.isBlank()) { sendError(ex, 400, "key required"); return; }
                metadataStore.dismissSuggestion(key);
                sendJson(ex, 200, map("ok", true));
                return;
            }

            // /api/clients/{id} — edit or delete.
            if (!sub.isEmpty()) {
                String id = sub;
                if (isMethod(ex, "DELETE")) {
                    metadataStore.deleteClient(id);
                    recomputeMembership();
                    sendJson(ex, 200, map("ok", true));
                    return;
                }
                if (isMethod(ex, "POST")) {
                    Map<?, ?> body = readJson(ex);
                    if (body.get("name") != null) metadataStore.renameClient(id, String.valueOf(body.get("name")));
                    if (body.get("addIdentifier") != null) metadataStore.addClientIdentifier(id, String.valueOf(body.get("addIdentifier")));
                    if (body.get("removeIdentifier") != null) metadataStore.removeClientIdentifier(id, String.valueOf(body.get("removeIdentifier")));
                    recomputeMembership();
                    sendJson(ex, 200, map("ok", true));
                    return;
                }
            }
            methodNotAllowed(ex);
        } catch (Exception e) {
            log.warn("/api/clients failed", e);
            sendError(ex, 500, e.getMessage());
        }
    }

    private MembershipEngine.Result recomputeMembership() {
        return new MembershipEngine(metadataStore, vectorStore).recomputeAll();
    }

    private List<Map<String, Object>> listClientsJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IndexMetadataStore.Client c : metadataStore.listClients()) {
            out.add(map(
                "id",          c.id(),
                "name",        c.name(),
                "identifiers", c.identifiers(),
                "fileCount",   metadataStore.pathsForClient(c.id()).size()));
        }
        return out;
    }

    private String resolveConversation(Object conversationIdRaw, String question) {
        ChatStore cs = chatStore();
        String convId = conversationIdRaw instanceof String s ? s : null;
        if (convId != null && cs.exists(convId)) return convId;
        return cs.createConversation(deriveTitle(question));
    }

    /** First line / ~6 words of the opening question, capped, as a thread title. */
    private static String deriveTitle(String question) {
        String t = question.strip().replaceAll("\\s+", " ");
        if (t.length() > 60) t = t.substring(0, 60).strip() + "…";
        return t.isEmpty() ? "New chat" : t;
    }

    /**
     * Rehydrates the engine with a thread's recent history and persists the
     * user turn, then runs {@code answerer}, persists the assistant turn, and
     * returns its result. Serialized so the shared engine's swapped-in history
     * stays consistent across concurrent requests.
     */
    private QueryEngine.QueryResult answerInConversation(
            String convId, String question,
            java.util.function.Function<QueryEngine, QueryEngine.QueryResult> answerer) {
        synchronized (queryLock) {
            ChatStore cs = chatStore();
            QueryEngine engine = getOrInitQueryEngine();
            engine.resetHistory();
            for (ChatStore.Exchange e : cs.getRecentExchanges(convId, 20)) {
                engine.addHistoryExchange(e.question(), e.answer());
            }
            cs.addMessage(convId, "user", question);
            QueryEngine.QueryResult result = answerer.apply(engine);
            cs.addMessage(convId, "assistant", result.answer(), sourcesToJson(result.sourceFiles()));
            return result;
        }
    }

    /** Serializes source chips for storage; null/failure → no stored sources. */
    private String sourcesToJson(Object sources) {
        if (sources == null) return null;
        try {
            return mapper.writeValueAsString(sources);
        } catch (Exception e) {
            log.debug("Could not serialize sources for persistence: {}", e.getMessage());
            return null;
        }
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
        Path configFile = config.getConfigFilePath();
        Files.createDirectories(configFile.toAbsolutePath().getParent());
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

    /** Sets a single key in {@code config.properties}, preserving the rest. */
    private void updateConfigProperty(String key, String value) throws IOException {
        Path configFile = config.getConfigFilePath();
        Files.createDirectories(configFile.toAbsolutePath().getParent());
        Properties props = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) { props.load(in); }
        }
        props.setProperty(key, value);
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
            // The index must FOLLOW the moved files: re-point metadata (incl.
            // cached summaries and LLM-extracted deadlines — never re-paid) and
            // the Lucene chunks, so source chips / Timeline / attention still
            // open the file at its new home. Best-effort per file; a healing
            // failure never undoes a successful move.
            for (var o : result.outcomes()) {
                if (o.status() == ReorgExecutionResult.Status.SUCCESS
                        && o.resolvedTo() != null) {
                    healMovedFile(o.from().toString(), o.resolvedTo().toString());
                }
            }
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
            // Mirror of the execute-side healing: an undone file moved from its
            // reorg destination (toPath) back to its original home (fromPath).
            for (var o : r.outcomes()) {
                if (o.status() == ReorgExecutionResult.Status.SUCCESS) {
                    healMovedFile(o.toPath(), o.fromPath());
                }
            }
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

    /**
     * Re-points every index record of a moved file at its new location: all
     * SQLite tables via {@link IndexMetadataStore#renamePath} (preserving the
     * cached summary, LLM-extracted deadlines, doc type, dates, client tag —
     * nothing is re-paid), and the Lucene chunks by re-adding them under the
     * new path (text is stored, so re-embedding is local and free). Best-effort:
     * a healing failure is logged, never surfaced as a move failure — the
     * startup rescan / watcher will reconcile eventually.
     */
    private void healMovedFile(String oldAbs, String newAbs) {
        try {
            String oldC = PathNormalizer.canonical(oldAbs);
            String newC = PathNormalizer.canonical(newAbs);
            if (oldC.equals(newC)) return;

            List<VectorStore.SearchResult> chunks = vectorStore.getChunksForFile(oldC);
            if (!chunks.isEmpty() && embeddingClient != null) {
                String newName = Paths.get(newC).getFileName().toString();
                int dot = newName.lastIndexOf('.');
                String ext = dot > 0 ? newName.substring(dot + 1).toLowerCase() : "";
                long mtime = 0L;
                try { mtime = Files.getLastModifiedTime(Paths.get(newC)).toMillis(); }
                catch (Exception ignored) { /* file may still be settling */ }

                List<String> texts = new ArrayList<>(chunks.size());
                for (var c : chunks) texts.add(c.text() == null ? "" : c.text());
                List<float[]> embeddings = embeddingClient.embedBatch(texts);

                List<com.localfilebrain.model.DocumentChunk> moved = new ArrayList<>(chunks.size());
                for (var c : chunks) {
                    moved.add(com.localfilebrain.model.DocumentChunk.builder()
                            .chunkId(java.util.UUID.randomUUID().toString())
                            .sourceFilePath(newC)
                            .fileName(newName)
                            .fileExtension(ext)
                            .chunkIndex(c.chunkIndex())
                            .totalChunks(chunks.size())
                            .text(c.text() == null ? "" : c.text())
                            .fileLastModifiedMs(mtime)
                            .pageStart(c.pageStart())
                            .pageEnd(c.pageEnd())
                            .build());
                }
                vectorStore.replaceBySourceFile(newC, moved, embeddings);
                vectorStore.deleteBySourceFile(oldC);
            }

            metadataStore.renamePath(oldC, newC);
            log.info("Reorg healing: index re-pointed {} -> {}", oldC, newC);
        } catch (Exception e) {
            log.warn("Reorg healing failed for {} -> {}: {}", oldAbs, newAbs, e.getMessage());
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
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Shelfbot-Token");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            // CORS preflight carries no custom headers — answer it before the
            // token gate so the browser is allowed to send the real request
            // (which DOES carry the token).
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        // Local-origin auth. /api/health stays open: it returns only liveness
        // (no user data) and the renderer + readiness probes hit it without a
        // token. Everything else must present the per-launch secret.
        if (localToken != null && !localToken.isBlank()
                && !"/api/health".equals(ex.getRequestURI().getPath())
                && !hasValidLocalToken(ex)) {
            sendError(ex, 403, "Forbidden");
            return true;
        }
        return false;
    }

    /** Constant-time check of the X-Shelfbot-Token header against the launch secret. */
    private boolean hasValidLocalToken(HttpExchange ex) {
        String supplied = ex.getRequestHeaders().getFirst("X-Shelfbot-Token");
        if (supplied == null) return false;
        return java.security.MessageDigest.isEqual(
                supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                localToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean isMethod(HttpExchange ex, String method) {
        return method.equalsIgnoreCase(ex.getRequestMethod());
    }

    private void methodNotAllowed(HttpExchange ex) throws IOException {
        sendError(ex, 405, "Method not allowed");
    }

    // Every legitimate request body here (a question, settings, a target dir) is
    // tiny. Cap the read so a malformed or hostile body can't OOM the backend.
    private static final int MAX_REQUEST_BODY_BYTES = 8 * 1024 * 1024;

    /** Thrown for client-side input errors so the route wrapper replies 400, not 500. */
    static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) { super(message); }
    }

    private Map<?, ?> readJson(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (body.length > MAX_REQUEST_BODY_BYTES) {
            throw new BadRequestException("Request body too large (max "
                    + MAX_REQUEST_BODY_BYTES + " bytes)");
        }
        try {
            return mapper.readValue(body, Map.class);
        } catch (Exception e) {
            throw new BadRequestException("Invalid JSON body");
        }
    }

    /** Reads a single decoded query-string parameter, or null if absent. */
    private static String queryParam(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getQuery();
        if (raw == null || raw.isEmpty()) return null;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i >= 0 ? pair.substring(0, i) : pair;
            if (k.equals(key)) {
                String v = i >= 0 ? pair.substring(i + 1) : "";
                return java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
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

    /** Snapshot of the in-flight deadline scan, streamed via {@code progress} on /api/deadlines/scan GET. */
    public static final class DeadlineProgress {
        final int processed;
        final int total;
        final int found;
        final String currentFile;
        DeadlineProgress(int processed, int total, int found, String currentFile) {
            this.processed = processed; this.total = total; this.found = found; this.currentFile = currentFile;
        }
    }

    /** Terminal state of the last deadline scan. */
    public static final class DeadlineScanState {
        final String stop;        // COMPLETE | PAUSED_QUOTA | NOT_SIGNED_IN | ERROR
        final String message;
        final int    filesScanned;
        final int    found;
        DeadlineScanState(String stop, String message, int filesScanned, int found) {
            this.stop = stop; this.message = message; this.filesScanned = filesScanned; this.found = found;
        }
    }

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

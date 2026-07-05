package com.localfilebrain;

import com.localfilebrain.api.ApiServer;
import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.embedding.EmbeddingClientFactory;
import com.localfilebrain.ingestion.FileWatcher;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IngestionPipeline;
import com.localfilebrain.query.QueryEngine;
import com.localfilebrain.storage.VectorStore;
import com.localfilebrain.util.PathNormalizer;
import com.localfilebrain.model.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String RESET   = "\u001B[0m";
    private static final String BOLD    = "\u001B[1m";
    private static final String DIM     = "\u001B[2m";
    private static final String CYAN    = "\u001B[36m";
    private static final String GREEN   = "\u001B[32m";
    private static final String YELLOW  = "\u001B[33m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String RED     = "\u001B[31m";
    private static final String WHITE   = "\u001B[97m";

    private static QueryEngine queryEngine;
    // Lazily-created shared vector store / embedding client for CLI mode.
    // The Electron-driven server mode owns its own; these only exist for
    // the interactive menu.
    private static VectorStore     cliVectorStore;
    private static EmbeddingClient cliEmbedding;

    private static synchronized VectorStore cliVectorStore(AppConfig config) {
        if (cliVectorStore == null) {
            cliVectorStore = new VectorStore(config.getVectorIndexPath());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (cliVectorStore != null) cliVectorStore.close();
            }));
        }
        return cliVectorStore;
    }

    private static synchronized EmbeddingClient cliEmbedding(AppConfig config) {
        if (cliEmbedding == null) {
            cliEmbedding = EmbeddingClientFactory.create(config);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (cliEmbedding != null) cliEmbedding.close();
            }));
        }
        return cliEmbedding;
    }

    public static void main(String[] args) throws InterruptedException {
        // ── Server mode ──────────────────────────────────────────────────────
        int  serverPort = 9876;
        boolean serverMode = false;
        for (int i = 0; i < args.length; i++) {
            if ("--server".equals(args[i])) serverMode = true;
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                try { serverPort = Integer.parseInt(args[i + 1]); } catch (NumberFormatException ignored) {}
            }
        }
        if (serverMode) {
            runServerMode(serverPort);
            return;
        }

        // ── Interactive CLI mode ─────────────────────────────────────────────
        migrateUserData();
        printBanner();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            printMenu();
            System.out.print(CYAN + "  → " + RESET);

            String input = scanner.nextLine().trim();

            try {
                switch (input) {
                    case "1" -> runIndex();
                    case "2" -> runStatus();
                    case "3" -> runQueryLoop(scanner);
                    case "4" -> {
                        System.out.println("\n" + DIM + "  Goodbye." + RESET + "\n");
                        scanner.close();
                        System.exit(0);
                    }
                    default -> System.out.println(RED + "  Invalid option." + RESET);
                }
            } catch (AppConfig.ConfigurationException e) {
                System.err.println(RED + "\n  [CONFIG ERROR] " + e.getMessage() + RESET);
                System.err.println(DIM + "  Make sure config.properties exists in the current directory." + RESET);
            } catch (Exception e) {
                log.error("Unexpected error: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Relocates pre-existing user data into the OS-standard per-user data
     * directory exactly once. Delegates to {@link com.localfilebrain.config.DataMigrator},
     * which is idempotent, verifies every copy before removing the originals,
     * and never overwrites newer data. Failures are logged and swallowed — the
     * app boots against the (possibly empty) new location and retries next launch.
     */
    private static void migrateUserData() {
        try {
            var target = com.localfilebrain.config.UserDataPaths.resolve();
            var result = com.localfilebrain.config.DataMigrator.migrateIfNeeded(
                    target, com.localfilebrain.config.DataMigrator.defaultLegacyCandidates());
            log.info("[startup] user-data migration: {} — {}", result.status(), result.message());
        } catch (Exception e) {
            log.warn("[startup] user-data migration skipped ({})", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Server mode — started by the Electron UI via --server flag
    // -------------------------------------------------------------------------

    private static void runServerMode(int port) throws InterruptedException {
        log.info("Starting ShelfBot API server on port {}", port);

        // One-time relocation of any pre-existing data (old working-dir store)
        // into the OS-standard per-user data directory. Idempotent and safe:
        // it verifies copies before touching the originals and never runs twice.
        // Must happen BEFORE any store is opened.
        migrateUserData();

        AppConfig config = AppConfig.load();

        IndexMetadataStore metadataStore;
        try {
            metadataStore = new IndexMetadataStore(config.getMetadataDbPath());
        } catch (Exception e) {
            log.warn("Could not open metadata store at configured path, using default: {}", e.getMessage());
            metadataStore = new IndexMetadataStore(
                    com.localfilebrain.config.UserDataPaths.resolve().metadataDb());
        }

        // Holds the JWT issued by the proxy after sign-in. The Electron
        // layer pushes the token here via POST /api/auth; OpenAI clients
        // read from it whenever they need an Authorization header.
        final AuthTokenStore tokenStore = new AuthTokenStore();

        // Single shared embedding client. Local-by-default; downloads the BGE
        // model on first launch (~130 MB) and caches it under ~/.shelfbot/models.
        // The OpenAI fallback path is still available via embedding.provider=openai.
        // Lazily — if the local model can't load (e.g. no network on first run)
        // we fall back to OpenAI so the app still works.
        EmbeddingClient embeddingClient = null;
        try {
            embeddingClient = EmbeddingClientFactory.create(config, tokenStore);
        } catch (Exception e) {
            log.warn("Could not load configured embedding backend ({}): {}", config.getEmbeddingProvider(), e.getMessage());
        }

        // Single, shared Lucene index. Owned by Main for the lifetime of the
        // server. Every consumer (ApiServer's DELETE handler, QueryEngine,
        // IngestionPipeline used by the watcher + manual jobs) is wired to
        // this same instance because Lucene only allows one writer per dir.
        final VectorStore vectorStore = new VectorStore(config.getVectorIndexPath());

        // If the embedding backend changed since the last run (e.g. user
        // flipped openai ↔ local), the existing vectors are in the wrong
        // dimension / vector space. Wipe and re-index.
        if (embeddingClient != null) {
            resetIfEmbeddingChanged(config, metadataStore, vectorStore, embeddingClient);
        }

        // Self-heal the metadata/vector-store drift case: if the metadata DB
        // claims files are indexed but the vector store is actually empty
        // (e.g. the user deleted the index dir, a migration recreated it,
        // or storage paths got moved), wipe the stale "INDEXED" rows so the
        // next scan re-processes everything instead of skipping.
        resetMetadataIfDrifted(metadataStore, vectorStore);

        // Collapse rows that point at the same physical file via different
        // path casings. Older builds didn't canonicalize path keys, so on
        // case-insensitive macOS APFS the Library could show "Others2/foo"
        // and "others2/foo" as two separate files.
        deduplicateCasePaths(metadataStore, vectorStore);

        // One-time deadline housekeeping on app open: drop past one-time
        // deadlines (no longer actionable) and roll past recurring ones forward
        // to their next occurrence, so both deadline lists show only what's ahead.
        try {
            String summary = com.localfilebrain.deadline.DeadlineMaintenance
                    .purgeAndRoll(metadataStore, java.time.LocalDate.now());
            log.info("[startup] deadline maintenance: {}", summary);
        } catch (Exception e) {
            log.warn("[startup] deadline maintenance failed: {}", e.getMessage());
        }

        final EmbeddingClient finalEmbedding = embeddingClient;

        // QueryEngine requires a valid OpenAI key (for the answer LLM, not
        // necessarily embeddings); it may not be set yet — ApiServer
        // initialises it lazily on the first query request.
        QueryEngine queryEngine = null;
        try {
            queryEngine = new QueryEngine(config, vectorStore, finalEmbedding, tokenStore, metadataStore);
        } catch (Exception e) {
            log.warn("QueryEngine not ready ({}). It will be initialised on first query.", e.getMessage());
        }

        final IndexMetadataStore finalStore = metadataStore;
        try {
            ApiServer server = new ApiServer(port, config, finalStore, vectorStore, finalEmbedding, tokenStore, queryEngine);
            server.start();

            // Signal to Electron that the server is ready (read from stdout)
            System.out.println("SHELFBOT_SERVER_READY:" + port);
            System.out.flush();

            // Live file watcher — keeps the index in sync as the user edits
            // files. Skips silently if the embedding/llm config isn't ready;
            // the user can still re-index manually after fixing config.
            final FileWatcher watcher = tryStartWatcher(config, finalStore, vectorStore, finalEmbedding);

            // Catch files that were modified while the app was closed. The
            // live FileWatcher only sees events that happen AFTER startup,
            // so without this pass a doc edited overnight would still serve
            // a stale summary from the cache (its summary row matches the
            // stored content_hash, which itself is now out of date).
            //
            // pipeline.run() is idempotent and cheap when nothing changed
            // (FileScanner short-circuits on timestamp match). Anything it
            // does re-process goes through metadataStore.upsert, which
            // calls deleteSummary — so the summary cache is invalidated
            // automatically for exactly the files that need it.
            runStartupReindex(config, finalStore, vectorStore, finalEmbedding);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (watcher != null) watcher.close();
                server.stop();
                vectorStore.close();
                if (finalEmbedding != null) finalEmbedding.close();
                finalStore.close();
            }));

            // Block the main thread — server runs on daemon threads.
            Thread.currentThread().join();

        } catch (Exception e) {
            log.error("Failed to start API server: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Constructs the {@link FileWatcher} if the configuration is complete
     * enough (OpenAI key present, embedding client constructible). Any
     * failure is logged and swallowed — the API server still runs, and
     * the user can re-index manually via the UI.
     */
    /**
     * Runs a background scan after server start that picks up files
     * modified, added, or deleted while the app was closed and re-indexes
     * them. Re-indexing fires {@code metadataStore.upsert}, which in turn
     * deletes the stale summary cache row for that file — so subsequent
     * /api/files/summary requests regenerate from fresh chunks.
     *
     * Daemon thread so it never blocks shutdown; failures are logged but
     * never crash the server (the user can still index manually).
     */
    private static void runStartupReindex(AppConfig config,
                                          IndexMetadataStore meta,
                                          VectorStore vec,
                                          EmbeddingClient embed) {
        if (embed == null) {
            log.warn("[startup-rescan] skipped — embedding client unavailable");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                // Small grace period so the HTTP server has a quiet startup
                // window — otherwise the first UI requests race with a busy
                // indexing thread that's reading every root recursively.
                Thread.sleep(2_000);
                IngestionPipeline pipeline = new IngestionPipeline(config, meta, vec, embed);
                log.info("[startup-rescan] checking for changes since the app was last open");
                var result = pipeline.run();
                if (result.getFilesProcessed() > 0 || result.getFilesFailed() > 0) {
                    log.info("[startup-rescan] re-indexed {} file(s), {} failed — summary cache invalidated for those rows",
                            result.getFilesProcessed(), result.getFilesFailed());
                    // Refresh client membership so changed/new files are re-filed
                    // to the right client (pinned ones preserved). No-op if no clients.
                    if (result.getFilesProcessed() > 0 && meta.countClients() > 0) {
                        new com.localfilebrain.client.MembershipEngine(meta, vec).recomputeAll();
                        log.info("[startup-rescan] client membership refreshed");
                    }
                } else {
                    log.info("[startup-rescan] no changes detected ({} files scanned, all up to date)",
                            result.getTotalFilesScanned());
                }
                // Free, local backfill so existing libraries get the new
                // document-type chips (Library) and obligation-date Timeline
                // WITHOUT a re-index. Both skip files already done, so this is a
                // no-op once everything's classified/scanned. No LLM, no cost.
                try { com.localfilebrain.ingestion.DocumentTypeClassifier.classifyAll(meta, vec); }
                catch (Exception e) { log.warn("[startup] doc-type backfill failed: {}", e.getMessage()); }
                try { new com.localfilebrain.timeline.LocalDateScanner(meta, vec).scanAll(); }
                catch (Exception e) { log.warn("[startup] date backfill failed: {}", e.getMessage()); }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[startup-rescan] failed: {}", e.getMessage());
            }
        }, "shelfbot-startup-rescan");
        t.setDaemon(true);
        t.start();
    }

    private static FileWatcher tryStartWatcher(AppConfig config,
                                               IndexMetadataStore store,
                                               VectorStore vectorStore,
                                               EmbeddingClient embeddingClient) {
        try {
            IngestionPipeline pipeline = new IngestionPipeline(config, store, vectorStore, embeddingClient);
            // Live-dropped/edited files get the SAME free local enrichment as a
            // full index run — doc type (Library chips), obligation + primary
            // dates (Timeline / attention / period questions), entity identity
            // (client suggestions), and client re-tagging. Each pass is
            // incremental (skips already-done files), so a single watcher event
            // only ever processes the file that just changed.
            com.localfilebrain.client.MembershipEngine membership =
                    new com.localfilebrain.client.MembershipEngine(store, vectorStore);
            pipeline.setPostIndexHook(path -> {
                try { com.localfilebrain.ingestion.DocumentTypeClassifier.classifyAll(store, vectorStore); }
                catch (Exception e) { log.debug("[watcher] doc-type pass failed: {}", e.getMessage()); }
                try { new com.localfilebrain.timeline.LocalDateScanner(store, vectorStore).scanAll(); }
                catch (Exception e) { log.debug("[watcher] date pass failed: {}", e.getMessage()); }
                try { new com.localfilebrain.client.LocalEntityScanner(store, vectorStore).scanAll(); }
                catch (Exception e) { log.debug("[watcher] entity pass failed: {}", e.getMessage()); }
                if (store.countClients() > 0) membership.recomputeFile(path);
            });
            FileWatcher watcher = new FileWatcher(config, pipeline);
            watcher.start();
            return watcher;
        } catch (Exception e) {
            log.warn("File watcher not started ({}). Manual re-indexing still works.", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Banner
    // -------------------------------------------------------------------------

    private static void printBanner() {
        System.out.println();
        System.out.println(CYAN + BOLD + "   ███████╗██╗  ██╗███████╗██╗     ███████╗██████╗  ██████╗ ████████╗" + RESET);
        System.out.println(CYAN + BOLD + "   ██╔════╝██║  ██║██╔════╝██║     ██╔════╝██╔══██╗██╔═══██╗╚══██╔══╝" + RESET);
        System.out.println(CYAN + BOLD + "   ███████╗███████║█████╗  ██║     █████╗  ██████╔╝██║   ██║   ██║   " + RESET);
        System.out.println(CYAN + BOLD + "   ╚════██║██╔══██║██╔══╝  ██║     ██╔══╝  ██╔══██╗██║   ██║   ██║   " + RESET);
        System.out.println(CYAN + BOLD + "   ███████║██║  ██║███████╗███████╗██║     ██████╔╝╚██████╔╝   ██║   " + RESET);
        System.out.println(CYAN + BOLD + "   ╚══════╝╚═╝  ╚═╝╚══════╝╚══════╝╚═╝     ╚═════╝  ╚═════╝    ╚═╝  " + RESET);
        System.out.println();
        System.out.println(DIM + "              Ask anything. Your files have the answer." + RESET);
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Menu
    // -------------------------------------------------------------------------

    private static void printMenu() {
        System.out.println();
        System.out.println(DIM + "  ──────────────────────────────" + RESET);
        System.out.println(WHITE + "  " + BOLD + "1" + RESET + WHITE + "  Index files" + RESET);
        System.out.println(WHITE + "  " + BOLD + "2" + RESET + WHITE + "  Status" + RESET);
        System.out.println(WHITE + "  " + BOLD + "3" + RESET + WHITE + "  Query" + RESET);
        System.out.println(WHITE + "  " + BOLD + "4" + RESET + WHITE + "  Exit" + RESET);
        System.out.println(DIM + "  ──────────────────────────────" + RESET);
    }

    // -------------------------------------------------------------------------
    // Index
    // -------------------------------------------------------------------------

    private static void runIndex() {
        System.out.println();
        AppConfig config = AppConfig.load();
        try (IndexMetadataStore metadataStore = new IndexMetadataStore(config.getMetadataDbPath())) {
            VectorStore     vec       = cliVectorStore(config);
            EmbeddingClient embedding = cliEmbedding(config);
            resetIfEmbeddingChanged(config, metadataStore, vec, embedding);
            resetMetadataIfDrifted(metadataStore, vec);
            IngestionPipeline pipeline = new IngestionPipeline(config, metadataStore, vec, embedding);
            pipeline.run();
        }
    }

    /**
     * If the embedding backend has changed since the index was last written,
     * the existing vectors are in the wrong space (and usually the wrong
     * dimension — 1536 for OpenAI vs 384 for local). Wipe the Lucene index
     * AND clear the INDEXED metadata rows so the next scan re-embeds
     * everything with the new model.
     *
     * We persist the active model id in a tiny {@code .embedding-meta}
     * marker file next to the index. First-run writes it; subsequent runs
     * compare and act.
     */
    private static void resetIfEmbeddingChanged(AppConfig config,
                                                IndexMetadataStore meta,
                                                VectorStore vec,
                                                EmbeddingClient embedding) {
        Path indexDir   = config.getVectorIndexPath();
        Path markerFile = indexDir.resolve(".embedding-meta");
        String current  = embedding.modelId();
        String previous = null;
        try {
            if (java.nio.file.Files.exists(markerFile)) {
                previous = java.nio.file.Files.readString(markerFile).trim();
            }
        } catch (Exception e) {
            log.warn("Could not read embedding marker ({}); treating as first run.", e.getMessage());
        }

        if (previous == null) {
            // No marker yet. If the index already has vectors, they were
            // written by an older build of the app that didn't track the
            // embedding model — we can't safely query them with the current
            // model (likely different dimension / vector space). Wipe so
            // the next scan re-embeds with the active model.
            int existing = vec.count();
            if (existing > 0) {
                log.warn("Existing index has {} vectors but no embedding marker. "
                      + "Wiping for safety (pre-marker build); files will be re-embedded with '{}'.",
                        existing, current);
                vec.deleteAll();
                meta.clearAllIndexedRecords();
            }
            writeMarker(markerFile, current);
            return;
        }
        if (previous.equals(current)) return;

        log.warn("Embedding backend changed: '{}' → '{}'. Wiping Lucene index "
               + "and clearing INDEXED metadata so every file is re-embedded.", previous, current);
        try {
            // Drop every doc in the index. Safer than nuking the directory
            // because the shared writer is open inside this process.
            int before = vec.count();
            vec.deleteAll();
            log.info("Cleared {} vectors from the index.", before);
        } catch (Exception e) {
            log.error("Failed to clear vector store on embedding change: {}", e.getMessage(), e);
        }
        meta.clearAllIndexedRecords();
        writeMarker(markerFile, current);
    }

    private static void writeMarker(Path markerFile, String content) {
        try {
            java.nio.file.Files.createDirectories(markerFile.getParent());
            java.nio.file.Files.writeString(markerFile, content);
        } catch (Exception e) {
            log.warn("Could not write embedding marker '{}': {}", markerFile, e.getMessage());
        }
    }

    /**
     * Removes duplicate metadata rows that differ only in path casing on
     * case-insensitive filesystems. For each set of paths that canonicalize
     * to the same on-disk path:
     *   - if one row already uses the canonical path, keep it and drop the rest;
     *   - if none do, keep the first row, drop the rest — the surviving row's
     *     chunks remain valid until the next watcher/scan re-keys them.
     *
     * Each "drop" removes the row's chunks from Lucene too so the index
     * doesn't keep stale duplicates.
     */
    private static void deduplicateCasePaths(IndexMetadataStore meta, VectorStore vec) {
        List<FileRecord> all = meta.listIndexedFilesBySizeDesc();
        if (all.isEmpty()) return;

        Map<String, List<FileRecord>> byCanonical = new LinkedHashMap<>();
        for (FileRecord r : all) {
            String canonical = PathNormalizer.canonical(r.getAbsolutePath());
            byCanonical.computeIfAbsent(canonical, k -> new java.util.ArrayList<>()).add(r);
        }

        int removed = 0;
        for (Map.Entry<String, List<FileRecord>> e : byCanonical.entrySet()) {
            List<FileRecord> group = e.getValue();
            if (group.size() < 2) continue;

            String canonical = e.getKey();
            FileRecord keep = group.stream()
                    .filter(r -> canonical.equals(r.getAbsolutePath()))
                    .findFirst()
                    .orElse(group.get(0));

            for (FileRecord r : group) {
                if (r == keep) continue;
                try { vec.deleteBySourceFile(r.getAbsolutePath()); }
                catch (Exception ex) {
                    log.warn("dedup: failed to drop chunks for '{}': {}", r.getAbsolutePath(), ex.getMessage());
                }
                meta.delete(r.getAbsolutePath());
                removed++;
            }
        }

        if (removed > 0) {
            log.warn("dedup: removed {} duplicate metadata row(s) for case-only path variants", removed);
        }
    }

    /**
     * Detects the "metadata says indexed, vector store is empty" drift state
     * and resets the metadata so the next scan re-processes every file.
     * No-op when the two stores are already consistent.
     */
    private static void resetMetadataIfDrifted(IndexMetadataStore meta, VectorStore vec) {
        int filesIndexed = meta.countIndexed();
        int vectorChunks = vec.count();
        if (filesIndexed > 0 && vectorChunks == 0) {
            log.warn("Drift detected: metadata claims {} indexed files but vector store is empty. "
                   + "Clearing metadata so files get re-indexed.", filesIndexed);
            meta.clearAllIndexedRecords();
        }
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    private static void runStatus() {
        System.out.println();
        AppConfig config = AppConfig.load();
        try (IndexMetadataStore metadataStore = new IndexMetadataStore(config.getMetadataDbPath())) {
            int count = metadataStore.countIndexed();
            System.out.println(DIM + "  ──────────────────────────────" + RESET);
            System.out.printf("  " + CYAN + "%-14s" + RESET + " %s%n", "Files indexed", BOLD + count + RESET);
            System.out.printf("  " + CYAN + "%-14s" + RESET + " %s%n", "Metadata DB",   config.getMetadataDbPath().toAbsolutePath());
            var roots = config.getFilesRootPaths();
            System.out.printf("  " + CYAN + "%-14s" + RESET + " %s%n", "Files roots",   roots.get(0).toAbsolutePath());
            for (int i = 1; i < roots.size(); i++) {
                System.out.printf("  " + DIM + "%-14s" + RESET + " %s%n", "", roots.get(i).toAbsolutePath());
            }
            System.out.println(DIM + "  ──────────────────────────────" + RESET);
        }
    }

    // -------------------------------------------------------------------------
    // Query loop
    // -------------------------------------------------------------------------

    private static void runQueryLoop(Scanner scanner) {
        System.out.println();
        AppConfig config = AppConfig.load();

        if (queryEngine == null) {
            queryEngine = new QueryEngine(config, cliVectorStore(config), cliEmbedding(config));
            System.out.println(DIM + "  Ready  ·  conversation history on (last 5 exchanges)" + RESET);
        }

        System.out.println(DIM + "  'exit' → menu   'clear' → reset history" + RESET);
        System.out.println();

        while (true) {
            System.out.print(GREEN + BOLD + "  you   " + RESET + WHITE + "› " + RESET);
            String question = scanner.nextLine().trim();

            if (question.isBlank()) continue;

            if (question.equalsIgnoreCase("exit")) {
                System.out.println(DIM + "\n  Returning to menu...\n" + RESET);
                break;
            }

            if (question.equalsIgnoreCase("clear")) {
                queryEngine.clearHistory();
                System.out.println(DIM + "  History cleared.\n" + RESET);
                continue;
            }

            AtomicBoolean done = new AtomicBoolean(false);
            Thread spinner = new Thread(() -> showSpinner(done));
            spinner.setDaemon(true);
            spinner.start();

            try {
                QueryEngine.QueryResult result = queryEngine.query(question);

                done.set(true);
                spinner.join();
                System.out.print("\r\033[K");

                System.out.println();
                System.out.println(MAGENTA + BOLD + "  shelf " + RESET + WHITE + "› " + RESET + result.answer());

                if (result.found() && !result.sourceFiles().isEmpty()) {
                    System.out.println();
                    System.out.print(DIM + "  sources  ");
                    for (QueryEngine.Source src : result.sourceFiles()) {
                        System.out.print(CYAN + src.fileName() + RESET + DIM + "  ");
                    }
                    System.out.println(RESET);
                }

                System.out.println();

            } catch (Exception e) {
                done.set(true);
                try { spinner.join(); } catch (InterruptedException ignored) {}
                System.out.print("\r\033[K");
                System.out.println(RED + "\n  [ERROR] " + e.getMessage() + RESET);
                log.error("Query failed", e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Spinner
    // -------------------------------------------------------------------------

    private static void showSpinner(AtomicBoolean done) {
        String[] frames = { "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏" };
        String[] labels = { "reading files" };
        int i = 0;
        int labelIdx = 0;
        int labelTick = 0;

        while (!done.get()) {
            String frame = frames[i % frames.length];
            String label = labels[labelIdx];
            System.out.print("\r  " + CYAN + frame + RESET + DIM + "  " + label + "..." + RESET);
            System.out.flush();
            i++;
            labelTick++;
            if (labelTick >= 8) {
                labelTick = 0;
                labelIdx = (labelIdx + 1) % labels.length;
            }
            try { Thread.sleep(80); } catch (InterruptedException e) { break; }
        }
    }
}

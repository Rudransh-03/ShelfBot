package com.localfilebrain;

import com.localfilebrain.api.ApiServer;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.ingestion.FileWatcher;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IngestionPipeline;
import com.localfilebrain.query.QueryEngine;
import com.localfilebrain.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
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
    // Lazily-created shared vector store for CLI mode. The Electron-driven
    // server mode owns its own; this one only exists for the interactive menu.
    private static VectorStore cliVectorStore;

    private static synchronized VectorStore cliVectorStore(AppConfig config) {
        if (cliVectorStore == null) {
            cliVectorStore = new VectorStore(config.getVectorIndexPath());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (cliVectorStore != null) cliVectorStore.close();
            }));
        }
        return cliVectorStore;
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

    // -------------------------------------------------------------------------
    // Server mode — started by the Electron UI via --server flag
    // -------------------------------------------------------------------------

    private static void runServerMode(int port) throws InterruptedException {
        log.info("Starting ShelfBot API server on port {}", port);

        AppConfig config = AppConfig.load();

        IndexMetadataStore metadataStore;
        try {
            metadataStore = new IndexMetadataStore(config.getMetadataDbPath());
        } catch (Exception e) {
            log.warn("Could not open metadata store at configured path, using default: {}", e.getMessage());
            metadataStore = new IndexMetadataStore(Paths.get("shelfbot-metadata.db"));
        }

        // Single, shared Lucene index. Owned by Main for the lifetime of the
        // server. Every consumer (ApiServer's DELETE handler, QueryEngine,
        // IngestionPipeline used by the watcher + manual jobs) is wired to
        // this same instance because Lucene only allows one writer per dir.
        final VectorStore vectorStore = new VectorStore(config.getVectorIndexPath());

        // Self-heal the metadata/vector-store drift case: if the metadata DB
        // claims files are indexed but the vector store is actually empty
        // (e.g. the user deleted the index dir, a migration recreated it,
        // or storage paths got moved), wipe the stale "INDEXED" rows so the
        // next scan re-processes everything instead of skipping.
        resetMetadataIfDrifted(metadataStore, vectorStore);

        // QueryEngine requires a valid OpenAI key; it may not be set yet —
        // ApiServer initialises it lazily on the first query request.
        QueryEngine queryEngine = null;
        try {
            queryEngine = new QueryEngine(config, vectorStore);
        } catch (Exception e) {
            log.warn("QueryEngine not ready ({}). It will be initialised on first query.", e.getMessage());
        }

        final IndexMetadataStore finalStore = metadataStore;
        try {
            ApiServer server = new ApiServer(port, config, finalStore, vectorStore, queryEngine);
            server.start();

            // Signal to Electron that the server is ready (read from stdout)
            System.out.println("SHELFBOT_SERVER_READY:" + port);
            System.out.flush();

            // Live file watcher — keeps the index in sync as the user edits
            // files. Requires a working OpenAI key (for re-embedding changed
            // files); if absent, we skip the watcher and the user can still
            // re-index manually after configuring the key in Settings.
            final FileWatcher watcher = tryStartWatcher(config, finalStore, vectorStore);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (watcher != null) watcher.close();
                server.stop();
                // Close the shared store before the metadata DB so any final
                // commit lands before we tear down dependencies it doesn't
                // actually have, but ordering here is defensive anyway.
                vectorStore.close();
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
    private static FileWatcher tryStartWatcher(AppConfig config, IndexMetadataStore store, VectorStore vectorStore) {
        try {
            IngestionPipeline pipeline = new IngestionPipeline(config, store, vectorStore);
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
            VectorStore vec = cliVectorStore(config);
            resetMetadataIfDrifted(metadataStore, vec);
            IngestionPipeline pipeline = new IngestionPipeline(config, metadataStore, vec);
            pipeline.run();
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
            queryEngine = new QueryEngine(config, cliVectorStore(config));
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

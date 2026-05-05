package com.localfilebrain;

import com.localfilebrain.config.AppConfig;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IngestionPipeline;
import com.localfilebrain.query.QueryEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public static void main(String[] args) {
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
            IngestionPipeline pipeline = new IngestionPipeline(config, metadataStore);
            pipeline.run();
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
            System.out.printf("  " + CYAN + "%-14s" + RESET + " %s%n", "Files root",    config.getFilesRootPath().toAbsolutePath());
//            System.out.printf("  " + CYAN + "%-14s" + RESET + " %s%n", "ChromaDB",      config.getChromaDbUrl());
//            System.out.printf("  " + CYAN + "%-14s" + RESET + " %s%n", "Collection",    config.getChromaDbCollection());
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
            queryEngine = new QueryEngine(config);
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
                    for (String src : result.sourceFiles()) {
                        System.out.print(CYAN + src + RESET + DIM + "  ");
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

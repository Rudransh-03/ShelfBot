package com.localfilebrain.ingestion;

import com.localfilebrain.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Live file-system watcher that keeps the index in sync with the user's
 * configured root folders without any manual re-indexing.
 *
 * Design notes:
 *
 *   • Uses the JDK's built-in {@link WatchService}. On macOS the JDK falls back
 *     to a polling implementation with ~10s latency; on Linux/Windows it uses
 *     the native inotify / ReadDirectoryChangesW APIs and reacts within ms.
 *     A future enhancement can swap this for a library like methvin's
 *     directory-watcher to get FSEvents-quality latency on macOS too.
 *
 *   • WatchService does not recurse. We walk each root once at startup and
 *     register every subdirectory. New directories created later are
 *     registered on-the-fly when we see their {@code ENTRY_CREATE} event.
 *
 *   • Events are debounced per-path (~1.5s) so editor save storms — and the
 *     common "atomic save" pattern of delete-then-create — collapse into a
 *     single re-index, and the dispatcher resolves the final state by
 *     checking {@link Files#exists(Path, java.nio.file.LinkOption...)} at
 *     fire time.
 *
 *   • On {@code OVERFLOW} (the OS dropped events under heavy load) we
 *     re-walk only that subtree and rely on {@link IngestionPipeline#indexOne}
 *     being idempotent via the metadata store's timestamp + hash check.
 */
public final class FileWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);

    private static final long DEBOUNCE_MS = 1_500;

    private final AppConfig         config;
    private final IngestionPipeline pipeline;
    private final Set<String>       supportedExtensions;

    private WatchService                  watchService;
    private Thread                        watcherThread;
    private ScheduledExecutorService      debouncer;

    private final Map<WatchKey, Path>             keyToDir   = new HashMap<>();
    private final Map<Path, ScheduledFuture<?>>   pending    = new ConcurrentHashMap<>();

    private volatile boolean running;

    public FileWatcher(AppConfig config, IngestionPipeline pipeline) {
        this.config              = config;
        this.pipeline            = pipeline;
        this.supportedExtensions = Set.of(config.getSupportedExtensions());
    }

    /**
     * Walks every configured root, registers every directory with the
     * WatchService, then starts the background poll thread. Failures on
     * any single root are logged and skipped — one inaccessible folder
     * shouldn't take down the watcher for the others.
     */
    public synchronized void start() throws IOException {
        if (running) return;

        this.watchService = FileSystems.getDefault().newWatchService();
        this.debouncer    = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shelfbot-watcher-debounce");
            t.setDaemon(true);
            return t;
        });

        int registeredDirs = 0;
        for (Path root : config.getFilesRootPaths()) {
            try {
                registeredDirs += registerRecursive(root);
            } catch (IOException e) {
                log.warn("[watcher] could not register root '{}': {}", root, e.getMessage());
            }
        }

        running = true;

        this.watcherThread = new Thread(this::pollLoop, "shelfbot-watcher");
        this.watcherThread.setDaemon(true);
        this.watcherThread.start();

        log.info("[watcher] started — watching {} director{} across {} root(s)",
                registeredDirs, registeredDirs == 1 ? "y" : "ies",
                config.getFilesRootPaths().size());
    }

    /**
     * Cleanly shuts the watcher down. Outstanding debounced re-indexes are
     * cancelled; the metadata store / Chroma stay consistent because
     * {@link IngestionPipeline#indexOne} is idempotent on next start.
     */
    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;

        try { if (watchService != null) watchService.close(); }
        catch (Exception ignored) {}

        if (debouncer != null) {
            pending.values().forEach(f -> f.cancel(false));
            pending.clear();
            debouncer.shutdownNow();
        }

        if (watcherThread != null) {
            try { watcherThread.join(2_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        log.info("[watcher] stopped");
    }

    // ── Recursive registration ──────────────────────────────────────────────

    /**
     * Registers {@code dir} and every non-skipped subdirectory below it
     * with the WatchService. Returns the total number of directories
     * registered (for the startup log line).
     */
    private int registerRecursive(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;

        int[] count = { 0 };
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                String name = d.getFileName() == null ? "" : d.getFileName().toString();
                if (!d.equals(dir) && (name.startsWith(".") || FileScanner.SKIP_DIRECTORIES.contains(name))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                try {
                    registerOne(d);
                    count[0]++;
                } catch (IOException e) {
                    log.debug("[watcher] could not register '{}': {}", d, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private void registerOne(Path dir) throws IOException {
        WatchKey key = dir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.OVERFLOW
        );
        keyToDir.put(key, dir);
    }

    // ── Poll loop ───────────────────────────────────────────────────────────

    private void pollLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                return;
            }

            Path dir = keyToDir.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    log.warn("[watcher] OVERFLOW on '{}' — re-walking subtree", dir);
                    debouncer.schedule(() -> recoverFromOverflow(dir), 200, TimeUnit.MILLISECONDS);
                    continue;
                }

                Path name    = (Path) event.context();
                Path changed = dir.resolve(name);

                handleEvent(kind, changed);
            }

            boolean stillValid = key.reset();
            if (!stillValid) {
                keyToDir.remove(key);
                // The directory was deleted; its files would have produced
                // DELETE events that we already debounced.
            }
        }
    }

    private void handleEvent(WatchEvent.Kind<?> kind, Path changed) {
        // 1) If it's a directory being created, register it so we see its files too.
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
            String name = changed.getFileName().toString();
            if (name.startsWith(".") || FileScanner.SKIP_DIRECTORIES.contains(name)) return;
            try {
                int added = registerRecursive(changed);
                if (added > 0) log.debug("[watcher] registered new directory '{}' (+{} subdirs)", changed, added);
            } catch (IOException e) {
                log.warn("[watcher] could not register new dir '{}': {}", changed, e.getMessage());
            }
            // Also schedule indexing of any files inside the new directory.
            walkAndDebounce(changed);
            return;
        }

        // 2) Skip files we don't index (wrong extension, hidden, etc.).
        String fileName = changed.getFileName() == null ? "" : changed.getFileName().toString();
        if (fileName.startsWith(".")) return;
        if (!supportedExtensions.contains(extensionOf(fileName))) return;

        debounce(changed);
    }

    private void walkAndDebounce(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String n = file.getFileName().toString();
                    if (!n.startsWith(".") && supportedExtensions.contains(extensionOf(n))) {
                        debounce(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("[watcher] walk failed for '{}': {}", dir, e.getMessage());
        }
    }

    /**
     * Schedule a re-index of {@code file} after the debounce window. If
     * another event fires for the same path before the window expires, we
     * cancel and reschedule — collapsing editor save storms into one
     * dispatch.
     */
    private void debounce(Path file) {
        ScheduledFuture<?> prev = pending.remove(file);
        if (prev != null) prev.cancel(false);

        ScheduledFuture<?> next = debouncer.schedule(
                () -> dispatch(file),
                DEBOUNCE_MS,
                TimeUnit.MILLISECONDS
        );
        pending.put(file, next);
    }

    /**
     * Called from the debouncer once a file has been idle for the debounce
     * window. We resolve the file's *current* state at this moment — which
     * cleanly handles atomic-save (delete + create) and rapid create-then-
     * delete sequences.
     */
    private void dispatch(Path file) {
        pending.remove(file);
        try {
            if (Files.exists(file) && Files.isRegularFile(file)) {
                pipeline.indexOne(file);
            } else {
                pipeline.removeFile(file);
            }
        } catch (Exception e) {
            log.warn("[watcher] dispatch failed for '{}': {}", file, e.getMessage());
        }
    }

    private void recoverFromOverflow(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    String n = d.getFileName() == null ? "" : d.getFileName().toString();
                    if (!d.equals(dir) && (n.startsWith(".") || FileScanner.SKIP_DIRECTORIES.contains(n))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String n = file.getFileName().toString();
                    if (!n.startsWith(".") && supportedExtensions.contains(extensionOf(n))) {
                        debounce(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[watcher] overflow recovery walk failed for '{}': {}", dir, e.getMessage());
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }
}

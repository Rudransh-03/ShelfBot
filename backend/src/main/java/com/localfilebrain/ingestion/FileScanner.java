package com.localfilebrain.ingestion;

import com.localfilebrain.config.AppConfig;
import com.localfilebrain.util.FileHashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recursively scans a root directory for files to ingest.
 *
 * Change detection strategy (fast + accurate):
 * 1. Check last-modified timestamp first — zero I/O, just a SQLite lookup.
 *    If timestamp unchanged since last index → skip immediately, no file reading at all.
 * 2. Only if timestamp changed → compute SHA-256 to confirm real content change.
 *    This handles editors that touch timestamps without changing content.
 *
 * On a typical second run across 1000 unchanged files, this costs only SQLite lookups.
 */
public final class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    // Directory names that should never be indexed regardless of where they appear.
    // Hidden directories (anything starting with ".") are skipped separately, so
    // entries like ".git", ".venv", ".cache", ".next" do not need to be listed here.
    // Exposed package-private so the FileWatcher applies the same exclusions.
    static final Set<String> SKIP_DIRECTORIES = Set.of(
            "node_modules",
            "target",          // Maven build output
            "build",           // Gradle / generic build output
            "dist",            // JS/TS bundler output
            "out",             // various build outputs (Next.js, IntelliJ, etc.)
            "bin",             // compiled binaries / build output
            "obj",             // .NET intermediate output
            "venv",            // Python virtualenv (un-dotted form)
            "__pycache__",     // Python bytecode cache
            "vendor",          // Composer / Go vendored deps
            "Pods",            // CocoaPods
            "bower_components",// legacy JS deps
            "coverage",        // test coverage output
            "Library"          // macOS user library cache (huge, never user-authored)
    );

    private final AppConfig config;
    private final IndexMetadataStore metadataStore;
    private final Set<String> supportedExtensions;

    public FileScanner(AppConfig config, IndexMetadataStore metadataStore) {
        this.config              = config;
        this.metadataStore       = metadataStore;
        this.supportedExtensions = Set.of(config.getSupportedExtensions());
    }

    public ScanResult scan() {
        List<Path> roots = config.getFilesRootPaths();
        log.info("Starting file scan across {} root(s):", roots.size());
        for (Path root : roots) {
            log.info("  • {}", root.toAbsolutePath());
        }

        List<Path> toProcess   = new ArrayList<>();
        List<Path> skipped     = new ArrayList<>();
        List<Path> tooLarge    = new ArrayList<>();
        List<Path> unsupported = new ArrayList<>();
        List<Path> errors      = new ArrayList<>();

        for (Path root : roots) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String dirName = dir.getFileName().toString();
                        if (dirName.startsWith(".") || SKIP_DIRECTORIES.contains(dirName)) {
                            log.debug("Skipping hidden/system directory: {}", dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        processFile(file, attrs, toProcess, skipped, tooLarge, unsupported, errors);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        log.warn("Cannot access file (permission denied?): {} — {}", file, exc.getMessage());
                        errors.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // One unreadable root shouldn't abort the whole scan — log and continue.
                // This is important for the desktop app where Desktop/Downloads/Documents
                // each have independent permission grants on macOS.
                log.error("Failed to walk root '{}': {}", root.toAbsolutePath(), e.getMessage());
                errors.add(root);
            }
        }

        log.info("Scan complete — to process: {}, skipped: {}, too large: {}, unsupported: {}, errors: {}",
            toProcess.size(), skipped.size(), tooLarge.size(), unsupported.size(), errors.size());

        return new ScanResult(toProcess, skipped, tooLarge, unsupported, errors);
    }

    private void processFile(
        Path file,
        BasicFileAttributes attrs,
        List<Path> toProcess,
        List<Path> skipped,
        List<Path> tooLarge,
        List<Path> unsupported,
        List<Path> errors
    ) {
        String fileName = file.getFileName().toString();

        if (fileName.startsWith(".")) return;
        if (!attrs.isRegularFile()) return;

        String extension = getExtension(fileName);
        if (!supportedExtensions.contains(extension)) {
            unsupported.add(file);
            return;
        }

        long sizeBytes = attrs.size();
        if (sizeBytes > config.getMaxFileSizeBytes()) {
            log.warn("File too large ({} MB), skipping: {}", sizeBytes / (1024 * 1024), fileName);
            tooLarge.add(file);
            return;
        }

        if (sizeBytes == 0) {
            log.debug("Skipping empty file: {}", fileName);
            return;
        }

        String absolutePath = file.toAbsolutePath().toString();
        long lastModifiedMs = attrs.lastModifiedTime().toMillis();

        // Step 1: timestamp check — zero I/O, instant SQLite lookup
        if (metadataStore.isUpToDateByTimestamp(absolutePath, lastModifiedMs)) {
            log.debug("Skipping (timestamp unchanged): {}", fileName);
            skipped.add(file);
            return;
        }

        // Step 2: timestamp changed — hash to confirm real content change
        try {
            String hash = FileHashUtil.sha256(file);
            if (metadataStore.isUpToDateByHash(absolutePath, hash)) {
                // Content unchanged — just update stored timestamp so Step 1 fires next time
                metadataStore.updateTimestamp(absolutePath, lastModifiedMs);
                log.debug("Skipping (content unchanged, timestamp refreshed): {}", fileName);
                skipped.add(file);
            } else {
                log.debug("Queued for processing: {}", fileName);
                toProcess.add(file);
            }
        } catch (IOException e) {
            log.warn("Failed to hash file: {} — {}", file, e.getMessage());
            errors.add(file);
        }
    }

    static String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) return "";
        return fileName.substring(lastDot + 1).toLowerCase();
    }

    public record ScanResult(
        List<Path> filesToProcess,
        List<Path> skippedUnchanged,
        List<Path> skippedTooLarge,
        List<Path> skippedUnsupported,
        List<Path> errors
    ) {
        public int totalScanned() {
            return filesToProcess.size() + skippedUnchanged.size()
                 + skippedTooLarge.size() + errors.size();
        }
    }

    /**
     * @deprecated retained only for binary compatibility — current scan() logs and continues
     * instead of throwing, so this is no longer raised. New code should not catch it.
     */
    @Deprecated
    public static class ScanException extends RuntimeException {
        public ScanException(String message, Throwable cause) { super(message, cause); }
    }
}

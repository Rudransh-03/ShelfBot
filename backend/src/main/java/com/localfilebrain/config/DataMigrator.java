package com.localfilebrain.config;

import com.localfilebrain.util.FileHashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One-time, idempotent migration of persistent user data from a legacy
 * location (the old working-directory-relative store) into the OS-standard
 * per-user data directory ({@link UserDataPaths}).
 *
 * Safety contract (from the milestone):
 *   • Idempotent — a completion marker in the target root short-circuits every
 *     subsequent run.
 *   • Never overwrite newer data — if the target already holds a metadata DB we
 *     treat it as the live install and do nothing.
 *   • Verify before deleting — artifacts are copied into a staging area,
 *     checksum/size-verified there, atomically moved into place, and only then
 *     is the legacy copy best-effort removed. The legacy data is the last thing
 *     touched, so any failure leaves it fully intact.
 *   • On failure, preserve the originals, report, and let the app continue
 *     (it will retry on the next launch because the marker is not written).
 *
 * This runs BEFORE any store is opened, so it must not depend on AppConfig.
 */
public final class DataMigrator {

    private static final Logger log = LoggerFactory.getLogger(DataMigrator.class);

    /** Written to the target root once migration has fully succeeded (or when
     *  there was nothing to migrate) so we never scan/copy again. */
    static final String MARKER = ".rudo-migrated";

    /** Staging directory inside the target root; copies land here first. */
    static final String STAGING = ".rudo-migrating";

    /** File artifacts we relocate (each may carry SQLite -wal / -shm siblings). */
    private static final String[] DB_FILES = {
            "shelfbot-metadata.db",
            "shelfbot-chats.db",
    };
    private static final String[] DB_SUFFIXES = { "", "-wal", "-shm" };

    /** Plain single-file artifacts. */
    private static final String[] PLAIN_FILES = { "config.properties" };

    /** Directory artifacts copied recursively. */
    private static final String[] DIRS = { "shelfbot-vector-index" };

    /** The authoritative "there is real data here" signal in a legacy dir. */
    private static final String SIGNAL_FILE = "shelfbot-metadata.db";

    public enum Status { ALREADY_DONE, TARGET_POPULATED, NOTHING_TO_MIGRATE, MIGRATED, FAILED }

    public record Result(Status status, String message, Path source, List<String> migrated) {
        static Result of(Status s, String msg) { return new Result(s, msg, null, List.of()); }
    }

    private DataMigrator() {}

    /**
     * The legacy locations to probe, in priority order:
     *   1. {@code SHELFBOT_LEGACY_DIR} — the Electron main process passes the
     *      previous working directory here.
     *   2. The current working directory (covers CLI / dev runs whose data sat
     *      beside the JAR).
     *   3. {@code <cwd>/backend} (dev layout).
     */
    public static List<Path> defaultLegacyCandidates() {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        String env = System.getenv("SHELFBOT_LEGACY_DIR");
        if (env != null && !env.isBlank()) out.add(Paths.get(env.trim()).toAbsolutePath().normalize());
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        out.add(cwd);
        out.add(cwd.resolve("backend").normalize());
        return new ArrayList<>(out);
    }

    /**
     * Runs migration if needed. Never throws — every failure is captured in the
     * returned {@link Result} so the caller can log it and continue booting.
     */
    public static Result migrateIfNeeded(UserDataPaths target, List<Path> legacyCandidates) {
        try {
            Path root = target.root();
            Files.createDirectories(root);

            // (1) Idempotency: already migrated?
            if (Files.exists(root.resolve(MARKER))) {
                return Result.of(Status.ALREADY_DONE, "migration marker present");
            }

            // (2) Never overwrite newer/live data: target already has a DB.
            if (Files.exists(target.metadataDb())) {
                writeMarker(root);
                return Result.of(Status.TARGET_POPULATED,
                        "target already has a metadata DB; leaving it untouched");
            }

            // (3) Find the first legacy dir that actually holds data.
            Path source = firstPopulatedLegacyDir(root, legacyCandidates);
            if (source == null) {
                writeMarker(root);
                return Result.of(Status.NOTHING_TO_MIGRATE, "no legacy data found");
            }

            log.warn("[migration] relocating user data from '{}' to '{}'", source, root);
            return migrateFrom(source, root);

        } catch (Exception e) {
            // Preserve originals, report, continue. No marker written → retried next launch.
            log.error("[migration] failed; original data left in place: {}", e.getMessage(), e);
            return new Result(Status.FAILED, e.getMessage(), null, List.of());
        }
    }

    private static Path firstPopulatedLegacyDir(Path targetRoot, List<Path> candidates) {
        if (candidates == null) return null;
        for (Path c : candidates) {
            if (c == null) continue;
            Path norm = c.toAbsolutePath().normalize();
            if (norm.equals(targetRoot)) continue;                 // never migrate onto ourselves
            if (!Files.isDirectory(norm)) continue;
            if (Files.exists(norm.resolve(SIGNAL_FILE))) return norm;
        }
        return null;
    }

    private static Result migrateFrom(Path source, Path root) throws IOException {
        Path staging = root.resolve(STAGING);
        deleteRecursively(staging);                                // clear any crashed-run leftovers
        Files.createDirectories(staging);

        List<String> planned = new ArrayList<>();
        try {
            // ── Copy every present artifact into staging and verify it there ──
            for (String db : DB_FILES) {
                for (String suffix : DB_SUFFIXES) {
                    String name = db + suffix;
                    Path src = source.resolve(name);
                    if (!Files.isRegularFile(src)) continue;
                    Path dst = staging.resolve(name);
                    Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                    verifyFile(src, dst);
                    planned.add(name);
                }
            }
            for (String name : PLAIN_FILES) {
                Path src = source.resolve(name);
                if (!Files.isRegularFile(src)) continue;
                Path dst = staging.resolve(name);
                Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                verifyFile(src, dst);
                planned.add(name);
            }
            for (String name : DIRS) {
                Path src = source.resolve(name);
                if (!Files.isDirectory(src)) continue;
                Path dst = staging.resolve(name);
                copyDirectory(src, dst);
                verifyDirectory(src, dst);
                planned.add(name + "/");
            }

            if (planned.isEmpty()) {
                deleteRecursively(staging);
                writeMarker(root);
                return new Result(Status.NOTHING_TO_MIGRATE,
                        "legacy dir had the signal file but no movable artifacts", source, List.of());
            }

            // ── Promote staging → target root (atomic per artifact) ──────────
            for (String name : new LinkedHashSet<>(stripTrailingSlash(planned))) {
                Path from = staging.resolve(name);
                Path to   = root.resolve(name);
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            }
            deleteRecursively(staging);

            // Success: mark done, THEN best-effort remove the legacy copy.
            writeMarker(root);
            bestEffortDeleteLegacy(source);

            log.warn("[migration] complete: {} artifact(s) moved into '{}'", planned.size(), root);
            return new Result(Status.MIGRATED, "migrated " + planned.size() + " artifact(s)", source, planned);

        } catch (Exception e) {
            // Roll back staging; leave the target root and the legacy source untouched.
            try { deleteRecursively(staging); } catch (Exception ignored) {}
            log.error("[migration] copy/verify failed; rolled back, originals preserved: {}", e.getMessage(), e);
            return new Result(Status.FAILED, e.getMessage(), source, List.of());
        }
    }

    // ── Verification ─────────────────────────────────────────────────────────

    private static void verifyFile(Path src, Path dst) throws IOException {
        if (!Files.exists(dst)) throw new IOException("copy missing: " + dst);
        long a = Files.size(src), b = Files.size(dst);
        if (a != b) throw new IOException("size mismatch for " + src.getFileName() + " (" + a + " vs " + b + ")");
        // Content check on the authoritative DB so a truncated/corrupt copy is caught.
        if (src.getFileName().toString().equals(SIGNAL_FILE)) {
            if (!FileHashUtil.sha256(src).equals(FileHashUtil.sha256(dst))) {
                throw new IOException("checksum mismatch for " + src.getFileName());
            }
        }
    }

    private static void verifyDirectory(Path src, Path dst) throws IOException {
        long[] srcStats = dirStats(src);
        long[] dstStats = dirStats(dst);
        if (srcStats[0] != dstStats[0] || srcStats[1] != dstStats[1]) {
            throw new IOException("directory mismatch for " + src.getFileName()
                    + " (files " + srcStats[0] + " vs " + dstStats[0]
                    + ", bytes " + srcStats[1] + " vs " + dstStats[1] + ")");
        }
    }

    /** Returns {regularFileCount, totalBytes}. */
    private static long[] dirStats(Path dir) throws IOException {
        long[] acc = { 0, 0 };
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                acc[0]++;
                try { acc[1] += Files.size(p); } catch (IOException ignored) {}
            });
        }
        return acc;
    }

    // ── Filesystem helpers ────────────────────────────────────────────────────

    private static void copyDirectory(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    private static void bestEffortDeleteLegacy(Path source) {
        for (String db : DB_FILES) {
            for (String suffix : DB_SUFFIXES) {
                try { Files.deleteIfExists(source.resolve(db + suffix)); } catch (IOException ignored) {}
            }
        }
        for (String name : PLAIN_FILES) {
            try { Files.deleteIfExists(source.resolve(name)); } catch (IOException ignored) {}
        }
        for (String name : DIRS) {
            try { deleteRecursively(source.resolve(name)); } catch (IOException ignored) {}
        }
    }

    private static void writeMarker(Path root) {
        try {
            Files.writeString(root.resolve(MARKER),
                    "migrated_at=" + java.time.Instant.now() + "\n");
        } catch (IOException e) {
            log.warn("[migration] could not write completion marker: {}", e.getMessage());
        }
    }

    private static Set<String> stripTrailingSlash(List<String> names) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String n : names) out.add(n.endsWith("/") ? n.substring(0, n.length() - 1) : n);
        return out;
    }
}

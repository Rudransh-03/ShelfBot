package com.localfilebrain.reorg;

import com.localfilebrain.ingestion.IndexMetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.DirectoryStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reverses a batch executed by {@link MoveExecutor}.
 *
 * <p>Per-row best-effort: each undo entry is processed independently,
 * skipped when the world has moved on (file no longer at its post-move
 * location, original slot now occupied), and reported back in the
 * {@link UndoResult}. The batch's undo-log rows are removed only after
 * the full pass completes — so a partially-failed undo can be retried
 * by calling again with the same batch ID.
 *
 * <p>Empty destination directories that {@link MoveExecutor} created
 * for the batch are deleted at the end. Non-empty ones are left alone:
 * the user may have added something to them after the original reorg.
 */
public final class UndoExecutor {

    private static final Logger log = LoggerFactory.getLogger(UndoExecutor.class);

    private final IndexMetadataStore metadataStore;

    public UndoExecutor(IndexMetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    public UndoResult undo(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        List<IndexMetadataStore.UndoEntry> entries = metadataStore.getUndoBatchReversed(batchId);
        if (entries.isEmpty()) {
            return new UndoResult(batchId, List.of(), 0, 0, 0);
        }
        log.info("Undoing reorg batch {} ({} entries)", batchId, entries.size());

        List<UndoMoveOutcome> outcomes      = new ArrayList<>(entries.size());
        Set<Path>             createdDirs   = new LinkedHashSet<>();

        for (IndexMetadataStore.UndoEntry entry : entries) {
            Path currentLoc = Path.of(entry.toPath());     // where the file lives now
            Path originalLoc = Path.of(entry.fromPath());  // where we put it back

            if (entry.createdDestinationDir() != null) {
                createdDirs.add(Path.of(entry.createdDestinationDir()));
            }

            outcomes.add(undoOne(entry, currentLoc, originalLoc));
        }

        // Try to remove every dir we created — but only if it's empty.
        // A non-empty created dir means the user added something else
        // there, and removing it would lose their work.
        for (Path dir : createdDirs) {
            try {
                if (Files.isDirectory(dir) && isEmptyDir(dir)) {
                    Files.delete(dir);
                    log.debug("Removed empty created dir during undo: {}", dir);
                }
            } catch (IOException e) {
                log.debug("Couldn't delete created dir {}: {}", dir, e.getMessage());
            }
        }

        // Only remove the undo entries after the pass — leaves the batch
        // re-runnable if anything failed mid-undo.
        int success = (int) outcomes.stream()
                .filter(o -> o.status() == ReorgExecutionResult.Status.SUCCESS).count();
        int skipped = (int) outcomes.stream()
                .filter(o -> o.status() == ReorgExecutionResult.Status.SKIPPED).count();
        int failed  = (int) outcomes.stream()
                .filter(o -> o.status() == ReorgExecutionResult.Status.FAILED).count();

        if (failed == 0) {
            metadataStore.deleteUndoBatch(batchId);
        }

        log.info("Undo {} done — success={}, skipped={}, failed={}", batchId, success, skipped, failed);
        return new UndoResult(batchId, List.copyOf(outcomes), success, skipped, failed);
    }

    private UndoMoveOutcome undoOne(IndexMetadataStore.UndoEntry entry, Path currentLoc, Path originalLoc) {
        try {
            if (!Files.exists(currentLoc)) {
                return UndoMoveOutcome.skipped(entry,
                        "File is no longer at " + currentLoc + " — already moved or deleted");
            }
            if (Files.exists(originalLoc)) {
                return UndoMoveOutcome.skipped(entry,
                        "Original location " + originalLoc + " is now occupied");
            }
            // Make sure the original parent directory still exists (it
            // would, unless the user deleted it manually).
            Path parent = originalLoc.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                return UndoMoveOutcome.skipped(entry,
                        "Original parent folder no longer exists: " + parent);
            }
            try {
                Files.move(currentLoc, originalLoc, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ame) {
                Files.move(currentLoc, originalLoc);
            }
            return UndoMoveOutcome.succeeded(entry);
        } catch (Exception e) {
            return UndoMoveOutcome.failed(entry, "Undo move failed: " + e.getMessage());
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        }
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    public record UndoResult(
            String batchId,
            List<UndoMoveOutcome> outcomes,
            int successCount,
            int skippedCount,
            int failedCount
    ) {}

    public record UndoMoveOutcome(
            String fromPath,                              // original location (where we put it back)
            String toPath,                                // post-move location (where we found it)
            ReorgExecutionResult.Status status,
            String reason
    ) {
        public static UndoMoveOutcome succeeded(IndexMetadataStore.UndoEntry e) {
            return new UndoMoveOutcome(e.fromPath(), e.toPath(),
                    ReorgExecutionResult.Status.SUCCESS, "");
        }
        public static UndoMoveOutcome skipped(IndexMetadataStore.UndoEntry e, String reason) {
            return new UndoMoveOutcome(e.fromPath(), e.toPath(),
                    ReorgExecutionResult.Status.SKIPPED, reason);
        }
        public static UndoMoveOutcome failed(IndexMetadataStore.UndoEntry e, String reason) {
            return new UndoMoveOutcome(e.fromPath(), e.toPath(),
                    ReorgExecutionResult.Status.FAILED, reason);
        }
    }
}

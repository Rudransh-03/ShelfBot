package com.localfilebrain.reorg;

import com.localfilebrain.ingestion.IndexMetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Performs a batch of {@link ReorgProposal.ProposedMove}s against the
 * filesystem with multiple safety belts:
 *
 * <ol>
 *   <li><b>Path boundary check</b>: both source and destination must be
 *       within {@code targetDir} after normalization. Prevents a buggy or
 *       hostile plan from moving files outside the dir the user authorized.</li>
 *   <li><b>Source check</b>: must be a regular file (no symlinks, no dirs).</li>
 *   <li><b>Deny-list</b>: any segment of source or destination matching
 *       {@code .git}, {@code node_modules}, etc. → skip.</li>
 *   <li><b>No-overwrite</b>: if destination file already exists, skip the
 *       move entirely. The user's existing file wins.</li>
 *   <li><b>Undo-log-first</b>: the undo entry is written BEFORE the move
 *       runs, so a crash mid-batch still leaves a recoverable trail.</li>
 *   <li><b>Atomic when possible</b>: try {@link StandardCopyOption#ATOMIC_MOVE}
 *       first; fall back to copy-and-delete (Files.move without flag) when
 *       the source and destination live on different filesystems.</li>
 * </ol>
 *
 * <p>One {@link MoveExecutor} call = one {@code batchId}. The caller hands
 * that ID back to {@link UndoExecutor} to reverse the batch.
 */
public final class MoveExecutor {

    private static final Logger log = LoggerFactory.getLogger(MoveExecutor.class);

    /**
     * Directory names that must not appear ANYWHERE along the resolved
     * source or destination path between the user-chosen target and the
     * file. Same set used by the analyzer's profiling pass, kept here
     * independently so the executor is the last line of defense even if
     * upstream missed something. Comparison is case-insensitive.
     *
     * <p>{@code restricted} is the user-facing opt-out — any folder named
     * "restricted" (case-insensitive) is treated as off-limits. Users
     * can drop sensitive files into a {@code Restricted/} folder and
     * trust that reorg will leave them alone.
     */
    private static final Set<String> DENY_DIR_NAMES = Set.of(
            ".git", ".hg", ".svn", "node_modules", ".idea", ".vscode",
            "__pycache__", "restricted"
    );

    private static boolean isDenyListedDirName(String name) {
        return name != null && DENY_DIR_NAMES.contains(name.toLowerCase());
    }

    private final IndexMetadataStore metadataStore;

    public MoveExecutor(IndexMetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    /**
     * Executes the given moves. Returns a {@link ReorgExecutionResult} with
     * a fresh batch ID and a per-move outcome list. The returned batch ID
     * can be passed to {@link UndoExecutor#undo} to reverse the batch.
     */
    public ReorgExecutionResult execute(Path targetDir, List<ReorgProposal.ProposedMove> moves) {
        if (targetDir == null) {
            throw new IllegalArgumentException("targetDir must not be null");
        }
        Path absTarget = targetDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(absTarget)) {
            throw new IllegalArgumentException("targetDir is not a directory: " + absTarget);
        }

        String batchId = UUID.randomUUID().toString();
        log.info("Executing reorg batch {} ({} moves) in {}", batchId, moves.size(), absTarget);

        List<ReorgExecutionResult.MoveOutcome> outcomes = new ArrayList<>(moves.size());
        int sequence = 0;

        for (ReorgProposal.ProposedMove move : moves) {
            sequence++;
            outcomes.add(runOne(move, absTarget, batchId, sequence));
        }

        ReorgExecutionResult result = ReorgExecutionResult.of(batchId, outcomes);
        log.info("Batch {} done — success={}, skipped={}, failed={}",
                batchId, result.successCount(), result.skippedCount(), result.failedCount());
        return result;
    }

    private ReorgExecutionResult.MoveOutcome runOne(
            ReorgProposal.ProposedMove move, Path absTarget, String batchId, int sequence) {

        Path absFrom = move.from().toAbsolutePath().normalize();
        Path absTo   = move.to().toAbsolutePath().normalize();

        // 1. Boundary check — both endpoints must live under targetDir.
        if (!isWithin(absFrom, absTarget)) {
            return ReorgExecutionResult.MoveOutcome
                    .skipped(move, "Source is outside the target folder");
        }
        if (!isWithin(absTo, absTarget)) {
            return ReorgExecutionResult.MoveOutcome
                    .skipped(move, "Destination is outside the target folder");
        }

        // 2. Deny-list any segment between targetDir and either endpoint.
        if (touchesDenyList(absFrom, absTarget) || touchesDenyList(absTo, absTarget)) {
            return ReorgExecutionResult.MoveOutcome
                    .skipped(move, "Path includes a protected directory");
        }

        // 3. Source must exist as a regular non-symlink file.
        if (!Files.exists(absFrom) || Files.isSymbolicLink(absFrom)) {
            return ReorgExecutionResult.MoveOutcome
                    .failed(move, "Source file is missing or is a symlink");
        }
        if (!Files.isRegularFile(absFrom)) {
            return ReorgExecutionResult.MoveOutcome
                    .failed(move, "Source is not a regular file");
        }

        // 4. Destination folder must not be a symlink. The path-string
        //    boundary check above doesn't follow links, so a pre-existing
        //    symlink at a name like "Photos" could redirect the move to
        //    a directory outside targetDir even though absTo "startsWith"
        //    absTarget lexically.
        if (Files.exists(absTo, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(absTo)) {
            return ReorgExecutionResult.MoveOutcome
                    .skipped(move, "Destination folder is a symbolic link");
        }

        // 5. Destination file must not already exist (no overwrites).
        Path destFile = absTo.resolve(absFrom.getFileName());
        if (Files.exists(destFile)) {
            return ReorgExecutionResult.MoveOutcome
                    .skipped(move, "A file with the same name already exists at the destination");
        }

        // 6. Create destination directory if needed; record so undo can clean up.
        String createdDir = null;
        try {
            if (!Files.isDirectory(absTo)) {
                Files.createDirectories(absTo);
                createdDir = absTo.toString();
            }
        } catch (IOException e) {
            return ReorgExecutionResult.MoveOutcome
                    .failed(move, "Couldn't create destination folder: " + e.getMessage());
        }

        // 6. Write undo entry BEFORE the move. If we crash between the DB
        //    write and the move, the undo entry exists but the source is
        //    still in place — undo will skip it (source-at-from check).
        try {
            metadataStore.appendUndoEntry(
                    batchId, sequence,
                    absFrom.toString(),
                    destFile.toString(),
                    createdDir);
        } catch (Exception e) {
            // Couldn't persist undo — refuse to move. We never want a move
            // we can't reverse.
            return ReorgExecutionResult.MoveOutcome
                    .failed(move, "Couldn't record undo entry: " + e.getMessage());
        }

        // 7. Perform the move. ATOMIC_MOVE is preferred but fails across
        //    filesystems; fall back to non-atomic Files.move which does
        //    copy-then-delete behind the scenes.
        try {
            try {
                Files.move(absFrom, destFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ame) {
                Files.move(absFrom, destFile);
            }
        } catch (IOException e) {
            // Move failed AFTER we wrote the undo entry. Leave the entry —
            // it's harmless (undo will skip) and removing it would race
            // with concurrent operations. The file is still at its source.
            log.warn("Move failed for batch {} seq {}: {} → {} ({})",
                    batchId, sequence, absFrom, destFile, e.getMessage());
            return ReorgExecutionResult.MoveOutcome
                    .failed(move, "Move failed: " + e.getMessage());
        }

        return ReorgExecutionResult.MoveOutcome
                .succeeded(move, destFile);
    }

    // -------------------------------------------------------------------------
    // Path-safety helpers
    // -------------------------------------------------------------------------

    /** True if {@code candidate} is at {@code root} or any descendant. */
    private static boolean isWithin(Path candidate, Path root) {
        return candidate.startsWith(root);
    }

    /**
     * True if any path segment from {@code root} (exclusive) to {@code p}
     * (inclusive) matches a deny-list directory name. We only inspect
     * segments inside the user-authorized scope — segments above {@code root}
     * are filesystem layout the user lives with, not something we judge.
     */
    private static boolean touchesDenyList(Path p, Path root) {
        Path cursor = p;
        // If p == root we don't traverse anything — the analyzer never
        // proposes target-dir-as-destination (it's always a subdirectory).
        while (cursor != null && !cursor.equals(root)) {
            String name = cursor.getFileName() == null ? "" : cursor.getFileName().toString();
            if (isDenyListedDirName(name)) return true;
            if (name.startsWith(".") && !name.equals(".")) return true;
            cursor = cursor.getParent();
        }
        return false;
    }
}

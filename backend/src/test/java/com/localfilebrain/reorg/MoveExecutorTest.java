package com.localfilebrain.reorg;

import com.localfilebrain.ingestion.IndexMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for {@link MoveExecutor} and {@link UndoExecutor}:
 *   - happy-path move + undo,
 *   - destination already exists → SKIPPED, source untouched,
 *   - source outside targetDir → SKIPPED (boundary check),
 *   - source in deny-list dir → SKIPPED,
 *   - source is symlink → FAILED,
 *   - new destination dir is created on move, deleted on undo,
 *   - undo leaves the world untouched when a file has wandered.
 */
final class MoveExecutorTest {

    @TempDir Path tmp;

    private IndexMetadataStore meta;
    private MoveExecutor       executor;
    private UndoExecutor       undoer;
    private Path               target;

    @BeforeEach
    void setup() throws IOException {
        meta     = new IndexMetadataStore(tmp.resolve("meta.db"));
        executor = new MoveExecutor(meta);
        undoer   = new UndoExecutor(meta);
        target   = Files.createDirectories(tmp.resolve("target"));
    }

    @AfterEach
    void teardown() throws Exception {
        meta.close();
    }

    // -------------------------------------------------------------------------
    // Happy path: move + undo
    // -------------------------------------------------------------------------

    @Test
    void execute_movesFileIntoNewFolder_andUndoRestoresIt() throws Exception {
        Path file = writeFile(target, "tax.pdf", "tax content");
        Path destFolder = target.resolve("Tax Documents");

        ReorgProposal.ProposedMove move = new ReorgProposal.ProposedMove(
                file, destFolder, /* destinationIsNew */ true,
                ReorgProposal.ProposedMove.Source.NEW_CLUSTER,
                "tax cluster", 0.9f);

        ReorgExecutionResult r = executor.execute(target, List.of(move));

        assertEquals(1, r.successCount());
        assertFalse(Files.exists(file), "source must be gone after move");
        Path dest = destFolder.resolve("tax.pdf");
        assertTrue(Files.exists(dest), "destination must exist after move");
        assertTrue(Files.isDirectory(destFolder), "new folder created");

        // Undo restores everything.
        UndoExecutor.UndoResult u = undoer.undo(r.batchId());
        assertEquals(1, u.successCount());
        assertTrue(Files.exists(file), "source restored after undo");
        assertFalse(Files.exists(dest), "destination cleared after undo");
        assertFalse(Files.isDirectory(destFolder), "empty created folder deleted after undo");
    }

    @Test
    void undo_isIdempotent_secondCallReportsZeroEntries() throws Exception {
        Path file = writeFile(target, "x.pdf", "x");
        Path dest = target.resolve("Box");
        ReorgExecutionResult r = executor.execute(target, List.of(move(file, dest, true)));

        undoer.undo(r.batchId());
        UndoExecutor.UndoResult second = undoer.undo(r.batchId());
        assertEquals(0, second.outcomes().size(),
                "after a clean undo the batch's rows are gone");
    }

    // -------------------------------------------------------------------------
    // Skipped: dest already exists
    // -------------------------------------------------------------------------

    @Test
    void execute_skipsWhenDestinationFileAlreadyExists() throws Exception {
        Path file = writeFile(target, "tax.pdf", "new");
        Path destFolder = Files.createDirectories(target.resolve("Tax"));
        writeFile(destFolder, "tax.pdf", "old");           // collision

        ReorgExecutionResult r = executor.execute(target, List.of(move(file, destFolder, false)));

        assertEquals(0, r.successCount());
        assertEquals(1, r.skippedCount());
        assertTrue(Files.exists(file), "source must remain in place when skipped");
        assertEquals("old", Files.readString(destFolder.resolve("tax.pdf")),
                "existing destination file must not be overwritten");
    }

    // -------------------------------------------------------------------------
    // Boundary checks
    // -------------------------------------------------------------------------

    @Test
    void execute_refusesSourceOutsideTargetDir() throws Exception {
        Path outside = writeFile(tmp, "outsider.pdf", "");
        Path destFolder = Files.createDirectories(target.resolve("Files"));

        ReorgExecutionResult r = executor.execute(target,
                List.of(move(outside, destFolder, false)));

        assertEquals(1, r.skippedCount());
        assertTrue(Files.exists(outside), "outside-source file must not be touched");
    }

    @Test
    void execute_refusesDestinationOutsideTargetDir() throws Exception {
        Path file = writeFile(target, "x.pdf", "");
        Path destOutside = tmp.resolve("Stolen");

        ReorgExecutionResult r = executor.execute(target,
                List.of(move(file, destOutside, true)));

        assertEquals(1, r.skippedCount());
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(destOutside),
                "must not create a folder outside the target dir");
    }

    // -------------------------------------------------------------------------
    // Deny-list
    // -------------------------------------------------------------------------

    @Test
    void execute_skipsFilesInsideDenyListSubdir() throws Exception {
        Path gitDir = Files.createDirectories(target.resolve(".git"));
        Path file = writeFile(gitDir, "config", "");
        Path destFolder = Files.createDirectories(target.resolve("Files"));

        ReorgExecutionResult r = executor.execute(target,
                List.of(move(file, destFolder, false)));
        assertEquals(1, r.skippedCount());
        assertTrue(Files.exists(file), ".git contents must not be moved");
    }

    @Test
    void execute_skipsDestinationInsideDenyListSubdir() throws Exception {
        Path file = writeFile(target, "x.pdf", "");
        Path nodeModules = Files.createDirectories(target.resolve("node_modules").resolve("pkg"));

        ReorgExecutionResult r = executor.execute(target,
                List.of(move(file, nodeModules, false)));
        assertEquals(1, r.skippedCount());
        assertTrue(Files.exists(file));
    }

    @Test
    void execute_skipsRestrictedFolderRegardlessOfCase() throws Exception {
        Path file = writeFile(target, "x.pdf", "");
        // User's opt-out folder, case-insensitive, mid-path or terminal.
        Path inLower = Files.createDirectories(target.resolve("restricted"));
        Path inUpper = Files.createDirectories(target.resolve("Restricted"));
        Path inMid   = Files.createDirectories(target.resolve("Photos").resolve("RESTRICTED"));

        ReorgExecutionResult r1 = executor.execute(target, List.of(move(file, inLower, false)));
        assertEquals(1, r1.skippedCount(), "destination 'restricted/' must be refused");

        Path file2 = writeFile(target, "y.pdf", "");
        ReorgExecutionResult r2 = executor.execute(target, List.of(move(file2, inUpper, false)));
        assertEquals(1, r2.skippedCount(), "destination 'Restricted/' must be refused");

        Path file3 = writeFile(target, "z.pdf", "");
        ReorgExecutionResult r3 = executor.execute(target, List.of(move(file3, inMid, false)));
        assertEquals(1, r3.skippedCount(),
                "any segment named 'restricted' anywhere in the destination path must refuse the move");

        assertTrue(Files.exists(file));
        assertTrue(Files.exists(file2));
        assertTrue(Files.exists(file3));
    }

    // -------------------------------------------------------------------------
    // Source type checks
    // -------------------------------------------------------------------------

    @Test
    void execute_failsWhenSourceIsMissing() {
        Path ghost = target.resolve("ghost.pdf");
        Path dest = target.resolve("Files");
        ReorgExecutionResult r = executor.execute(target, List.of(move(ghost, dest, true)));
        assertEquals(1, r.failedCount());
    }

    @Test
    void execute_skipsWhenDestinationFolderIsSymlink() throws Exception {
        Path file = writeFile(target, "x.pdf", "");
        Path outsideDir = Files.createDirectories(tmp.resolve("outside"));
        Path destSymlink = target.resolve("Photos");
        try {
            Files.createSymbolicLink(destSymlink, outsideDir);
        } catch (UnsupportedOperationException | IOException e) {
            return;   // filesystem doesn't allow symlinks (rare)
        }
        ReorgExecutionResult r = executor.execute(target, List.of(move(file, destSymlink, false)));
        assertEquals(1, r.skippedCount(),
                "must refuse to move INTO a symlink — could redirect outside the target dir");
        assertTrue(Files.exists(file), "source must remain in place when skipped");
        // The outside dir should not have received anything.
        try (var stream = Files.newDirectoryStream(outsideDir)) {
            assertFalse(stream.iterator().hasNext(),
                    "symlink target must not receive any files");
        }
    }

    @Test
    void execute_failsWhenSourceIsSymlink() throws Exception {
        Path real = writeFile(target, "real.pdf", "x");
        Path link = target.resolve("link.pdf");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            // Filesystem doesn't support symlinks (rare). Skip.
            return;
        }
        Path destFolder = target.resolve("Files");
        ReorgExecutionResult r = executor.execute(target, List.of(move(link, destFolder, true)));
        assertEquals(1, r.failedCount());
    }

    // -------------------------------------------------------------------------
    // Undo behaviour
    // -------------------------------------------------------------------------

    @Test
    void undo_skipsWhenFileNoLongerWhereWeLeftIt() throws Exception {
        Path file = writeFile(target, "x.pdf", "");
        Path destFolder = target.resolve("Box");
        ReorgExecutionResult r = executor.execute(target, List.of(move(file, destFolder, true)));

        // User manually deletes the moved file.
        Files.delete(destFolder.resolve("x.pdf"));

        UndoExecutor.UndoResult u = undoer.undo(r.batchId());
        assertEquals(0, u.successCount());
        assertEquals(1, u.skippedCount(),
                "missing-at-destination must be a skip, not a failure");
    }

    @Test
    void undo_skipsWhenOriginalLocationIsOccupied() throws Exception {
        Path file = writeFile(target, "x.pdf", "");
        Path destFolder = target.resolve("Box");
        ReorgExecutionResult r = executor.execute(target, List.of(move(file, destFolder, true)));

        // Someone creates a new file at the original path.
        Files.writeString(file, "different content");

        UndoExecutor.UndoResult u = undoer.undo(r.batchId());
        assertEquals(0, u.successCount());
        assertEquals(1, u.skippedCount());
        assertEquals("different content", Files.readString(file),
                "the user's manually-created file must not be clobbered");
    }

    @Test
    void listRecentUndoBatches_returnsSummariesNewestFirst() throws Exception {
        Path a = writeFile(target, "a.pdf", "");
        Path b = writeFile(target, "b.pdf", "");
        Path destFolder = target.resolve("Box");

        ReorgExecutionResult r1 = executor.execute(target, List.of(move(a, destFolder, true)));
        // Tiny pause so the second batch's executed_at is strictly later.
        Thread.sleep(20);
        ReorgExecutionResult r2 = executor.execute(target, List.of(move(b, destFolder, false)));

        var summaries = meta.listRecentUndoBatches(10);
        assertEquals(2, summaries.size());
        // Newest first
        assertEquals(r2.batchId(), summaries.get(0).batchId());
        assertEquals(r1.batchId(), summaries.get(1).batchId());
        // Each batch had one move
        assertEquals(1, summaries.get(0).moveCount());
        assertEquals(1, summaries.get(1).moveCount());
    }

    @Test
    void undo_keepsCreatedDirIfUserAddedSomethingToIt() throws Exception {
        Path file = writeFile(target, "x.pdf", "");
        Path destFolder = target.resolve("Box");
        ReorgExecutionResult r = executor.execute(target, List.of(move(file, destFolder, true)));

        // User drops an unrelated file into the new folder.
        Files.writeString(destFolder.resolve("user_added.txt"), "hi");

        undoer.undo(r.batchId());
        assertTrue(Files.isDirectory(destFolder),
                "non-empty created dir must survive undo");
        assertTrue(Files.exists(destFolder.resolve("user_added.txt")));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private static ReorgProposal.ProposedMove move(Path from, Path toFolder, boolean newDest) {
        return new ReorgProposal.ProposedMove(
                from, toFolder, newDest,
                ReorgProposal.ProposedMove.Source.NEW_CLUSTER,
                "test", 0.9f);
    }
}

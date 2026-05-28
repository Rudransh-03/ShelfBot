package com.localfilebrain.reorg;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Final output of the reorg pipeline before the user sees it. Contains the
 * concrete list of file moves the UI will render as a diff-style preview,
 * plus the parallel "we decided NOT to do anything with these" lists so
 * the UI can show a complete picture.
 *
 * <p>This is what {@link com.localfilebrain.reorg.ReorgPlanBuilder} produces
 * by combining the local clustering decisions ({@link DirectoryReorgPlan})
 * with the LLM's verdicts ({@link ReorgToolLoopResult}).
 */
public record ReorgProposal(
        Path targetDir,

        /** Moves we propose to the user. Each row becomes a checkbox in the UI. */
        List<ProposedMove> moves,

        /** Decisions we deliberately dropped (low confidence, bad LLM output, etc.). */
        List<DroppedDecision> dropped,

        /** Files that survive reorg untouched — explicit LEAVE, unjudged, or below cutoff. */
        List<Path> leftAlone,

        int llmCallsAttempted,
        int llmCallsSuccessful,

        /** Populated if the LLM tool loop didn't finish (budget / session). */
        Optional<String> stoppedReason
) {
    public boolean hasAnyChanges() { return !moves.isEmpty(); }

    /**
     * A single proposed file move. The actual destination file path is
     * {@code to.resolve(from.getFileName())} — we surface the directory
     * separately because the UI groups moves by destination folder.
     */
    public record ProposedMove(
            Path from,
            Path to,                      // destination folder
            boolean destinationIsNew,     // false → folder exists; true → mkdir on execute
            Source source,
            String reason,
            float confidence
    ) {
        public enum Source {
            FIT_TO_EXISTING,
            NEW_CLUSTER,
            LONER_TO_EXISTING,
            LONER_TO_NEW_FOLDER
        }

        /** Where the file will actually end up after the move. */
        public Path destinationFile() { return to.resolve(from.getFileName()); }
    }

    /**
     * A decision we considered but rejected. Surfaced so the UI / logs can
     * show "we thought about moving X but dropped it because Y" — useful
     * for debugging and for nudging the user to investigate manually.
     */
    public record DroppedDecision(
            Path file,
            String reason,
            float confidence
    ) {}
}

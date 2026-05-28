package com.localfilebrain.reorg;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of executing one batch of {@link ReorgProposal.ProposedMove}s.
 *
 * <p>Each move ends in one of three states:
 * <ul>
 *   <li>{@link Status#SUCCESS} — file is now at the proposed destination.</li>
 *   <li>{@link Status#SKIPPED} — move was deliberately not performed (e.g.
 *       destination already exists, source path is in the deny-list).</li>
 *   <li>{@link Status#FAILED}  — an unexpected error blocked the move
 *       (permissions, missing source, etc.). The {@code reason} field
 *       carries the user-facing message.</li>
 * </ul>
 *
 * <p>{@code batchId} is the handle the caller passes to undo. It's
 * generated server-side as a UUID and stamped onto every undo-log row
 * for the batch.
 */
public record ReorgExecutionResult(
        String batchId,
        List<MoveOutcome> outcomes,
        int successCount,
        int skippedCount,
        int failedCount
) {

    public enum Status { SUCCESS, SKIPPED, FAILED }

    /**
     * Per-move outcome. {@code resolvedTo} is the actual destination file
     * path (for SUCCESS) or null (for SKIPPED / FAILED).
     */
    public record MoveOutcome(
            Path from,
            Path to,                // destination folder requested
            Path resolvedTo,        // actual file path after move; null if not moved
            Status status,
            String reason
    ) {
        public static MoveOutcome succeeded(ReorgProposal.ProposedMove move, Path resolvedTo) {
            return new MoveOutcome(move.from(), move.to(), resolvedTo, Status.SUCCESS, "");
        }
        public static MoveOutcome skipped(ReorgProposal.ProposedMove move, String reason) {
            return new MoveOutcome(move.from(), move.to(), null, Status.SKIPPED, reason);
        }
        public static MoveOutcome failed(ReorgProposal.ProposedMove move, String reason) {
            return new MoveOutcome(move.from(), move.to(), null, Status.FAILED, reason);
        }
    }

    public static ReorgExecutionResult of(String batchId, List<MoveOutcome> outcomes) {
        int s = 0, k = 0, f = 0;
        for (MoveOutcome o : outcomes) {
            switch (o.status()) {
                case SUCCESS -> s++;
                case SKIPPED -> k++;
                case FAILED  -> f++;
            }
        }
        return new ReorgExecutionResult(batchId, List.copyOf(outcomes), s, k, f);
    }
}

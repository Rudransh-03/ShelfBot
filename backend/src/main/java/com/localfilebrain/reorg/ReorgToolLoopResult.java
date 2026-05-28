package com.localfilebrain.reorg;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Output of {@link ReorgToolLoop}. Pairs each input decision
 * (a new-cluster candidate or a loner) with the LLM's verdict, plus
 * counters so the UI / telemetry can show "used 33 of 50 LLM calls".
 *
 * <p>{@code stoppedReason} is populated when the loop didn't finish
 * everything — typically because the per-session budget was exhausted,
 * the session expired, or the proxy returned an unrecoverable error.
 * Partial results are still useful: the plan builder downstream can
 * accept named clusters as-is and treat unjudged loners as
 * {@code LEAVE_ALONE} by default.
 */
public record ReorgToolLoopResult(
        String sessionId,
        List<NamedCluster> namedClusters,
        List<JudgedLoner>  judgedLoners,
        int llmCallsAttempted,
        int llmCallsSuccessful,
        Optional<String> stoppedReason
) {

    /** A new-cluster candidate paired with the LLM's name suggestion. */
    public record NamedCluster(
            DirectoryReorgPlan.NewClusterCandidate cluster,
            ToolPrompts.ClusterNaming naming
    ) {}

    /** A loner file paired with the LLM's placement decision. */
    public record JudgedLoner(
            Path file,
            ToolPrompts.FileJudgment judgment
    ) {}
}

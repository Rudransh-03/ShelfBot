package com.localfilebrain.reorg;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Output of {@link DirectoryAnalyzer}: the raw clustering decisions for a
 * target directory, BEFORE any LLM naming or user approval.
 *
 * <p>This is the data structure that feeds into Step 5 (LLM tool loop) for
 * naming new clusters + judging loners, and ultimately Step 6 (plan
 * builder) which turns it into a concrete list of file moves.
 *
 * <p>Conservatism contract: this plan ONLY describes what to do with files
 * directly inside {@code targetDir}. It never proposes touching files that
 * already live inside existing subdirectories — those are assumed to be
 * where the user put them on purpose.
 *
 * <p>If {@code scopeError} is present, the directory was too varied to
 * reorganize confidently in one pass and downstream callers should
 * surface the error to the user without invoking the LLM.
 */
public record DirectoryReorgPlan(
        Path targetDir,

        /**
         * Loose files (directly inside targetDir) that fit cleanly into one
         * of targetDir's existing subdirectories. Key = absolute path of the
         * loose file. Value = the existing subdirectory it should move to.
         */
        Map<Path, ExistingFolderMatch> assignedToExisting,

        /**
         * Loose files that didn't fit any existing subdirectory but DO
         * cluster with at least one other loose file. These are candidates
         * for new folders; the LLM names them downstream.
         */
        List<NewClusterCandidate> newClusters,

        /**
         * Loose files that didn't fit any existing subdirectory AND didn't
         * cluster with any other loose file. Downstream the LLM may decide
         * to leave them in place or suggest a single-file home.
         */
        List<Path> loners,

        /**
         * Loose files we have no vector for (embedding error etc.). Skipped
         * entirely from the reorg — surfaced here so the UI can mention
         * them rather than silently dropping.
         */
        List<Path> skippedNoVector,

        /**
         * Loose-file count vs. decision-budget bookkeeping. Used by the
         * scope guard (Step 3) and as plan-level telemetry.
         */
        int totalLooseFiles,
        int totalDecisions,

        /**
         * Snapshot of the immediate subdirectories of {@code targetDir}
         * along with how many files each contains. Powers the scope-error
         * "try these subdirs instead" suggestions, and is useful for the
         * UI's "files we're NOT touching" summary.
         */
        List<ExistingSubdirSummary> existingSubdirs,

        /**
         * Count of loose files by extension family. Used by the scope
         * guard to suggest carving out a single family (e.g. "Run on
         * just the PDFs") when the directory has no existing subdirs.
         */
        Map<ExtensionFamily.Family, Integer> looseFileCountByFamily,

        /**
         * Populated when the directory is too varied to reorganize in one
         * pass. Callers downstream MUST check this before invoking the
         * LLM — if present, surface the error to the user instead.
         */
        Optional<ScopeError> scopeError,

        /**
         * L2-normalized file vectors for every loose file we considered
         * for grouping — both loners AND members of {@link #newClusters}.
         * Passed through to {@link ReorgPlanBuilder} so it can verify
         * LLM-named clusters and LLM-proposed loner groupings against
         * actual embedding cohesion.
         *
         * <p>Why both: local clustering uses single-linkage HAC, which
         * means the weakest chain edge in a cluster meets the bar but
         * arbitrary pairs in the cluster might not. The plan builder
         * re-checks all-pairs cohesion as a second opinion before
         * trusting the cluster. Also catches the OCR'd-image false
         * positives where Tika gives unrelated images "content" that
         * BGE happens to embed similarly.
         *
         * <p>May be empty but never null.
         */
        Map<Path, float[]> looseFileVectors,

        /**
         * Subset of loose files (loners + cluster members) whose vector
         * was derived from the filename only (no indexed content). For
         * pairs that are both filename-only, BGE embeddings of short
         * strings are unreliable — the plan builder requires a shared
         * meaningful filename token (via {@link FilenameTokenizer}) in
         * addition to passing the cosine gate.
         */
        Set<Path> filenameOnlyLooseFiles,

        /**
         * Short text previews (first ~200 chars of indexed content) for
         * every loose file we have any text for. Missing keys mean the
         * file has no indexed content (Tika couldn't extract it — Pages,
         * .key, unsupported binaries). The LLM is shown these previews
         * so it can judge file relatedness by actual content instead of
         * guessing from the filename alone — BGE-small's noise floor
         * makes cosine similarity unreliable as the sole grouping signal.
         *
         * <p>May be empty but never null.
         */
        Map<Path, String> looseFileContentPreviews
) {

    public boolean isInScope() { return scopeError.isEmpty(); }

    /**
     * A loose file's match against one of targetDir's existing subdirs.
     * {@code similarity} is in [0, 1], higher = better fit.
     */
    public record ExistingFolderMatch(Path existingFolder, float similarity) {}

    /**
     * A new-folder candidate: a group of ≥ 2 loose files that clustered
     * together. {@code cohesion} = 1 - maxIntraDistance ∈ [0, 1], higher
     * = tighter cluster.
     */
    public record NewClusterCandidate(List<Path> members, float cohesion) {
        public int size() { return members.size(); }
    }

    /** Lightweight summary of an existing subdirectory of {@code targetDir}. */
    public record ExistingSubdirSummary(Path path, int fileCount) {}
}

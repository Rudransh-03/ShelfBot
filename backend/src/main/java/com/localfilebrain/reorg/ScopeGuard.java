package com.localfilebrain.reorg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks whether a {@link DirectoryReorgPlan} is small enough to run
 * through the LLM in one pass, and — when it isn't — builds a polite,
 * actionable {@link ScopeError} that names specific alternatives the user
 * can try instead.
 *
 * <p>The decision metric is the number of LLM calls the downstream tool
 * loop would need: one per {@link DirectoryReorgPlan.NewClusterCandidate}
 * to name it, plus one per loner to judge it. Files already assigned to an
 * existing subdirectory don't consume budget because the decision was made
 * locally during clustering.
 */
public final class ScopeGuard {

    /**
     * Hard ceiling on per-reorg LLM decisions. Matches the proxy-side
     * {@code llm_calls_in_session} cap so the local guard fails fast
     * with a friendly message before we'd hit a 429 from the proxy.
     */
    public static final int DEFAULT_DECISION_BUDGET = 50;

    /**
     * For the no-existing-subdirs fallback, we only suggest an
     * extension-family carve-out if the family has at least this many
     * files (smaller suggestions are noise).
     */
    private static final int MIN_FAMILY_SUGGESTION_FILES = 5;

    /**
     * And we only suggest carving out a family if the carve-out itself
     * would plausibly fit the budget — using a rough estimate that an
     * average cluster of 3 files makes 1 decision, so files / 3 should
     * be ≤ budget.
     */
    private static final float DECISION_PER_FILE_ESTIMATE = 1f / 3f;

    private final int decisionBudget;

    public ScopeGuard() { this(DEFAULT_DECISION_BUDGET); }

    public ScopeGuard(int decisionBudget) {
        if (decisionBudget < 1) {
            throw new IllegalArgumentException("decisionBudget must be >= 1, got " + decisionBudget);
        }
        this.decisionBudget = decisionBudget;
    }

    /**
     * Returns {@link Optional#empty()} if the plan fits the budget. Returns
     * a populated {@link ScopeError} otherwise. The error message is
     * already polite and user-facing; the UI should render it verbatim
     * (plus the structured suggestions).
     */
    public Optional<ScopeError> check(DirectoryReorgPlan plan) {
        return check(
                plan.totalDecisions(),
                plan.targetDir(),
                plan.existingSubdirs(),
                plan.looseFileCountByFamily());
    }

    /**
     * Same as {@link #check(DirectoryReorgPlan)}, but takes only the fields
     * the guard actually depends on. Used by {@link DirectoryAnalyzer} to
     * compute the scope decision before the plan record exists, so the
     * plan can be constructed in one shot with the result baked in.
     */
    public Optional<ScopeError> check(int totalDecisions,
                                      Path targetDir,
                                      List<DirectoryReorgPlan.ExistingSubdirSummary> existingSubdirs,
                                      Map<ExtensionFamily.Family, Integer> looseFileCountByFamily) {
        if (totalDecisions <= decisionBudget) {
            return Optional.empty();
        }

        String title = "This folder has too much variety to reorganize in one pass.";
        String detail = String.format(
                "I'd need to make about %d separate decisions to do this well, "
              + "but my per-reorg limit is %d. Going beyond that leads to "
              + "lower-quality groupings and risks moving the wrong file to "
              + "the wrong place.",
                totalDecisions, decisionBudget);

        List<ScopeError.Suggestion> suggestions =
                buildSuggestions(targetDir, existingSubdirs, looseFileCountByFamily);

        return Optional.of(new ScopeError(
                title, detail, suggestions, totalDecisions, decisionBudget));
    }

    private List<ScopeError.Suggestion> buildSuggestions(
            Path targetDir,
            List<DirectoryReorgPlan.ExistingSubdirSummary> existingSubdirs,
            Map<ExtensionFamily.Family, Integer> looseFileCountByFamily) {
        List<ScopeError.Suggestion> out = new ArrayList<>();

        // Strategy 1: existing subdirs sorted by file count, descending. The
        // user already has a taxonomy started — point them at the biggest
        // existing buckets first.
        List<DirectoryReorgPlan.ExistingSubdirSummary> subs = new ArrayList<>(existingSubdirs);
        subs.sort(Comparator.comparingInt(DirectoryReorgPlan.ExistingSubdirSummary::fileCount).reversed());
        for (var sub : subs) {
            if (out.size() >= 2) break;
            if (sub.fileCount() == 0) continue;
            String label = String.format("Run on %s/ (%d %s) instead",
                    sub.path().getFileName(), sub.fileCount(),
                    sub.fileCount() == 1 ? "file" : "files");
            out.add(new ScopeError.Suggestion(label, sub.path(), null));
        }

        // Strategy 2: extension-family carve-outs. Useful when the
        // directory has no existing subdirs (flat 200-file Downloads) or
        // only one trivial subdir.
        List<Map.Entry<ExtensionFamily.Family, Integer>> familyEntries =
                new ArrayList<>(looseFileCountByFamily.entrySet());
        familyEntries.sort(Map.Entry.<ExtensionFamily.Family, Integer>comparingByValue().reversed());

        for (var entry : familyEntries) {
            if (out.size() >= 3) break;
            int count = entry.getValue();
            if (count < MIN_FAMILY_SUGGESTION_FILES) continue;
            // Skip families whose carve-out would also blow the budget —
            // sending the user to another scope-too-broad isn't helpful.
            if (count * DECISION_PER_FILE_ESTIMATE > decisionBudget) continue;

            String familyName = humanFamilyName(entry.getKey());
            String label = String.format("Run on just the %s (%d %s) first",
                    familyName, count, count == 1 ? "file" : "files");
            out.add(new ScopeError.Suggestion(
                    label,
                    targetDir,                                       // same scope, filtered
                    new ScopeError.Suggestion.FamilyFilter(entry.getKey(), count)));
        }

        // Last-resort suggestion if we still have nothing concrete: just
        // tell the user to pick a subset themselves. Better than blank.
        if (out.isEmpty()) {
            out.add(new ScopeError.Suggestion(
                    "Move some files into a new subfolder first, then re-run",
                    targetDir,
                    null));
        }

        return List.copyOf(out);
    }

    private static String humanFamilyName(ExtensionFamily.Family f) {
        return switch (f) {
            case DOCS     -> "documents";
            case IMAGES   -> "images";
            case VIDEO    -> "videos";
            case AUDIO    -> "audio files";
            case ARCHIVES -> "archives";
            case CODE     -> "code files";
            case SHEETS   -> "spreadsheets";
            case SLIDES   -> "presentations";
            case OTHER    -> "other files";
        };
    }
}

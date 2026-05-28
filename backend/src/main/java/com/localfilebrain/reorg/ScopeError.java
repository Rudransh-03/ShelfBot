package com.localfilebrain.reorg;

import java.nio.file.Path;
import java.util.List;

/**
 * Structured "this folder is too varied to reorganize in one pass" error.
 *
 * <p>Designed so the UI can render it without string-parsing. Each
 * suggestion is a self-contained "try this instead" action the user can
 * one-click into. The first suggestion is the most preferred one (largest
 * existing subdir, or biggest extension-family carve-out).
 */
public record ScopeError(
        /** Top-line, user-facing message — already polite, no emojis. */
        String title,

        /** One-sentence "why this happened" explanation. */
        String detail,

        /** Concrete next-steps the user can take, in priority order. */
        List<Suggestion> suggestions,

        /** Total estimated LLM decisions that would have been required. */
        int decisionsRequired,

        /** The hard ceiling that triggered this error. */
        int decisionBudget
) {

    /**
     * A single "try this instead" option. {@code scopePath} is the exact
     * filesystem path the UI should pre-fill into the reorg dialog when the
     * user clicks the suggestion.
     */
    public record Suggestion(
            /** UI label, ready to render as-is (e.g. "Run on Tax/ (12 files)"). */
            String label,

            /** The directory to use as the new reorg scope, if any. */
            Path scopePath,

            /**
             * If the suggestion is "run on a single extension family rather
             * than a whole folder", this carries the family name and the
             * file count — used by the UI to render a checkbox-style filter
             * rather than a directory picker. Null when {@code scopePath}
             * alone is sufficient.
             */
            FamilyFilter familyFilter
    ) {
        public record FamilyFilter(ExtensionFamily.Family family, int fileCount) {}
    }
}

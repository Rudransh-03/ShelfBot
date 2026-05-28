package com.localfilebrain.reorg;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class ScopeGuardTest {

    @Test
    void check_returnsEmpty_whenWithinBudget() {
        DirectoryReorgPlan plan = planWithDecisions(20, List.of(), Map.of());
        assertTrue(new ScopeGuard(50).check(plan).isEmpty());
    }

    @Test
    void check_returnsEmpty_atExactlyBudget() {
        DirectoryReorgPlan plan = planWithDecisions(50, List.of(), Map.of());
        assertTrue(new ScopeGuard(50).check(plan).isEmpty());
    }

    @Test
    void check_returnsError_overBudget() {
        DirectoryReorgPlan plan = planWithDecisions(75, List.of(), Map.of());
        Optional<ScopeError> err = new ScopeGuard(50).check(plan);
        assertTrue(err.isPresent());
        assertEquals(75, err.get().decisionsRequired());
        assertEquals(50, err.get().decisionBudget());
        // Title must be polite — no "ERROR" or shouty caps.
        assertFalse(err.get().title().toUpperCase().equals(err.get().title()));
        // Detail must mention the numbers so user understands the gap.
        assertTrue(err.get().detail().contains("50"));
    }

    @Test
    void suggestions_preferLargestExistingSubdirs() {
        List<DirectoryReorgPlan.ExistingSubdirSummary> subs = List.of(
                new DirectoryReorgPlan.ExistingSubdirSummary(Path.of("/t/Photos"), 30),
                new DirectoryReorgPlan.ExistingSubdirSummary(Path.of("/t/Tax"),    12),
                new DirectoryReorgPlan.ExistingSubdirSummary(Path.of("/t/Junk"),   3));
        DirectoryReorgPlan plan = planWithDecisions(75, subs, Map.of());

        ScopeError err = new ScopeGuard(50).check(plan).orElseThrow();

        // First two suggestions should be the two biggest subdirs.
        assertEquals(2, err.suggestions().stream()
                .filter(s -> s.scopePath() != null && s.familyFilter() == null
                          && (s.scopePath().endsWith("Photos") || s.scopePath().endsWith("Tax")))
                .count());
        // Photos is the biggest, must come before Tax.
        assertTrue(err.suggestions().get(0).label().contains("Photos"));
        assertTrue(err.suggestions().get(1).label().contains("Tax"));
    }

    @Test
    void suggestions_skipEmptySubdirs() {
        List<DirectoryReorgPlan.ExistingSubdirSummary> subs = List.of(
                new DirectoryReorgPlan.ExistingSubdirSummary(Path.of("/t/Real"), 10),
                new DirectoryReorgPlan.ExistingSubdirSummary(Path.of("/t/Empty"), 0));
        DirectoryReorgPlan plan = planWithDecisions(75, subs, Map.of());

        ScopeError err = new ScopeGuard(50).check(plan).orElseThrow();
        assertTrue(err.suggestions().stream()
                .noneMatch(s -> s.label().contains("Empty")),
                "empty subdirs must not be suggested");
    }

    @Test
    void suggestions_familyCarveout_whenNoExistingSubdirs() {
        // No existing subdirs, but 40 PDFs and 20 images among loose files.
        Map<ExtensionFamily.Family, Integer> familyCounts = Map.of(
                ExtensionFamily.Family.DOCS,   40,
                ExtensionFamily.Family.IMAGES, 20);
        DirectoryReorgPlan plan = planWithDecisions(75, List.of(), familyCounts);

        ScopeError err = new ScopeGuard(50).check(plan).orElseThrow();
        assertFalse(err.suggestions().isEmpty());
        // DOCS (40 files) ≈ 40/3 ≈ 13 decisions — fits 50 budget → suggested
        assertTrue(err.suggestions().stream()
                        .anyMatch(s -> s.familyFilter() != null
                                    && s.familyFilter().family() == ExtensionFamily.Family.DOCS),
                "DOCS carve-out should be suggested when it fits the budget");
    }

    @Test
    void suggestions_familyCarveout_skipsFamiliesThatStillOverBudget() {
        // 200 files of one family — even just that family would over-budget.
        Map<ExtensionFamily.Family, Integer> familyCounts = Map.of(
                ExtensionFamily.Family.DOCS, 200);
        DirectoryReorgPlan plan = planWithDecisions(75, List.of(), familyCounts);

        ScopeError err = new ScopeGuard(50).check(plan).orElseThrow();
        // 200 * (1/3) = 66.7 > 50 → must NOT be suggested as a fix
        assertTrue(err.suggestions().stream()
                .noneMatch(s -> s.familyFilter() != null
                             && s.familyFilter().family() == ExtensionFamily.Family.DOCS),
                "carve-out that itself would over-budget should not be suggested");
    }

    @Test
    void suggestions_familyCarveout_skipsTooSmallFamilies() {
        // Only 3 PDFs — below MIN_FAMILY_SUGGESTION_FILES = 5
        Map<ExtensionFamily.Family, Integer> familyCounts = Map.of(
                ExtensionFamily.Family.DOCS, 3);
        DirectoryReorgPlan plan = planWithDecisions(75, List.of(), familyCounts);

        ScopeError err = new ScopeGuard(50).check(plan).orElseThrow();
        assertTrue(err.suggestions().stream()
                .noneMatch(s -> s.familyFilter() != null),
                "tiny families should not generate family-filter suggestions");
        // ... but we should still have a last-resort suggestion
        assertFalse(err.suggestions().isEmpty(),
                "should always have at least one suggestion even as last resort");
    }

    @Test
    void suggestions_lastResort_whenNothingFits() {
        // No subdirs, no useful families
        DirectoryReorgPlan plan = planWithDecisions(75, List.of(), Map.of());

        ScopeError err = new ScopeGuard(50).check(plan).orElseThrow();
        assertEquals(1, err.suggestions().size());
        assertTrue(err.suggestions().get(0).label().toLowerCase().contains("subfolder")
                || err.suggestions().get(0).label().toLowerCase().contains("move"));
    }

    @Test
    void constructor_rejectsZeroOrNegativeBudget() {
        assertThrows(IllegalArgumentException.class, () -> new ScopeGuard(0));
        assertThrows(IllegalArgumentException.class, () -> new ScopeGuard(-1));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Builds a DirectoryReorgPlan with the totalDecisions field set to a
     * specific value. Other fields are filled with safe defaults — the
     * ScopeGuard ignores everything except the decision count, the
     * existing-subdir list, and the family-count map.
     */
    private static DirectoryReorgPlan planWithDecisions(
            int totalDecisions,
            List<DirectoryReorgPlan.ExistingSubdirSummary> subs,
            Map<ExtensionFamily.Family, Integer> familyCounts) {
        return new DirectoryReorgPlan(
                Path.of("/t"),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                100,             // totalLooseFiles (not used by guard)
                totalDecisions,
                subs,
                familyCounts,
                Optional.empty(),
                Map.of(),
                java.util.Set.of(),
                Map.of());
    }
}

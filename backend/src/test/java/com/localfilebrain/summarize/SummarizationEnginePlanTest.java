package com.localfilebrain.summarize;

import com.localfilebrain.summarize.SummarizationEngine.CallPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive, LLM-free validation of {@link SummarizationEngine#planCalls}.
 *
 * The call count IS the cost: the proxy charges one daily-quota slot per
 * chat-completion call, so these assertions pin exactly how many slots a
 * document of a given size can ever consume.
 */
class SummarizationEnginePlanTest {

    // Mirror of the engine's constants — hard-coded on purpose so this test
    // FAILS (rather than silently tracking) if someone retunes them.
    private static final int SINGLE_PASS_MAX     = 60;
    private static final int MAX_CHUNKS_PER_CALL  = 150;
    private static final int MAX_TOTAL_CALLS      = 4;   // 3 partials + 1 merge
    private static final int MAX_COVERABLE        = 3 * 150; // 450 chunks

    @Test
    void smallAndMediumDocsUseExactlyOneCall() {
        for (int n : new int[] {1, 5, 12, 30, 59, SINGLE_PASS_MAX}) {
            CallPlan p = SummarizationEngine.planCalls(n);
            assertTrue(p.singlePass(), n + " chunks should be single-pass");
            assertEquals(1, p.totalCalls(), n + " chunks → 1 call (1 slot)");
            assertEquals(n, p.chunksProcessed(), n + " chunks fully covered");
        }
    }

    @Test
    void justOverThresholdIsTwoPartialsPlusMerge() {
        CallPlan p = SummarizationEngine.planCalls(SINGLE_PASS_MAX + 1); // 61
        assertFalse(p.singlePass());
        assertEquals(2, p.partialCalls());
        assertEquals(3, p.totalCalls(), "61 chunks → 3 calls (3 slots)");
        assertEquals(61, p.chunksProcessed(), "still fully covered");
    }

    @Test
    void largeDocsAreCappedAtFourCalls() {
        for (int n : new int[] {121, 180, 300, 450, 1_000, 100_000}) {
            CallPlan p = SummarizationEngine.planCalls(n);
            assertEquals(3, p.partialCalls(), n + " chunks → 3 partials");
            assertEquals(MAX_TOTAL_CALLS, p.totalCalls(),
                    n + " chunks → 4 calls max (4 slots)");
        }
    }

    @Test
    void noCallEverExceedsTheContextWindow() {
        // Sweep every size up to a huge doc; the per-call chunk count must
        // always stay within the window-safety ceiling, and the slot count
        // within the budget.
        for (int n = 1; n <= 6_000; n++) {
            CallPlan p = SummarizationEngine.planCalls(n);
            assertTrue(p.chunksPerCall() <= MAX_CHUNKS_PER_CALL,
                    n + " chunks → " + p.chunksPerCall() + " per call, exceeds " + MAX_CHUNKS_PER_CALL);
            assertTrue(p.totalCalls() <= MAX_TOTAL_CALLS,
                    n + " chunks → " + p.totalCalls() + " calls, exceeds " + MAX_TOTAL_CALLS);
        }
    }

    @Test
    void coversWholeDocUpToBudgetThenTruncatesTail() {
        assertEquals(449, SummarizationEngine.planCalls(449).chunksProcessed());
        assertEquals(MAX_COVERABLE, SummarizationEngine.planCalls(450).chunksProcessed());
        // Beyond the budget, only the leading MAX_COVERABLE chunks are summarized.
        assertEquals(MAX_COVERABLE, SummarizationEngine.planCalls(451).chunksProcessed());
        assertEquals(MAX_COVERABLE, SummarizationEngine.planCalls(10_000).chunksProcessed());
    }

    @Test
    void everyGroupIsNonEmptyAndGroupsTimesPerCoversProcessed() {
        // The slicing loop in summarize() relies on groups * chunksPerCall
        // covering all processed chunks with no empty trailing group.
        for (int n = SINGLE_PASS_MAX + 1; n <= 2_000; n++) {
            CallPlan p = SummarizationEngine.planCalls(n);
            int groups = p.partialCalls();
            int per = p.chunksPerCall();
            assertTrue((long) groups * per >= p.chunksProcessed(),
                    n + ": groups*per must cover processed chunks");
            // last group start index must be < processed (i.e. non-empty)
            assertTrue((long) (groups - 1) * per < p.chunksProcessed(),
                    n + ": last partial group must be non-empty");
        }
    }
}

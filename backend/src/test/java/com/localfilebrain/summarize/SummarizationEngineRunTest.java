package com.localfilebrain.summarize;

import com.localfilebrain.storage.VectorStore.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end exercise of the real {@link SummarizationEngine#runSummary}
 * (the production single-pass / map-reduce logic) with a fake LLM and
 * synthetic, individually-tagged chunks. No Lucene, no OpenAI.
 *
 * It proves three things that actually matter:
 *   1. SLOTS — the number of LLM calls (= daily-quota slots charged) matches
 *      the plan and the reported {@code Result.llmCalls}.
 *   2. CONTENT INTEGRITY — every covered chunk reaches the model exactly once,
 *      in document order: nothing lost, duplicated, or reordered.
 *   3. WINDOW SAFETY — no single call ever carries more than the per-call
 *      chunk ceiling, even for absurd document sizes.
 */
class SummarizationEngineRunTest {

    private static final int PER_CALL_CEILING = 150;
    private static final Pattern MARKER = Pattern.compile("\\[\\[CHUNK (\\d+)]]");

    /** Records every LLM call and returns a stub the merge step can ingest. */
    private static final class RecordingLlm implements SummarizationEngine.OneShot {
        final List<String> userPrompts = new ArrayList<>();
        @Override public String call(String systemPrompt, String userPrompt) {
            userPrompts.add(userPrompt);
            return "PARTIAL_SUMMARY_STUB_" + userPrompts.size();
        }
    }

    private static List<SearchResult> chunks(int n) {
        List<SearchResult> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // The marker is the ONLY chunk content, so we can recover exactly
            // which chunks were sent to the model and in what order.
            out.add(new SearchResult("id" + i, "/doc.txt", "doc.txt", i,
                    "[[CHUNK " + i + "]]", 0.0));
        }
        return out;
    }

    private static List<Integer> markersInOrder(String text) {
        List<Integer> ids = new ArrayList<>();
        Matcher m = MARKER.matcher(text);
        while (m.find()) ids.add(Integer.parseInt(m.group(1)));
        return ids;
    }

    @Test
    void drivesRealLogicAcrossEverySizeRegime() {
        int[] sizes = {1, 7, 12, 13, 59, 60, 61, 62, 90, 120, 121, 150, 151,
                       179, 180, 181, 300, 449, 450, 451, 600, 1_000, 5_000, 9_999};

        for (int n : sizes) {
            RecordingLlm llm = new RecordingLlm();
            SummarizationEngine.Result result =
                    SummarizationEngine.runSummary("doc.txt", chunks(n), llm);

            SummarizationEngine.CallPlan plan = SummarizationEngine.planCalls(n);
            int processed = plan.chunksProcessed();

            // (1) SLOTS: calls made == plan == reported llmCalls.
            assertEquals(plan.totalCalls(), llm.userPrompts.size(),
                    n + " chunks: LLM calls made should equal the plan");
            assertEquals(plan.totalCalls(), result.llmCalls(),
                    n + " chunks: reported llmCalls (slots charged) must match");
            assertTrue(result.llmCalls() <= 4, n + " chunks: never more than 4 slots");

            // (2) CONTENT INTEGRITY: gather every chunk marker sent to the model,
            //     in call order. The merge prompt (map-reduce) carries stubs, not
            //     markers, so it contributes nothing here.
            List<Integer> seen = new ArrayList<>();
            for (String prompt : llm.userPrompts) seen.addAll(markersInOrder(prompt));

            List<Integer> expected = new ArrayList<>(processed);
            for (int i = 0; i < processed; i++) expected.add(i);

            assertEquals(expected, seen, n + " chunks: leading " + processed
                    + " chunks must reach the model exactly once, in order "
                    + "(no loss, dup, reorder, or out-of-range)");

            // (3) WINDOW SAFETY: per-call chunk count never exceeds the ceiling.
            if (plan.singlePass()) {
                assertEquals(n, markersInOrder(llm.userPrompts.get(0)).size(),
                        n + " chunks: single pass carries the whole doc");
                assertTrue(n <= PER_CALL_CEILING || n <= 60,
                        n + " chunks: single-pass size within bounds");
            } else {
                int groups = plan.partialCalls();
                for (int g = 0; g < groups; g++) {
                    int inThisCall = markersInOrder(llm.userPrompts.get(g)).size();
                    assertTrue(inThisCall >= 1 && inThisCall <= PER_CALL_CEILING,
                            n + " chunks: partial " + g + " carries " + inThisCall
                                    + " chunks (must be 1.." + PER_CALL_CEILING + ")");
                }
                // The trailing call is the merge — it must carry no raw chunks.
                assertTrue(markersInOrder(llm.userPrompts.get(groups)).isEmpty(),
                        n + " chunks: merge call must not carry raw chunks");
            }
        }
    }
}

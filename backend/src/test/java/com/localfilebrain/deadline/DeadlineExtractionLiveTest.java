package com.localfilebrain.deadline;

import com.localfilebrain.config.AppConfig;
import com.localfilebrain.llm.GPT4oMiniClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE end-to-end check of deadline extraction against the real OpenAI API
 * (direct mode, key from config.properties). Gated like the other live tests:
 *
 *   mvn -Dtest=DeadlineExtractionLiveTest -Dshelfbot.runLiveLlmTest=true test
 *
 * Verifies what the unit tests can't: that the model, given realistic
 * neighbour-wrapped excerpts, actually returns sensible structured deadlines
 * with resolved ISO dates — including a relative date ("within 15 days") and a
 * renewal — and does NOT invent one from a plain non-deadline document.
 */
class DeadlineExtractionLiveTest {

    private static GPT4oMiniClient client() {
        AppConfig config = AppConfig.load();
        String key = config.getOpenAiApiKey();
        assumeTrue(key != null && !key.isBlank(), "openai.api.key not set — skipping live test");
        return new GPT4oMiniClient(key);
    }

    @Test
    void extractsRealDeadlinesFromMixedDocuments() {
        assumeTrue(Boolean.getBoolean("shelfbot.runLiveLlmTest"),
                "Skipping live LLM test (set -Dshelfbot.runLiveLlmTest=true to enable).");
        GPT4oMiniClient c = client();

        var docs = List.of(
            new DeadlineExtractionEngine.DocPayload(1, "docScanner_2024.pdf",
                "HDFC ERGO General Insurance — Private Car Package Policy. Policy No: 1234567890.",
                List.of("This motor insurance policy is valid until 14 March 2026 and must be "
                      + "renewed before that date to avoid a break in coverage.")),
            new DeadlineExtractionEngine.DocPayload(2, "letter.pdf",
                "Income Tax Department notice to the assessee.",
                List.of("You are required to submit the requested documents within 15 days of the "
                      + "date of this notice, failing which proceedings may be initiated.")),
            new DeadlineExtractionEngine.DocPayload(3, "recipe.txt",
                "Grandma's banana bread recipe.",
                List.of("Preheat the oven to 180 degrees and bake for 50 minutes until golden brown."))
        );

        List<ExtractedDeadline> items =
                DeadlineExtractionEngine.extractBatch(docs, LocalDate.of(2025, 6, 1),
                        c::oneShot);

        System.out.println("[live deadlines] " + items);

        assertFalse(items.isEmpty(), "expected at least the insurance + tax deadlines");

        // Every returned item must have a valid ISO date and a non-blank title.
        for (ExtractedDeadline d : items) {
            assertTrue(DeadlineExtractionEngine.looksLikeIsoDate(d.dueDate()),
                    "date should be ISO: " + d.dueDate());
            assertNotNull(d.title());
            assertFalse(d.title().isBlank());
            assertNotEquals(3, d.docId(), "the recipe (doc 3) is not a deadline and must not be extracted");
        }

        // The insurance renewal (doc 1) should resolve to 2026-03-14.
        boolean foundInsurance = items.stream()
                .anyMatch(d -> d.docId() == 1 && "2026-03-14".equals(d.dueDate()));
        assertTrue(foundInsurance, "expected the 14 March 2026 insurance renewal to be extracted");

        // The tax notice (doc 2) is relative: 15 days after 2025-06-01 = 2025-06-16.
        boolean foundTax = items.stream()
                .anyMatch(d -> d.docId() == 2 && "2025-06-16".equals(d.dueDate()));
        assertTrue(foundTax, "expected 'within 15 days' to resolve to 2025-06-16");
    }
}

package com.localfilebrain.llm;

import com.localfilebrain.config.AppConfig;
import com.localfilebrain.query.ConversationHistory;
import com.localfilebrain.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE integration test for the follow-up LLM path. Hits the real OpenAI
 * API in direct mode using the key from {@code config.properties}, so it
 * costs a few fractions of a cent per run and is gated behind a system
 * property — same pattern as {@code LocalEmbeddingClientTest}.
 *
 * Run with:
 *   mvn -Dtest=FollowUpLiveLlmTest -Dshelfbot.runLiveLlmTest=true test
 *
 * The point is to verify behaviour the unit tests can't: that
 * {@link GPT4oMiniClient#answerFollowUp} actually produces a brief
 * conversational response and does NOT re-emit the "From <filename>:"
 * source-dump format — which was the original failure mode.
 */
class FollowUpLiveLlmTest {

    private static GPT4oMiniClient client() {
        AppConfig config = AppConfig.load();
        String key = config.getOpenAiApiKey();
        assumeTrue(key != null && !key.isBlank(),
                "openai.api.key not set — skipping live test");
        // Use the direct-mode legacy constructor so we don't need the proxy.
        return new GPT4oMiniClient(key);
    }

    private static ConversationHistory historyForResume() {
        ConversationHistory h = new ConversationHistory(5);
        h.add("work experiences of Rudransh?",
                "From Rudransh_Resume.pdf:\n"
                        + "- Software Development Engineer, Sprinklr (Aug 2024–Present) "
                        + "— telephony system, Spring 5→6 migration, alerting framework, security work.\n"
                        + "- Software Development Intern, BNY Mellon (May 2023–Aug 2023) "
                        + "— built 25+ mock APIs, role-based auth, 2nd place hackathon.");
        return h;
    }

    @Test
    void followUpConfirmation_doesNotReemitSourceDump() {
        assumeTrue(Boolean.getBoolean("shelfbot.runLiveLlmTest"),
                "Skipping live LLM test (set -Dshelfbot.runLiveLlmTest=true to enable).");
        GPT4oMiniClient c = client();

        String answer = c.answerFollowUp(
                "these are his work experiences?", historyForResume());

        System.out.println("[follow-up answer] " + answer);

        // The original bug: the model re-templated another "From Source1:"
        // bullet dump instead of just confirming. Assert it does NOT do that.
        assertFalse(answer.toLowerCase().contains("from rudransh_resume.pdf:"),
                "follow-up answer must not re-emit the source-dump format");
        assertFalse(answer.toLowerCase().contains("from source 1"),
                "follow-up answer must not re-emit the source-dump format");

        // Should be a short conversational reply, not a six-bullet dump.
        long bulletCount = answer.lines().filter(l -> l.trim().startsWith("-")).count();
        assertTrue(bulletCount <= 2,
                "follow-up answer should be conversational, not a bullet dump, got: " + answer);
        assertTrue(answer.length() < 400,
                "follow-up answer should be brief, got " + answer.length() + " chars");
    }

    @Test
    void mainPath_stillEmitsFromFilenameFormat() {
        // Defence-in-depth check: the prompt edit to rule 4 must not have
        // softened the main retrieval path's behaviour. Given fresh chunks
        // and an empty history, the model should still produce a "From
        // <filename>:" formatted answer.
        assumeTrue(Boolean.getBoolean("shelfbot.runLiveLlmTest"),
                "Skipping live LLM test (set -Dshelfbot.runLiveLlmTest=true to enable).");
        GPT4oMiniClient c = client();

        VectorStore.SearchResult chunk = new VectorStore.SearchResult(
                "chunk-1",
                "/Users/test/resume.pdf",
                "resume.pdf",
                0,
                "Software Development Engineer, Sprinklr (Aug 2024–Present). "
                        + "Worked on telephony system development, migrated codebase from "
                        + "Spring 5 to Spring 6 with zero downtime, built a job runner "
                        + "framework, and resolved 50+ security vulnerabilities.",
                0.2
        );
        String answer = c.answer("work experiences of Rudransh?",
                List.of(chunk), new ConversationHistory(5));

        System.out.println("[main-path answer] " + answer);

        assertTrue(answer.toLowerCase().contains("from resume.pdf:")
                        || answer.toLowerCase().contains("from resume.pdf "),
                "main path should still emit the 'From <filename>:' format, got: " + answer);
        assertTrue(answer.toLowerCase().contains("sprinklr"),
                "answer should mention the content, got: " + answer);
    }

    @Test
    void followUpAboutMissingDetail_admitsCleanly() {
        assumeTrue(Boolean.getBoolean("shelfbot.runLiveLlmTest"),
                "Skipping live LLM test (set -Dshelfbot.runLiveLlmTest=true to enable).");
        GPT4oMiniClient c = client();

        // History mentions Sprinklr + BNY but no salary numbers — follow-up
        // should not fabricate one.
        String answer = c.answerFollowUp(
                "what was his salary at Sprinklr?", historyForResume());

        System.out.println("[missing-detail answer] " + answer);

        // The follow-up prompt instructs the model to refuse with a specific
        // sentence when history can't answer. Accept either the canonical
        // refusal or any answer that clearly does NOT invent a salary number.
        boolean refused = answer.toLowerCase().contains("don't have that detail")
                || answer.toLowerCase().contains("do not have that detail")
                || answer.toLowerCase().contains("not mentioned")
                || answer.toLowerCase().contains("not included")
                || answer.toLowerCase().contains("no information");
        boolean inventedSalary = answer.matches(".*\\$\\d.*")
                || answer.toLowerCase().matches(".*\\b\\d+[ ]?(lakh|lpa|crore|usd|inr|rupees)\\b.*");
        assertTrue(refused || !inventedSalary,
                "follow-up should admit missing detail rather than fabricate; got: " + answer);
    }
}

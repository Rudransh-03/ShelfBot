package com.localfilebrain.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link QueryEngine#isConversationalFollowUp}, the gate that routes
 * short clarification/confirmation messages away from the retrieval pipeline
 * (which is bound by the strict "From <filename>:" format prompt) and into
 * the conversational follow-up path that answers from history alone.
 *
 * The original failure: "these are his work experiences?" right after a
 * "work experiences of Rudransh?" answer would re-template another full
 * "From Source1:" dump instead of saying "Yes — Sprinklr and BNY Mellon."
 *
 * The detector is intentionally conservative: false negatives (re-retrieving
 * when we could have answered from history) are cheap; false positives
 * (answering from stale history when the user needed fresh chunks) are
 * the failure mode we must avoid.
 */
class FollowUpDetectorTest {

    private static ConversationHistory historyWithOneTurn() {
        ConversationHistory h = new ConversationHistory(5);
        h.add("work experiences of Rudransh?",
                "From resume.pdf:\n- SDE, Sprinklr ...\n- Intern, BNY Mellon ...");
        return h;
    }

    private static ConversationHistory empty() {
        return new ConversationHistory(5);
    }

    // ── Positive cases: should trigger the follow-up path ────────────────

    @Test
    void detectsTheOriginalFailingFollowUp() {
        // The exact wording from the bug report.
        assertTrue(QueryEngine.isConversationalFollowUp(
                "these are his work experiences?", historyWithOneTurn()));
    }

    @Test
    void detectsSingleWordConfirmation() {
        assertTrue(QueryEngine.isConversationalFollowUp("yes", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp("yes?", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp("Yes!", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp("no", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp("really?", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp("OK", historyWithOneTurn()));
    }

    @Test
    void detectsArePronounFollowUps() {
        assertTrue(QueryEngine.isConversationalFollowUp(
                "are these all of them?", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp(
                "are those everything?", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp(
                "is that correct?", historyWithOneTurn()));
    }

    @Test
    void detectsWhatAboutThatStyle() {
        assertTrue(QueryEngine.isConversationalFollowUp(
                "what about that?", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp(
                "what does that mean?", historyWithOneTurn()));
    }

    @Test
    void detectsThoseAreAndThatsConfirmations() {
        assertTrue(QueryEngine.isConversationalFollowUp(
                "those are correct", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp(
                "that's it?", historyWithOneTurn()));
        assertTrue(QueryEngine.isConversationalFollowUp(
                "that is everything?", historyWithOneTurn()));
    }

    // ── Negative cases: should NOT trigger the follow-up path ────────────

    @Test
    void doesNotTriggerWhenHistoryIsEmpty() {
        // No prior turn means there's nothing for the follow-up path to
        // answer from — even an obvious confirmation should fall through
        // to normal retrieval (which will likely return notFound).
        assertFalse(QueryEngine.isConversationalFollowUp("yes?", empty()));
        assertFalse(QueryEngine.isConversationalFollowUp(
                "these are his work experiences?", empty()));
    }

    @Test
    void doesNotTriggerOnFreshQuestionsWithNewEntities() {
        // These look superficially similar to follow-ups but are asking
        // about specific NEW content that needs retrieval.
        assertFalse(QueryEngine.isConversationalFollowUp(
                "what is the project name in BNY internship?", historyWithOneTurn()));
        assertFalse(QueryEngine.isConversationalFollowUp(
                "what about my Aadhaar number?", historyWithOneTurn()));
        assertFalse(QueryEngine.isConversationalFollowUp(
                "how about the dates for Sprinklr?", historyWithOneTurn()));
    }

    @Test
    void detailTriggersOverrideAndForceRetrieval() {
        // Even if the question opens with a follow-up-looking phrase, an
        // "in detail" / "expand" / "verbatim" word means the user wants
        // content history alone cannot provide.
        assertFalse(QueryEngine.isConversationalFollowUp(
                "these are his work experiences? in detail please", historyWithOneTurn()));
        assertFalse(QueryEngine.isConversationalFollowUp(
                "expand on that", historyWithOneTurn()));
        assertFalse(QueryEngine.isConversationalFollowUp(
                "elaborate", historyWithOneTurn()));
        assertFalse(QueryEngine.isConversationalFollowUp(
                "verbatim", historyWithOneTurn()));
    }

    @Test
    void doesNotTriggerOnLongQuestions() {
        // Real follow-ups are short. Long questions almost always carry new
        // intent that needs retrieval — don't risk a false positive there.
        String longQ = "Can you tell me about all the work experiences and projects "
                + "that Rudransh has done at his current company and previous internships, "
                + "with the dates and team sizes?";
        assertFalse(QueryEngine.isConversationalFollowUp(longQ, historyWithOneTurn()));
    }

    @Test
    void doesNotTriggerOnNullOrBlank() {
        assertFalse(QueryEngine.isConversationalFollowUp(null, historyWithOneTurn()));
        assertFalse(QueryEngine.isConversationalFollowUp("", historyWithOneTurn()));
        assertFalse(QueryEngine.isConversationalFollowUp("   ", historyWithOneTurn()));
    }
}

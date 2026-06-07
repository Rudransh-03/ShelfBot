package com.localfilebrain.deadline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the prefilter's recall on deliberately-varied deadline phrasings
 * (the exact set validated before the regex was ported into the engine) and its
 * restraint on non-deadline prose. These are the cheap, deterministic guarantees
 * the cost model relies on — the LLM only ever sees chunks this pass flags.
 */
class DeadlinePrefilterTest {

    @Test
    void catchesVariedDeadlinePhrasings() {
        String[] deadlines = {
            "The insurance policy expires on 12/09/2026 unless renewed before that date.",
            "Kindly remit the outstanding payment within 30 days of receipt of this notice.",
            "Your passport is valid until 15 August 2027; apply for renewal in advance.",
            "Renewal of the maintenance contract is due next quarter.",
            "The GST return for Q3 must be filed by the 20th to avoid a late fee.",
            "Please submit Form 15G before the financial year ends to avoid TDS deduction.",
            "Policy maturity date: 2030-04-01.",
        };
        for (String s : deadlines) {
            assertTrue(DeadlinePrefilter.isCandidate(s),
                    () -> "Expected to flag as candidate: " + s + " (reasons=" + DeadlinePrefilter.reasons(s) + ")");
        }
    }

    @Test
    void skipsNonDeadlineProse() {
        String[] notDeadlines = {
            "Attached are the meeting notes from our discussion about the new logo design.",
            "We hereby confirm the property handover and wish you a pleasant stay.",
            "Thank you for visiting our store; your feedback matters to us.",
        };
        for (String s : notDeadlines) {
            assertFalse(DeadlinePrefilter.isCandidate(s),
                    () -> "Expected to skip: " + s + " (reasons=" + DeadlinePrefilter.reasons(s) + ")");
        }
    }

    @Test
    void nullAndBlankAreNotCandidates() {
        assertFalse(DeadlinePrefilter.isCandidate(null));
        assertFalse(DeadlinePrefilter.isCandidate("   "));
        assertTrue(DeadlinePrefilter.reasons(null).isEmpty());
    }
}

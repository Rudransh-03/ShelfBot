package com.localfilebrain.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link QueryEngine#needsClassifier} — the free local gate that lets a
 * clear, self-contained lookup skip the LLM intent classifier (one call instead
 * of two). The contract: the gate may over-trigger (harmless — same behavior as
 * always classifying) but must NEVER skip a question that needs routing or a
 * conversational rewrite.
 */
class NeedsClassifierTest {

    // ── Must classify: collection-level asks ─────────────────────────────────

    @Test
    void collectionQuestions_classified() {
        assertTrue(QueryEngine.needsClassifier("how many invoices do I have this year", false));
        assertTrue(QueryEngine.needsClassifier("what is the total of all Rohan Mehta invoices", false));
        assertTrue(QueryEngine.needsClassifier("which is my largest invoice this quarter", false));
        assertTrue(QueryEngine.needsClassifier("list all the contracts I have signed", false));
        assertTrue(QueryEngine.needsClassifier("give me an overview of my documents", false));
        assertTrue(QueryEngine.needsClassifier("what are the most important things in my files", false));
        assertTrue(QueryEngine.needsClassifier("please compare the three GST returns for me", false));
        assertTrue(QueryEngine.needsClassifier("summarize the visa checklist document for me please", false));
    }

    // ── Must classify: short / vague / chitchat-shaped ───────────────────────

    @Test
    void shortOrVague_classified() {
        assertTrue(QueryEngine.needsClassifier("invoices", false));
        assertTrue(QueryEngine.needsClassifier("what can you do", false));
        assertTrue(QueryEngine.needsClassifier("hey there friend", false));
        assertTrue(QueryEngine.needsClassifier("", false));
        assertTrue(QueryEngine.needsClassifier(null, false));
    }

    // ── Must classify: possible follow-ups mid-conversation ─────────────────

    @Test
    void contextDependent_withHistory_classified() {
        assertTrue(QueryEngine.needsClassifier("and what happens if they miss it?", true));
        assertTrue(QueryEngine.needsClassifier("what about the other one?", true));
        assertTrue(QueryEngine.needsClassifier("when exactly is that payment actually due then?", true));
        // Short-ish mid-conversation → classify even without an obvious pronoun.
        assertTrue(QueryEngine.needsClassifier("what is his gross salary", true));
    }

    // ── May skip: clear, self-contained lookups ──────────────────────────────

    @Test
    void clearSelfContainedLookups_skipTheClassifier() {
        assertFalse(QueryEngine.needsClassifier("when does the Sharma Bakery food licence expire?", false));
        assertFalse(QueryEngine.needsClassifier("what does the AcmeCorp lease say about ending early?", false));
        assertFalse(QueryEngine.needsClassifier("who exactly is Rohan Mehta in these papers?", false));
        // Long, self-contained, no references — safe to skip even mid-conversation.
        assertFalse(QueryEngine.needsClassifier(
                "when does the Sharma Bakery food licence expire according to the renewal notice?", true));
    }

    @Test
    void referentialWording_evenWhenLong_classifiedWithHistory() {
        assertTrue(QueryEngine.needsClassifier(
                "could you tell me again when exactly they said it was supposed to expire?", true));
    }
}

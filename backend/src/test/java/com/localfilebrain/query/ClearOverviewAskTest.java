package com.localfilebrain.query;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic guard for unmistakable "overview of my whole collection" asks.
 * Must fire for those (so the LLM classifier can never flip them to a 2-file
 * lookup) and must NOT fire for focused questions.
 */
class ClearOverviewAskTest {

    @Test
    void firesForWholeCollectionOverviewAsks() {
        assertTrue(QueryEngine.isClearOverviewAsk("What are the most important things to know from my files?"));
        assertTrue(QueryEngine.isClearOverviewAsk("give me an overview of my documents"));
        assertTrue(QueryEngine.isClearOverviewAsk("summarize my files"));
        assertTrue(QueryEngine.isClearOverviewAsk("what's in my documents"));
        assertTrue(QueryEngine.isClearOverviewAsk("what do I have in my files"));
        assertTrue(QueryEngine.isClearOverviewAsk("give me the key things from my files"));
        assertTrue(QueryEngine.isClearOverviewAsk("what should I know about my documents"));
    }

    @Test
    void doesNotFireForFocusedQuestions() {
        assertFalse(QueryEngine.isClearOverviewAsk("what is the GST amount on the Sharma return"));
        assertFalse(QueryEngine.isClearOverviewAsk("summarize my lease"));
        assertFalse(QueryEngine.isClearOverviewAsk("how many invoices do I have"));
        assertFalse(QueryEngine.isClearOverviewAsk("which is my largest invoice"));
        assertFalse(QueryEngine.isClearOverviewAsk("total of all my invoices"));
        assertFalse(QueryEngine.isClearOverviewAsk("what's important in the Acme contract"));
        assertFalse(QueryEngine.isClearOverviewAsk("list my contracts"));
        assertFalse(QueryEngine.isClearOverviewAsk(null));
    }
}

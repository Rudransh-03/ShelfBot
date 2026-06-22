package com.localfilebrain.query;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The corpus-overview path must fire for whole-collection asks but NOT for
 * focused questions (which still belong on the semantic / file-scope path).
 */
class CorpusOverviewDetectionTest {

    @Test
    void triggersOnWholeCollectionAsks() {
        assertTrue(QueryEngine.isCorpusOverviewQuery("summarize what's in my documents"));
        assertTrue(QueryEngine.isCorpusOverviewQuery("summarize my documents"));
        assertTrue(QueryEngine.isCorpusOverviewQuery("give me an overview of my files"));
        assertTrue(QueryEngine.isCorpusOverviewQuery("what kinds of documents do I have"));
        assertTrue(QueryEngine.isCorpusOverviewQuery("list all my files"));
        assertTrue(QueryEngine.isCorpusOverviewQuery("what types of files do I have"));
    }

    @Test
    void doesNotTriggerOnFocusedQuestions() {
        // Named single doc / topic — belongs on file-scope or semantic search.
        assertFalse(QueryEngine.isCorpusOverviewQuery("summarize my lease agreement"));
        assertFalse(QueryEngine.isCorpusOverviewQuery("what is the GST amount on the Acme invoice"));
        assertFalse(QueryEngine.isCorpusOverviewQuery("how many rent receipts do I have from Priya Singh"));
        assertFalse(QueryEngine.isCorpusOverviewQuery("when does my visa appointment expire"));
        // A content question that merely mentions "my" + an amount, not the corpus.
        assertFalse(QueryEngine.isCorpusOverviewQuery("what do I have to pay this month"));
    }

    @Test
    void nullSafe() {
        assertFalse(QueryEngine.isCorpusOverviewQuery(null));
        assertFalse(QueryEngine.isCorpusOverviewQuery(""));
    }
}

package com.localfilebrain.query;

import com.localfilebrain.storage.VectorStore.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link QueryEngine#lexicalFocusFilter} — the hybrid lexical+vector
 * focus filter. When the question names a distinctive entity/term, files with
 * no chunk containing it are pruned as semantic noise; files WITH a hit keep
 * ALL their retrieved chunks (the answer often lives in a chunk that doesn't
 * repeat the entity name from the document's header).
 */
class LexicalFocusFilterTest {

    private static SearchResult chunk(String file, String text) {
        return new SearchResult("id-" + file + "-" + text.hashCode(),
                "/path/" + file, file, 0, text, 0.5);
    }

    @Test
    void noiseFilesDropped_whenQuestionNamesEntity() {
        List<SearchResult> in = List.of(
                chunk("invoice.pdf",   "TAX INVOICE billed to Nova Systems for services"),
                chunk("screenshot.png", "a chat screenshot mentioning payments"),
                chunk("notes.md",       "random meeting notes about the roadmap"));
        List<SearchResult> out = QueryEngine.lexicalFocusFilter(in, "who is Nova Systems?");
        assertEquals(1, out.size());
        assertEquals("invoice.pdf", out.get(0).fileName());
    }

    @Test
    void matchingFileKeepsAllItsChunks_notJustTheMatchingOne() {
        // The lease's header chunk contains "lease"; the termination-clause
        // chunk doesn't repeat the word — it must survive anyway, because it's
        // the chunk that actually answers "what if I break the lease early".
        List<SearchResult> in = List.of(
                chunk("lease.pdf", "LEASE AGREEMENT between landlord and tenant"),
                chunk("lease.pdf", "either party may terminate with 60 days written notice"),
                chunk("recipe.txt", "mix flour and water, bake for 20 minutes"));
        List<SearchResult> out = QueryEngine.lexicalFocusFilter(in, "what happens if I break the lease early");
        assertEquals(2, out.size());
        assertEquals("lease.pdf", out.get(0).fileName());
        assertEquals("lease.pdf", out.get(1).fileName());
    }

    @Test
    void pluralQuestionMatchesSingularDocument() {
        // Live miss: "which notices need a response" pruned BOTH real notice
        // documents because they say "notice"/"reply", not "notices"/"response".
        List<SearchResult> in = List.of(
                chunk("drc01a.pdf", "This notice requires a reply by 18 July 2026"),
                chunk("recipe.txt", "mix flour and water, bake for 20 minutes"));
        List<SearchResult> out = QueryEngine.lexicalFocusFilter(in, "which notices need attention?");
        assertEquals(1, out.size());
        assertEquals("drc01a.pdf", out.get(0).fileName());
    }

    @Test
    void fileNameTokensCountAsMatches() {
        // "Tax Invoice" often appears only in the file NAME while the content
        // uses other wording — the name must keep the file in focus.
        List<SearchResult> in = List.of(
                chunk("MA_Fee_Invoice_014.pdf", "professional fees for services rendered, payable in 15 days"),
                chunk("recipe.txt", "mix flour and water, bake for 20 minutes"));
        List<SearchResult> out = QueryEngine.lexicalFocusFilter(in, "show my invoices");
        assertEquals(1, out.size());
        assertEquals("MA_Fee_Invoice_014.pdf", out.get(0).fileName());
    }

    @Test
    void unchangedWhenNoChunkContainsAQueryTerm() {
        // Paraphrased / purely-semantic query: nothing matches verbatim, so
        // the filter must be a no-op rather than emptying the pool.
        List<SearchResult> in = List.of(
                chunk("resume.pdf", "software engineer with five years experience"),
                chunk("notes.md",   "quarterly planning discussion"));
        List<SearchResult> out = QueryEngine.lexicalFocusFilter(in, "summarize the curriculum vitae");
        assertEquals(2, out.size());
    }

    @Test
    void unchangedWhenQuestionHasOnlyStopwords() {
        List<SearchResult> in = List.of(
                chunk("a.pdf", "alpha content"),
                chunk("b.pdf", "beta content"));
        // Every token is either <4 chars or a stopword ("what", "documents").
        List<SearchResult> out = QueryEngine.lexicalFocusFilter(in, "what do the documents say");
        assertEquals(2, out.size());
    }

    @Test
    void unchangedWhenEverythingMatches() {
        List<SearchResult> in = List.of(
                chunk("inv1.pdf", "invoice for March"),
                chunk("inv2.pdf", "invoice for April"));
        List<SearchResult> out = QueryEngine.lexicalFocusFilter(in, "show my invoice amounts");
        assertSame(in, out);
    }

    @Test
    void singleChunkAlwaysKept() {
        List<SearchResult> in = List.of(chunk("a.pdf", "unrelated"));
        assertEquals(1, QueryEngine.lexicalFocusFilter(in, "who is Nova Systems").size());
    }

    @Test
    void orderingPreserved() {
        List<SearchResult> in = List.of(
                chunk("nova-a.pdf", "Nova Systems purchase order"),
                chunk("junk.png",   "nothing relevant here"),
                chunk("nova-b.pdf", "Nova Systems invoice"));
        List<SearchResult> out = QueryEngine.lexicalFocusFilter(in, "tell me about Nova Systems");
        assertEquals(2, out.size());
        assertEquals("nova-a.pdf", out.get(0).fileName());
        assertEquals("nova-b.pdf", out.get(1).fileName());
    }
}

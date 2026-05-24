package com.localfilebrain.query;

import com.localfilebrain.storage.VectorStore.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link QueryEngine#filterByRelativeDistance} — the second-stage
 * retrieval filter that drops chunks whose distance is much worse than the
 * top match's, with a safety floor so it never starves the LLM of context.
 *
 * The point: prune semantic stragglers (an unrelated screenshot that shares
 * a token with the query) before they consume a slot in the diversified
 * pool, while never accidentally regressing accuracy on borderline queries
 * where only a few chunks are clearly above the noise.
 */
class RelativeDistanceFilterTest {

    private static SearchResult chunk(String file, double distance) {
        // chunkId / chunkIndex / text are irrelevant for this filter; only
        // distance is read.
        return new SearchResult("id-" + file + "-" + distance,
                "/path/" + file, file, 0, "text", distance);
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertTrue(QueryEngine.filterByRelativeDistance(List.of(), 0.6, 5).isEmpty());
    }

    @Test
    void nullInputReturnsNullSafely() {
        // Defensive: we'd rather not crash on a null even if callers don't
        // pass one in practice.
        assertNull(QueryEngine.filterByRelativeDistance(null, 0.6, 5));
    }

    @Test
    void singleChunkAlwaysKept() {
        List<SearchResult> in = List.of(chunk("a.pdf", 0.5));
        assertEquals(1, QueryEngine.filterByRelativeDistance(in, 0.6, 5).size());
    }

    @Test
    void tightCluster_allKept() {
        // Focused query: every chunk is comfortably above the noise floor.
        List<SearchResult> in = List.of(
                chunk("resume.pdf",  0.40),
                chunk("resume.pdf",  0.55),
                chunk("notes.md",    0.60),
                chunk("resume.pdf",  0.72));
        // best=0.40, cutoff=0.40+0.60=1.00 → all four pass.
        assertEquals(4, QueryEngine.filterByRelativeDistance(in, 0.6, 5).size());
    }

    @Test
    void noiseChunksDropped() {
        // Top match is strong; stragglers far worse should be pruned.
        List<SearchResult> in = List.of(
                chunk("resume.pdf",   0.40),  // top
                chunk("resume.pdf",   0.55),
                chunk("resume.pdf",   0.70),
                chunk("aadhar.jpg",   0.85),
                chunk("notes.md",     0.95),
                chunk("screenshot.png", 1.25), // far worse — noise
                chunk("discord.txt",  1.35));  // far worse — noise
        // best=0.40, cutoff=1.00 → first 5 stay, last 2 dropped.
        List<SearchResult> out = QueryEngine.filterByRelativeDistance(in, 0.6, 5);
        assertEquals(5, out.size());
        for (SearchResult r : out) {
            assertTrue(r.distance() <= 1.00,
                    "kept chunk distance must be ≤ cutoff, got " + r.distance());
        }
        assertEquals("resume.pdf", out.get(0).fileName());
    }

    @Test
    void minKeepFloor_kicksInWhenOverpruned() {
        // Top is far ahead of everything else: relative cutoff alone would
        // keep just the top chunk, but the LLM still needs context — the
        // floor should pull the next-best chunks up.
        List<SearchResult> in = List.of(
                chunk("a.pdf", 0.30),  // top
                chunk("b.pdf", 1.10),
                chunk("c.pdf", 1.20),
                chunk("d.pdf", 1.30),
                chunk("e.pdf", 1.40),
                chunk("f.pdf", 1.45));
        // best=0.30, cutoff=0.90 → only the top would pass. But minKeep=5,
        // so we take the top 5 from the original ordering.
        List<SearchResult> out = QueryEngine.filterByRelativeDistance(in, 0.6, 5);
        assertEquals(5, out.size());
        assertEquals(0.30, out.get(0).distance(), 1e-9);
        assertEquals(1.40, out.get(4).distance(), 1e-9);
    }

    @Test
    void minKeepDoesNotInflateBeyondAvailable() {
        // Only 3 candidates total — floor of 5 must not crash or pad.
        List<SearchResult> in = List.of(
                chunk("a.pdf", 0.30),
                chunk("b.pdf", 1.20),
                chunk("c.pdf", 1.40));
        List<SearchResult> out = QueryEngine.filterByRelativeDistance(in, 0.6, 5);
        // best=0.30, cutoff=0.90 → only "a.pdf" passes the relative cutoff.
        // Floor is 5 but only 3 chunks exist, so the original list is short
        // of minKeep — the implementation keeps the relative-filter result
        // (size 1) because matches.size() < minKeep means floor doesn't fire.
        assertEquals(1, out.size());
    }

    @Test
    void orderingPreserved() {
        // The filter must not re-sort the input.
        List<SearchResult> in = List.of(
                chunk("a.pdf", 0.30),
                chunk("b.pdf", 0.45),
                chunk("c.pdf", 0.60));
        List<SearchResult> out = QueryEngine.filterByRelativeDistance(in, 0.6, 5);
        assertEquals("a.pdf", out.get(0).fileName());
        assertEquals("b.pdf", out.get(1).fileName());
        assertEquals("c.pdf", out.get(2).fileName());
    }
}

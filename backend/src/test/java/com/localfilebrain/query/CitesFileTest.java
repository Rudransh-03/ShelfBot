package com.localfilebrain.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link QueryEngine#citesFile} — the boundary-aware citation check that
 * decides which source chips an answer shows. A plain substring test produced
 * phantom chips: citing "annual-report.pdf" also matched a file named
 * "report.pdf".
 */
class CitesFileTest {

    @Test
    void standaloneCitations_match() {
        assertTrue(QueryEngine.citesFile("From report.pdf: the total is 5,000.", "report.pdf"));
        assertTrue(QueryEngine.citesFile("See report.pdf.", "report.pdf"));           // sentence period
        assertTrue(QueryEngine.citesFile("(report.pdf, page 2)", "report.pdf"));      // punctuation
        assertTrue(QueryEngine.citesFile("report.pdf", "report.pdf"));                // whole answer
    }

    @Test
    void embeddedInLongerFileName_doesNotMatch() {
        assertFalse(QueryEngine.citesFile("From annual-report.pdf: revenue grew.", "report.pdf"));
        assertFalse(QueryEngine.citesFile("From my_report.pdf: notes.", "report.pdf"));
        assertFalse(QueryEngine.citesFile("From 2024report.pdf: notes.", "report.pdf"));
        assertFalse(QueryEngine.citesFile("Backup at report.pdf-old is stale.", "report.pdf"));
        assertFalse(QueryEngine.citesFile("See report.pdf2 for details.", "report.pdf"));
    }

    @Test
    void bothMentioned_bothMatch() {
        String answer = "From annual-report.pdf and report.pdf: combined figures below.";
        assertTrue(QueryEngine.citesFile(answer, "annual-report.pdf"));
        assertTrue(QueryEngine.citesFile(answer, "report.pdf"));
    }

    @Test
    void nullAndEmpty_safe() {
        assertFalse(QueryEngine.citesFile(null, "a.pdf"));
        assertFalse(QueryEngine.citesFile("text", null));
        assertFalse(QueryEngine.citesFile("text", ""));
        assertFalse(QueryEngine.citesFile("no mention here", "a.pdf"));
    }
}

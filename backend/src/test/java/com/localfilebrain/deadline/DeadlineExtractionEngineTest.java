package com.localfilebrain.deadline;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the prompt-building + JSON-parsing seam of the extraction engine with a
 * fake LLM — no network, no key. These cover the parts that must be robust
 * regardless of how the real model phrases its reply: code fences, surrounding
 * prose, undated/garbage items, hallucinated doc ids, and field normalization.
 */
class DeadlineExtractionEngineTest {

    private static final DeadlineExtractionEngine.DocPayload DOC1 =
            new DeadlineExtractionEngine.DocPayload(1, "policy.pdf", "HDFC ERGO car insurance",
                    List.of("Your policy expires on 2026-09-12 unless renewed."));

    @Test
    void parsesCleanJsonArray() {
        String reply = """
            [{"doc":1,"title":"Car insurance renewal","description":"HDFC ERGO policy",
              "date":"2026-09-12","kind":"renewal","confidence":"high","recurring":"yearly"}]
            """;
        List<ExtractedDeadline> items =
                DeadlineExtractionEngine.extractBatch(List.of(DOC1), LocalDate.now(), (s, u) -> reply);
        assertEquals(1, items.size());
        ExtractedDeadline d = items.get(0);
        assertEquals("Car insurance renewal", d.title());
        assertEquals("2026-09-12", d.dueDate());
        assertEquals("RENEWAL", d.kind());
        assertEquals("HIGH", d.confidence());
        assertEquals("YEARLY", d.recurring());
    }

    @Test
    void toleratesCodeFencesAndProse() {
        String reply = """
            Sure! Here is the JSON:
            ```json
            [{"doc":1,"title":"Renewal","date":"2027-01-01","kind":"renewal","confidence":"medium"}]
            ```
            Let me know if you need anything else.
            """;
        List<ExtractedDeadline> items =
                DeadlineExtractionEngine.extractBatch(List.of(DOC1), LocalDate.now(), (s, u) -> reply);
        assertEquals(1, items.size());
        assertEquals("2027-01-01", items.get(0).dueDate());
    }

    @Test
    void dropsUndatedAndInvalidDateItems() {
        String reply = """
            [
              {"doc":1,"title":"No date here","date":null,"kind":"action"},
              {"doc":1,"title":"Bad date","date":"2026-13-40","kind":"deadline"},
              {"doc":1,"title":"Good","date":"2026-12-01","kind":"deadline"}
            ]
            """;
        List<ExtractedDeadline> items =
                DeadlineExtractionEngine.extractBatch(List.of(DOC1), LocalDate.now(), (s, u) -> reply);
        assertEquals(1, items.size());
        assertEquals("Good", items.get(0).title());
    }

    @Test
    void dropsItemsWithUnknownDocId() {
        String reply = """
            [{"doc":99,"title":"Belongs to nobody","date":"2026-12-01","kind":"deadline"}]
            """;
        List<ExtractedDeadline> items =
                DeadlineExtractionEngine.extractBatch(List.of(DOC1), LocalDate.now(), (s, u) -> reply);
        assertTrue(items.isEmpty(), "items for a doc id we never sent must be dropped");
    }

    @Test
    void normalizesUnknownEnumsToSafeDefaults() {
        String reply = """
            [{"doc":1,"title":"X","date":"2026-12-01","kind":"weird","confidence":"???","recurring":"annually"}]
            """;
        List<ExtractedDeadline> items =
                DeadlineExtractionEngine.extractBatch(List.of(DOC1), LocalDate.now(), (s, u) -> reply);
        assertEquals(1, items.size());
        assertEquals("ACTION", items.get(0).kind());
        assertEquals("MEDIUM", items.get(0).confidence());
        assertEquals("YEARLY", items.get(0).recurring()); // "annually" → YEARLY
    }

    @Test
    void emptyOrGarbageReplyYieldsNoItems() {
        assertTrue(DeadlineExtractionEngine.extractBatch(List.of(DOC1), LocalDate.now(), (s, u) -> "").isEmpty());
        assertTrue(DeadlineExtractionEngine.extractBatch(List.of(DOC1), LocalDate.now(), (s, u) -> "no json here").isEmpty());
        assertTrue(DeadlineExtractionEngine.extractBatch(List.of(DOC1), LocalDate.now(), (s, u) -> "[]").isEmpty());
    }

    @Test
    void promptIncludesTodayAndDocContext() {
        StringBuilder captured = new StringBuilder();
        DeadlineExtractionEngine.extractBatch(List.of(DOC1), LocalDate.of(2026, 6, 6),
                (s, u) -> { captured.append(u); return "[]"; });
        String prompt = captured.toString();
        assertTrue(prompt.contains("2026-06-06"), "prompt must state today's date for relative-date resolution");
        assertTrue(prompt.contains("HDFC ERGO car insurance"), "prompt must include the cheap doc header");
        assertTrue(prompt.contains("id=1"), "prompt must tag the doc with its batch-local id");
    }
}

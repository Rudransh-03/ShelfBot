package com.localfilebrain.client;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.Client;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClientMatcherTest {

    private static Client c(String id, String name, String... tokens) {
        List<String> norms = new ArrayList<>();
        for (String t : tokens) norms.add(IndexMetadataStore.normToken(t));
        return new Client(id, name, List.of(tokens), norms);
    }

    private static final Client SHARMA = c("A", "Sharma Bakery", "Sharma Bakery", "29ABCDE1234F1Z5");
    private static final Client VERMA  = c("B", "Verma Textiles", "Verma Textiles", "27ZZZZZ9999Z1Z2");

    @Test
    void matchesByNameOnWordBoundary() {
        assertEquals(Set.of("A"),
                ClientMatcher.matchingClients("what is Sharma Bakery's GST for Feb?", List.of(SHARMA, VERMA)));
    }

    @Test
    void matchesByIdentifier() {
        assertEquals(Set.of("A"),
                ClientMatcher.matchingClients("GSTIN 29ABCDE1234F1Z5 return", List.of(SHARMA, VERMA)));
    }

    @Test
    void doesNotMatchInsideALargerToken() {
        // "verma" glued inside another word must not match.
        assertTrue(ClientMatcher.matchingClients("vermaxtextilesinc summary", List.of(VERMA)).isEmpty());
    }

    @Test
    void returnsBothWhenTwoClientsReferenced() {
        assertEquals(Set.of("A", "B"),
                ClientMatcher.matchingClients("compare Sharma Bakery and Verma Textiles turnover",
                        List.of(SHARMA, VERMA)));
    }

    @Test
    void noMatchWhenNoneMentioned() {
        assertTrue(ClientMatcher.matchingClients("what's the filing due date?", List.of(SHARMA, VERMA)).isEmpty());
    }

    @Test
    void sameEntityNames_detectsDuplicateSpellings() {
        // Duplicate registrations of one entity — must NOT trigger a clarify.
        assertTrue(ClientMatcher.sameEntityNames(
                List.of("Meridian Exports Private Limited", "MERIDIAN EXPORTS PVT LTD")));
        assertTrue(ClientMatcher.sameEntityNames(
                List.of("M/s Malhotra & Associates", "Malhotra & Associates")));
        // Genuinely different clients — clarify stays.
        assertFalse(ClientMatcher.sameEntityNames(
                List.of("Verma Textiles", "Verma Exports")));
        assertFalse(ClientMatcher.sameEntityNames(
                List.of("Sharma Bakery", "Sharma Traders")));
    }

    @Test
    void containsTokenRespectsBoundaries() {
        assertTrue(ClientMatcher.containsToken("a sharma bakery b", "sharma bakery"));
        assertFalse(ClientMatcher.containsToken("sharmabakery", "sharma"));
        assertTrue(ClientMatcher.containsToken("29abcde1234f1z5", "29abcde1234f1z5"));
        assertFalse(ClientMatcher.containsToken("x29abcde1234f1z5x", "29abcde1234f1z5"));
    }
}

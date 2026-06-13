package com.localfilebrain.client;

import com.localfilebrain.client.ClientResolver.Kind;
import com.localfilebrain.client.ClientResolver.Resolution;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.Client;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientResolverTest {

    private static Client c(String id, String name, String... tokens) {
        List<String> norms = new ArrayList<>();
        for (String t : tokens) norms.add(IndexMetadataStore.normToken(t));
        return new Client(id, name, List.of(tokens), norms);
    }

    private static final Client A = c("A", "Sharma Bakery", "Sharma Bakery");
    private static final Client B = c("B", "Verma Textiles", "Verma Textiles");
    private static final List<Client> TWO = List.of(A, B);

    @Test
    void dormantWhenNoClients() {
        assertEquals(Kind.NONE, ClientResolver.resolve("Sharma's GST", null, List.of()).kind());
    }

    @Test
    void namedClientScopes() {
        Resolution r = ClientResolver.resolve("Sharma Bakery GST for Feb", null, TWO);
        assertEquals(Kind.SCOPED, r.kind());
        assertEquals("A", r.clientId());
    }

    @Test
    void switchesWhenADifferentClientIsNamed() {
        Resolution r = ClientResolver.resolve("now show Verma Textiles turnover", "A", TWO);
        assertEquals(Kind.SCOPED, r.kind());
        assertEquals("B", r.clientId());
    }

    @Test
    void followUpKeepsFocus() {
        Resolution r = ClientResolver.resolve("and March?", "A", TWO);
        assertEquals(Kind.SCOPED, r.kind());
        assertEquals("A", r.clientId());
    }

    @Test
    void singleDistinctiveWordResolvesToTheClient() {
        // "Verma" alone should reach "Verma Textiles" — distinctive, unique word.
        Resolution r = ClientResolver.resolve("what is Verma's turnover this year?", null, TWO);
        assertEquals(Kind.SCOPED, r.kind());
        assertEquals("B", r.clientId());
    }

    @Test
    void genericWordDoesNotScopeOnItsOwn() {
        // "textiles" is a generic descriptor → must not silently scope to Verma.
        Resolution r = ClientResolver.resolve("what are the textiles due dates?", null, TWO);
        assertEquals(Kind.CLARIFY, r.kind());
    }

    @Test
    void wordSharedByTwoClientsClarifiesNotGuesses() {
        Client v1 = c("V1", "Verma Textiles", "Verma Textiles");
        Client v2 = c("V2", "Verma Exports", "Verma Exports");
        // "Verma" belongs to both → not a unique signature → ask.
        Resolution r = ClientResolver.resolve("show Verma's filings", null, List.of(v1, v2));
        assertEquals(Kind.CLARIFY, r.kind());
    }

    @Test
    void twoNamedClientsClarify() {
        Resolution r = ClientResolver.resolve("compare Sharma Bakery and Verma Textiles", null, TWO);
        assertEquals(Kind.CLARIFY, r.kind());
        assertEquals(2, r.candidateIds().size());
    }

    @Test
    void noClientNamedNoFocusMultipleClientsClarify() {
        Resolution r = ClientResolver.resolve("what are the overdue returns?", null, TWO);
        assertEquals(Kind.CLARIFY, r.kind());
        assertEquals("no client specified", r.reason());
    }

    @Test
    void singleClientNeedsNoClarification() {
        Resolution r = ClientResolver.resolve("what are the overdue returns?", null, List.of(A));
        assertEquals(Kind.SCOPED, r.kind());
        assertEquals("A", r.clientId());
    }

    @Test
    void staleFocusIsIgnored() {
        // Focus points to a client that no longer exists → treated as no focus.
        Resolution r = ClientResolver.resolve("and March?", "GHOST", TWO);
        assertEquals(Kind.CLARIFY, r.kind());
    }

    @Test
    void ambiguousNameClarifiesEvenWithFocus() {
        // Two clients share the alias "Sharma"; naming it is ambiguous → ask.
        Client a2 = c("A", "Sharma Bakery", "Sharma");
        Client b2 = c("B", "Sharma Traders", "Sharma");
        Resolution r = ClientResolver.resolve("Sharma's GST for March", "A", List.of(a2, b2));
        assertEquals(Kind.CLARIFY, r.kind());
    }
}

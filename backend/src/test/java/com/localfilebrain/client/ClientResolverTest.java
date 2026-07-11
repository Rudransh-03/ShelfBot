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
        // "textiles" is a generic descriptor → must not silently scope to Verma;
        // with no client identified it's a general question → general answer.
        Resolution r = ClientResolver.resolve("what are the textiles due dates?", null, TWO);
        assertEquals(Kind.NONE, r.kind());
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
    void generalQuestionAnswersGenerally_neverInterrogates() {
        // No client named, none in focus → GENERAL question → search everything.
        // (A clarify wall on every unscoped message — even "hi" — made the app
        // unusable; this was a live failure.)
        assertEquals(Kind.NONE,
                ClientResolver.resolve("what are the overdue returns?", null, TWO).kind());
        assertEquals(Kind.NONE,
                ClientResolver.resolve("Summarize what's in my documents", null, TWO).kind());
        assertEquals(Kind.NONE, ClientResolver.resolve("hi", null, TWO).kind());
    }

    @Test
    void singleClient_generalQuestionStillGeneral() {
        // Even with exactly one client, an unscoped question must search
        // everything — the client's scope would exclude the user's own
        // non-client files.
        Resolution r = ClientResolver.resolve("what are the overdue returns?", null, List.of(A));
        assertEquals(Kind.NONE, r.kind());
    }

    @Test
    void staleFocusIsIgnored() {
        // Focus points to a client that no longer exists → treated as no focus,
        // so the follow-up is answered generally rather than interrogated.
        Resolution r = ClientResolver.resolve("and March?", "GHOST", TWO);
        assertEquals(Kind.NONE, r.kind());
    }

    @Test
    void ambiguousNameClarifiesEvenWithFocus() {
        // Two clients share the alias "Sharma"; naming it is ambiguous → ask.
        Client a2 = c("A", "Sharma Bakery", "Sharma");
        Client b2 = c("B", "Sharma Traders", "Sharma");
        Resolution r = ClientResolver.resolve("Sharma's GST for March", "A", List.of(a2, b2));
        assertEquals(Kind.CLARIFY, r.kind());
    }

    @Test
    void freshQuestionDoesNotInheritStaleFocus() {
        // In focus on A, but the new question names no client and is NOT a
        // continuation (no pronoun/opener) → a fresh general question, answered
        // across everything. This is the reported "engagement letter locked to
        // Anjali" bug: it must NOT stay scoped to A.
        Resolution r = ClientResolver.resolve(
                "what are the terms of the engagement letter?", "A", TWO);
        assertEquals(Kind.NONE, r.kind());
    }

    @Test
    void pronounFollowUpKeepsFocus() {
        // A genuine continuation (pronoun referring back) still rides the focus.
        Resolution r = ClientResolver.resolve("what is their PAN?", "A", TWO);
        assertEquals(Kind.SCOPED, r.kind());
        assertEquals("A", r.clientId());
    }

    @Test
    void weakDeicticThisWeekIsNotAContinuation() {
        // "this week" must not count as a back-reference and trap a fresh
        // obligations question inside the focused client.
        Resolution r = ClientResolver.resolve("what should I chase this week?", "A", TWO);
        assertEquals(Kind.NONE, r.kind());
    }

    @Test
    void explicitGeneralOverridesCarriedFocus() {
        // In focus on A, but the user says "in general" → broaden.
        Resolution r = ClientResolver.resolve(
                "in general, is there an engagement letter?", "A", TWO);
        assertEquals(Kind.NONE, r.kind());
    }

    @Test
    void relativeFollowUpBroadensBeyondClient() {
        // "his son's PAN" is about a DIFFERENT person than the focused client, whose
        // papers are filed separately → answer generally, don't dead-end in scope.
        assertEquals(Kind.NONE, ClientResolver.resolve("what is his son's PAN?", "A", TWO).kind());
        // Even with the client named: the daughter's docs live elsewhere.
        assertEquals(Kind.NONE, ClientResolver.resolve("who is Sharma Bakery's daughter?", null, TWO).kind());
    }

    @Test
    void nonRelativePronounStillKeepsFocus() {
        // Control: a pronoun follow-up that is NOT about a relative rides the focus.
        Resolution r = ClientResolver.resolve("what is his GST number?", "A", TWO);
        assertEquals(Kind.SCOPED, r.kind());
        assertEquals("A", r.clientId());
    }

    @Test
    void explicitExclusionOverridesTheNamedClient() {
        // Names Sharma only to EXCLUDE it ("don't answer about Sharma") → must
        // broaden, not scope to Sharma. Reproduces the user's exact phrasing.
        Resolution r = ClientResolver.resolve(
                "don't answer about Sharma, in general is there an engagement letter?", "A", TWO);
        assertEquals(Kind.NONE, r.kind());
    }
}

package com.localfilebrain.client;

import com.localfilebrain.ingestion.IndexMetadataStore.Client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides, for a single question, which client it's about — or that we must ask.
 *
 * Pure and side-effect free so every branch is unit-testable without a DB or an
 * LLM. The orchestration layer feeds it the question, the chat's remembered
 * client (focus), and the client list; it returns one of:
 *
 *   NONE     — no clients registered → feature dormant, search everything.
 *   SCOPED   — confidently one client (named in the question, or carried over).
 *   CLARIFY  — ambiguous or unspecified → ask the user; never guess.
 *
 * The guiding rule: we only scope when we're sure, and the ONLY way scope is
 * ever wrong is a mis-identification — so whenever it's unclear, we CLARIFY
 * instead of risking the wrong client's data.
 */
public final class ClientResolver {

    private ClientResolver() {}

    public enum Kind { NONE, SCOPED, CLARIFY }

    /**
     * @param clientId     set when {@link Kind#SCOPED}
     * @param candidateIds the options to offer when {@link Kind#CLARIFY}
     * @param reason       why we're clarifying (for the message / logs)
     */
    public record Resolution(Kind kind, String clientId, List<String> candidateIds, String reason) {
        static Resolution none()                 { return new Resolution(Kind.NONE, null, List.of(), null); }
        static Resolution scoped(String id)      { return new Resolution(Kind.SCOPED, id, List.of(), null); }
        static Resolution clarify(List<String> ids, String reason) {
            return new Resolution(Kind.CLARIFY, null, List.copyOf(ids), reason);
        }
    }

    /**
     * @param question  the user's current message
     * @param focusId   the client this chat is currently "about", or null
     * @param clients   all registered clients
     */
    public static Resolution resolve(String question, String focusId, List<Client> clients) {
        if (clients == null || clients.isEmpty()) return Resolution.none();   // dormant

        boolean focusValid = focusId != null && clients.stream().anyMatch(c -> c.id().equals(focusId));

        Set<String> named = ClientMatcher.matchingClientsForQuestion(question, clients);

        if (named.size() == 1) {
            // The question explicitly identifies exactly one client → use it
            // (this is also how a mid-chat switch happens).
            return Resolution.scoped(named.iterator().next());
        }
        if (named.size() > 1) {
            // Two+ clients referenced (a "Sharma" that matches two, or "compare A and B").
            // Never blend — ask which one.
            return Resolution.clarify(new ArrayList<>(named), "multiple clients referenced");
        }

        // No client named in the question.
        if (focusValid) {
            // Follow-up like "what about March?" → stay on the current client.
            return Resolution.scoped(focusId);
        }
        if (clients.size() == 1) {
            // Only one client exists → no ambiguity, no need to ask.
            return Resolution.scoped(clients.get(0).id());
        }
        // Multiple clients, none named, none in focus → must ask.
        return Resolution.clarify(clients.stream().map(Client::id).toList(), "no client specified");
    }
}

package com.localfilebrain.client;

import com.localfilebrain.ingestion.IndexMetadataStore.Client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides, for a single question, which client it's about — or that we must ask.
 *
 * Pure and side-effect free so every branch is unit-testable without a DB or an
 * LLM. The orchestration layer feeds it the question, the chat's remembered
 * client (focus), and the client list; it returns one of:
 *
 *   NONE     — nothing ties the question to a client → search everything
 *              (general question, or no clients registered at all).
 *   SCOPED   — confidently one client (named in the question, or carried over).
 *   CLARIFY  — the question references a client AMBIGUOUSLY (a name shared by
 *              two clients, or several named at once) → ask; never guess.
 *
 * The guiding rules: (1) we only scope when we're sure — a mis-identification
 * is the only way scope can be wrong, so genuine ambiguity always asks;
 * (2) a question that names no client at all is a GENERAL question and gets a
 * general answer across everything. Interrogating the user on every message
 * ("hi" → "which client?") made the app unusable; isolation still holds
 * whenever a client IS named, in focus, or picked in the UI.
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

        // An explicit "don't answer about <X> / regardless of client" is a broaden
        // instruction even though it NAMES a client — the name is there only to be
        // EXCLUDED. This must win over the name-match below, otherwise
        // "don't answer about Anjali, in general…" re-scoped straight back to Anjali.
        if (excludesNamedClient(question)) return Resolution.none();

        // A question about a RELATIVE ("what is his son's PAN?", "Suresh's daughter")
        // is about a DIFFERENT person than the client, whose documents are usually
        // filed separately (a school receipt, a personal ID). Scoping it to the
        // client hides that doc and dead-ends — so answer generally. Checked before
        // name-matching so "Suresh Gupta's son" broadens too.
        if (mentionsRelative(question)) return Resolution.none();

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

        // Explicit "answer across everything / not just this client" ALWAYS wins,
        // even over a carried focus — the user is telling us to broaden. Without
        // this a chat stuck on one client (e.g. after "who is Anjali Rao?") ignored
        // "in general, is there an engagement letter?" and kept dead-ending inside
        // that client's docs.
        if (wantsGeneral(question)) return Resolution.none();

        // A carried focus only survives a GENUINE continuation of the same topic —
        // a pronoun/ellipsis follow-up ("what about April?", "and his PAN?"). A
        // FRESH, self-contained question that names no client is a general question
        // and must NOT be trapped in the previous client's scope (the reported bug:
        // "what are the terms of the engagement letter?" after an Anjali turn stayed
        // scoped to Anjali and found nothing). Rule the user set: named client →
        // that client; nothing named → answer generally.
        if (focusValid && isContinuation(question)) {
            return Resolution.scoped(focusId);
        }

        // Nothing names a client, no continuation, no valid focus → general.
        // Answer across everything (even when only one client exists — its
        // scope would wrongly exclude the user's own non-client files).
        return Resolution.none();
    }

    // Strong deictic/possessive pronouns and elliptical openers that mark a message
    // as continuing the previous turn. Deliberately EXCLUDES weak deictics
    // "this/that/these/those" — they appear non-referentially ("this week", "that
    // amount") and would wrongly trap a fresh question in the old client's scope.
    private static final Pattern CONTINUATION_PRONOUN = Pattern.compile(
            "\\b(it|its|it's|they|them|their|theirs|he|him|his|she|her|hers|same)\\b");
    private static final String[] CONTINUATION_OPENERS = {
            "and ", "also ", "then ", "what about", "how about", "what if", "and the",
            "and its", "and their", "plus "
    };

    /** True when the message reads as a continuation of the prior turn (a pronoun
     *  referring back, or an elliptical opener), so a carried client focus applies. */
    static boolean isContinuation(String question) {
        if (question == null) return false;
        String q = question.toLowerCase().replaceAll("\\s+", " ").trim();
        if (q.isEmpty()) return false;
        if (CONTINUATION_PRONOUN.matcher(q).find()) return true;
        for (String o : CONTINUATION_OPENERS) if (q.startsWith(o)) return true;
        return false;
    }

    // High-precision phrases that ask to answer across ALL files / clients.
    // Checked only when NO client is named this turn, so they override a carried
    // focus without stripping a scope the user explicitly asked for in the same
    // breath ("in general terms, what is Sharma's GST?" still scopes to Sharma).
    private static final String[] GENERAL_CUES = {
            "in general", "across all", "all clients", "all my clients",
            "all of my clients", "every client", "any client",
            "all my files", "all my documents", "all of my files",
            "all of my documents", "across my files", "across my documents",
            "answer generally", "in general terms"
    };

    /** True when the user explicitly asks to broaden beyond a carried focus. */
    static boolean wantsGeneral(String question) {
        if (question == null) return false;
        String q = " " + question.toLowerCase().replaceAll("\\s+", " ").trim() + " ";
        for (String c : GENERAL_CUES) if (q.contains(c)) return true;
        return false;
    }

    // Unambiguous "exclude the named client / ignore whose it is" phrases. These
    // BROADEN even when the message names a client (the name is only there to be
    // excluded), so they're checked BEFORE name-matching.
    private static final String[] EXCLUSION_CUES = {
            "don't answer about", "dont answer about", "do not answer about",
            "forget about", "regardless of client", "regardless of the client",
            "regardless of whose", "not just this client", "not just that client",
            "no matter the client", "any client"
    };

    /** True when the user explicitly tells us to drop the named/focused client. */
    static boolean excludesNamedClient(String question) {
        if (question == null) return false;
        String q = " " + question.toLowerCase().replaceAll("\\s+", " ").trim() + " ";
        for (String c : EXCLUSION_CUES) if (q.contains(c)) return true;
        return false;
    }

    // Kinship words that make a question about a PERSON related to the client, not
    // the client itself. "parent" is excluded on purpose ("parent company").
    private static final Pattern RELATIONSHIP = Pattern.compile(
            "\\b(son|sons|daughter|daughters|wife|wives|husband|spouse|father|mother|"
          + "child|children|kid|kids|brother|sister|sibling|siblings|nephew|niece|"
          + "grandson|granddaughter|family|in-law|in-laws)\\b");

    /** True when the question is about a relative of the client/person. */
    static boolean mentionsRelative(String question) {
        return question != null && RELATIONSHIP.matcher(question.toLowerCase()).find();
    }
}

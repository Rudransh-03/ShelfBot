package com.localfilebrain.client;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.Client;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic matching of registered client identifiers (GSTIN / PAN / name /
 * alias) against arbitrary text — a document's content or a chat question.
 *
 * Matching is case-insensitive, whitespace-normalized, and **word-boundary**:
 * a token only matches when it isn't glued to surrounding letters/digits, so a
 * short alias like "abc" can't match inside "abcdef". No LLM, no guessing — the
 * same routine drives both file-tagging and per-question scope resolution, so
 * they can never disagree.
 */
public final class ClientMatcher {

    private ClientMatcher() {}

    /**
     * Returns the ids of every client at least one of whose identifiers appears
     * in {@code text}. An empty set = no client; size 1 = unambiguous; size &gt; 1
     * = the text references multiple clients (caller decides: conflict for a
     * document, clarify for a question).
     */
    public static Set<String> matchingClients(String text, List<Client> clients) {
        Set<String> hits = new LinkedHashSet<>();
        if (text == null || clients == null || clients.isEmpty()) return hits;
        String hay = IndexMetadataStore.normToken(text);
        if (hay.isEmpty()) return hits;
        for (Client c : clients) {
            for (String token : c.norms()) {
                if (containsToken(hay, token)) { hits.add(c.id()); break; }
            }
        }
        return hits;
    }

    /**
     * True if {@code token} occurs in {@code haystack} on word boundaries. Both
     * are expected pre-normalized (lowercase, single-spaced). A boundary is the
     * string edge or any non-alphanumeric char, so "29abcde1234f1z5" matches as a
     * whole but "sharma" won't match inside "sharmaa".
     */
    static boolean containsToken(String haystack, String token) {
        if (token == null || token.isEmpty()) return false;
        int from = 0;
        while (from <= haystack.length() - token.length()) {
            int i = haystack.indexOf(token, from);
            if (i < 0) return false;
            boolean leftOk  = (i == 0) || !isWordChar(haystack.charAt(i - 1));
            int end = i + token.length();
            boolean rightOk = (end >= haystack.length()) || !isWordChar(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
        return false;
    }

    private static boolean isWordChar(char ch) { return Character.isLetterOrDigit(ch); }

    // Generic words that must NOT, on their own, scope a question to a client —
    // legal forms + common business descriptors + stopwords. A client whose
    // distinctive word is one of these still matches via its full name/alias.
    private static final Set<String> GENERIC = Set.of(
            "the", "and", "for", "of", "ltd", "llp", "llc", "inc", "pvt", "private",
            "limited", "corporation", "corp", "company", "co", "plc", "gmbh", "sons",
            "group", "holdings", "enterprises", "ventures", "associates", "industries",
            "textiles", "bakery", "stores", "store", "services", "solutions", "traders",
            "trading", "foods", "retail", "systems", "technologies", "tech", "global",
            "india", "international");

    /**
     * Question-resolution match: like {@link #matchingClients} (exact identifier /
     * alias substrings) but ALSO matches on a "signature" token — a distinctive
     * word from a client's name (length ≥ 4, not generic) that is UNIQUE to that
     * one client across the whole client list. This lets a user type "Verma" and
     * reach "Verma Textiles LLP", while a word shared by two clients (or a generic
     * word) never scopes on its own — those fall through to a clarify.
     *
     * Deliberately separate from {@link #matchingClients}: document tagging stays
     * strict (full identifiers / GSTIN only) to avoid mis-filing; only question
     * resolution uses these looser signature tokens, where a wrong guess can't
     * leak (the answer is still hard-filtered to one client and labelled).
     */
    public static Set<String> matchingClientsForQuestion(String question, List<Client> clients) {
        Set<String> hits = matchingClients(question, clients); // exact identifier/alias matches first
        if (clients == null || clients.isEmpty()) return hits;
        String hay = IndexMetadataStore.normToken(question);
        if (hay.isEmpty()) return hits;

        // Count how many clients each candidate token belongs to.
        Map<String, Integer> tokenClientCount = new HashMap<>();
        Map<String, Set<String>> clientTokens = new HashMap<>();
        for (Client c : clients) {
            Set<String> toks = new LinkedHashSet<>();
            for (String norm : c.norms()) {
                for (String t : norm.split("\\s+")) {
                    if (t.length() >= 4 && !GENERIC.contains(t)) toks.add(t);
                }
            }
            clientTokens.put(c.id(), toks);
            for (String t : toks) tokenClientCount.merge(t, 1, Integer::sum);
        }
        // A token is a signature only if unique to one client; match it as a word.
        for (Client c : clients) {
            if (hits.contains(c.id())) continue;
            for (String t : clientTokens.get(c.id())) {
                if (tokenClientCount.get(t) == 1 && containsToken(hay, t)) { hits.add(c.id()); break; }
            }
        }
        return hits;
    }
}

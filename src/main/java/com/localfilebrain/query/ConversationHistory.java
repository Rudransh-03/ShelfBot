package com.localfilebrain.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the last N question-answer exchanges in memory.
 * Cleared on application restart. Not persisted anywhere.
 *
 * Used to give the LLM context about what was discussed recently,
 * so follow-up questions like "what about OAuth?" make sense.
 */
public final class ConversationHistory {

    private final int maxExchanges;
    private final List<Exchange> exchanges;

    public ConversationHistory(int maxExchanges) {
        this.maxExchanges = maxExchanges;
        this.exchanges    = new ArrayList<>();
    }

    /**
     * Adds a new exchange. If at capacity, drops the oldest one.
     */
    public void add(String question, String answer) {
        if (exchanges.size() >= maxExchanges) {
            exchanges.remove(0);
        }
        exchanges.add(new Exchange(question, answer));
    }

    /**
     * Returns an unmodifiable view of all exchanges, oldest first.
     */
    public List<Exchange> getAll() {
        return Collections.unmodifiableList(exchanges);
    }

    public boolean isEmpty() {
        return exchanges.isEmpty();
    }

    public void clear() {
        exchanges.clear();
    }

    /**
     * A single question-answer pair.
     */
    public record Exchange(String question, String answer) {}
}

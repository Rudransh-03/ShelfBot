package com.localfilebrain.reorg;

import java.time.Instant;

/**
 * Abstracts the LLM-call side of the reorg pipeline so the orchestrator
 * ({@link ReorgToolLoop}) can be unit-tested without standing up a
 * real OpenAI proxy.
 *
 * <p>The contract is intentionally small: open a session, then send
 * (system, user) prompts under that session until the per-session budget
 * runs out. Each {@link #chat} call corresponds to exactly one
 * {@code POST /reorg/llm} call upstream, so per-call costs are 1 LLM
 * decision.
 *
 * <p>Exception semantics:
 * <ul>
 *   <li>{@link BudgetExhausted} — session is alive but its LLM-call budget
 *       has been drained. The orchestrator stops gracefully and returns
 *       partial results.</li>
 *   <li>{@link SessionExpired} — session is gone (TTL elapsed or the proxy
 *       lost the row). Caller may open a new session and retry.</li>
 *   <li>{@link LlmHttpException} — any other upstream / network failure.
 *       The orchestrator logs and skips that specific decision, but keeps
 *       the loop running.</li>
 * </ul>
 */
public interface ReorgLlmClient {

    /** Opens a new reorg session via the proxy. */
    SessionInfo start();

    /**
     * Sends one chat-completion request against the proxy. The proxy
     * decrements the session's per-call budget atomically and forwards
     * to OpenAI. Returns the raw assistant content string — the caller
     * is responsible for parsing it.
     *
     * @param jsonMode if true, requests OpenAI's JSON-only output mode.
     *                 The system prompt must mention "JSON" or the API
     *                 will reject the call (proxy passes the error through).
     */
    default String chat(String sessionId, String systemPrompt, String userPrompt, boolean jsonMode) {
        return chat(sessionId, systemPrompt, userPrompt, jsonMode, 250);
    }

    /**
     * Same as {@link #chat(String, String, String, boolean)} but with an
     * explicit {@code maxTokens} cap on the response. The batched
     * judge-loners path uses ~1500 tokens because the JSON list can be
     * long; per-cluster naming sticks with the small default.
     */
    String chat(String sessionId, String systemPrompt, String userPrompt, boolean jsonMode, int maxTokens);

    /** Returned by {@link #start} — fields mirror the proxy response. */
    record SessionInfo(String sessionId, int llmBudget, Instant expiresAt) {}

    // -------------------------------------------------------------------------
    // Exception types
    // -------------------------------------------------------------------------

    /** Per-session LLM budget has been exhausted. Loop should stop and return partial results. */
    class BudgetExhausted extends RuntimeException {
        public BudgetExhausted(String message) { super(message); }
    }

    /** Session no longer exists (expired or never created). Caller may retry with a fresh start(). */
    class SessionExpired extends RuntimeException {
        public SessionExpired(String message) { super(message); }
    }

    /** Catch-all for unexpected upstream / network issues. */
    class LlmHttpException extends RuntimeException {
        public LlmHttpException(String message) { super(message); }
        public LlmHttpException(String message, Throwable cause) { super(message, cause); }
    }
}

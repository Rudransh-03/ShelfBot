package com.localfilebrain.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localfilebrain.query.ConversationHistory;
import com.localfilebrain.storage.ChromaDBClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Calls OpenAI's GPT-4o mini chat completions API to answer questions
 * based on retrieved document chunks.
 *
 * Prompt structure sent to the model:
 *   1. System message — strict RAG instruction (only answer from context)
 *   2. Past exchanges from ConversationHistory (user/assistant pairs)
 *   3. User message — retrieved chunks as context + current question
 *
 * Cost: ~$0.0009 per query (5250 input tokens + 300 output tokens at GPT-4o mini rates)
 */
public final class GPT4oMiniClient {

    private static final Logger log = LoggerFactory.getLogger(GPT4oMiniClient.class);

    private static final String ENDPOINT   = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL      = "gpt-4o-mini";
    private static final int    MAX_TOKENS = 1000;

    private static final String SYSTEM_PROMPT = """
        You are a personal document assistant. You answer questions strictly based on \
        the document excerpts provided to you in each message.

        Rules you must follow:
        - Only use information present in the provided excerpts. Never use outside knowledge.
        - If the excerpts do not contain enough information to answer the question, respond \
          with exactly: "I could not find relevant information in your files."
        - Always mention which file(s) the information came from (the fileName field in the context).
        - Be concise and factual. Do not speculate.
        - If the user's question refers to a previous topic (e.g., "what about that?" or \
          "compare with the previous"), use the conversation history to understand the reference.
        """;

    private final String     apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public GPT4oMiniClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key must not be blank");
        }
        this.apiKey     = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates an answer given retrieved chunks and conversation history.
     *
     * @param question the user's current question
     * @param chunks   top-K chunks retrieved from ChromaDB
     * @param history  recent conversation exchanges for context
     * @return the model's answer string
     */
    public String answer(
            String question,
            List<ChromaDBClient.QueryResult.Match> chunks,
            ConversationHistory history
    ) {
        try {
            String requestJson = buildRequest(question, chunks, history);
            log.debug("Calling GPT-4o mini...");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new LLMException("GPT-4o mini API error (" + response.statusCode() + "): " + response.body());
            }

            return parseAnswer(response.body());

        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMException("Failed to call GPT-4o mini: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Request building
    // -------------------------------------------------------------------------

    private String buildRequest(
            String question,
            List<ChromaDBClient.QueryResult.Match> chunks,
            ConversationHistory history
    ) throws Exception {

        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("temperature", 0.2); // low temperature = more factual, less creative

        ArrayNode messages = body.putArray("messages");

        // 1. System message
        addMessage(messages, "system", SYSTEM_PROMPT);

        // 2. Conversation history — gives context for follow-up questions
        for (ConversationHistory.Exchange exchange : history.getAll()) {
            addMessage(messages, "user", exchange.question());
            addMessage(messages, "assistant", exchange.answer());
        }

        // 3. Current user message: context chunks + question
        String userMessage = buildUserMessage(question, chunks);
        addMessage(messages, "user", userMessage);

        return mapper.writeValueAsString(body);
    }

    private String buildUserMessage(String question, List<ChromaDBClient.QueryResult.Match> chunks) {
        StringBuilder sb = new StringBuilder();

        sb.append("Here are the relevant excerpts from your documents:\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            ChromaDBClient.QueryResult.Match chunk = chunks.get(i);
            sb.append("--- Excerpt ").append(i + 1)
                    .append(" (from: ").append(chunk.fileName()).append(") ---\n");
            sb.append(chunk.text()).append("\n\n");
        }

        sb.append("Based only on the excerpts above, answer this question:\n");
        sb.append(question);

        return sb.toString();
    }

    private void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode msg = messages.addObject();
        msg.put("role", role);
        msg.put("content", content);
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    private String parseAnswer(String responseBody) throws Exception {
        JsonNode root    = mapper.readTree(responseBody);
        JsonNode choices = root.get("choices");

        if (choices == null || choices.isEmpty()) {
            throw new LLMException("No choices in GPT-4o mini response: " + responseBody);
        }

        String answer = choices.get(0).path("message").path("content").asText();
        if (answer.isBlank()) {
            throw new LLMException("Empty answer from GPT-4o mini");
        }

        // Log token usage for cost awareness
        JsonNode usage = root.get("usage");
        if (usage != null) {
            log.debug("Tokens used — input: {}, output: {}, total: {}",
                    usage.path("prompt_tokens").asInt(),
                    usage.path("completion_tokens").asInt(),
                    usage.path("total_tokens").asInt());
        }

        return answer.trim();
    }

    // -------------------------------------------------------------------------
    // Typed exception
    // -------------------------------------------------------------------------

    public static class LLMException extends RuntimeException {
        public LLMException(String message) { super(message); }
        public LLMException(String message, Throwable cause) { super(message, cause); }
    }
}

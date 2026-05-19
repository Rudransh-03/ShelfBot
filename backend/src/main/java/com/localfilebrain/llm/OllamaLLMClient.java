package com.localfilebrain.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localfilebrain.query.ConversationHistory;
import com.localfilebrain.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Calls Ollama's local chat completions API to answer questions
 * based on retrieved document chunks.
 *
 * Recommended model: llama3.1:8b or mistral:7b
 *   Pull with: ollama pull llama3.1:8b
 *
 * Cost: zero. Runs entirely on the user's machine.
 * Speed: 3-8 seconds on M1 Mac, 5-15 seconds on modern Windows laptop.
 */
public final class OllamaLLMClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLLMClient.class);

    private static final String SYSTEM_PROMPT = """
        You are a personal document assistant. You answer questions strictly based on \
        the document excerpts provided to you in each message.

        Rules you must follow:
        - Only use information present in the provided excerpts. Never use outside knowledge.
        - If the excerpts do not contain enough information to answer the question, say so briefly and explain what you did find instead.
        - Always mention which file(s) the information came from (the fileName field in the context).
        - Be concise and factual. Do not speculate.
        - If the user's question refers to a previous topic, use the conversation history \
          to understand the reference.
        """;

    private final String     baseUrl;
    private final String     model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaLLMClient(String baseUrl, String model) {
        this.baseUrl    = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model      = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public String answer(
            String question,
            List<VectorStore.SearchResult> chunks,
            ConversationHistory history
    ) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("stream", false);

            ObjectNode options = body.putObject("options");
            options.put("temperature", 0.2);

            ArrayNode messages = body.putArray("messages");

            // 1. System message
            addMessage(messages, "system", SYSTEM_PROMPT);

            // 2. Conversation history
            for (ConversationHistory.Exchange exchange : history.getAll()) {
                addMessage(messages, "user",      exchange.question());
                addMessage(messages, "assistant", exchange.answer());
            }

            // 3. Current user message with context
            addMessage(messages, "user", buildUserMessage(question, chunks));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(300)) // local LLM can be slow on first token
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            log.debug("Calling Ollama ({})...", model);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new LLMException("Ollama error (" + response.statusCode() + "): " + response.body());
            }

            return parseAnswer(response.body());

        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMException("Failed to call Ollama: " + e.getMessage(), e);
        }
    }

    private String buildUserMessage(String question, List<VectorStore.SearchResult> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Here are the relevant excerpts from your documents:\n\n");

        for (int i = 0; i < chunks.size(); i++) {
            VectorStore.SearchResult chunk = chunks.get(i);
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

    private String parseAnswer(String responseBody) throws Exception {
        JsonNode root    = mapper.readTree(responseBody);
        String   answer  = root.path("message").path("content").asText();
        if (answer.isBlank()) {
            throw new LLMException("Empty answer from Ollama: " + responseBody);
        }
        return answer.trim();
    }

    public static class LLMException extends RuntimeException {
        public LLMException(String message) { super(message); }
        public LLMException(String message, Throwable cause) { super(message, cause); }
    }
}

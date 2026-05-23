package com.localfilebrain.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.query.ConversationHistory;
import com.localfilebrain.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class GPT4oMiniClient {

    private static final Logger log = LoggerFactory.getLogger(GPT4oMiniClient.class);

    private static final String DIRECT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String PROXY_PATH      = "/proxy/chat/completions";
    private static final String MODEL           = "gpt-4o-mini";
    private static final int    MAX_TOKENS      = 1000;

    private static final String SYSTEM_PROMPT = """
        You are a personal document assistant. You answer questions strictly based on \
        the document excerpts provided to you in each message.

        Rules you must follow:
        - Only use information present in the provided excerpts. Never use outside knowledge \
          and never invent details that are not in the excerpts.
        - Be EXHAUSTIVE across files. The excerpts may come from multiple files. When the user \
          asks for "all", "every", "each", "list", "what are…", or any plural/enumerable item \
          (e.g. "work experiences", "projects", "skills"), enumerate EVERY relevant item from \
          EVERY excerpt — do not stop after the first match and do not summarise away details.
        - When information appears in more than one file, group your answer by file (e.g. \
          "From resume_A.pdf: …", "From resume_B.pdf: …") so the user can see what each file \
          contributes. Always cite the fileName(s) the information came from.
        - If the user gives a single topic word or a very short query (e.g. "resume", \
          "experience", "skills"), treat it as a request to summarise everything the excerpts \
          contain on that topic. Pull from EVERY excerpt that touches the topic, organised by \
          file. Do not refuse just because the query is short.
        - Only respond with exactly "I could not find relevant information in your files." \
          when none of the provided excerpts contain anything related to the question. If even \
          one excerpt is loosely related, use it and say so plainly — partial information is \
          better than refusal.
        - Be concise but complete. Prefer bullet points or short labelled sections for lists.
        - If the user's question refers to a previous topic (e.g., "what about that?" or \
          "compare with the previous"), use the conversation history to understand the reference.
        """;

    private final String         apiKey;       // direct mode only
    private final String         endpoint;
    private final boolean        proxyMode;
    private final AuthTokenStore tokenStore;   // proxy mode only
    private final ObjectMapper   mapper;

    /** Legacy direct-OpenAI constructor for tests / api.mode=direct. */
    public GPT4oMiniClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key must not be blank");
        }
        this.apiKey     = apiKey;
        this.endpoint   = DIRECT_ENDPOINT;
        this.proxyMode  = false;
        this.tokenStore = null;
        this.mapper     = new ObjectMapper();
    }

    /** Preferred constructor — routes through the auth proxy by default. */
    public GPT4oMiniClient(AppConfig config, AuthTokenStore tokenStore) {
        this.mapper = new ObjectMapper();
        if ("proxy".equals(config.getApiMode())) {
            this.proxyMode  = true;
            this.endpoint   = config.getProxyUrl() + PROXY_PATH;
            this.tokenStore = tokenStore;
            this.apiKey     = null;
            log.info("GPT4oMiniClient → proxy mode ({})", endpoint);
        } else {
            String key = config.getOpenAiApiKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "api.mode=direct but openai.api.key is not set");
            }
            this.proxyMode  = false;
            this.endpoint   = DIRECT_ENDPOINT;
            this.tokenStore = null;
            this.apiKey     = key;
            log.info("GPT4oMiniClient → direct mode (api.openai.com)");
        }
    }

    private String authHeader() {
        if (proxyMode) {
            String t = tokenStore == null ? null : tokenStore.getToken();
            if (t == null) {
                throw new LLMException("Not signed in. Sign in to ShelfBot before asking questions.");
            }
            return "Bearer " + t;
        }
        return "Bearer " + apiKey;
    }

    public String answer(
            String question,
            List<VectorStore.SearchResult> chunks,
            ConversationHistory history
    ) {
        try {
            byte[] requestBytes = mapper.writeValueAsBytes(buildRequest(question, chunks, history));
            log.debug("Calling GPT-4o mini...");

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", authHeader());
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(requestBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBytes);
            }

            int status = conn.getResponseCode();
            InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            if (status == 429) {
                // Daily limit from the auth proxy. Body shape:
                // {"error":"Daily query limit reached.","used":3,"limit":3,"remaining":0}
                String friendly = "You've hit your daily query limit. Come back tomorrow or upgrade your plan.";
                try {
                    JsonNode err = mapper.readTree(responseBody);
                    int used  = err.path("used").asInt(-1);
                    int limit = err.path("limit").asInt(-1);
                    if (limit > 0) {
                        friendly = String.format(
                                "Daily query limit reached (%d of %d used). Resets at midnight UTC.",
                                used, limit);
                    }
                } catch (Exception ignored) { /* keep default friendly message */ }
                throw new LLMException(friendly);
            }
            if (status == 401 || status == 403) {
                throw new LLMException("Your session has expired. Please sign out and sign back in.");
            }
            if (status != 200) {
                throw new LLMException("GPT-4o mini API error (" + status + "): " + responseBody);
            }

            return parseAnswer(responseBody);

        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMException("Failed to call GPT-4o mini: " + e.getMessage(), e);
        }
    }

    private ObjectNode buildRequest(
            String question,
            List<VectorStore.SearchResult> chunks,
            ConversationHistory history
    ) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("temperature", 0.2);

        ArrayNode messages = body.putArray("messages");
        addMessage(messages, "system", SYSTEM_PROMPT);

        for (ConversationHistory.Exchange exchange : history.getAll()) {
            addMessage(messages, "user", exchange.question());
            addMessage(messages, "assistant", exchange.answer());
        }

        addMessage(messages, "user", buildUserMessage(question, chunks));
        return body;
    }

    private String buildUserMessage(String question, List<VectorStore.SearchResult> chunks) {
        StringBuilder sb = new StringBuilder("Here are the relevant excerpts from your documents:\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            VectorStore.SearchResult chunk = chunks.get(i);
            sb.append("--- Excerpt ").append(i + 1)
              .append(" (from: ").append(chunk.fileName()).append(") ---\n")
              .append(chunk.text()).append("\n\n");
        }
        sb.append("Based only on the excerpts above, answer this question:\n").append(question);
        return sb.toString();
    }

    private void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode msg = messages.addObject();
        msg.put("role", role);
        msg.put("content", content);
    }

    private String parseAnswer(String responseBody) throws Exception {
        JsonNode root    = mapper.readTree(responseBody);
        JsonNode choices = root.get("choices");

        if (choices == null || choices.isEmpty()) {
            throw new LLMException("No choices in GPT-4o mini response: " + responseBody);
        }

        String answer = choices.get(0).path("message").path("content").asText();
        if (answer.isBlank()) throw new LLMException("Empty answer from GPT-4o mini");

        JsonNode usage = root.get("usage");
        if (usage != null) {
            log.debug("Tokens used — input: {}, output: {}, total: {}",
                    usage.path("prompt_tokens").asInt(),
                    usage.path("completion_tokens").asInt(),
                    usage.path("total_tokens").asInt());
        }

        return answer.trim();
    }

    public static class LLMException extends RuntimeException {
        public LLMException(String message) { super(message); }
        public LLMException(String message, Throwable cause) { super(message, cause); }
    }
}

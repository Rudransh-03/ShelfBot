package com.localfilebrain.reorg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Real {@link ReorgLlmClient} implementation. Routes through the
 * ShelfBot proxy ({@code /reorg/start} and {@code /reorg/llm}) — the
 * proxy holds the OpenAI key and enforces the per-day + per-session
 * caps. Matches the auth + HTTP pattern used by
 * {@link com.localfilebrain.llm.GPT4oMiniClient}.
 */
public final class ProxyReorgLlmClient implements ReorgLlmClient {

    private static final Logger log = LoggerFactory.getLogger(ProxyReorgLlmClient.class);

    private static final String START_PATH = "/reorg/start";
    private static final String LLM_PATH   = "/reorg/llm";
    private static final String MODEL      = "gpt-4o-mini";

    private final String         startEndpoint;
    private final String         llmEndpoint;
    private final AuthTokenStore tokenStore;
    private final ObjectMapper   mapper;

    public ProxyReorgLlmClient(AppConfig config, AuthTokenStore tokenStore) {
        String base = config.getProxyUrl();
        this.startEndpoint = base + START_PATH;
        this.llmEndpoint   = base + LLM_PATH;
        this.tokenStore    = tokenStore;
        this.mapper        = new ObjectMapper();
        log.info("ProxyReorgLlmClient → {}", base);
    }

    private String authHeader() {
        String t = tokenStore == null ? null : tokenStore.getToken();
        if (t == null) {
            throw new LlmHttpException(
                    "Not signed in. Sign in to ShelfBot before starting a reorganization.");
        }
        return "Bearer " + t;
    }

    @Override
    public SessionInfo start() {
        try {
            HttpURLConnection conn = post(startEndpoint, mapper.createObjectNode());
            int status = conn.getResponseCode();
            String body = readBody(status < 400 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();

            if (status == 429) {
                // Daily reorg cap reached — surface the proxy's friendly message
                // verbatim so the UI doesn't have to assemble it from fragments.
                String friendly = "Daily reorganization limit reached. Try again tomorrow.";
                try {
                    JsonNode err = mapper.readTree(body);
                    if (err.hasNonNull("detail")) friendly = err.path("detail").asText();
                    else if (err.hasNonNull("error")) friendly = err.path("error").asText();
                } catch (Exception ignored) {}
                throw new LlmHttpException(friendly);
            }
            if (status == 401 || status == 403) {
                throw new LlmHttpException("Your session has expired. Please sign out and sign back in.");
            }
            if (status != 200) {
                throw new LlmHttpException("Failed to start reorg session (" + status + "): " + body);
            }

            JsonNode root = mapper.readTree(body);
            String sessionId = root.path("sessionId").asText("");
            int    budget    = root.path("llmBudget").asInt(0);
            String expiresAt = root.path("expiresAt").asText("");
            if (sessionId.isEmpty() || budget <= 0) {
                throw new LlmHttpException("Proxy returned invalid session: " + body);
            }
            return new SessionInfo(sessionId, budget, parseInstant(expiresAt));

        } catch (ReorgLlmClient.BudgetExhausted | SessionExpired | LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmHttpException("Failed to open reorg session: " + e.getMessage(), e);
        }
    }

    @Override
    public String chat(String sessionId, String systemPrompt, String userPrompt, boolean jsonMode, int maxTokens) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (maxTokens < 32) maxTokens = 32;       // sanity floor
        if (maxTokens > 4000) maxTokens = 4000;   // sanity ceiling

        ObjectNode request = mapper.createObjectNode();
        request.put("sessionId",  sessionId);
        request.put("model",      MODEL);
        request.put("max_tokens", maxTokens);
        request.put("temperature", 0.2);
        if (jsonMode) {
            ObjectNode rf = request.putObject("response_format");
            rf.put("type", "json_object");
        }
        ArrayNode messages = request.putArray("messages");
        addMessage(messages, "system", systemPrompt);
        addMessage(messages, "user",   userPrompt);

        try {
            HttpURLConnection conn = post(llmEndpoint, request);
            int status = conn.getResponseCode();
            String body = readBody(status < 400 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();

            if (status == 429) {
                // Per-session LLM budget exhausted — distinct from the per-day
                // /reorg/start cap, so the loop knows to stop gracefully
                // rather than retry.
                String detail = body;
                try { detail = mapper.readTree(body).path("detail").asText(body); }
                catch (Exception ignored) {}
                throw new BudgetExhausted(detail);
            }
            if (status == 404) {
                // Session expired or not found (or belongs to another device).
                throw new SessionExpired(body);
            }
            if (status == 401 || status == 403) {
                throw new LlmHttpException("Your session has expired. Please sign out and sign back in.");
            }
            if (status != 200) {
                throw new LlmHttpException("/reorg/llm error (" + status + "): " + body);
            }

            // Standard OpenAI shape: choices[0].message.content
            JsonNode root    = mapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmHttpException("No choices in /reorg/llm response: " + body);
            }
            String content = choices.get(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new LlmHttpException("Empty content in /reorg/llm response");
            }
            return content;

        } catch (BudgetExhausted | SessionExpired | LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmHttpException("Reorg LLM call failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // HTTP plumbing
    // -------------------------------------------------------------------------

    private HttpURLConnection post(String endpoint, ObjectNode body) throws Exception {
        byte[] requestBytes = mapper.writeValueAsBytes(body);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type",  "application/json");
        conn.setRequestProperty("Authorization", authHeader());
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(requestBytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBytes);
        }
        return conn;
    }

    private static void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode msg = messages.addObject();
        msg.put("role", role);
        msg.put("content", content);
    }

    private static String readBody(InputStream is) {
        try { return is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    private static Instant parseInstant(String iso) {
        try { return iso == null || iso.isBlank() ? Instant.EPOCH : Instant.parse(iso); }
        catch (Exception e) { return Instant.EPOCH; }
    }
}

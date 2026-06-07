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
        You are a personal document assistant. Answer using only the excerpts provided.

        Rules:
        1. NEVER invent facts. Only use what's in the excerpts. No outside knowledge.
        2. ADJUST DETAIL TO THE QUESTION.
           DEFAULT (no detail word) — summarise. List items by their name/title with a \
             one-line gist. Example for "work experiences of X": \
             "Software Development Engineer at Sprinklr (Aug 2024–present) — telephony \
              system, Spring 5→6 migration, alerting framework, security work." \
             NOT a six-bullet copy of every responsibility.
           DETAIL REQUESTED — include every bullet under each item, with the original \
             wording where it matters. Trigger words: "in detail", "details", "detailed", \
             "elaborate", "expand", "expanded", "verbatim", "full", "all bullets", \
             "everything about", "tell me more", "show me the wording", "quote", \
             "as written", "exact". \
             When triggered, do not paraphrase responsibilities — preserve them.
           Either way: list EVERY item from the relevant file (every job, every \
           project, every entry). Don't drop entries; resumes are reverse-chronological \
           and split across excerpts — treat all excerpts of the same file as one \
           document.
        3. FOCUSED across files. If the question is about a specific person, project, \
           or file, only use sources actually about that subject. Skip semantically \
           similar but unrelated sources (e.g. someone else's resume for a question \
           about Person A; an ID-card scan that mentions the name in a work-experience \
           question).
        4. FORMAT. When introducing information from the excerpts, use \
           "From <filename>:" once per file with bullets for items, concise. \
           BUT: if the user's current question is a brief follow-up that only \
           confirms or clarifies something you already stated in a prior turn \
           (e.g. "really?", "yes?", "these are his work experiences?"), reply \
           in 1-2 conversational sentences WITHOUT the "From <filename>:" format \
           and WITHOUT re-listing bullets you have already given.
        5. REFUSAL. If no excerpt answers the question after applying rule 3, respond \
           with EXACTLY this sentence as your ENTIRE reply and NOTHING ELSE: \
           "I could not find relevant information in your files." \
           Do NOT append this sentence after a real answer.
        6. CONVERSATION. Use prior turns to resolve references like "what about that?".
        """;

    /**
     * System prompt used for the follow-up path — invoked when QueryEngine has
     * detected that the user's current question is a short clarification or
     * confirmation about the immediately preceding turn. Retrieval is skipped
     * for that path, so the model gets only the conversation history (no
     * excerpts block) and must answer from prior turns alone.
     *
     * Strictly forbids the "From <filename>:" format and the re-listing of
     * bullets — those are the failure mode that prompted the split.
     */
    private static final String FOLLOW_UP_SYSTEM_PROMPT = """
        You are continuing a prior conversation with the user. The user's
        current message is a brief follow-up (a confirmation, a clarification,
        or a pronoun reference) about what was just discussed.

        Rules:
        1. Answer using ONLY the information already present in the prior \
           assistant turns of this conversation. Do NOT invent facts. Do NOT \
           bring in outside knowledge. Do NOT fabricate excerpts.
        2. Respond in 1-3 conversational sentences. Do NOT use the \
           "From <filename>:" format. Do NOT re-list bullets you have already \
           given in earlier turns — the user can see them.
        3. For yes/no confirmations about facts you already stated, answer with \
           a short "Yes — …" or "No — …" plus a one-line reason that references \
           the prior turn (e.g. "Yes — those are the two listed in the resume.").
        4. If the prior turns do not contain enough information to answer the \
           follow-up, reply with EXACTLY this sentence and NOTHING ELSE: \
           "I don't have that detail from what we discussed — could you ask the \
           question with more specifics?"
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

    /**
     * Streaming variant of {@link #answer}: invokes OpenAI with
     * {@code stream: true}, pushes each delta token through the consumer
     * the moment it lands, and returns the full final answer string once
     * the stream closes. Used by the SSE endpoint in ApiServer so the
     * desktop chat UI can render the answer growing live.
     */
    public String answerStream(
            String question,
            List<VectorStore.SearchResult> chunks,
            ConversationHistory history,
            java.util.function.Consumer<String> onToken
    ) {
        return doStream(buildRequest(question, chunks, history, false), onToken);
    }

    /**
     * Follow-up streaming variant. No excerpts are sent (retrieval was skipped
     * upstream because the question is a brief clarification of the prior
     * turn); the LLM is instructed to answer from conversation history only.
     */
    public String answerFollowUpStream(
            String question,
            ConversationHistory history,
            java.util.function.Consumer<String> onToken
    ) {
        return doStream(buildRequest(question, List.of(), history, true), onToken);
    }

    private String doStream(ObjectNode requestBody, java.util.function.Consumer<String> onToken) {
        try {
            ObjectNode req = requestBody;
            req.put("stream", true);
            // Streaming responses ignore max_tokens far less reliably than
            // batched ones; keep our existing cap anyway as the safety belt.
            byte[] requestBytes = mapper.writeValueAsBytes(req);

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept",       "text/event-stream");
            conn.setRequestProperty("Authorization", authHeader());
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000); // streams can run long
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(requestBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBytes);
            }

            int status = conn.getResponseCode();
            if (status == 429) {
                String body = readBody(conn.getErrorStream());
                throw rateLimitException(body);
            }
            if (status == 401 || status == 403) {
                throw new LLMException("Your session has expired. Please sign out and sign back in.");
            }
            if (status != 200) {
                String body = readBody(conn.getErrorStream());
                throw new LLMException("GPT-4o mini API error (" + status + "): " + body);
            }

            // Parse the SSE stream line-by-line. Each event looks like:
            //   data: {"id":"...","choices":[{"delta":{"content":"He"},...}]}
            //   data: {"id":"...","choices":[{"delta":{"content":"llo"},...}]}
            //   data: [DONE]
            StringBuilder full = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || !line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.equals("[DONE]")) break;
                    try {
                        JsonNode evt = mapper.readTree(payload);
                        JsonNode delta = evt.path("choices").path(0).path("delta").path("content");
                        if (delta.isTextual()) {
                            String piece = delta.asText();
                            if (!piece.isEmpty()) {
                                full.append(piece);
                                if (onToken != null) {
                                    try { onToken.accept(piece); }
                                    catch (Exception cbErr) { log.warn("token consumer threw: {}", cbErr.getMessage()); }
                                }
                            }
                        }
                    } catch (Exception parseErr) {
                        // Malformed event — skip. OpenAI occasionally emits
                        // heartbeat / error frames that aren't our delta shape.
                        log.debug("ignored unparsable SSE line: {}", line);
                    }
                }
            }
            conn.disconnect();

            String answer = full.toString().trim();
            if (answer.isEmpty()) throw new LLMException("Empty answer from GPT-4o mini stream");
            return stripRefusalPostscript(answer);

        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMException("Streaming call to GPT-4o mini failed: " + e.getMessage(), e);
        }
    }

    private static String readBody(InputStream is) {
        try { return is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    private LLMException rateLimitException(String responseBody) {
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
        } catch (Exception ignored) {}
        return new LLMException(friendly);
    }

    public String answer(
            String question,
            List<VectorStore.SearchResult> chunks,
            ConversationHistory history
    ) {
        return doRequest(buildRequest(question, chunks, history, false));
    }

    /**
     * One-shot call with a caller-supplied system + user prompt, no
     * retrieval excerpts and no conversation history attached. Used by the
     * summarization pipeline where the prompt is fully self-contained.
     */
    public String oneShot(String systemPrompt, String userPrompt) {
        return oneShot(systemPrompt, userPrompt, MAX_TOKENS);
    }

    /**
     * One-shot call with an explicit output token ceiling. Used by tasks whose
     * structured output can exceed the default 1000 tokens — e.g. batched
     * deadline extraction emitting JSON for several documents at once.
     */
    public String oneShot(String systemPrompt, String userPrompt, int maxTokens) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        ArrayNode messages = body.putArray("messages");
        addMessage(messages, "system", systemPrompt);
        addMessage(messages, "user",   userPrompt);
        return doRequest(body);
    }

    /**
     * Follow-up non-streaming variant — see {@link #answerFollowUpStream}.
     */
    public String answerFollowUp(String question, ConversationHistory history) {
        return doRequest(buildRequest(question, List.of(), history, true));
    }

    private String doRequest(ObjectNode requestBody) {
        try {
            byte[] requestBytes = mapper.writeValueAsBytes(requestBody);
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
            ConversationHistory history,
            boolean followUp
    ) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("temperature", 0.2);

        ArrayNode messages = body.putArray("messages");
        addMessage(messages, "system", followUp ? FOLLOW_UP_SYSTEM_PROMPT : SYSTEM_PROMPT);

        if (history != null) {
            for (ConversationHistory.Exchange exchange : history.getAll()) {
                addMessage(messages, "user", exchange.question());
                addMessage(messages, "assistant", exchange.answer());
            }
        }

        // Follow-up mode bypasses retrieval entirely, so we send only the
        // raw question (no "Here are the relevant excerpts…" wrapper). The
        // model has the prior turns and the follow-up system prompt — that's
        // all the context it needs to answer conversationally.
        String userMsg = followUp ? question : buildUserMessage(question, chunks);
        addMessage(messages, "user", userMsg);
        return body;
    }

    /**
     * Groups all retrieved chunks by their source file before sending them
     * to the LLM. We do this — instead of listing each chunk as its own
     * excerpt block — because the model otherwise treats two chunks from
     * the same file as two distinct documents and emits duplicate
     * "From: foo.png" sections in the answer.
     *
     * Output shape (one section per source file, even if 5 chunks from it):
     *
     *   === Source 1: resume.pdf ===
     *   <chunk-1 text>
     *
     *   <chunk-2 text>
     *
     *   === Source 2: notes.md ===
     *   <chunk-3 text>
     *
     * File order is preserved (which itself was already diversified by the
     * QueryEngine), so the strongest match still leads.
     */
    private String buildUserMessage(String question, List<VectorStore.SearchResult> chunks) {
        // sourceFilePath → (fileName, list of chunk texts)
        java.util.LinkedHashMap<String, java.util.List<String>> byFile = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> displayName = new java.util.HashMap<>();
        for (VectorStore.SearchResult chunk : chunks) {
            byFile.computeIfAbsent(chunk.sourceFilePath(), k -> new java.util.ArrayList<>())
                  .add(chunk.text());
            displayName.putIfAbsent(chunk.sourceFilePath(), chunk.fileName());
        }

        StringBuilder sb = new StringBuilder("Here are the relevant excerpts from your documents, grouped by source file:\n\n");
        int n = 1;
        for (java.util.Map.Entry<String, java.util.List<String>> e : byFile.entrySet()) {
            sb.append("=== Source ").append(n++).append(": ").append(displayName.get(e.getKey())).append(" ===\n");
            for (int i = 0; i < e.getValue().size(); i++) {
                sb.append(e.getValue().get(i));
                if (i < e.getValue().size() - 1) sb.append("\n\n");
            }
            sb.append("\n\n");
        }

        sb.append("Based only on the excerpts above, answer this question:\n").append(question);
        sb.append("\n\nWhen citing a file, mention it ONCE per file even if you used multiple excerpts from it. ");
        sb.append("Do not output the same filename heading twice.");
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

        return stripRefusalPostscript(answer.trim());
    }

    /**
     * Strip a trailing "I could not find relevant information in your files."
     * sentence (or close variants) when it appears as a postscript after a
     * real answer. The model occasionally appends it despite explicit
     * instructions; rather than fight that with prompt engineering forever,
     * we clean it up deterministically here.
     *
     * Only strips when the answer has substantial content BEFORE the
     * sentence — otherwise we'd accidentally erase a legitimate refusal.
     */
    static String stripRefusalPostscript(String answer) {
        if (answer == null) return null;
        String s = answer.trim();
        // Anchor on the canonical sentence (with and without trailing period)
        // and a couple of close variants the model produces.
        String[] suffixes = {
            "I could not find relevant information in your files.",
            "I could not find relevant information in your files",
            "I cannot find relevant information in your files.",
            "I couldn't find relevant information in your files.",
        };
        for (String suf : suffixes) {
            if (s.endsWith(suf)) {
                String before = s.substring(0, s.length() - suf.length()).trim();
                // If there's any real content before the postscript, drop the
                // postscript. The only case we KEEP the sentence is when it's
                // the entire reply — i.e. a genuine refusal.
                if (!before.isBlank()) return before;
                return s; // genuine refusal — keep as is
            }
        }
        return s;
    }

    public static class LLMException extends RuntimeException {
        public LLMException(String message) { super(message); }
        public LLMException(String message, Throwable cause) { super(message, cause); }
    }
}

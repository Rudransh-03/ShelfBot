package com.localfilebrain.query;

import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.embedding.EmbeddingClientFactory;
import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.storage.VectorStore;
import com.localfilebrain.storage.VectorStore.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the full query pipeline:
 *   1. Short-circuit conversational greetings / thanks / farewells
 *   2. Embed question via OpenAI
 *   3. Search ChromaDB for top-K similar chunks
 *   4. Relevance threshold check
 *   5. Call GPT-4o mini with context + history (LLM is the final arbiter
 *      for borderline matches — it returns the not-found message itself
 *      when the excerpts are too weak to answer)
 *   6. Store exchange in ConversationHistory
 *   7. Return answer + source files
 */
public final class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);

    // Cosine-distance ceiling. Above this the top match is essentially unrelated
    // to the query, so we skip the LLM and return the default fallback. Below
    // this — even a loose semantic match like "resume" hitting a resume doc —
    // we let the LLM see the excerpts and decide whether it can answer.
    //
    // Tuned slightly upward (was 1.3) so that when the user has multiple files
    // on the same topic (e.g. two resumes), a borderline-scoring second file
    // isn't filtered out entirely — the LLM is the better arbiter for borderline
    // matches than a hard distance cut.
    private static final double RELEVANCE_THRESHOLD = 1.5;

    // Top-K retrieval count from ChromaDB. Intentionally much wider than the
    // number of chunks we'll actually send to the LLM, because diversification
    // happens AFTER retrieval — we need enough raw matches for every relevant
    // file to surface, not just the single best-scoring one.
    //
    // Example: with two resumes of ~6 chunks each, one resume's chunks can
    // easily occupy the top 6-10 ranks. A small TOP_K (was 12) means the
    // second resume's first chunk lands at rank 13+ and never enters the
    // candidate pool. ChromaDB/embedding cost for a wider K is negligible.
    private static final int    TOP_K               = 40;

    // After retrieval, cap how many chunks we keep per source file so the
    // prompt doesn't get dominated by one document.
    private static final int    MAX_CHUNKS_PER_FILE = 3;

    // Hard cap on chunks sent to the LLM — keeps token usage predictable.
    private static final int    MAX_CONTEXT_CHUNKS  = 10;

    private static final Set<String> GREETINGS = Set.of(
            "hi", "hii", "hiii", "hello", "helo", "hey", "heya", "hiya",
            "yo", "sup", "wassup", "whatsup", "howdy", "hola", "namaste",
            "good morning", "good afternoon", "good evening", "good night",
            "morning", "evening", "greetings"
    );
    private static final Set<String> THANKS = Set.of(
            "thanks", "thank you", "thx", "ty", "thank u", "thankyou",
            "thanks a lot", "thanks!", "many thanks"
    );
    private static final Set<String> FAREWELLS = Set.of(
            "bye", "goodbye", "good bye", "see you", "see ya", "cya",
            "later", "ttyl", "take care"
    );

    private final EmbeddingClient     embeddingClient;
    private final VectorStore         vectorStore;
    private final GPT4oMiniClient     llmClient;
    private final ConversationHistory history;
    private final boolean             ownsVectorStore;
    private final boolean             ownsEmbeddingClient;

    public QueryEngine(AppConfig config) {
        this(config, null, null, new AuthTokenStore());
    }

    public QueryEngine(AppConfig config, VectorStore sharedStore) {
        this(config, sharedStore, null, new AuthTokenStore());
    }

    public QueryEngine(AppConfig config, VectorStore sharedStore, EmbeddingClient sharedEmbedding) {
        this(config, sharedStore, sharedEmbedding, new AuthTokenStore());
    }

    /**
     * Accepts a shared {@link VectorStore}, {@link EmbeddingClient}, and
     * {@link AuthTokenStore} so the indexer and the query engine operate
     * on the same instances. Critical: the embedding model used to query
     * MUST match the one that wrote the index, or the cosine search
     * returns nonsense. The token store is what binds outgoing OpenAI
     * calls to the currently signed-in user.
     */
    public QueryEngine(AppConfig config,
                       VectorStore sharedStore,
                       EmbeddingClient sharedEmbedding,
                       AuthTokenStore tokenStore) {
        if (sharedEmbedding != null) {
            this.embeddingClient     = sharedEmbedding;
            this.ownsEmbeddingClient = false;
        } else {
            this.embeddingClient     = EmbeddingClientFactory.create(config, tokenStore);
            this.ownsEmbeddingClient = true;
        }
        if (sharedStore != null) {
            this.vectorStore     = sharedStore;
            this.ownsVectorStore = false;
        } else {
            this.vectorStore     = new VectorStore(config.getVectorIndexPath());
            this.ownsVectorStore = true;
        }
        this.llmClient = new GPT4oMiniClient(config, tokenStore);
        this.history   = new ConversationHistory(5);
    }

    public void close() {
        if (ownsVectorStore)     vectorStore.close();
        if (ownsEmbeddingClient) embeddingClient.close();
    }

    public QueryResult query(String question) {
        if (question == null || question.isBlank()) {
            return QueryResult.notFound("Please enter a question.");
        }

        String trimmed = question.trim();
        log.info("Query: {}", trimmed);

        String chatReply = handleSmallTalk(trimmed);
        if (chatReply != null) {
            history.add(trimmed, chatReply);
            return QueryResult.found(chatReply, List.of());
        }

        List<float[]> embeddings  = embeddingClient.embedBatch(List.of(trimmed));
        float[]       queryVector = embeddings.get(0);

        List<SearchResult> matches = vectorStore.query(queryVector, TOP_K);

        if (matches.isEmpty()) {
            log.info("VectorStore returned no matches");
            return notFound(trimmed);
        }

        double bestDistance = matches.get(0).distance();
        log.debug("Best match distance: {}", bestDistance);

        if (bestDistance > RELEVANCE_THRESHOLD) {
            log.info("No relevant chunks found (best distance: {})", bestDistance);
            return notFound(trimmed);
        }

        List<SearchResult> withinThreshold = matches.stream()
                .filter(m -> m.distance() <= RELEVANCE_THRESHOLD)
                .collect(Collectors.toList());

        long candidateFiles = withinThreshold.stream()
                .map(SearchResult::sourceFilePath)
                .distinct()
                .count();

        List<SearchResult> relevantMatches =
                diversifyByFile(withinThreshold, MAX_CHUNKS_PER_FILE, MAX_CONTEXT_CHUNKS);

        long finalFiles = relevantMatches.stream()
                .map(SearchResult::sourceFilePath)
                .distinct()
                .count();

        log.info("Retrieval: {} chunks in pool from {} file(s) → {} chunks sent to LLM from {} file(s)",
                withinThreshold.size(), candidateFiles, relevantMatches.size(), finalFiles);

        String answer = llmClient.answer(trimmed, relevantMatches, history);

        List<Source> sources = groupMatchesByFile(relevantMatches);

        history.add(trimmed, answer);

        log.info("Answer generated from {} chunk(s) across {} file(s)",
                relevantMatches.size(), sources.size());

        boolean answerFound = !isFallbackAnswer(answer);
        return answerFound
                ? QueryResult.found(answer, sources)
                : QueryResult.notFound(answer);
    }

    /**
     * Groups the retrieved chunks by source file so the UI can render one
     * clickable chip per file, each carrying the file's absolute path (for
     * "open in default app") and a short list of matched snippets (for the
     * hover preview).
     *
     * Snippet text is truncated to keep the response payload small and to
     * avoid leaking irrelevantly large amounts of file content into the UI.
     */
    private List<Source> groupMatchesByFile(List<SearchResult> matches) {
        // LinkedHashMap preserves the diversified ordering produced earlier
        // — most relevant file first, then per-file the best chunks first.
        LinkedHashMap<String, Source.Builder> byPath = new LinkedHashMap<>();

        for (SearchResult m : matches) {
            byPath.computeIfAbsent(
                    m.sourceFilePath(),
                    p -> new Source.Builder(m.fileName(), p)
            ).addSnippet(snippet(m.text()));
        }

        return byPath.values().stream()
                .map(Source.Builder::build)
                .collect(Collectors.toList());
    }

    private static final int SNIPPET_MAX_CHARS = 320;

    private static String snippet(String text) {
        if (text == null) return "";
        String cleaned = text.strip();
        if (cleaned.length() <= SNIPPET_MAX_CHARS) return cleaned;
        return cleaned.substring(0, SNIPPET_MAX_CHARS).stripTrailing() + "…";
    }

    public void clearHistory() {
        history.clear();
        System.out.println("Conversation history cleared.");
    }

    private QueryResult notFound(String question) {
        String message = "I could not find relevant information in your files.";
        history.add(question, message);
        return QueryResult.notFound(message);
    }

    /**
     * Returns a canned reply for conversational openers (hi, thanks, bye…)
     * so basic small-talk doesn't end up as "no information found".
     * Returns null when the input isn't pure small-talk and should go
     * through the retrieval pipeline.
     */
    private String handleSmallTalk(String question) {
        String normalised = question.toLowerCase()
                .replaceAll("[\\p{Punct}]+$", "")
                .trim();

        if (normalised.isEmpty()) return null;

        if (GREETINGS.contains(normalised)) {
            return "Hi! I'm Rudo — ask me anything about the files you've indexed.";
        }
        if (THANKS.contains(normalised)) {
            return "You're welcome! Let me know if there's anything else you'd like to look up.";
        }
        if (FAREWELLS.contains(normalised)) {
            return "Goodbye! I'll be here whenever you need to search your files again.";
        }
        return null;
    }

    private boolean isFallbackAnswer(String answer) {
        return answer != null
                && answer.toLowerCase().contains("could not find relevant information");
    }

    /**
     * Round-robin interleave of matches grouped by source file. Ensures every
     * relevant file is represented before any single file contributes a second
     * chunk — so a query like "work experience in resume" sees chunks from
     * BOTH resumes instead of only the top-scoring one.
     *
     * Grouping uses the absolute source path, not the file name, so two files
     * named the same in different folders (e.g. ~/Desktop/resume.pdf and
     * ~/Documents/resume.pdf) are correctly treated as distinct.
     */
    private List<SearchResult> diversifyByFile(
            List<SearchResult> matches,
            int perFileCap,
            int totalCap
    ) {
        LinkedHashMap<String, List<SearchResult>> byFile = new LinkedHashMap<>();
        for (SearchResult m : matches) {
            byFile.computeIfAbsent(m.sourceFilePath(), k -> new ArrayList<>()).add(m);
        }

        List<SearchResult> interleaved = new ArrayList<>();
        int round = 0;
        boolean addedThisRound = true;
        while (addedThisRound && interleaved.size() < totalCap && round < perFileCap) {
            addedThisRound = false;
            for (Map.Entry<String, List<SearchResult>> entry : byFile.entrySet()) {
                List<SearchResult> chunks = entry.getValue();
                if (round < chunks.size()) {
                    interleaved.add(chunks.get(round));
                    addedThisRound = true;
                    if (interleaved.size() >= totalCap) break;
                }
            }
            round++;
        }
        return interleaved;
    }

    public record QueryResult(
            String       answer,
            List<Source> sourceFiles,
            boolean      found
    ) {
        public static QueryResult found(String answer, List<Source> sources) {
            return new QueryResult(answer, sources, true);
        }
        public static QueryResult notFound(String message) {
            return new QueryResult(message, List.of(), false);
        }
    }

    /**
     * One source file referenced by an answer.
     *
     * @param fileName     display name shown on the chip
     * @param absolutePath full path used by the UI to open the file in the default app
     * @param snippets     truncated chunk excerpts that contributed to the answer
     */
    public record Source(
            String       fileName,
            String       absolutePath,
            List<String> snippets
    ) {
        static final class Builder {
            private final String       fileName;
            private final String       absolutePath;
            private final List<String> snippets = new ArrayList<>();

            Builder(String fileName, String absolutePath) {
                this.fileName     = fileName;
                this.absolutePath = absolutePath;
            }

            void addSnippet(String snippet) {
                if (snippet != null && !snippet.isBlank()) snippets.add(snippet);
            }

            Source build() {
                return new Source(fileName, absolutePath, List.copyOf(snippets));
            }
        }
    }
}

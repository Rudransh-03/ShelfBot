package com.localfilebrain.query;

import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.OpenAIEmbeddingClient;
import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.storage.ChromaDBClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates the full query pipeline:
 *   1. Embed question via OpenAI
 *   2. Search ChromaDB for top-K similar chunks
 *   3. Relevance threshold check
 *   4. Call GPT-4o mini with context + history
 *   5. Store exchange in ConversationHistory
 *   6. Return answer + source files
 */
public final class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);

    private static final double RELEVANCE_THRESHOLD = 0.7;
    private static final int    TOP_K               = 5;

    private final OpenAIEmbeddingClient embeddingClient;
    private final ChromaDBClient        chromaClient;
    private final GPT4oMiniClient       llmClient;
    private final ConversationHistory   history;

    public QueryEngine(AppConfig config) {
        this.embeddingClient = new OpenAIEmbeddingClient(
                config.getOpenAiApiKey(),
                config.getEmbeddingBatchSize()
        );
        this.chromaClient = new ChromaDBClient(
                config.getChromaDbUrl(),
                config.getChromaDbCollection()
        );
        this.llmClient = new GPT4oMiniClient(config.getOpenAiApiKey());
        this.history   = new ConversationHistory(5);
    }

    public QueryResult query(String question) {
        if (question == null || question.isBlank()) {
            return QueryResult.notFound("Please enter a question.");
        }

        log.info("Query: {}", question);

        List<float[]> embeddings  = embeddingClient.embedBatch(List.of(question));
        float[]       queryVector = embeddings.get(0);

        ChromaDBClient.QueryResult chromaResult = chromaClient.query(queryVector, TOP_K);
        List<ChromaDBClient.QueryResult.Match> matches = chromaResult.matches();

        if (matches.isEmpty()) {
            log.info("ChromaDB returned no matches");
            return notFound(question);
        }

        double bestDistance = matches.get(0).distance();
        log.debug("Best match distance: {}", bestDistance);

        if (bestDistance > RELEVANCE_THRESHOLD) {
            log.info("No relevant chunks found (best distance: {})", bestDistance);
            return notFound(question);
        }

        List<ChromaDBClient.QueryResult.Match> relevantMatches = matches.stream()
                .filter(m -> m.distance() <= RELEVANCE_THRESHOLD)
                .collect(Collectors.toList());

        String answer = llmClient.answer(question, relevantMatches, history);

        List<String> sources = relevantMatches.stream()
                .map(ChromaDBClient.QueryResult.Match::fileName)
                .distinct()
                .collect(Collectors.toList());

        history.add(question, answer);

        log.info("Answer generated from {} chunk(s) across {} file(s)",
                relevantMatches.size(), sources.size());

        return QueryResult.found(answer, sources);
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

    public record QueryResult(
            String       answer,
            List<String> sourceFiles,
            boolean      found
    ) {
        public static QueryResult found(String answer, List<String> sources) {
            return new QueryResult(answer, sources, true);
        }
        public static QueryResult notFound(String message) {
            return new QueryResult(message, List.of(), false);
        }
    }
}

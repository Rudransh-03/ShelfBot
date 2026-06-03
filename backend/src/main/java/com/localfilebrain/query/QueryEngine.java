package com.localfilebrain.query;

import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.embedding.EmbeddingClientFactory;
import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.storage.VectorStore;
import com.localfilebrain.storage.VectorStore.SearchResult;
import com.localfilebrain.util.PathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
    // prompt doesn't get dominated by one document. Lowered from 6 to 4 —
    // with 1800-char chunks a typical resume fits in 2-3 chunks total, and
    // 4-per-file still comfortably handles the two-resume edge case (one
    // chunk for each entry would only ever need 3-4).
    private static final int    MAX_CHUNKS_PER_FILE = 4;

    // Hard cap on chunks sent to the LLM — keeps token usage predictable.
    // Lowered from 14 to 10: round-robin diversification kept giving every
    // semantically-nearby file a free slot even when only the top 2-3 files
    // genuinely answer the question. ~30% input-token reduction with no
    // measurable accuracy hit on the validation queries.
    private static final int    MAX_CONTEXT_CHUNKS  = 10;

    // Relative-distance cutoff applied after the absolute RELEVANCE_THRESHOLD.
    // A chunk is kept only if its cosine distance is within this delta of the
    // BEST match's distance. So for a focused query whose top match scores
    // ~0.5, we drop anything beyond ~1.1 — the noise files (semantic stragglers
    // like an unrelated screenshot that happens to share a token) get pruned
    // BEFORE diversifyByFile, instead of consuming a precious slot in the
    // diversified pool. Absolute threshold still acts as the upper ceiling
    // when the top match itself is weak.
    private static final double RELATIVE_DISTANCE_DELTA = 0.6;

    // Safety floor: even if the relative cutoff would drop almost everything
    // (e.g. only the top chunk is clearly relevant), keep at least this many
    // of the next-best matches so the LLM still has enough context to answer
    // multi-part questions like "list everything about X". Stops the relative
    // filter from accidentally regressing accuracy on borderline queries.
    private static final int    MIN_KEPT_CHUNKS         = 5;

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

    // Single-word confirmation / clarification tokens. When the entire
    // question is just one of these (with optional trailing punctuation),
    // it's a follow-up about whatever was just said.
    private static final Set<String> FOLLOW_UP_ONE_WORDS = Set.of(
            "yes", "no", "yeah", "yep", "yup", "nope",
            "really", "right", "correct", "wrong", "true", "false",
            "ok", "okay", "sure", "indeed", "exactly", "huh"
    );

    // Multi-word prefixes that signal a confirmation or clarification about
    // the immediately-preceding assistant turn rather than a fresh question
    // requiring new retrieval. Kept conservative — we'd rather miss a
    // follow-up (and re-retrieve unnecessarily) than wrongly classify a
    // fresh question and answer it from stale history.
    private static final String[] FOLLOW_UP_PREFIXES = {
            "are these", "are those", "are they", "are you sure",
            "is this", "is that", "is it",
            "was that", "were those", "were these",
            "these are", "those are",
            "that's", "that is", "this is", "it is", "it's",
            "what about that", "what about it", "what about them",
            "how about that", "how about it",
            "what do you mean", "what does that mean", "can you clarify",
            "and that's", "and that is", "and these", "and those",
            "so that's", "so that is", "so these", "so those", "so it",
            "really?", "right?", "correct?",
            "do you mean", "you mean"
    };

    // If the question contains any of these "give me more / give me everything"
    // phrases, force the full retrieval path even if it superficially looks
    // like a follow-up — because the user is asking for content (bullets,
    // verbatim wording) that history alone won't have.
    private static final String[] DETAIL_TRIGGER_OVERRIDES = {
            "in detail", "details", "detailed", "elaborate",
            "expand", "expanded", "verbatim", "full", "all bullets",
            "everything about", "tell me more", "show me the wording",
            "quote", "as written", "exact"
    };

    // Number of recent question-answer exchanges kept as LLM context. For the
    // per-conversation chat feature this is rehydrated from ChatStore before
    // each query (see resetHistory/addHistoryExchange), so it bounds how far
    // back a single thread "remembers".
    private static final int HISTORY_SIZE = 20;

    private final EmbeddingClient     embeddingClient;
    private final VectorStore         vectorStore;
    private final GPT4oMiniClient     llmClient;
    // Non-final + volatile: swapped per request so each chat thread gets its
    // own rehydrated context. Mutations are guarded by the caller (ApiServer
    // serializes load→query→persist), keeping the shared engine consistent.
    private volatile ConversationHistory history;
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
        this.history   = new ConversationHistory(HISTORY_SIZE);
    }

    public void close() {
        if (ownsVectorStore)     vectorStore.close();
        if (ownsEmbeddingClient) embeddingClient.close();
    }

    /**
     * Replaces the active conversation context with an empty one. Called
     * before rehydrating a specific chat thread's history via
     * {@link #addHistoryExchange}. Distinct from {@link #clearHistory()},
     * which the legacy single-conversation endpoint uses to wipe state.
     */
    public synchronized void resetHistory() {
        this.history = new ConversationHistory(HISTORY_SIZE);
    }

    /** Appends a stored exchange while rehydrating a thread's context. */
    public synchronized void addHistoryExchange(String question, String answer) {
        this.history.add(question, answer);
    }

    /**
     * Streaming variant of {@link #query}. Resolves small-talk and "no
     * matches" cases synchronously (no LLM call → no streaming needed),
     * otherwise calls the LLM in streaming mode and pushes each token
     * delta into {@code onToken}. The returned {@link QueryResult} holds
     * the complete final answer + sources, identical to the non-streaming
     * path's shape.
     *
     * The caller (ApiServer's SSE endpoint) is expected to also surface
     * the sources to the client once the stream ends.
     */
    public QueryResult queryStream(String question, java.util.function.Consumer<String> onToken) {
        if (question == null || question.isBlank()) {
            return QueryResult.notFound("Please enter a question.");
        }

        String trimmed = question.trim();
        log.info("Query (stream): {}", trimmed);

        String chatReply = handleSmallTalk(trimmed);
        if (chatReply != null) {
            history.add(trimmed, chatReply);
            // Small-talk: emit the whole reply as one "token" so the UI can
            // still render it through the same streaming pipeline.
            if (onToken != null) onToken.accept(chatReply);
            return QueryResult.found(chatReply, List.of());
        }

        if (isConversationalFollowUp(trimmed, history)) {
            log.info("Detected follow-up question; skipping retrieval and answering from history");
            String followUpAnswer = llmClient.answerFollowUpStream(trimmed, history, onToken);
            history.add(trimmed, followUpAnswer);
            // No source chips for follow-ups — the answer comes from prior
            // turns, not from a fresh retrieval, so there are no new files
            // to cite.
            return QueryResult.found(followUpAnswer, List.of());
        }

        // File-targeted path: when the question literally names an indexed
        // file (typically by absolute path), bypass semantic search and feed
        // the LLM that file's full chunk list. Semantic vectors of "give a
        // half page brief of /Users/.../foo.pdf" don't reliably hit the
        // doc's content chunks, but the user clearly meant that one file.
        java.util.Optional<String> scoped = detectFileScope(trimmed);
        if (scoped.isPresent()) {
            String scopedAnswer = answerFromFileScopeStream(trimmed, scoped.get(), onToken);
            if (scopedAnswer != null) return scopedAnswer.isEmpty()
                    ? notFound(trimmed)
                    : scopedAnswerResult(trimmed, scoped.get(), scopedAnswer);
        }

        List<float[]> embeddings  = embeddingClient.embedBatch(List.of(trimmed));
        float[]       queryVector = embeddings.get(0);

        List<SearchResult> matches = vectorStore.query(queryVector, TOP_K);
        if (matches.isEmpty() || matches.get(0).distance() > RELEVANCE_THRESHOLD) {
            return notFound(trimmed);
        }

        List<SearchResult> withinThreshold = matches.stream()
                .filter(m -> m.distance() <= RELEVANCE_THRESHOLD)
                .collect(Collectors.toList());

        List<SearchResult> withinRelative = filterByRelativeDistance(
                withinThreshold, RELATIVE_DISTANCE_DELTA, MIN_KEPT_CHUNKS);
        if (withinRelative.size() < withinThreshold.size()) {
            log.info("Relative-distance cutoff dropped {} noisy chunk(s) "
                    + "(top={}, cutoff={}+delta={}={})",
                    withinThreshold.size() - withinRelative.size(),
                    String.format("%.3f", withinThreshold.get(0).distance()),
                    String.format("%.3f", withinThreshold.get(0).distance()),
                    RELATIVE_DISTANCE_DELTA,
                    String.format("%.3f", withinThreshold.get(0).distance() + RELATIVE_DISTANCE_DELTA));
        }

        List<SearchResult> relevantMatches =
                diversifyByFile(withinRelative, MAX_CHUNKS_PER_FILE, MAX_CONTEXT_CHUNKS);

        // Code-side template/sample filter — guarantees the LLM never sees
        // template content unless the user asked for it.
        relevantMatches = filterTemplatesIfNotAsked(relevantMatches, trimmed);
        if (relevantMatches.isEmpty()) return notFound(trimmed);

        logChunksGoingToLlm(relevantMatches);
        String answer = llmClient.answerStream(trimmed, relevantMatches, history, onToken);

        List<Source> sources = trimSourcesToCited(groupMatchesByFile(relevantMatches), answer);
        history.add(trimmed, answer);

        boolean answerFound = !isFallbackAnswer(answer);
        return answerFound
                ? QueryResult.found(answer, sources)
                : QueryResult.notFound(answer);
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

        if (isConversationalFollowUp(trimmed, history)) {
            log.info("Detected follow-up question; skipping retrieval and answering from history");
            String followUpAnswer = llmClient.answerFollowUp(trimmed, history);
            history.add(trimmed, followUpAnswer);
            return QueryResult.found(followUpAnswer, List.of());
        }

        // File-targeted path — see streaming variant for the rationale.
        java.util.Optional<String> scoped = detectFileScope(trimmed);
        if (scoped.isPresent()) {
            String scopedAnswer = answerFromFileScope(trimmed, scoped.get());
            if (scopedAnswer != null) return scopedAnswer.isEmpty()
                    ? notFound(trimmed)
                    : scopedAnswerResult(trimmed, scoped.get(), scopedAnswer);
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

        List<SearchResult> withinRelative = filterByRelativeDistance(
                withinThreshold, RELATIVE_DISTANCE_DELTA, MIN_KEPT_CHUNKS);
        if (withinRelative.size() < withinThreshold.size()) {
            log.info("Relative-distance cutoff dropped {} noisy chunk(s) "
                    + "(top={}, cutoff={}+delta={}={})",
                    withinThreshold.size() - withinRelative.size(),
                    String.format("%.3f", withinThreshold.get(0).distance()),
                    String.format("%.3f", withinThreshold.get(0).distance()),
                    RELATIVE_DISTANCE_DELTA,
                    String.format("%.3f", withinThreshold.get(0).distance() + RELATIVE_DISTANCE_DELTA));
        }

        long candidateFiles = withinRelative.stream()
                .map(SearchResult::sourceFilePath)
                .distinct()
                .count();

        List<SearchResult> relevantMatches =
                diversifyByFile(withinRelative, MAX_CHUNKS_PER_FILE, MAX_CONTEXT_CHUNKS);

        // Code-side template filter (same logic as the streaming path).
        relevantMatches = filterTemplatesIfNotAsked(relevantMatches, trimmed);
        if (relevantMatches.isEmpty()) return notFound(trimmed);

        logChunksGoingToLlm(relevantMatches);

        long finalFiles = relevantMatches.stream()
                .map(SearchResult::sourceFilePath)
                .distinct()
                .count();

        log.info("Retrieval: {} chunks in pool from {} file(s) → {} chunks sent to LLM from {} file(s)",
                withinThreshold.size(), candidateFiles, relevantMatches.size(), finalFiles);

        String answer = llmClient.answer(trimmed, relevantMatches, history);

        List<Source> sources = trimSourcesToCited(groupMatchesByFile(relevantMatches), answer);

        history.add(trimmed, answer);

        log.info("Answer generated from {} chunk(s) across {} file(s) (sources trimmed to cited: {})",
                relevantMatches.size(), sources.size(), sources.size());

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
    /**
     * Number of top-ranked source files to pull FULL chunk-by-chunk content
     * from after the initial semantic search identifies them. Avoids the
     * "best chunk wins, others ignored" failure where a label chunk outranks
     * content chunks within the same file.
     */
    private static final int FULL_FILE_EXPANSION_TOP_N = 3;

    /**
     * After semantic search ranks files, fetch every chunk of the top N
     * files in document order so the LLM sees their complete content,
     * not just whichever chunks happened to embed closest to the query.
     *
     * Inputs:
     *   diversified — the top chunks from semantic search (post-threshold,
     *                 post-diversify, post-template-filter)
     * Returns: an expanded list where the top FULL_FILE_EXPANSION_TOP_N files
     *   contribute ALL their chunks (in chunk_index order), and any
     *   remaining files keep only the chunks that scored above threshold.
     *   Capped at MAX_CONTEXT_CHUNKS total.
     */
    private List<SearchResult> expandTopFilesToFullContent(List<SearchResult> diversified) {
        if (diversified.isEmpty()) return diversified;

        // Preserve the order in which files first appear (semantic-rank order
        // because diversifyByFile maintains it).
        LinkedHashMap<String, String> fileOrder = new LinkedHashMap<>(); // path → fileName
        for (SearchResult m : diversified) {
            fileOrder.putIfAbsent(m.sourceFilePath(), m.fileName());
        }

        List<String> filePaths = new ArrayList<>(fileOrder.keySet());
        Set<String> expandPaths = filePaths.stream()
                .limit(FULL_FILE_EXPANSION_TOP_N)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        List<SearchResult> out = new ArrayList<>();

        // For top-N files: fetch ALL chunks from the index in document order.
        for (String path : expandPaths) {
            List<SearchResult> full = vectorStore.getChunksForFile(path);
            int taken = 0;
            for (SearchResult c : full) {
                if (out.size() >= MAX_CONTEXT_CHUNKS) break;
                if (taken >= MAX_CHUNKS_PER_FILE) break;
                out.add(c);
                taken++;
            }
        }

        // For other files (semantically related but not top-ranked): keep
        // only the chunks that already passed the relevance threshold.
        for (SearchResult m : diversified) {
            if (out.size() >= MAX_CONTEXT_CHUNKS) break;
            if (expandPaths.contains(m.sourceFilePath())) continue; // already added in full
            out.add(m);
        }

        log.info("Expanded {} → {} chunks: full content for top {} file(s), diversified chunks for the rest",
                diversified.size(), out.size(), expandPaths.size());
        return out;
    }

    /**
     * Files whose names match this pattern are template / sample / boilerplate
     * documents. We exclude them from retrieval results UNLESS the user's
     * question is explicitly about them — either by name (the filename appears
     * in the question) or by category (the question itself contains a
     * template-style keyword).
     *
     * This is a code-side guarantee. Prompt-only filtering was unreliable —
     * the LLM repeatedly cherry-picked the "real-looking" parts of template
     * docs even with strict instructions to drop them. Filtering chunks out
     * of the LLM input completely removes the failure mode.
     */
    private static final Pattern TEMPLATE_FILENAME = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(template|sample|example|boilerplate|placeholder|" +
            "lorem|starter|demo|blank|_default)(?:[^a-z0-9]|$)"
    );

    private static final Set<String> TEMPLATE_KEYWORDS = Set.of(
            "template", "templates", "sample", "samples", "example", "examples",
            "boilerplate", "placeholder", "demo", "blank"
    );

    /**
     * If the question doesn't mention templates / samples / a template
     * filename, strips out chunks whose source file looks like a template.
     * Returns the original list unchanged when the user IS asking about
     * those files (e.g. "what's in resume_Template.docx?").
     */
    private List<SearchResult> filterTemplatesIfNotAsked(List<SearchResult> matches, String question) {
        if (matches.isEmpty()) return matches;
        String lq = question.toLowerCase();

        // Case A: user used a template keyword → don't filter.
        for (String kw : TEMPLATE_KEYWORDS) {
            if (lq.contains(kw)) return matches;
        }

        // Case B: user mentioned a specific filename that looks template-y
        // → keep that file (but still drop other templates).
        Set<String> templateFilesUserMentioned = new java.util.HashSet<>();
        for (SearchResult m : matches) {
            String fn = m.fileName();
            if (fn == null) continue;
            if (TEMPLATE_FILENAME.matcher(fn).find() && lq.contains(fn.toLowerCase())) {
                templateFilesUserMentioned.add(fn);
            }
        }

        List<SearchResult> kept = new ArrayList<>();
        int dropped = 0;
        for (SearchResult m : matches) {
            String fn = m.fileName();
            boolean isTemplate = fn != null && TEMPLATE_FILENAME.matcher(fn).find();
            if (isTemplate && !templateFilesUserMentioned.contains(fn)) {
                dropped++;
                continue;
            }
            kept.add(m);
        }
        if (dropped > 0) {
            log.info("Filtered {} chunk(s) from template/sample-named files (question not about templates)", dropped);
        }
        return kept;
    }

    /**
     * Prints what we're about to hand the LLM: per file, how many chunks
     * and the first ~80 chars of each chunk's content. Invaluable when the
     * answer goes wrong (was the chunk even retrieved? did the filter eat it?
     * was the chunk's content actually relevant?).
     */
    private void logChunksGoingToLlm(List<SearchResult> chunks) {
        if (!log.isInfoEnabled() || chunks.isEmpty()) return;
        Map<String, Integer> byFile = new LinkedHashMap<>();
        for (SearchResult c : chunks) {
            byFile.merge(c.fileName(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("→ LLM context (");
        sb.append(chunks.size()).append(" chunks across ").append(byFile.size()).append(" file(s)): ");
        boolean first = true;
        for (var e : byFile.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(" x").append(e.getValue());
            first = false;
        }
        log.info(sb.toString());
        for (int i = 0; i < chunks.size(); i++) {
            SearchResult c = chunks.get(i);
            String preview = c.text() == null ? "" : c.text().replaceAll("\\s+", " ").trim();
            if (preview.length() > 260) preview = preview.substring(0, 260) + "…";
            log.info("    [{}] {} → \"{}\"", i + 1, c.fileName(), preview);
        }
    }

    /**
     * Trims the source list to only files that the LLM actually cited in its
     * answer. We retrieve chunks aggressively (TOP_K=40, diversified across
     * files) so the model has enough context, but most of those files get
     * filtered out by the LLM's TYPE A entity-check. Without this trim the
     * UI would show chips for retrieved-but-rejected files (e.g. an
     * Aadhaar scan turning up next to a "work experience" answer just
     * because the chunk text contained the user's name).
     *
     * Heuristic: a source is "cited" if its fileName appears as a substring
     * of the answer text. The prompt requires the LLM to cite each used file
     * by name, so this hits the common case. Safe fallback: if the answer
     * cites NOTHING (rare — e.g. small-talk), return the full source list
     * unchanged so the user isn't left without any provenance.
     */
    private List<Source> trimSourcesToCited(List<Source> all, String answer) {
        if (all.isEmpty() || answer == null || answer.isBlank()) return all;
        List<Source> cited = new ArrayList<>();
        for (Source s : all) {
            if (s.fileName() != null && answer.contains(s.fileName())) {
                cited.add(s);
            }
        }
        // If nothing matched, fall back to the full list — better to show
        // potentially-related sources than none at all.
        return cited.isEmpty() ? all : cited;
    }

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

    // Absolute paths in user questions: must start with '/' and end at the
    // first whitespace OR end of string. Filenames on macOS can contain
    // spaces (rare in pasted paths), so this won't handle every case; the
    // user can drop the space-containing path with a trailing newline to
    // force termination.
    private static final Pattern ABS_PATH_IN_QUESTION = Pattern.compile(
            "(/[^\\s\\n]+\\.[A-Za-z0-9]{1,8})"
    );

    /**
     * Returns the canonical path of an indexed file explicitly named in the
     * question (typically pasted as an absolute path), or empty if no such
     * file is found in the index. A path is considered indexed only when
     * the Lucene store has at least one chunk tagged with it.
     */
    private java.util.Optional<String> detectFileScope(String question) {
        if (question == null) return java.util.Optional.empty();
        java.util.regex.Matcher m = ABS_PATH_IN_QUESTION.matcher(question);
        while (m.find()) {
            String candidate = m.group(1);
            // Trim trailing punctuation that's almost never part of a real path.
            while (candidate.endsWith(".") || candidate.endsWith(",")
                    || candidate.endsWith(";") || candidate.endsWith(":")
                    || candidate.endsWith(")") || candidate.endsWith("]")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            String canonical = PathNormalizer.canonical(candidate);
            List<SearchResult> chunks = vectorStore.getChunksForFile(canonical);
            if (!chunks.isEmpty()) return java.util.Optional.of(canonical);
        }
        return java.util.Optional.empty();
    }

    /**
     * Calls the LLM with every indexed chunk of {@code path} (capped at
     * {@link #MAX_CONTEXT_CHUNKS}) as context. Returns the answer text, or
     * an empty string if the file had zero retrievable chunks, or null if
     * the caller should fall through to the normal semantic-search path.
     */
    private String answerFromFileScope(String question, String path) {
        List<SearchResult> chunks = vectorStore.getChunksForFile(path);
        if (chunks.isEmpty()) return null;
        if (chunks.size() > MAX_CONTEXT_CHUNKS) chunks = chunks.subList(0, MAX_CONTEXT_CHUNKS);
        log.info("File-scoped retrieval: {} chunk(s) from {}", chunks.size(), path);
        logChunksGoingToLlm(chunks);
        return llmClient.answer(question, chunks, history);
    }

    /** Streaming variant of {@link #answerFromFileScope}. */
    private String answerFromFileScopeStream(String question, String path,
                                             java.util.function.Consumer<String> onToken) {
        List<SearchResult> chunks = vectorStore.getChunksForFile(path);
        if (chunks.isEmpty()) return null;
        if (chunks.size() > MAX_CONTEXT_CHUNKS) chunks = chunks.subList(0, MAX_CONTEXT_CHUNKS);
        log.info("File-scoped retrieval (stream): {} chunk(s) from {}", chunks.size(), path);
        logChunksGoingToLlm(chunks);
        return llmClient.answerStream(question, chunks, history, onToken);
    }

    /** Wraps a file-scoped answer in the same result shape as semantic search. */
    private QueryResult scopedAnswerResult(String question, String path, String answer) {
        List<SearchResult> chunks = vectorStore.getChunksForFile(path);
        List<Source> sources = groupMatchesByFile(chunks);
        history.add(question, answer);
        return isFallbackAnswer(answer)
                ? QueryResult.notFound(answer)
                : QueryResult.found(answer, sources);
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
     * True when the user's current message is a short clarification or
     * confirmation about the immediately preceding turn — the kind of
     * message that should be answered conversationally from history alone,
     * with no fresh retrieval.
     *
     * Bypassing retrieval here matters because the main retrieval+prompt
     * path is bound by the strict "From <filename>:" output format, so a
     * pronoun question like "these are his work experiences?" was getting
     * a re-templated source dump instead of a simple confirmation.
     *
     * Conservative on purpose: detail-trigger words ("in detail", "expand",
     * "verbatim", …) force the full retrieval path even when the surface
     * looks like a follow-up, because the user needs content history alone
     * won't have.
     */
    static boolean isConversationalFollowUp(String question, ConversationHistory history) {
        if (history == null || history.isEmpty()) return false;
        if (question == null) return false;

        String trimmed = question.trim();
        if (trimmed.isEmpty()) return false;
        // Generous upper bound: real follow-ups in this app are short.
        if (trimmed.length() > 70) return false;

        String lower = trimmed.toLowerCase();

        // If the user asked for detail / verbatim / bullets, they need the
        // retrieval path — history alone won't carry the source text.
        for (String t : DETAIL_TRIGGER_OVERRIDES) {
            if (lower.contains(t)) return false;
        }

        // Strip trailing punctuation for the equality / prefix checks.
        String normalised = lower.replaceAll("[\\p{Punct}]+$", "").trim();
        if (normalised.isEmpty()) return false;

        if (FOLLOW_UP_ONE_WORDS.contains(normalised)) return true;

        for (String prefix : FOLLOW_UP_PREFIXES) {
            if (normalised.startsWith(prefix)) return true;
        }
        return false;
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

    /**
     * The LLM is instructed to emit the exact "I could not find relevant
     * information in your files." sentence as its WHOLE response only when
     * nothing matched. Treat the answer as a refusal only when that's
     * essentially all it says — not when the phrase appears as a footer
     * after a real answer. Otherwise a model that hedges with a
     * postscript causes us to drop a perfectly good answer + its sources.
     */
    private boolean isFallbackAnswer(String answer) {
        if (answer == null) return false;
        String stripped = answer.trim().toLowerCase();
        if (stripped.isEmpty()) return false;
        // Strip a trailing period for the equality check.
        String noDot = stripped.endsWith(".") ? stripped.substring(0, stripped.length() - 1) : stripped;
        if (noDot.equals("i could not find relevant information in your files")) return true;
        // Short answers that are essentially just the refusal sentence (e.g.
        // the LLM might say "Sorry — I could not find relevant information in your files.")
        return stripped.length() < 120 && stripped.contains("could not find relevant information");
    }

    /**
     * Drops chunks whose distance is more than {@code delta} worse than the
     * top match's distance, with a {@code minKeep} safety floor so we never
     * strand the LLM on too few chunks.
     *
     * Applied AFTER the absolute {@link #RELEVANCE_THRESHOLD} filter so the
     * absolute cap acts as an outer ceiling. Applied BEFORE
     * {@link #diversifyByFile} so noisy files don't get a free slot in the
     * diversified pool.
     *
     * Input must be in ascending-distance order (i.e. best match first),
     * which is what {@link VectorStore#query} already returns.
     */
    static List<SearchResult> filterByRelativeDistance(
            List<SearchResult> matches, double delta, int minKeep) {
        if (matches == null || matches.isEmpty()) return matches;
        double best   = matches.get(0).distance();
        double cutoff = best + delta;
        List<SearchResult> kept = matches.stream()
                .filter(m -> m.distance() <= cutoff)
                .collect(Collectors.toList());
        // Safety floor: if the relative cutoff would over-prune, keep the
        // top minKeep chunks regardless so the LLM still has enough context.
        if (kept.size() < minKeep && matches.size() >= minKeep) {
            return new ArrayList<>(matches.subList(0, minKeep));
        }
        return kept;
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
    static List<SearchResult> diversifyByFile(
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

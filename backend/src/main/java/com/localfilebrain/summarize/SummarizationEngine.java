package com.localfilebrain.summarize;

import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.storage.VectorStore;
import com.localfilebrain.storage.VectorStore.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a one-page brief for a single indexed file by map-reducing its
 * Lucene chunks through GPT-4o mini.
 *
 * Sizing:
 *   - Files with ≤ {@link #SINGLE_PASS_MAX} chunks → one LLM call.
 *   - Larger files → at most {@link #MAX_PARTIAL_CALLS} partial summaries
 *     across roughly equal chunk groups, then one merge call. Total per
 *     document is capped at {@code MAX_PARTIAL_CALLS + 1} (= 4) LLM calls
 *     — i.e. at most 4 daily-quota slots, since the proxy counts one slot
 *     per chat-completion call.
 *   - Documents too large to cover within that budget and the model's
 *     context window have only their leading
 *     {@code MAX_PARTIAL_CALLS * MAX_CHUNKS_PER_CALL} chunks summarized;
 *     the tail is dropped (logged) rather than overflowing a request.
 *
 * The pipeline reads from the existing vector store; there's no re-Tika
 * extraction. That keeps "Summarise" responsive on a 60-page PDF and reuses
 * the work the user already paid for at indexing time.
 */
public final class SummarizationEngine {

    private static final Logger log = LoggerFactory.getLogger(SummarizationEngine.class);

    // One LLM call comfortably summarizes a document up to this many chunks
    // (~60 × 1800 chars ≈ 27K tokens, well inside gpt-4o-mini's 128K window).
    // Raised from 12: most real documents now summarize in a SINGLE call,
    // which is cheaper (no repeated system prompt, no re-reading partials) and
    // more accurate (the model sees the whole document at once) than splitting
    // everything bigger than ~24 pages into map-reduce.
    private static final int SINGLE_PASS_MAX     = 60;

    // Hard cap on partial (map) calls. Total LLM calls per document is therefore
    // MAX_PARTIAL_CALLS + 1 (the merge) = 4, and since the proxy counts each
    // call as one daily-quota slot, a single summary can never cost more than 4.
    private static final int MAX_PARTIAL_CALLS   = 3;

    // Window-safety ceiling on how many chunks any one call may carry, so a
    // partial can never overflow the 128K context once MAX_PARTIAL_CALLS is
    // small. 150 × 1800 chars ≈ 68K tokens, leaving ample room for the system
    // prompt and the response.
    private static final int MAX_CHUNKS_PER_CALL = 150;

    private static final String DIRECT_SYSTEM_PROMPT = """
            You write tight, scannable one-page briefs of documents.

            Output sections in this exact order:
              **TL;DR** — one sentence that captures what the document is and its purpose.
              **Key Points** — 3 to 6 bullets that DESCRIBE the document for someone \
                who hasn't opened it: what kind of document it is, what it's about, \
                the main topics or sections it covers, and how it's organised. \
                Summarise the content — do NOT copy individual questions, problems, \
                list entries, or line items verbatim. \
                (e.g. "A 25-question practice worksheet on percentages, covering \
                profit/loss, discounts, and successive change" — NOT the questions themselves.)
              **Entities** — people, organisations, products, places that appear (comma-separated).
              **Dates & Numbers** — important dates, deadlines, amounts (comma-separated).

            Rules:
              - Use only what's in the excerpts. Never invent.
              - Be concrete about what the document contains, but describe — don't transcribe.
              - If a section has no content in the source, write "—" for it.
              - Keep the whole brief under ~250 words.
            """;

    private static final String PARTIAL_SYSTEM_PROMPT = """
            You extract the load-bearing facts from a section of a document.

            Return:
              - A short paragraph (3-5 sentences) capturing what this section says.
              - A bullet list of entities, dates, and numbers worth carrying forward.

            Use only the excerpts. Never invent. Be specific over generic.
            """;

    private static final String MERGE_SYSTEM_PROMPT = """
            You merge multiple section-summaries of the same document into one
            one-page brief.

            Output sections in this exact order:
              **TL;DR** — one sentence that captures what the document is and its purpose.
              **Key Points** — 3 to 6 bullets that DESCRIBE the document end-to-end: \
                what kind of document it is, what it's about, the main topics or \
                sections it covers, and how it's organised. Summarise the content — \
                do NOT copy individual questions, problems, list entries, or line \
                items verbatim.
              **Entities** — people, organisations, products, places (comma-separated).
              **Dates & Numbers** — important dates, deadlines, amounts (comma-separated).

            Rules:
              - Use only the section summaries provided. Never invent.
              - Describe what the document contains; don't transcribe individual items.
              - De-duplicate entities/dates that appear in multiple sections.
              - Keep the whole brief under ~250 words.
            """;

    private final GPT4oMiniClient llm;
    private final VectorStore     vectorStore;

    public SummarizationEngine(GPT4oMiniClient llm, VectorStore vectorStore) {
        this.llm         = llm;
        this.vectorStore = vectorStore;
    }

    public Result summarize(String absolutePath, String fileName) {
        List<SearchResult> chunks = vectorStore.getChunksForFile(absolutePath);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No indexed chunks for: " + absolutePath);
        }

        String displayName = (fileName != null && !fileName.isBlank())
                ? fileName
                : absolutePath.substring(absolutePath.lastIndexOf('/') + 1);

        return runSummary(displayName, chunks, llm::oneShot);
    }

    /**
     * Executes the single-pass / map-reduce plan for an already-fetched chunk
     * list, invoking {@code llmCall} once per LLM call. Split out of
     * {@link #summarize} (which only adds the vector-store fetch) so the call
     * routing and chunk slicing can be driven end-to-end in tests with a fake
     * LLM and synthetic chunks — no Lucene, no real OpenAI calls.
     */
    static Result runSummary(String displayName, List<SearchResult> chunks, OneShot llmCall) {
        CallPlan plan = planCalls(chunks.size());

        if (plan.singlePass()) {
            String userPrompt = buildSinglePassPrompt(displayName, chunks);
            String summary = llmCall.call(DIRECT_SYSTEM_PROMPT, userPrompt);
            log.info("Summarized '{}' in 1 LLM call ({} chunks)", displayName, chunks.size());
            return new Result(summary, 1);
        }

        // Map-reduce path. We only cover the leading plan.chunksProcessed()
        // chunks: for almost every real document that's the whole file, but a
        // document too large for the 4-call budget + context window has its
        // tail dropped (logged) rather than overflowing a single request.
        List<SearchResult> work = chunks.size() > plan.chunksProcessed()
                ? chunks.subList(0, plan.chunksProcessed())
                : chunks;
        if (work.size() < chunks.size()) {
            log.warn("'{}' has {} chunks; summarizing the leading {} to stay within "
                    + "the {}-call budget and context window",
                    displayName, chunks.size(), work.size(), MAX_PARTIAL_CALLS + 1);
        }

        int groups = plan.partialCalls();
        int per    = plan.chunksPerCall();

        List<String> partials = new ArrayList<>(groups);
        int actualGroupCount = 0;
        for (int g = 0; g < groups; g++) {
            int from = g * per;
            if (from >= work.size()) break;
            int to = Math.min(work.size(), from + per);
            List<SearchResult> slice = work.subList(from, to);
            String prompt = buildPartialPrompt(displayName, g + 1, groups, slice);
            partials.add(llmCall.call(PARTIAL_SYSTEM_PROMPT, prompt));
            actualGroupCount++;
        }

        String mergePrompt = buildMergePrompt(displayName, partials);
        String summary = llmCall.call(MERGE_SYSTEM_PROMPT, mergePrompt);
        int totalCalls = actualGroupCount + 1;
        log.info("Summarized '{}' via map-reduce: {} partials + 1 merge = {} LLM calls "
                + "({} of {} chunks)",
                displayName, actualGroupCount, totalCalls, work.size(), chunks.size());
        return new Result(summary, totalCalls);
    }

    /** Seam over {@link GPT4oMiniClient#oneShot} so the summary execution can
     *  be unit-tested with a fake LLM. */
    @FunctionalInterface
    interface OneShot { String call(String systemPrompt, String userPrompt); }

    /**
     * Pure, side-effect-free plan for how a document of {@code chunkCount}
     * chunks will be summarized — extracted so the call/slot accounting can be
     * unit-tested without invoking the LLM. It encodes the whole cost contract:
     *
     *   - ≤ SINGLE_PASS_MAX chunks  → single pass, 1 call.
     *   - more                      → {@code partialCalls} partials + 1 merge,
     *                                 with {@code partialCalls ≤ MAX_PARTIAL_CALLS}
     *                                 (≤ 4 calls total) and every group
     *                                 ≤ MAX_CHUNKS_PER_CALL chunks.
     *   - too large for that budget → only the leading {@code chunksProcessed}
     *                                 chunks are covered.
     */
    static CallPlan planCalls(int chunkCount) {
        if (chunkCount <= SINGLE_PASS_MAX) {
            return new CallPlan(true, 0, Math.max(chunkCount, 0), Math.max(chunkCount, 0));
        }
        int coverable = MAX_PARTIAL_CALLS * MAX_CHUNKS_PER_CALL;
        int processed = Math.min(chunkCount, coverable);
        // Aim for ~SINGLE_PASS_MAX chunks per group (quality), capped at
        // MAX_PARTIAL_CALLS groups (cost). Because processed ≤ coverable, the
        // resulting group size never exceeds MAX_CHUNKS_PER_CALL (window safety).
        int groups = Math.min(MAX_PARTIAL_CALLS,
                (processed + SINGLE_PASS_MAX - 1) / SINGLE_PASS_MAX);
        int per = (int) Math.ceil(processed / (double) groups);
        return new CallPlan(false, groups, processed, per);
    }

    /**
     * @param singlePass      true → one direct call; false → map-reduce.
     * @param partialCalls    number of partial (map) calls (0 when single-pass).
     * @param chunksProcessed how many leading chunks are actually summarized.
     * @param chunksPerCall   largest chunk group handed to any one call.
     */
    record CallPlan(boolean singlePass, int partialCalls, int chunksProcessed, int chunksPerCall) {
        int totalCalls() { return singlePass ? 1 : partialCalls + 1; }
    }

    private static String buildSinglePassPrompt(String fileName, List<SearchResult> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source file: ").append(fileName).append("\n\n");
        sb.append("Excerpts (in document order):\n\n");
        appendChunks(sb, chunks);
        sb.append("\nWrite the one-page brief now.");
        return sb.toString();
    }

    private static String buildPartialPrompt(String fileName, int groupIdx, int groupTotal, List<SearchResult> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source file: ").append(fileName)
          .append("  (section ").append(groupIdx).append(" of ").append(groupTotal).append(")\n\n");
        sb.append("Excerpts (in document order):\n\n");
        appendChunks(sb, chunks);
        sb.append("\nSummarize this section now.");
        return sb.toString();
    }

    private static String buildMergePrompt(String fileName, List<String> partials) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source file: ").append(fileName).append("\n\n");
        sb.append("Section summaries:\n\n");
        for (int i = 0; i < partials.size(); i++) {
            sb.append("=== Section ").append(i + 1).append(" ===\n");
            sb.append(partials.get(i)).append("\n\n");
        }
        sb.append("Merge into one brief now.");
        return sb.toString();
    }

    private static void appendChunks(StringBuilder sb, List<SearchResult> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            sb.append("--- chunk ").append(i + 1).append(" ---\n");
            sb.append(chunks.get(i).text() == null ? "" : chunks.get(i).text());
            sb.append("\n\n");
        }
    }

    public record Result(String summary, int llmCalls) {}
}

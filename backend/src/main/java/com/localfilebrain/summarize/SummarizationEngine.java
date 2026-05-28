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
 *     document is capped at {@code MAX_PARTIAL_CALLS + 1} (= 10), matching
 *     the credit budget noted on the feature card.
 *
 * The pipeline reads from the existing vector store; there's no re-Tika
 * extraction. That keeps "Summarise" responsive on a 60-page PDF and reuses
 * the work the user already paid for at indexing time.
 */
public final class SummarizationEngine {

    private static final Logger log = LoggerFactory.getLogger(SummarizationEngine.class);

    private static final int SINGLE_PASS_MAX  = 12;
    private static final int MAX_PARTIAL_CALLS = 9;

    private static final String DIRECT_SYSTEM_PROMPT = """
            You write tight, scannable one-page briefs of documents.

            Output sections in this exact order:
              **TL;DR** — one sentence that captures the document's purpose.
              **Key Points** — 3 to 6 bullets, each a fact or argument from the document.
              **Entities** — people, organisations, products, places that appear (comma-separated).
              **Dates & Numbers** — important dates, deadlines, amounts (comma-separated).

            Rules:
              - Use only what's in the excerpts. Never invent.
              - Be concrete. Names, amounts, dates over generic phrases.
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
              **TL;DR** — one sentence that captures the document's purpose.
              **Key Points** — 3 to 6 bullets covering the document end-to-end.
              **Entities** — people, organisations, products, places (comma-separated).
              **Dates & Numbers** — important dates, deadlines, amounts (comma-separated).

            Rules:
              - Use only the section summaries provided. Never invent.
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

        if (chunks.size() <= SINGLE_PASS_MAX) {
            String userPrompt = buildSinglePassPrompt(displayName, chunks);
            String summary = llm.oneShot(DIRECT_SYSTEM_PROMPT, userPrompt);
            log.info("Summarized '{}' in 1 LLM call ({} chunks)", displayName, chunks.size());
            return new Result(summary, 1);
        }

        // Map-reduce path. Distribute chunks across at most MAX_PARTIAL_CALLS
        // groups so the total never exceeds MAX_PARTIAL_CALLS + 1 LLM calls.
        int groups = Math.min(MAX_PARTIAL_CALLS, (chunks.size() + SINGLE_PASS_MAX - 1) / SINGLE_PASS_MAX);
        int per    = (int) Math.ceil(chunks.size() / (double) groups);

        List<String> partials = new ArrayList<>(groups);
        int actualGroupCount = 0;
        for (int g = 0; g < groups; g++) {
            int from = g * per;
            if (from >= chunks.size()) break;
            int to = Math.min(chunks.size(), from + per);
            List<SearchResult> slice = chunks.subList(from, to);
            String prompt = buildPartialPrompt(displayName, g + 1, groups, slice);
            partials.add(llm.oneShot(PARTIAL_SYSTEM_PROMPT, prompt));
            actualGroupCount++;
        }

        String mergePrompt = buildMergePrompt(displayName, partials);
        String summary = llm.oneShot(MERGE_SYSTEM_PROMPT, mergePrompt);
        int totalCalls = actualGroupCount + 1;
        log.info("Summarized '{}' via map-reduce: {} partials + 1 merge = {} LLM calls ({} chunks)",
                displayName, actualGroupCount, totalCalls, chunks.size());
        return new Result(summary, totalCalls);
    }

    private String buildSinglePassPrompt(String fileName, List<SearchResult> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source file: ").append(fileName).append("\n\n");
        sb.append("Excerpts (in document order):\n\n");
        appendChunks(sb, chunks);
        sb.append("\nWrite the one-page brief now.");
        return sb.toString();
    }

    private String buildPartialPrompt(String fileName, int groupIdx, int groupTotal, List<SearchResult> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source file: ").append(fileName)
          .append("  (section ").append(groupIdx).append(" of ").append(groupTotal).append(")\n\n");
        sb.append("Excerpts (in document order):\n\n");
        appendChunks(sb, chunks);
        sb.append("\nSummarize this section now.");
        return sb.toString();
    }

    private String buildMergePrompt(String fileName, List<String> partials) {
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

    private void appendChunks(StringBuilder sb, List<SearchResult> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            sb.append("--- chunk ").append(i + 1).append(" ---\n");
            sb.append(chunks.get(i).text() == null ? "" : chunks.get(i).text());
            sb.append("\n\n");
        }
    }

    public record Result(String summary, int llmCalls) {}
}

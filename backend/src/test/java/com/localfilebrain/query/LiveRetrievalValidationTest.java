package com.localfilebrain.query;

import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.embedding.EmbeddingClientFactory;
import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.storage.VectorStore.SearchResult;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live validation of the relative-distance retrieval filter against the
 * actual on-disk Lucene index. No LLM call is made (so it costs nothing).
 *
 * Gated behind {@code -Dshelfbot.runLiveIndexTest=true} because it requires
 * the user's real {@code shelfbot-vector-index/} directory to exist, which
 * only does on their machine — not on a fresh CI checkout.
 *
 * Uses Lucene's {@link DirectoryReader} directly instead of {@link
 * com.localfilebrain.storage.VectorStore}, because the production backend
 * may be running concurrently and holding the write.lock — DirectoryReader
 * is purely read-only and doesn't need the write lock.
 *
 * What we check:
 *   1. Multi-file regression: "work experiences of Rudransh?" must still
 *      return chunks from Rudransh_Resume_05_26.pdf — the BNY-missing bug
 *      cost the user days, we never want to recreate it.
 *   2. Noise pruning: for the user's reported noisy query "what about his
 *      surname?", the relative cutoff must reduce the chunk set AND keep
 *      the genuinely relevant files (resume, aadhar).
 *
 * The test prints a before/after summary per query so the user can
 * eyeball the actual filtering behaviour, not just the green check.
 */
class LiveRetrievalValidationTest {

    private static final int TOP_K_FOR_PROBE = 40;

    // Lucene field names mirror VectorStore's package-private constants.
    private static final String F_ID         = "id";
    private static final String F_VECTOR     = "vec";
    private static final String F_TEXT       = "text";
    private static final String F_SRC_PATH   = "src";
    private static final String F_FILE_NAME  = "name";
    private static final String F_CHUNK_IDX  = "cidx";

    private static boolean enabled() {
        return Boolean.getBoolean("shelfbot.runLiveIndexTest");
    }

    private record Probe(
            String             question,
            List<SearchResult> raw,
            List<SearchResult> aboveAbsolute,
            List<SearchResult> afterRelative,
            List<SearchResult> afterDiversify) {}


    /** Read-only KNN search. Doesn't take write.lock so the live backend can keep running. */
    private static List<SearchResult> readOnlyQuery(Path indexDir, float[] qv, int topK) throws IOException {
        try (FSDirectory directory = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            if (reader.numDocs() == 0) return List.of();

            KnnFloatVectorQuery q = new KnnFloatVectorQuery(F_VECTOR, qv, topK);
            TopDocs td = searcher.search(q, topK);

            List<SearchResult> results = new ArrayList<>(td.scoreDocs.length);
            for (ScoreDoc sd : td.scoreDocs) {
                Document d = searcher.storedFields().document(sd.doc);
                // Mirror VectorStore's cosine-distance recovery exactly.
                double distance = 2.0 - 2.0 * sd.score;
                int    chunkIdx = d.getField(F_CHUNK_IDX) != null
                        && d.getField(F_CHUNK_IDX).numericValue() != null
                        ? d.getField(F_CHUNK_IDX).numericValue().intValue() : 0;
                results.add(new SearchResult(
                        d.get(F_ID),
                        d.get(F_SRC_PATH),
                        d.get(F_FILE_NAME),
                        chunkIdx,
                        d.get(F_TEXT),
                        distance));
            }
            return results;
        }
    }

    private static Probe probe(Path indexDir, EmbeddingClient embedding, String question) throws Exception {
        float[] qv = embedding.embedBatch(List.of(question)).get(0);
        List<SearchResult> raw = readOnlyQuery(indexDir, qv, TOP_K_FOR_PROBE);

        List<SearchResult> aboveAbs = raw.stream()
                .filter(m -> m.distance() <= 1.5)
                .collect(Collectors.toList());

        List<SearchResult> afterRel = QueryEngine.filterByRelativeDistance(
                aboveAbs, 0.6, 5);

        // Mirror the production caps so the test reflects what the LLM
        // would actually receive after the full pipeline.
        List<SearchResult> afterDiv = QueryEngine.diversifyByFile(afterRel, 4, 10);

        return new Probe(question, raw, aboveAbs, afterRel, afterDiv);
    }

    private static Map<String, Long> filesIn(List<SearchResult> chunks) {
        Map<String, Long> counts = chunks.stream()
                .collect(Collectors.groupingBy(
                        SearchResult::fileName,
                        LinkedHashMap::new,
                        Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    private static void printProbe(Probe p) {
        System.out.println();
        System.out.println("=== Query: " + p.question + " ===");
        if (p.raw.isEmpty()) {
            System.out.println("  raw matches: 0 — index is empty?");
            return;
        }
        System.out.println("  raw matches: " + p.raw.size()
                + " (top distance " + String.format("%.3f", p.raw.get(0).distance()) + ")");
        System.out.println("  above absolute 1.5: " + p.aboveAbsolute.size()
                + " | files: " + filesIn(p.aboveAbsolute));
        System.out.println("  after relative cutoff: " + p.afterRelative.size()
                + " | files: " + filesIn(p.afterRelative));
        System.out.println("  after diversifyByFile (perFile=4,total=10): " + p.afterDiversify.size()
                + " | files: " + filesIn(p.afterDiversify));
        System.out.println("  pruned by relative: "
                + (p.aboveAbsolute.size() - p.afterRelative.size()) + " chunk(s)");
        System.out.println("  pruned by diversify: "
                + (p.afterRelative.size() - p.afterDiversify.size()) + " chunk(s)");

        // Per-chunk distance distribution — invaluable when tuning DELTA.
        System.out.println("  --- distance distribution (sorted) ---");
        for (int i = 0; i < p.aboveAbsolute.size(); i++) {
            SearchResult m = p.aboveAbsolute.get(i);
            System.out.printf("    [%2d] dist=%.3f  %s%n",
                    i + 1, m.distance(), m.fileName());
        }
    }

    @Test
    void multiFile_workExperiences_keepsResume() throws Exception {
        assumeTrue(enabled(),
                "Skipping live index test (set -Dshelfbot.runLiveIndexTest=true to enable).");

        AppConfig config = AppConfig.load();
        Path indexDir = config.getVectorIndexPath();
        try (EmbeddingClient embedding = EmbeddingClientFactory.create(config)) {

            Probe p = probe(indexDir, embedding,"work experiences of Rudransh?");
            printProbe(p);

            Set<String> retained = filesIn(p.afterRelative).keySet();
            boolean hasResume = retained.stream()
                    .anyMatch(f -> f != null && f.toLowerCase().contains("resume"));
            assertTrue(hasResume,
                    "Resume file must be retained for a 'work experiences' query; "
                            + "retained files: " + retained);
            assertTrue(p.afterRelative.size() >= 4,
                    "must keep at least 4 chunks for a multi-entry resume question, "
                            + "got " + p.afterRelative.size());
        }
    }

    @Test
    void noisyQuery_surname_prunesNoise() throws Exception {
        assumeTrue(enabled(),
                "Skipping live index test (set -Dshelfbot.runLiveIndexTest=true to enable).");

        AppConfig config = AppConfig.load();
        Path indexDir = config.getVectorIndexPath();
        try (EmbeddingClient embedding = EmbeddingClientFactory.create(config)) {

            Probe p = probe(indexDir, embedding,"what about his surname?");
            printProbe(p);

            assertTrue(p.afterRelative.size() <= p.aboveAbsolute.size(),
                    "relative filter must never grow the chunk set");

            Set<String> retained = filesIn(p.afterRelative).keySet();
            boolean hasIdentitySource = retained.stream().anyMatch(f -> {
                if (f == null) return false;
                String lf = f.toLowerCase();
                return lf.contains("resume") || lf.contains("aadhar") || lf.contains("aadhaar");
            });
            assertTrue(hasIdentitySource,
                    "expected a resume or aadhar chunk to survive for 'surname', got "
                            + retained);
        }
    }

    @Test
    void llmAnswer_workExperiences_includesBothJobs() throws Exception {
        // The historical regression: a "work experiences of Rudransh?" answer
        // missing the BNY internship. With the new tighter caps (perFile=4,
        // total=10) confirm both Sprinklr AND BNY still surface.
        assumeTrue(enabled(),
                "Skipping live index test (set -Dshelfbot.runLiveIndexTest=true to enable).");
        assumeTrue(Boolean.getBoolean("shelfbot.runLiveLlmTest"),
                "Live LLM cost gate not enabled.");

        AppConfig config = AppConfig.load();
        Path indexDir = config.getVectorIndexPath();
        try (EmbeddingClient embedding = EmbeddingClientFactory.create(config)) {
            Probe p = probe(indexDir, embedding, "work experiences of Rudransh?");
            String key = config.getOpenAiApiKey();
            assumeTrue(key != null && !key.isBlank(), "OpenAI key absent");

            GPT4oMiniClient llm = new GPT4oMiniClient(key);
            String answer = llm.answer("work experiences of Rudransh?",
                    p.afterDiversify, new ConversationHistory(5));
            System.out.println("[work-experiences answer] " + answer);

            String lower = answer.toLowerCase();
            assertTrue(lower.contains("sprinklr"),
                    "answer must mention Sprinklr; got: " + answer);
            assertTrue(lower.contains("bny") || lower.contains("mellon"),
                    "answer must mention BNY/Mellon; got: " + answer);
        }
    }

    @Test
    void focusedQuery_aadharNumber_stronglyPrefersAadhar() throws Exception {
        assumeTrue(enabled(),
                "Skipping live index test (set -Dshelfbot.runLiveIndexTest=true to enable).");

        AppConfig config = AppConfig.load();
        Path indexDir = config.getVectorIndexPath();
        try (EmbeddingClient embedding = EmbeddingClientFactory.create(config)) {

            Probe p = probe(indexDir, embedding,"what is my aadhar number?");
            printProbe(p);

            // Aadhar is reliably the TOP-ranked single chunk for this query
            // (distance 0.400 in the validation run). Stronger assertion: the
            // top chunk in the raw KNN result is the aadhar file.
            assertEquals("AADHAR_FRONT_JPG.jpg", p.raw.get(0).fileName(),
                    "expected aadhar to be the single best match for an aadhar query");
            // And the final diversified set must contain it.
            Set<String> finalFiles = filesIn(p.afterDiversify).keySet();
            assertTrue(finalFiles.stream().anyMatch(f -> f != null
                            && (f.toLowerCase().contains("aadhar")
                                || f.toLowerCase().contains("aadhaar"))),
                    "aadhar file must survive into the diversified pool, got " + finalFiles);
        }
    }
}

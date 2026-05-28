package com.localfilebrain.reorg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.LocalEmbeddingClient;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IngestionPipeline;
import com.localfilebrain.storage.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-shot diagnostic that opens the live ShelfBot metadata + vector
 * stores and prints everything the reorg pipeline sees for a target
 * directory. Run with:
 *
 *   mvn test -Dtest=ReorgDiagnostic -Dshelfbot.diag=true
 *
 * Disabled by default — it touches real on-disk state (read-only).
 */
final class ReorgDiagnostic {

    private static final Path BACKEND_DIR = Paths.get(System.getProperty("user.dir"));
    private static final Path TARGET_DIR  =
            Paths.get(System.getProperty("shelfbot.diag.target",
                    "/Users/rudransh03/Desktop/test-folder"));

    @Test
    @EnabledIfSystemProperty(named = "shelfbot.diag", matches = "true")
    void diagnose() throws Exception {
        Path metaPath  = BACKEND_DIR.resolve("shelfbot-metadata.db");
        Path indexPath = BACKEND_DIR.resolve("shelfbot-vector-index");
        Path modelPath = Paths.get(System.getProperty("user.home"),
                ".shelfbot", "models", "bge-small-en-v1.5");

        System.out.println("=== ReorgDiagnostic ===");
        System.out.println("meta:  " + metaPath);
        System.out.println("index: " + indexPath);
        System.out.println("model: " + modelPath);
        System.out.println("target:" + TARGET_DIR);
        System.out.println();

        try (IndexMetadataStore meta   = new IndexMetadataStore(metaPath);
             VectorStore        vstore = new VectorStore(indexPath);
             LocalEmbeddingClient embed = new LocalEmbeddingClient(modelPath)) {

            // Mirror ApiServer.handleReorgPreview — JIT-index loose files
            // so the analyzer sees real content vectors rather than
            // filename embeddings only.
            jitIndex(TARGET_DIR, meta, vstore, embed);

            FileVectorService fvs  = new FileVectorService(vstore, meta, embed);
            DirectoryAnalyzer ana  = new DirectoryAnalyzer(fvs);
            DirectoryReorgPlan plan = ana.analyze(TARGET_DIR);

            // ── Per-file inspection ─────────────────────────────────────
            List<Path> allLoose = new ArrayList<>();
            for (Path p : plan.assignedToExisting().keySet()) allLoose.add(p);
            for (var c : plan.newClusters()) allLoose.addAll(c.members());
            allLoose.addAll(plan.loners());
            allLoose.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

            System.out.println("Loose files: " + plan.totalLooseFiles());
            System.out.println("Decisions:   " + plan.totalDecisions());
            System.out.println();

            Map<Path, float[]> vecs = new LinkedHashMap<>(plan.looseFileVectors());
            for (Path p : allLoose) {
                if (!vecs.containsKey(p)) {
                    fvs.getFileVector(p.toAbsolutePath().toString()).ifPresent(v -> vecs.put(p, v));
                }
            }

            System.out.println("Per-file metadata + first chunk:");
            for (Path p : allLoose) {
                String key = p.toAbsolutePath().toString();
                boolean filenameOnly = fvs.isFilenameOnly(key);
                int chunkCount = vstore.getChunkVectorsForFile(key).size();
                List<VectorStore.SearchResult> chunks = vstore.getChunksForFile(key);
                String snippet = chunks.isEmpty()
                        ? "(no chunks)"
                        : chunks.get(0).text().replaceAll("\\s+", " ").trim();
                if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "…";
                System.out.printf("  %-60s | chunks=%d %s%n",
                        truncate(p.getFileName().toString(), 60),
                        chunkCount,
                        filenameOnly ? "[FILENAME-ONLY]" : "");
                System.out.printf("     ↳ %s%n", snippet);
            }
            System.out.println();

            // ── Pairwise cosine similarity (file vectors) ───────────────
            System.out.println("Pairwise cosine similarity between loose file vectors:");
            List<Path> ordered = new ArrayList<>(vecs.keySet());
            ordered.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
            for (int i = 0; i < ordered.size(); i++) {
                Path a = ordered.get(i);
                for (int j = i + 1; j < ordered.size(); j++) {
                    Path b = ordered.get(j);
                    float[] va = vecs.get(a);
                    float[] vb = vecs.get(b);
                    if (va == null || vb == null) continue;
                    float sim = ClusteringEngine.cosineSimilarity(va, vb);
                    boolean shareTok = FilenameTokenizer.shareMeaningfulToken(
                            a.getFileName().toString(), b.getFileName().toString());
                    System.out.printf(Locale.US,
                            "  %-40s  ⟷  %-40s  sim=%.3f  shareTok=%s%n",
                            truncate(a.getFileName().toString(), 40),
                            truncate(b.getFileName().toString(), 40),
                            sim, shareTok);
                }
            }
            System.out.println();

            // ── Local-clustering outcome ────────────────────────────────
            System.out.println("Local clustering outcome:");
            System.out.println("  newClusters:");
            for (var c : plan.newClusters()) {
                System.out.printf("    cohesion=%.3f  files=%s%n", c.cohesion(),
                        c.members().stream().map(p -> p.getFileName().toString()).toList());
            }
            System.out.println("  loners:");
            for (Path lp : plan.loners()) {
                System.out.printf("    %s%n", lp.getFileName());
            }
            System.out.println("  assignedToExisting:");
            for (var e : plan.assignedToExisting().entrySet()) {
                System.out.printf("    %s → %s (sim=%.3f)%n",
                        e.getKey().getFileName(),
                        e.getValue().existingFolder().getFileName(),
                        e.getValue().similarity());
            }
            System.out.println();

            // ── Meaningful filename tokens per file ─────────────────────
            System.out.println("Meaningful filename tokens:");
            for (Path p : allLoose) {
                var toks = FilenameTokenizer.meaningfulTokens(p.getFileName().toString());
                System.out.printf("  %-60s → %s%n",
                        truncate(p.getFileName().toString(), 60), toks);
            }

            // ── End-to-end with live LLM (gated by shelfbot.diag.live=true) ──
            if (Boolean.getBoolean("shelfbot.diag.live")) {
                System.out.println();
                System.out.println("Running full pipeline against live OpenAI (gpt-4o-mini)...");
                runLiveLlmPipeline(plan);
            } else {
                System.out.println();
                System.out.println("(set -Dshelfbot.diag.live=true to run the LLM tool loop)");
            }
        }
    }

    /** Calls the real LLM tool loop with a direct-OpenAI client so we can see the actual proposal. */
    private void runLiveLlmPipeline(DirectoryReorgPlan plan) {
        AppConfig config = AppConfig.load();
        String apiKey = config.getOpenAiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("No openai.api.key in config.properties — skipping live LLM step.");
            return;
        }

        DirectOpenAiReorgClient llm = new DirectOpenAiReorgClient(apiKey);
        ReorgToolLoop loop = new ReorgToolLoop(llm);
        ReorgToolLoopResult llmResult = loop.run(plan);

        System.out.println("LLM attempts: " + llmResult.llmCallsAttempted()
                + " successful: " + llmResult.llmCallsSuccessful()
                + " stopped: " + llmResult.stoppedReason().orElse("no"));

        System.out.println("Named clusters:");
        for (var nc : llmResult.namedClusters()) {
            System.out.printf("  cluster %s → \"%s\" (confidence=%.2f) reason: %s%n",
                    nc.cluster().members().stream().map(p -> p.getFileName().toString()).toList(),
                    nc.naming().name(), nc.naming().confidence(), nc.naming().reason());
        }

        System.out.println("Judged loners:");
        for (var j : llmResult.judgedLoners()) {
            System.out.printf("  %s → %s '%s' (confidence=%.2f) reason: %s%n",
                    j.file().getFileName(),
                    j.judgment().placement(),
                    j.judgment().folderName(),
                    j.judgment().confidence(),
                    j.judgment().reason());
        }

        ReorgProposal proposal = new ReorgPlanBuilder().build(plan, llmResult);
        System.out.println();
        System.out.println("FINAL PROPOSAL:");
        for (var m : proposal.moves()) {
            System.out.printf("  MOVE  %-40s → %-30s  (%s, conf=%.2f)%n",
                    truncate(m.from().getFileName().toString(), 40),
                    truncate(m.to().getFileName().toString(), 30),
                    m.source(),
                    m.confidence());
        }
        for (var d : proposal.dropped()) {
            System.out.printf("  DROP  %-40s  reason=%s%n",
                    truncate(d.file().getFileName().toString(), 40),
                    d.reason());
        }
        for (var lp : proposal.leftAlone()) {
            System.out.printf("  STAY  %s%n", lp.getFileName());
        }
    }

    /**
     * Bypass for the proxy: a direct-OpenAI implementation of
     * {@link ReorgLlmClient} so the diagnostic can run the full
     * tool loop using only the api key from config.properties.
     * Not used by production code.
     */
    private static final class DirectOpenAiReorgClient implements ReorgLlmClient {
        private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
        private static final String MODEL    = "gpt-4o-mini";
        private final String apiKey;
        private final ObjectMapper mapper = new ObjectMapper();

        DirectOpenAiReorgClient(String apiKey) { this.apiKey = apiKey; }

        @Override
        public SessionInfo start() {
            return new SessionInfo("diag-session", 50, Instant.now().plusSeconds(1800));
        }

        @Override
        public String chat(String sessionId, String systemPrompt, String userPrompt,
                           boolean jsonMode, int maxTokens) {
            try {
                ObjectNode body = mapper.createObjectNode();
                body.put("model", MODEL);
                body.put("max_tokens", maxTokens);
                body.put("temperature", 0.2);
                if (jsonMode) body.putObject("response_format").put("type", "json_object");
                ArrayNode messages = body.putArray("messages");
                msg(messages, "system", systemPrompt);
                msg(messages, "user", userPrompt);

                byte[] bytes = mapper.writeValueAsBytes(body);
                HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(60_000);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }

                int status = conn.getResponseCode();
                var is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                String responseBody = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();
                if (status != 200) throw new LlmHttpException("openai " + status + ": " + responseBody);

                JsonNode root = mapper.readTree(responseBody);
                return root.path("choices").get(0).path("message").path("content").asText("");
            } catch (LlmHttpException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmHttpException("direct openai call failed: " + e.getMessage(), e);
            }
        }

        private static void msg(ArrayNode arr, String role, String content) {
            ObjectNode m = arr.addObject();
            m.put("role", role);
            m.put("content", content);
        }
    }

    /** Mirrors ApiServer.jitIndexLooseFiles for the diagnostic. */
    private static void jitIndex(Path targetDir,
                                 IndexMetadataStore meta,
                                 VectorStore vstore,
                                 LocalEmbeddingClient embed) {
        AppConfig cfg = AppConfig.load();
        java.util.Set<String> supported = new java.util.HashSet<>();
        for (String e : cfg.getSupportedExtensions()) supported.add(e.toLowerCase());

        IngestionPipeline pipeline = new IngestionPipeline(cfg, meta, vstore, embed);
        int indexed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                int dot = name.lastIndexOf('.');
                if (dot <= 0 || dot == name.length() - 1) continue;
                String ext = name.substring(dot + 1).toLowerCase();
                if (!supported.contains(ext)) continue;
                try {
                    int chunks = pipeline.indexOne(p);
                    if (chunks > 0) indexed++;
                } catch (Exception ex) {
                    System.out.println("  [jit-index skip] " + name + " — " + ex.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("[jit-index] scan failed: " + e.getMessage());
        }
        if (indexed > 0) {
            System.out.println("JIT-indexed " + indexed + " new file(s)");
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}

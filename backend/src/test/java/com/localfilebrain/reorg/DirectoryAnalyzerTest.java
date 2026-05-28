package com.localfilebrain.reorg;

import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.storage.VectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for {@link DirectoryAnalyzer} using real Lucene + SQLite
 * (in @TempDir) and a programmable embedding client. We don't index any
 * chunks in these tests — every file goes through the filename-embedding
 * fallback path, which we drive by mapping filenames to hand-crafted
 * vectors so cluster outcomes are deterministic.
 */
final class DirectoryAnalyzerTest {

    @TempDir Path tmp;

    private VectorStore                 vectorStore;
    private IndexMetadataStore          metadataStore;
    private ProgrammableEmbeddingClient embed;
    private FileVectorService           fileVectorService;
    private DirectoryAnalyzer           analyzer;

    @BeforeEach
    void setup() {
        vectorStore       = new VectorStore(tmp.resolve("idx"));
        metadataStore     = new IndexMetadataStore(tmp.resolve("meta.db"));
        embed             = new ProgrammableEmbeddingClient(4);
        fileVectorService = new FileVectorService(vectorStore, metadataStore, embed);
        analyzer          = new DirectoryAnalyzer(fileVectorService);
    }

    @AfterEach
    void teardown() throws Exception {
        vectorStore.close();
        metadataStore.close();
    }

    // -------------------------------------------------------------------------
    // Fit-to-existing pass
    // -------------------------------------------------------------------------

    @Test
    void analyze_assignsLooseFileToExistingSubdir() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path taxDir = Files.createDirectories(target.resolve("Tax"));

        Path existingTax1 = touch(taxDir, "tax_return_2022.pdf");
        Path existingTax2 = touch(taxDir, "tax_form_w2.pdf");
        Path looseTax     = touch(target, "tax_invoice_acme.pdf");
        Path loosePhoto   = touch(target, "photo_vacation.jpg");

        // All four files are tax-vector or photo-vector.
        float[] taxVec   = unit(new float[]{1, 0, 0, 0});
        float[] photoVec = unit(new float[]{0, 1, 0, 0});
        embed.map(existingTax1.getFileName().toString(), taxVec);
        embed.map(existingTax2.getFileName().toString(), taxVec);
        embed.map(looseTax.getFileName().toString(),     taxVec);
        embed.map(loosePhoto.getFileName().toString(),   photoVec);

        DirectoryReorgPlan plan = analyzer.analyze(target);

        // looseTax fits Tax/ (vector identical to folder centroid, extension family match)
        assertEquals(1, plan.assignedToExisting().size());
        assertTrue(plan.assignedToExisting().containsKey(looseTax));
        assertEquals(taxDir.toAbsolutePath(),
                plan.assignedToExisting().get(looseTax).existingFolder().toAbsolutePath());
        assertTrue(plan.assignedToExisting().get(looseTax).similarity() > 0.9f);

        // loosePhoto doesn't fit Tax/ (extension boundary IMAGES vs DOCS),
        // doesn't cluster with anything else → loner.
        assertTrue(plan.loners().contains(loosePhoto));
    }

    @Test
    void analyze_extensionBoundaryBlocksFitToExisting_evenIfVectorsMatch() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path docsDir = Files.createDirectories(target.resolve("Docs"));

        Path existingDoc  = touch(docsDir, "notes.pdf");
        Path looseImage   = touch(target, "tax_chart.jpg");

        // Both vectors identical — vector-only logic would assign the image
        // into Docs/. Extension family check must veto.
        float[] sameVec = unit(new float[]{1, 0, 0, 0});
        embed.map(existingDoc.getFileName().toString(), sameVec);
        embed.map(looseImage.getFileName().toString(),  sameVec);

        DirectoryReorgPlan plan = analyzer.analyze(target);

        assertTrue(plan.assignedToExisting().isEmpty(),
                "extension family mismatch must veto fit-to-existing");
        // loose image becomes a loner (no other image to cluster with)
        assertTrue(plan.loners().contains(looseImage));
    }

    @Test
    void analyze_belowFitThreshold_doesNotAssign() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path taxDir = Files.createDirectories(target.resolve("Tax"));

        Path existingTax = touch(taxDir, "form_w2.pdf");
        Path looseRandom = touch(target, "random_doc.pdf");

        // Cosine sim 0.5 between the two vectors — below FIT_THRESHOLD
        // (0.70 after the BGE-noise-floor recalibration).
        embed.map(existingTax.getFileName().toString(), unit(new float[]{1, 0, 0, 0}));
        embed.map(looseRandom.getFileName().toString(), unit(new float[]{0.5f, 0.866f, 0, 0}));

        DirectoryReorgPlan plan = analyzer.analyze(target);
        assertTrue(plan.assignedToExisting().isEmpty(),
                "loose-file sim 0.5 to Tax centroid is below the 0.70 FIT_THRESHOLD");
    }

    // -------------------------------------------------------------------------
    // New-cluster pass
    // -------------------------------------------------------------------------

    @Test
    void analyze_groupsLooseFilesIntoNewClusters() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));

        // Filenames share the meaningful token "vacation" — the new
        // token gate requires that for filename-only clustering. "photo"
        // alone is noise (machine-generated prefix), as is "img"/"dsc".
        Path photo1 = touch(target, "vacation_beach.jpg");
        Path photo2 = touch(target, "vacation_sunset.jpg");
        Path random = touch(target, "weird_thing.pdf");

        float[] photoVec  = unit(new float[]{0, 1, 0, 0});
        float[] randomVec = unit(new float[]{0, 0, 1, 0});   // orthogonal to photoVec
        embed.map(photo1.getFileName().toString(), photoVec);
        embed.map(photo2.getFileName().toString(), photoVec);
        embed.map(random.getFileName().toString(), randomVec);

        DirectoryReorgPlan plan = analyzer.analyze(target);

        assertTrue(plan.assignedToExisting().isEmpty(),
                "no existing subdirs to fit into");
        assertEquals(1, plan.newClusters().size());
        DirectoryReorgPlan.NewClusterCandidate cluster = plan.newClusters().get(0);
        assertEquals(2, cluster.size());
        assertTrue(cluster.members().contains(photo1));
        assertTrue(cluster.members().contains(photo2));
        assertTrue(cluster.cohesion() > 0.95f, "identical vectors should give cohesion ≈ 1");

        assertEquals(1, plan.loners().size());
        assertEquals(random, plan.loners().get(0));
    }

    @Test
    void analyze_extensionBoundarySplitsClusters() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));

        // Two loose files with IDENTICAL vectors but different extension families.
        // They'd merge under vector-only clustering; the extension boundary must
        // split them into two singletons.
        Path doc   = touch(target, "shared_topic.pdf");
        Path image = touch(target, "shared_topic.jpg");

        float[] sameVec = unit(new float[]{1, 0, 0, 0});
        embed.map(doc.getFileName().toString(),   sameVec);
        embed.map(image.getFileName().toString(), sameVec);

        DirectoryReorgPlan plan = analyzer.analyze(target);

        assertTrue(plan.newClusters().isEmpty(),
                "extension boundary must prevent cross-family cluster");
        assertEquals(2, plan.loners().size(),
                "both files should land as loners after the split");
    }

    // -------------------------------------------------------------------------
    // Filesystem hygiene
    // -------------------------------------------------------------------------

    @Test
    void analyze_skipsHiddenAndDenyListedEntries() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));

        // Should be skipped:
        Files.createDirectories(target.resolve(".git"));
        Files.createDirectories(target.resolve("node_modules"));
        Files.createFile(target.resolve(".DS_Store"));
        Files.createFile(target.resolve(".hidden.pdf"));

        // Should be analyzed:
        Path real = touch(target, "real_file.pdf");
        embed.map(real.getFileName().toString(), unit(new float[]{1, 0, 0, 0}));

        DirectoryReorgPlan plan = analyzer.analyze(target);

        assertEquals(1, plan.totalLooseFiles());
        assertTrue(plan.loners().contains(real));
        assertTrue(plan.assignedToExisting().isEmpty());
    }

    @Test
    void analyze_doesNotClusterAadharAndScreenshot_regressionForUserBug() throws IOException {
        // Headline regression test for the user's case. Two unrelated
        // images — an ID card and a macOS-style screenshot — must NOT
        // end up in the same cluster, even when their filename
        // embeddings happen to land moderately close in BGE space.
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path aadhar     = touch(target, "AADHAR_FRONT_JPG.jpg");
        Path screenshot = touch(target, "Screenshot 2026-04-13 at 12.51.07 AM.png");

        // Vectors deliberately CLOSE — well above the cluster sim threshold —
        // to prove the filename-token gate is what's protecting us, not
        // an accidental low similarity.
        embed.map(aadhar.getFileName().toString(),     unit(new float[]{1f, 0.05f, 0f, 0f}));
        embed.map(screenshot.getFileName().toString(), unit(new float[]{1f, 0.0f,  0f, 0f}));
        // Cosine sim between these is ≈ 0.999 — well above the 0.78 sim threshold.

        DirectoryReorgPlan plan = analyzer.analyze(target);

        // They must NOT share a cluster.
        assertEquals(0, plan.newClusters().size(),
                "AADHAR and a screenshot must never cluster — no shared meaningful filename token");
        // Both end up as loners (which Stage 2 LLM judging will then decide on).
        assertTrue(plan.loners().contains(aadhar),    "aadhar must end up as a loner");
        assertTrue(plan.loners().contains(screenshot),"screenshot must end up as a loner");
    }

    @Test
    void analyze_stillClustersRelatedFilenameOnlyFiles() throws IOException {
        // Companion test: the gate must NOT block legitimate clustering.
        // Two image files with a shared meaningful token ("vacation")
        // should still cluster.
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path beach  = touch(target, "vacation_beach.jpg");
        Path sunset = touch(target, "vacation_sunset.jpg");

        embed.map(beach.getFileName().toString(),  unit(new float[]{1, 0, 0, 0}));
        embed.map(sunset.getFileName().toString(), unit(new float[]{1, 0, 0, 0}));

        DirectoryReorgPlan plan = analyzer.analyze(target);

        assertEquals(1, plan.newClusters().size(),
                "shared meaningful filename token + close vectors must still cluster");
        assertEquals(2, plan.newClusters().get(0).size());
    }

    @Test
    void analyze_skipsRestrictedFolderRegardlessOfCase() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));

        // User's opt-out: any folder named "restricted" (case-insensitive)
        // must be invisible to reorg, including its contents.
        Path lowerRestricted = Files.createDirectories(target.resolve("restricted"));
        Path titleRestricted = Files.createDirectories(target.resolve("Restricted"));
        Path shoutyRestricted= Files.createDirectories(target.resolve("RESTRICTED"));
        Files.createFile(lowerRestricted.resolve("secret_a.pdf"));
        Files.createFile(titleRestricted.resolve("secret_b.pdf"));
        Files.createFile(shoutyRestricted.resolve("secret_c.pdf"));

        // Plus a normal subdir and a loose file that should be processed.
        Path normal = Files.createDirectories(target.resolve("Notes"));
        Files.createFile(normal.resolve("a.pdf"));
        Path loose = touch(target, "loose.pdf");
        embed.map("a.pdf", unit(new float[]{1, 0, 0, 0}));
        embed.map(loose.getFileName().toString(), unit(new float[]{1, 0, 0, 0}));

        DirectoryReorgPlan plan = analyzer.analyze(target);

        // Loose file count must not include anything inside any restricted/ variant.
        assertEquals(1, plan.totalLooseFiles());
        // None of the existing-subdir summaries should mention a restricted variant.
        for (var s : plan.existingSubdirs()) {
            assertNotEquals("restricted", s.path().getFileName().toString().toLowerCase(),
                    "restricted folders (in any case) must never appear in plan.existingSubdirs");
        }
        // The normal subdir IS present.
        assertTrue(plan.existingSubdirs().stream()
                .anyMatch(s -> s.path().getFileName().toString().equals("Notes")));
    }

    @Test
    void analyze_rejectsNonDirectory() throws IOException {
        Path file = Files.createFile(tmp.resolve("not_a_dir.txt"));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(file));
    }

    // -------------------------------------------------------------------------
    // Scope guard integration
    // -------------------------------------------------------------------------

    @Test
    void analyze_earlyBailsWithScopeError_whenLooseFilesExceedHardCap() throws IOException {
        Path huge = Files.createDirectories(tmp.resolve("huge"));
        // Create 1001 loose files to exceed the MAX_LOOSE_FILES = 1000 cap.
        // We deliberately do NOT map any filenames in the embedding client —
        // the early-bail path must short-circuit BEFORE any embedding call.
        for (int i = 0; i < 1001; i++) {
            Files.createFile(huge.resolve("f" + i + ".pdf"));
        }
        // Also drop in two subdirs so the suggestions list is non-empty.
        Path sub1 = Files.createDirectories(huge.resolve("Older"));
        Files.createFile(sub1.resolve("a.pdf"));
        Files.createFile(sub1.resolve("b.pdf"));
        Files.createDirectories(huge.resolve("Empty"));

        DirectoryReorgPlan plan = analyzer.analyze(huge);

        assertTrue(plan.scopeError().isPresent(),
                "huge dirs must short-circuit to ScopeError without spending embeddings");
        ScopeError err = plan.scopeError().get();
        assertEquals(1001, err.decisionsRequired());
        assertEquals(1000, err.decisionBudget());
        assertFalse(err.suggestions().isEmpty(),
                "scope error must include actionable suggestions even on the early-bail path");
        // The non-empty subdir should appear as a suggestion.
        assertTrue(err.suggestions().stream().anyMatch(s ->
                s.label() != null && s.label().contains("Older")));
    }

    @Test
    void analyze_populatesScopeError_whenOverBudget() throws IOException {
        // Tiny budget (2) so even 3 loose files trip the guard.
        DirectoryAnalyzer tinyBudgetAnalyzer = new DirectoryAnalyzer(
                fileVectorService, new ClusteringEngine(), new ScopeGuard(2));

        Path target = Files.createDirectories(tmp.resolve("target"));
        Path a = touch(target, "alpha.pdf");
        Path b = touch(target, "beta.pdf");
        Path c = touch(target, "gamma.pdf");

        // Three orthogonal vectors → three loners → 3 decisions, over budget 2.
        embed.map(a.getFileName().toString(), unit(new float[]{1, 0, 0, 0}));
        embed.map(b.getFileName().toString(), unit(new float[]{0, 1, 0, 0}));
        embed.map(c.getFileName().toString(), unit(new float[]{0, 0, 1, 0}));

        DirectoryReorgPlan plan = tinyBudgetAnalyzer.analyze(target);

        assertTrue(plan.scopeError().isPresent(), "scope error must be populated when over budget");
        assertFalse(plan.isInScope());
        ScopeError err = plan.scopeError().get();
        assertEquals(3, err.decisionsRequired());
        assertEquals(2, err.decisionBudget());
        assertFalse(err.suggestions().isEmpty(), "must always include actionable suggestions");
    }

    @Test
    void analyze_populatesFamilyCounts() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path pdf1   = touch(target, "doc1.pdf");
        Path pdf2   = touch(target, "doc2.pdf");
        Path img    = touch(target, "pic.jpg");

        embed.map(pdf1.getFileName().toString(), unit(new float[]{1, 0, 0, 0}));
        embed.map(pdf2.getFileName().toString(), unit(new float[]{1, 0, 0, 0}));
        embed.map(img.getFileName().toString(),  unit(new float[]{0, 1, 0, 0}));

        DirectoryReorgPlan plan = analyzer.analyze(target);

        assertEquals(2, plan.looseFileCountByFamily().get(ExtensionFamily.Family.DOCS));
        assertEquals(1, plan.looseFileCountByFamily().get(ExtensionFamily.Family.IMAGES));
    }

    @Test
    void analyze_populatesExistingSubdirSummaries() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path taxDir = Files.createDirectories(target.resolve("Tax"));
        Path emptyDir = Files.createDirectories(target.resolve("Empty"));

        touch(taxDir, "a.pdf");
        touch(taxDir, "b.pdf");

        // Need to map filenames so the fileVectorService doesn't blow up
        // when the analyzer profiles Tax/.
        embed.map("a.pdf", unit(new float[]{1, 0, 0, 0}));
        embed.map("b.pdf", unit(new float[]{1, 0, 0, 0}));

        DirectoryReorgPlan plan = analyzer.analyze(target);

        assertEquals(2, plan.existingSubdirs().size(),
                "both Tax and Empty should be summarized");
        // Tax should have file count 2
        assertTrue(plan.existingSubdirs().stream()
                .anyMatch(s -> s.path().getFileName().toString().equals("Tax") && s.fileCount() == 2));
        // Empty should appear with file count 0
        assertTrue(plan.existingSubdirs().stream()
                .anyMatch(s -> s.path().getFileName().toString().equals("Empty") && s.fileCount() == 0));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Path touch(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, "stub content");
        return p;
    }

    /** L2-normalize. */
    private static float[] unit(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n < 1e-9) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float)(v[i] / n);
        return out;
    }

    /**
     * Embedding client that hands back pre-registered vectors by exact
     * input string. Any unmapped input is an error — keeps tests strict
     * about which filenames they expect to embed.
     */
    private static final class ProgrammableEmbeddingClient implements EmbeddingClient {
        private final Map<String, float[]> mapping = new HashMap<>();
        private final int dim;
        ProgrammableEmbeddingClient(int dim) { this.dim = dim; }
        void map(String key, float[] v) { mapping.put(key, v); }
        @Override public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(t -> {
                float[] v = mapping.get(t);
                if (v == null) throw new IllegalStateException("Unmapped text: " + t);
                if (v.length != dim) throw new IllegalStateException("dim mismatch for " + t);
                return v;
            }).toList();
        }
        @Override public int dimensions() { return dim; }
        @Override public String modelId() { return "test-prog"; }
    }
}

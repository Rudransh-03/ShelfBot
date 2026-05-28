package com.localfilebrain.reorg;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class ReorgPlanBuilderTest {

    private static final Path TARGET = Path.of("/t");
    private static final Path TAX    = Path.of("/t/Tax");

    // -------------------------------------------------------------------------
    // sanitizeFolderName — internals worth testing because the LLM is unpredictable
    // -------------------------------------------------------------------------

    @Test
    void sanitize_replacesFilesystemIllegalCharsWithSpaces() {
        // Separators and reserved chars become spaces so "Tax/Documents" reads
        // as the user intended, rather than getting silently smashed into "TaxDocuments".
        assertEquals("Tax Documents", ReorgPlanBuilder.sanitizeFolderName("Tax/Documents"));
        assertEquals("Receipts", ReorgPlanBuilder.sanitizeFolderName("Receipts?"));   // trailing ? trimmed
        assertEquals("A B", ReorgPlanBuilder.sanitizeFolderName("A:B"));
        assertEquals("OK path",  ReorgPlanBuilder.sanitizeFolderName("OK\\path"));
        // Multiple separators in a row collapse to a single space.
        assertEquals("Foo Bar", ReorgPlanBuilder.sanitizeFolderName("Foo//Bar"));
    }

    @Test
    void sanitize_trimsTrailingDotsAndSpaces() {
        assertEquals("Photos", ReorgPlanBuilder.sanitizeFolderName("Photos..."));
        assertEquals("Photos", ReorgPlanBuilder.sanitizeFolderName("Photos   "));
        assertEquals("Photos", ReorgPlanBuilder.sanitizeFolderName(" Photos . "));
    }

    @Test
    void sanitize_rejectsDotAndDoubleDot() {
        assertEquals("", ReorgPlanBuilder.sanitizeFolderName("."));
        assertEquals("", ReorgPlanBuilder.sanitizeFolderName(".."));
    }

    @Test
    void sanitize_returnsEmptyForGarbage() {
        assertEquals("", ReorgPlanBuilder.sanitizeFolderName(""));
        assertEquals("", ReorgPlanBuilder.sanitizeFolderName(null));
        assertEquals("", ReorgPlanBuilder.sanitizeFolderName("///"));
        assertEquals("", ReorgPlanBuilder.sanitizeFolderName("  \t  "));
    }

    @Test
    void sanitize_capsLength() {
        String long_ = "A".repeat(200);
        String out = ReorgPlanBuilder.sanitizeFolderName(long_);
        assertTrue(out.length() <= 60);
    }

    // -------------------------------------------------------------------------
    // FIT_TO_EXISTING
    // -------------------------------------------------------------------------

    @Test
    void build_fitToExisting_producesMoveWhenConfident() {
        Path file = Path.of("/t/tax_receipt.pdf");
        Map<Path, DirectoryReorgPlan.ExistingFolderMatch> assigned = new LinkedHashMap<>();
        assigned.put(file, new DirectoryReorgPlan.ExistingFolderMatch(TAX, 0.85f));

        DirectoryReorgPlan plan = planWith(assigned, List.of(), List.of(),
                List.of(new DirectoryReorgPlan.ExistingSubdirSummary(TAX, 3)));
        ReorgToolLoopResult empty = emptyLlmResult();

        ReorgProposal proposal = new ReorgPlanBuilder().build(plan, empty);

        assertEquals(1, proposal.moves().size());
        ReorgProposal.ProposedMove m = proposal.moves().get(0);
        assertEquals(file, m.from());
        assertEquals(TAX, m.to());
        assertFalse(m.destinationIsNew());
        assertEquals(ReorgProposal.ProposedMove.Source.FIT_TO_EXISTING, m.source());
        assertEquals(0.85f, m.confidence(), 1e-4);
    }

    @Test
    void build_fitToExisting_droppedBelowCutoff() {
        Path file = Path.of("/t/maybe_tax.pdf");
        Map<Path, DirectoryReorgPlan.ExistingFolderMatch> assigned = new LinkedHashMap<>();
        // Cutoff is 0.6; pick a similarity comfortably below it.
        assigned.put(file, new DirectoryReorgPlan.ExistingFolderMatch(TAX, 0.45f));

        DirectoryReorgPlan plan = planWith(assigned, List.of(), List.of(),
                List.of(new DirectoryReorgPlan.ExistingSubdirSummary(TAX, 3)));

        ReorgProposal proposal = new ReorgPlanBuilder().build(plan, emptyLlmResult());
        assertTrue(proposal.moves().isEmpty());
        assertEquals(1, proposal.dropped().size());
        assertTrue(proposal.leftAlone().contains(file));
    }

    // -------------------------------------------------------------------------
    // NEW_CLUSTER
    // -------------------------------------------------------------------------

    @Test
    void build_newCluster_producesMoveForEachMember() {
        Path a = Path.of("/t/photo_a.jpg"), b = Path.of("/t/photo_b.jpg");
        var cluster = new DirectoryReorgPlan.NewClusterCandidate(List.of(a, b), 0.9f);
        var named = new ReorgToolLoopResult.NamedCluster(
                cluster, new ToolPrompts.ClusterNaming("Photos", 0.95f, ""));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(cluster), List.of(), List.of()),
                llmResultWith(List.of(named), List.of()));

        assertEquals(2, proposal.moves().size());
        for (var m : proposal.moves()) {
            assertEquals(TARGET.resolve("Photos"), m.to());
            assertTrue(m.destinationIsNew(), "new cluster → new folder");
            assertEquals(ReorgProposal.ProposedMove.Source.NEW_CLUSTER, m.source());
            // Effective confidence is the AVERAGE of naming + cohesion now:
            //   (0.95 + 0.9) / 2 = 0.925
            assertEquals(0.925f, m.confidence(), 1e-3);
        }
    }

    @Test
    void build_newCluster_droppedWhenEffectiveConfidenceBelowCutoff() {
        Path a = Path.of("/t/a.pdf"), b = Path.of("/t/b.pdf");
        // Both signals weak → average is well below the 0.6 cutoff.
        var cluster = new DirectoryReorgPlan.NewClusterCandidate(List.of(a, b), 0.3f);
        var named = new ReorgToolLoopResult.NamedCluster(
                cluster, new ToolPrompts.ClusterNaming("Stuff", 0.4f, ""));
        // (0.4 + 0.3) / 2 = 0.35 < 0.6

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(cluster), List.of(), List.of()),
                llmResultWith(List.of(named), List.of()));

        assertTrue(proposal.moves().isEmpty());
        assertEquals(2, proposal.dropped().size());
        assertTrue(proposal.leftAlone().containsAll(List.of(a, b)));
    }

    @Test
    void build_newCluster_survivesWeakLlmConfidence_whenCohesionIsHigh() {
        // Regression test for the resume.pdf + resume_template.docx case:
        // LLM gave 0.4 naming confidence (hedged on "Template"), but the
        // clustering signal is strong (cohesion 0.85). Under the old
        // product formula this was 0.34 → dropped. Average formula puts
        // it at (0.4 + 0.85) / 2 = 0.625 → barely survives the 0.6 cutoff.
        Path a = Path.of("/t/resume.pdf"), b = Path.of("/t/resume_template.docx");
        var cluster = new DirectoryReorgPlan.NewClusterCandidate(List.of(a, b), 0.85f);
        var named = new ReorgToolLoopResult.NamedCluster(
                cluster, new ToolPrompts.ClusterNaming("Resumes", 0.4f, "hedged on template"));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(cluster), List.of(), List.of()),
                llmResultWith(List.of(named), List.of()));

        assertEquals(2, proposal.moves().size(),
                "moderate LLM + strong cohesion must still produce a move");
        assertEquals(0.625f, proposal.moves().get(0).confidence(), 1e-3);
    }

    @Test
    void build_newCluster_mergesIntoExistingFolderWhenNameCollidesCaseInsensitive() {
        Path a = Path.of("/t/photo_a.jpg"), b = Path.of("/t/photo_b.jpg");
        var cluster = new DirectoryReorgPlan.NewClusterCandidate(List.of(a, b), 0.9f);
        var named = new ReorgToolLoopResult.NamedCluster(
                cluster, new ToolPrompts.ClusterNaming("photos", 0.95f, ""));  // lowercase

        Path existingPhotos = Path.of("/t/Photos");
        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(cluster), List.of(),
                        List.of(new DirectoryReorgPlan.ExistingSubdirSummary(existingPhotos, 4))),
                llmResultWith(List.of(named), List.of()));

        assertEquals(2, proposal.moves().size());
        for (var m : proposal.moves()) {
            assertEquals(existingPhotos, m.to(),
                    "LLM-proposed 'photos' must collapse into existing 'Photos'");
            assertFalse(m.destinationIsNew(),
                    "reusing an existing folder means destinationIsNew=false");
        }
    }

    @Test
    void build_multipleNewClustersSharingName_collapseToOneFolder() {
        Path a = Path.of("/t/note_a.txt"), b = Path.of("/t/note_b.txt");
        Path c = Path.of("/t/note_c.txt");
        var clusterA = new DirectoryReorgPlan.NewClusterCandidate(List.of(a, b), 0.9f);
        var clusterB = new DirectoryReorgPlan.NewClusterCandidate(List.of(c), 0.9f);

        var namedA = new ReorgToolLoopResult.NamedCluster(
                clusterA, new ToolPrompts.ClusterNaming("Notes", 0.95f, ""));
        var namedB = new ReorgToolLoopResult.NamedCluster(
                clusterB, new ToolPrompts.ClusterNaming("Notes", 0.95f, ""));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(clusterA, clusterB), List.of(), List.of()),
                llmResultWith(List.of(namedA, namedB), List.of()));

        assertEquals(3, proposal.moves().size());
        Path uniqueDest = proposal.moves().get(0).to();
        for (var m : proposal.moves()) {
            assertEquals(uniqueDest, m.to(), "all should target the same new Notes/ folder");
        }
    }

    @Test
    void build_newCluster_droppedWhenSecondOpinionCohesionFails() {
        // The headline scenario: AADHAR.jpg + Screenshot.png both got
        // OCR'd by Tika so they're "content-rich" from the pipeline's
        // perspective — slipping past the filename-only pairGate during
        // local clustering. They formed a 2-file cluster anyway because
        // BGE on their OCR garbage gave moderate similarity.
        // Section 2 must catch this via the second-opinion all-pairs
        // cohesion check: the vectors actually disagree → drop the whole
        // cluster, leave both files in place.
        Path aadhar = Path.of("/t/AADHAR_FRONT.jpg");
        Path shot   = Path.of("/t/Screenshot 2026-04-13.png");
        float[] vA  = new float[]{1f, 0f, 0f};
        float[] vS  = new float[]{0f, 1f, 0f};         // orthogonal → sim 0

        var cluster = new DirectoryReorgPlan.NewClusterCandidate(List.of(aadhar, shot), 0.7f);
        var named   = new ReorgToolLoopResult.NamedCluster(
                cluster, new ToolPrompts.ClusterNaming("Identity Documents", 0.7f, ""));

        DirectoryReorgPlan plan = planWith(
                Map.of(), List.of(cluster), List.of(), List.of(),
                Map.of(aadhar, vA, shot, vS),
                java.util.Set.of());     // both content-rich (OCRed)

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(named), List.of()));

        assertTrue(proposal.moves().isEmpty(),
                "embedding disagreement must dissolve the LOCAL cluster too");
        assertEquals(2, proposal.dropped().size());
        assertTrue(proposal.leftAlone().containsAll(List.of(aadhar, shot)));
    }

    @Test
    void build_newCluster_droppedWhenLlmPicksGenericName() {
        Path a = Path.of("/t/a.pdf"), b = Path.of("/t/b.pdf");
        float[] v = new float[]{1f, 0f, 0f};
        var cluster = new DirectoryReorgPlan.NewClusterCandidate(List.of(a, b), 0.9f);
        var named   = new ReorgToolLoopResult.NamedCluster(
                cluster, new ToolPrompts.ClusterNaming("Documents", 0.9f, ""));

        DirectoryReorgPlan plan = planWith(
                Map.of(), List.of(cluster), List.of(), List.of(),
                Map.of(a, v, b, v), java.util.Set.of());

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(named), List.of()));

        assertTrue(proposal.moves().isEmpty(),
                "generic cluster name must be rejected in section 2 too");
        assertEquals(2, proposal.dropped().size());
        assertTrue(proposal.dropped().get(0).reason().toLowerCase().contains("generic"));
    }

    @Test
    void build_newCluster_lowLlmConfidence_requiresSharedFilenameToken() {
        // Two PDFs the LLM hedged on (naming confidence 0.5 < SOLO bar).
        // Even with strong content embedding agreement (sim=1.0), we
        // demand a shared meaningful filename token — otherwise we'd be
        // trusting the same signal twice (BGE put them together; BGE
        // would be saying they belong together again).
        Path resume   = Path.of("/t/Rudransh_Resume.pdf");
        Path template = Path.of("/t/resume_Template.docx");
        float[] v = new float[]{1f, 0f, 0f};
        var cluster = new DirectoryReorgPlan.NewClusterCandidate(List.of(resume, template), 0.85f);
        var named   = new ReorgToolLoopResult.NamedCluster(
                cluster, new ToolPrompts.ClusterNaming("Resumes", 0.5f, "hedged"));

        DirectoryReorgPlan plan = planWith(
                Map.of(), List.of(cluster), List.of(), List.of(),
                Map.of(resume, v, template, v), java.util.Set.of());

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(named), List.of()));

        assertEquals(2, proposal.moves().size(),
                "shared 'resume' token + content agreement must let this survive even at LLM 0.5");
    }

    @Test
    void build_newCluster_lowLlmConfidence_noSharedToken_rejected() {
        // Same setup but no shared filename token → reject. This is the
        // unrelated-content-but-spurious-sim case we want to filter.
        Path a = Path.of("/t/alpha.pdf");
        Path b = Path.of("/t/beta.pdf");
        float[] v = new float[]{1f, 0f, 0f};        // perfect sim — but irrelevant
        var cluster = new DirectoryReorgPlan.NewClusterCandidate(List.of(a, b), 0.85f);
        var named   = new ReorgToolLoopResult.NamedCluster(
                cluster, new ToolPrompts.ClusterNaming("Stuff About Things", 0.5f, ""));

        DirectoryReorgPlan plan = planWith(
                Map.of(), List.of(cluster), List.of(), List.of(),
                Map.of(a, v, b, v), java.util.Set.of());

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(named), List.of()));

        assertTrue(proposal.moves().isEmpty(),
                "no shared token + LLM hedged → drop");
        assertEquals(2, proposal.dropped().size());
    }

    @Test
    void build_newCluster_unusableSanitizedName_dropsCluster() {
        Path a = Path.of("/t/a.pdf"), b = Path.of("/t/b.pdf");
        var cluster = new DirectoryReorgPlan.NewClusterCandidate(List.of(a, b), 0.9f);
        var named = new ReorgToolLoopResult.NamedCluster(
                cluster, new ToolPrompts.ClusterNaming("///", 0.95f, ""));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(cluster), List.of(), List.of()),
                llmResultWith(List.of(named), List.of()));

        assertTrue(proposal.moves().isEmpty());
        assertEquals(2, proposal.dropped().size());
        assertTrue(proposal.leftAlone().containsAll(List.of(a, b)));
    }

    // -------------------------------------------------------------------------
    // LONER judgments
    // -------------------------------------------------------------------------

    @Test
    void build_lonerToExisting_producesMoveWhenFolderExists() {
        Path file = Path.of("/t/orphan.pdf");
        var judged = new ReorgToolLoopResult.JudgedLoner(file,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.EXISTING_FOLDER,
                        "Tax", 0.85f, "fits"));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(), List.of(file),
                        List.of(new DirectoryReorgPlan.ExistingSubdirSummary(TAX, 3))),
                llmResultWith(List.of(), List.of(judged)));

        assertEquals(1, proposal.moves().size());
        assertEquals(TAX, proposal.moves().get(0).to());
        assertEquals(ReorgProposal.ProposedMove.Source.LONER_TO_EXISTING,
                proposal.moves().get(0).source());
    }

    @Test
    void build_lonerToExisting_droppedWhenLlmNamesNonexistentFolder() {
        Path file = Path.of("/t/orphan.pdf");
        var judged = new ReorgToolLoopResult.JudgedLoner(file,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.EXISTING_FOLDER,
                        "Phantom", 0.95f, "fits Phantom"));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(), List.of(file),
                        List.of(new DirectoryReorgPlan.ExistingSubdirSummary(TAX, 3))),
                llmResultWith(List.of(), List.of(judged)));

        assertTrue(proposal.moves().isEmpty());
        assertEquals(1, proposal.dropped().size());
        assertTrue(proposal.dropped().get(0).reason().contains("Phantom"));
        assertTrue(proposal.leftAlone().contains(file));
    }

    @Test
    void build_lonerNewFolder_createsFreshDestination() {
        Path file = Path.of("/t/spec_doc.pdf");
        var judged = new ReorgToolLoopResult.JudgedLoner(file,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Specs", 0.9f, ""));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(), List.of(file), List.of()),
                llmResultWith(List.of(), List.of(judged)));

        assertEquals(1, proposal.moves().size());
        var m = proposal.moves().get(0);
        assertEquals(TARGET.resolve("Specs"), m.to());
        assertTrue(m.destinationIsNew());
        assertEquals(ReorgProposal.ProposedMove.Source.LONER_TO_NEW_FOLDER, m.source());
    }

    @Test
    void build_lonerNewFolder_rejectsGenericName() {
        // The LLM's escape hatch: when it can't find a real theme it
        // proposes "Documents" / "Images" / "Personal Documents" at
        // moderate confidence. We treat that as evidence it should have
        // returned LEAVE and refuse to act on it.
        Path file = Path.of("/t/orphan.pdf");
        var judged = new ReorgToolLoopResult.JudgedLoner(file,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Documents", 0.9f, "general docs"));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(), List.of(file), List.of()),
                llmResultWith(List.of(), List.of(judged)));

        assertTrue(proposal.moves().isEmpty(),
                "generic folder name must be rejected even at high LLM confidence");
        assertEquals(1, proposal.dropped().size());
        assertTrue(proposal.dropped().get(0).reason().toLowerCase().contains("generic"));
        assertTrue(proposal.leftAlone().contains(file));
    }

    @Test
    void build_lonerNewFolder_soloFileRequiresHigherConfidence() {
        // A one-file new folder is a moonshot with no peer to corroborate
        // the theme. We require 0.75+; 0.65 is the LLM's "I'm hedging"
        // confidence and shouldn't slip through.
        Path file = Path.of("/t/spec_doc.pdf");
        var judged = new ReorgToolLoopResult.JudgedLoner(file,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Specs", 0.65f, "kinda specs"));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(), List.of(file), List.of()),
                llmResultWith(List.of(), List.of(judged)));

        assertTrue(proposal.moves().isEmpty(),
                "solo NEW folder at 0.65 must be dropped (below the 0.75 solo bar)");
        assertEquals(1, proposal.dropped().size());
        assertTrue(proposal.leftAlone().contains(file));
    }

    @Test
    void build_lonerGroup_rejectedWhenEmbeddingsDisagree() {
        // The headline scenario: LLM groups AADHAR.jpg + a totally unrelated
        // screenshot as "Identity Documents" at 0.7 confidence. Both files
        // are filename-only; their vectors don't actually agree, and they
        // share no meaningful filename token. Both gates fire — drop the
        // grouping and leave the files in place.
        Path aadhar = Path.of("/t/AADHAR_FRONT.jpg");
        Path shot   = Path.of("/t/Screenshot 2026-04-13.png");
        // Deliberately divergent unit vectors → cosine sim near 0.
        float[] vA = new float[]{1f, 0f, 0f};
        float[] vS = new float[]{0f, 1f, 0f};

        var jA = new ReorgToolLoopResult.JudgedLoner(aadhar,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity", 0.7f, "ID doc"));
        var jS = new ReorgToolLoopResult.JudgedLoner(shot,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity", 0.7f, "ID-ish"));

        DirectoryReorgPlan plan = planWith(
                Map.of(), List.of(), List.of(aadhar, shot), List.of(),
                Map.of(aadhar, vA, shot, vS),
                java.util.Set.of(aadhar, shot));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(), List.of(jA, jS)));

        assertTrue(proposal.moves().isEmpty(),
                "embedding disagreement must dissolve the LLM's grouping");
        assertEquals(2, proposal.dropped().size());
        assertTrue(proposal.leftAlone().containsAll(List.of(aadhar, shot)));
    }

    @Test
    void build_lonerGroup_acceptedWhenEmbeddingsAgree() {
        // Two files with strongly-aligned vectors → cohesion gate passes
        // and the grouping survives even at moderate LLM confidence.
        Path a = Path.of("/t/resume.pdf");
        Path b = Path.of("/t/cover_letter.pdf");
        float[] v = new float[]{1f, 0f, 0f};                  // identical → sim = 1.0

        var jA = new ReorgToolLoopResult.JudgedLoner(a,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Application Materials", 0.7f, "job app"));
        var jB = new ReorgToolLoopResult.JudgedLoner(b,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Application Materials", 0.7f, "job app"));

        DirectoryReorgPlan plan = planWith(
                Map.of(), List.of(), List.of(a, b), List.of(),
                Map.of(a, v, b, v),
                java.util.Set.of());                          // both content-rich

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(), List.of(jA, jB)));

        assertEquals(2, proposal.moves().size(),
                "matching embeddings + non-generic name must let the grouping survive");
        for (var m : proposal.moves()) {
            assertEquals(TARGET.resolve("Application Materials"), m.to());
        }
    }

    @Test
    void build_lonerGroup_highConfidenceLlmWithContent_overridesCohesionGate() {
        // The "aadhaar.jpg + pan.pdf" scenario. The vectors are below the
        // cohesion threshold (BGE noise floor), but the LLM read both
        // content previews and is sure (≥ 0.85) both are identity
        // documents. With previews present, we trust the LLM.
        Path aadhar = Path.of("/t/AADHAR_FRONT.jpg");
        Path pan    = Path.of("/t/PAN_CARD.pdf");
        // Vectors well below LLM_GROUP_COHESION_THRESHOLD (0.65). Forced
        // here to prove the override fires on cohesion failures.
        float[] vA = new float[]{1f, 0f, 0f};
        float[] vP = new float[]{0.3f, 0.95f, 0f};   // sim ≈ 0.3

        var jA = new ReorgToolLoopResult.JudgedLoner(aadhar,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity Documents", 0.9f, "Aadhaar — government ID"));
        var jP = new ReorgToolLoopResult.JudgedLoner(pan,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity Documents", 0.9f, "PAN — government ID"));

        DirectoryReorgPlan plan = new DirectoryReorgPlan(
                TARGET, Map.of(), List.of(), List.of(aadhar, pan), List.of(),
                2, 2, List.of(), Map.of(),
                Optional.empty(),
                Map.of(aadhar, vA, pan, vP),
                java.util.Set.of(),
                Map.of(aadhar, "Government of India Aadhaar card scan…",
                       pan, "Income Tax Department PAN card…"));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(), List.of(jA, jP)));

        assertEquals(2, proposal.moves().size(),
                "high-confidence content-aware LLM must override the cohesion floor");
        for (var m : proposal.moves()) {
            assertEquals(TARGET.resolve("Identity Documents"), m.to());
        }
    }

    @Test
    void build_lonerGroup_belowOverrideThreshold_stillGatedByCohesion() {
        // Same shape but LLM hedged at 0.84 (just below the override
        // threshold of 0.85). Cohesion must still gate this — the LLM
        // wasn't sure enough to override.
        Path aadhar = Path.of("/t/AADHAR_FRONT.jpg");
        Path pan    = Path.of("/t/PAN_CARD.pdf");
        float[] vA = new float[]{1f, 0f, 0f};
        float[] vP = new float[]{0.3f, 0.95f, 0f};

        var jA = new ReorgToolLoopResult.JudgedLoner(aadhar,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity Documents", 0.84f, "ish"));
        var jP = new ReorgToolLoopResult.JudgedLoner(pan,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity Documents", 0.84f, "ish"));

        DirectoryReorgPlan plan = new DirectoryReorgPlan(
                TARGET, Map.of(), List.of(), List.of(aadhar, pan), List.of(),
                2, 2, List.of(), Map.of(),
                Optional.empty(),
                Map.of(aadhar, vA, pan, vP),
                java.util.Set.of(),
                Map.of(aadhar, "preview A", pan, "preview B"));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(), List.of(jA, jP)));

        assertTrue(proposal.moves().isEmpty(),
                "0.84 is below the override threshold — cohesion gate still applies");
    }

    @Test
    void build_lonerGroup_overrideRequiresAtLeastOneContentPreview() {
        // Two filename-only files even with 0.9 LLM confidence must not
        // override — there was no content for the LLM to read, so its
        // high confidence is just a filename guess.
        Path a = Path.of("/t/AADHAR.jpg");
        Path b = Path.of("/t/PAN.pdf");
        float[] vA = new float[]{1f, 0f, 0f};
        float[] vP = new float[]{0.3f, 0.95f, 0f};

        var jA = new ReorgToolLoopResult.JudgedLoner(a,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity Documents", 0.9f, ""));
        var jB = new ReorgToolLoopResult.JudgedLoner(b,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity Documents", 0.9f, ""));

        DirectoryReorgPlan plan = new DirectoryReorgPlan(
                TARGET, Map.of(), List.of(), List.of(a, b), List.of(),
                2, 2, List.of(), Map.of(),
                Optional.empty(),
                Map.of(a, vA, b, vP),
                java.util.Set.of(a, b),
                Map.of());                       // NO previews

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(), List.of(jA, jB)));

        assertTrue(proposal.moves().isEmpty(),
                "no content previews → high LLM confidence cannot override cohesion");
    }

    @Test
    void build_lonerGroup_filenameOnlyPair_requiresSharedToken() {
        // Both filename-only, vectors agree numerically, but no shared
        // meaningful token → reject (BGE-on-short-strings is unreliable).
        Path a = Path.of("/t/AADHAR_FRONT.jpg");
        Path b = Path.of("/t/IMG_5821.png");        // no meaningful tokens after filter
        float[] v = new float[]{1f, 0f, 0f};

        var jA = new ReorgToolLoopResult.JudgedLoner(a,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity", 0.8f, ""));
        var jB = new ReorgToolLoopResult.JudgedLoner(b,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.NEW_FOLDER,
                        "Identity", 0.8f, ""));

        DirectoryReorgPlan plan = planWith(
                Map.of(), List.of(), List.of(a, b), List.of(),
                Map.of(a, v, b, v),
                java.util.Set.of(a, b));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                plan, llmResultWith(List.of(), List.of(jA, jB)));

        assertTrue(proposal.moves().isEmpty(),
                "filename-only pair with no shared token must be rejected");
        assertEquals(2, proposal.dropped().size());
    }

    @Test
    void isGenericFolderName_matchesCommonOnesCaseInsensitively() {
        assertTrue(ReorgPlanBuilder.isGenericFolderName("Documents"));
        assertTrue(ReorgPlanBuilder.isGenericFolderName("documents"));
        assertTrue(ReorgPlanBuilder.isGenericFolderName("  MISC  "));
        assertTrue(ReorgPlanBuilder.isGenericFolderName("Personal Documents"));
        assertTrue(ReorgPlanBuilder.isGenericFolderName("Miscellaneous"));
        assertFalse(ReorgPlanBuilder.isGenericFolderName("Tax Documents"));
        assertFalse(ReorgPlanBuilder.isGenericFolderName("Resumes"));
    }

    @Test
    void build_lonerLeaveAlone_neverProducesMove() {
        Path file = Path.of("/t/random.pdf");
        var judged = new ReorgToolLoopResult.JudgedLoner(file,
                new ToolPrompts.FileJudgment(
                        ToolPrompts.FileJudgment.Placement.LEAVE_ALONE,
                        null, 0.4f, ""));

        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(), List.of(file), List.of()),
                llmResultWith(List.of(), List.of(judged)));

        assertTrue(proposal.moves().isEmpty());
        assertTrue(proposal.leftAlone().contains(file));
        assertTrue(proposal.dropped().isEmpty(),
                "explicit LEAVE_ALONE is a decision, not a drop");
    }

    @Test
    void build_unjudgedLoner_endsUpInLeftAlone() {
        Path budgetVictim = Path.of("/t/unjudged.pdf");
        ReorgProposal proposal = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(), List.of(budgetVictim), List.of()),
                new ReorgToolLoopResult(
                        "s", List.of(), List.of(), 0, 0,
                        Optional.of("budget exhausted")));
        assertTrue(proposal.leftAlone().contains(budgetVictim));
        assertTrue(proposal.stoppedReason().isPresent());
    }

    // -------------------------------------------------------------------------
    // Aggregate output
    // -------------------------------------------------------------------------

    @Test
    void build_propagatesLlmCountersAndStoppedReason() {
        ReorgToolLoopResult res = new ReorgToolLoopResult(
                "sess", List.of(), List.of(), 42, 39,
                Optional.of("budget exhausted"));
        ReorgProposal p = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(), List.of(), List.of()), res);
        assertEquals(42, p.llmCallsAttempted());
        assertEquals(39, p.llmCallsSuccessful());
        assertEquals("budget exhausted", p.stoppedReason().orElseThrow());
    }

    @Test
    void constructor_rejectsBadCutoff() {
        assertThrows(IllegalArgumentException.class, () -> new ReorgPlanBuilder(-0.1f));
        assertThrows(IllegalArgumentException.class, () -> new ReorgPlanBuilder(1.5f));
    }

    @Test
    void build_emptyInputs_producesEmptyProposal() {
        ReorgProposal p = new ReorgPlanBuilder().build(
                planWith(Map.of(), List.of(), List.of(), List.of()),
                emptyLlmResult());
        assertTrue(p.moves().isEmpty());
        assertTrue(p.dropped().isEmpty());
        assertTrue(p.leftAlone().isEmpty());
        assertFalse(p.hasAnyChanges());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static DirectoryReorgPlan planWith(
            Map<Path, DirectoryReorgPlan.ExistingFolderMatch> assigned,
            List<DirectoryReorgPlan.NewClusterCandidate> newClusters,
            List<Path> loners,
            List<DirectoryReorgPlan.ExistingSubdirSummary> subdirs) {
        return planWith(assigned, newClusters, loners, subdirs, Map.of(), java.util.Set.of());
    }

    private static DirectoryReorgPlan planWith(
            Map<Path, DirectoryReorgPlan.ExistingFolderMatch> assigned,
            List<DirectoryReorgPlan.NewClusterCandidate> newClusters,
            List<Path> loners,
            List<DirectoryReorgPlan.ExistingSubdirSummary> subdirs,
            Map<Path, float[]> lonerVectors,
            java.util.Set<Path> filenameOnlyLoners) {
        return new DirectoryReorgPlan(
                TARGET, assigned, newClusters, loners, List.of(),
                /* totalLooseFiles */ assigned.size() + loners.size()
                        + newClusters.stream().mapToInt(DirectoryReorgPlan.NewClusterCandidate::size).sum(),
                newClusters.size() + loners.size(),
                subdirs, Map.of(),
                Optional.empty(),
                lonerVectors,
                filenameOnlyLoners,
                Map.of());
    }

    private static ReorgToolLoopResult emptyLlmResult() {
        return new ReorgToolLoopResult("s", List.of(), List.of(), 0, 0, Optional.empty());
    }

    private static ReorgToolLoopResult llmResultWith(
            List<ReorgToolLoopResult.NamedCluster> named,
            List<ReorgToolLoopResult.JudgedLoner> judged) {
        return new ReorgToolLoopResult("s", named, judged,
                named.size() + judged.size(), named.size() + judged.size(),
                Optional.empty());
    }
}

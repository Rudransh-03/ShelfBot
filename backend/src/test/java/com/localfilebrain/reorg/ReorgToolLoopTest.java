package com.localfilebrain.reorg;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReorgToolLoop} using a fake LLM client. We exercise:
 *  - the happy path (name clusters + judge loners),
 *  - graceful stop on BudgetExhausted mid-loop,
 *  - graceful stop on SessionExpired mid-loop,
 *  - per-decision failures (malformed JSON, transient HTTP) do NOT stop the loop,
 *  - the IllegalStateException guard when called on a plan with a scope error.
 */
final class ReorgToolLoopTest {

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void run_namesAllClustersAndJudgesAllLoners() {
        DirectoryReorgPlan plan = planWith(
                List.of(
                        new DirectoryReorgPlan.NewClusterCandidate(
                                List.of(Path.of("/t/tax_a.pdf"), Path.of("/t/tax_b.pdf")), 0.9f),
                        new DirectoryReorgPlan.NewClusterCandidate(
                                List.of(Path.of("/t/photo_a.jpg"), Path.of("/t/photo_b.jpg")), 0.85f)),
                List.of(Path.of("/t/weird.pdf"), Path.of("/t/random.txt")),
                List.of(new DirectoryReorgPlan.ExistingSubdirSummary(Path.of("/t/Inbox"), 3)));

        FakeReorgLlmClient client = new FakeReorgLlmClient(100);
        // Stage 1: name_cluster calls — 2 expected
        client.queueResponse("{\"name\":\"Tax Documents\",\"confidence\":0.95,\"reason\":\"all tax\"}");
        client.queueResponse("{\"name\":\"Photos\",\"confidence\":0.9,\"reason\":\"image cluster\"}");
        // Stage 2: ONE batched judge_loners call covering both loners.
        client.queueResponse("""
            {"decisions":[
              {"file":"weird.pdf",   "placement":"EXISTING:Inbox", "confidence":0.8, "reason":"fits Inbox"},
              {"file":"random.txt",  "placement":"LEAVE",          "confidence":0.4, "reason":"unclear"}
            ]}""");

        ReorgToolLoopResult result = new ReorgToolLoop(client).run(plan);

        // 2 cluster-naming + 1 batched judge-loners = 3 calls.
        assertEquals(3, result.llmCallsAttempted());
        assertEquals(3, result.llmCallsSuccessful());
        assertTrue(result.stoppedReason().isEmpty());

        assertEquals(2, result.namedClusters().size());
        assertEquals("Tax Documents", result.namedClusters().get(0).naming().name());
        assertEquals("Photos",        result.namedClusters().get(1).naming().name());

        assertEquals(2, result.judgedLoners().size());
        assertEquals(ToolPrompts.FileJudgment.Placement.EXISTING_FOLDER,
                result.judgedLoners().get(0).judgment().placement());
        assertEquals("Inbox", result.judgedLoners().get(0).judgment().folderName());
        assertEquals(ToolPrompts.FileJudgment.Placement.LEAVE_ALONE,
                result.judgedLoners().get(1).judgment().placement());
    }

    @Test
    void run_batchedJudgmentLetsLlmGroupCrossFormatLoners() {
        // The headline test for this change: two files from different
        // extension families (jpg + pdf) didn't cluster locally, but the
        // batched LLM call lets the model assign them the SAME
        // "NEW:Identity Documents" placement string — which means the
        // plan builder downstream will put them in one folder.
        Path aadhar = Path.of("/t/AADHAR_FRONT.jpg");
        Path pan    = Path.of("/t/PAN_CARD.pdf");
        Path random = Path.of("/t/random_thing.png");

        DirectoryReorgPlan plan = planWith(List.of(), List.of(aadhar, pan, random), List.of());

        FakeReorgLlmClient client = new FakeReorgLlmClient(100);
        client.queueResponse("""
            {"decisions":[
              {"file":"AADHAR_FRONT.jpg", "placement":"NEW:Identity Documents", "confidence":0.85, "reason":"Indian government ID"},
              {"file":"PAN_CARD.pdf",     "placement":"NEW:Identity Documents", "confidence":0.85, "reason":"Indian government ID"},
              {"file":"random_thing.png", "placement":"LEAVE",                  "confidence":0.3,  "reason":"no clear category"}
            ]}""");

        ReorgToolLoopResult result = new ReorgToolLoop(client).run(plan);

        assertEquals(3, result.judgedLoners().size());
        var aadharJ = result.judgedLoners().stream()
                .filter(j -> j.file().equals(aadhar)).findFirst().orElseThrow();
        var panJ = result.judgedLoners().stream()
                .filter(j -> j.file().equals(pan)).findFirst().orElseThrow();
        assertEquals(ToolPrompts.FileJudgment.Placement.NEW_FOLDER, aadharJ.judgment().placement());
        assertEquals(ToolPrompts.FileJudgment.Placement.NEW_FOLDER, panJ.judgment().placement());
        assertEquals("Identity Documents", aadharJ.judgment().folderName());
        assertEquals("Identity Documents", panJ.judgment().folderName(),
                "matching folder names in the batched response form a cross-format group");
    }

    // -------------------------------------------------------------------------
    // Budget / session-expired mid-loop
    // -------------------------------------------------------------------------

    @Test
    void run_stopsGracefullyOnBudgetExhaustedMidLoop() {
        DirectoryReorgPlan plan = planWith(
                List.of(
                        cluster("a"), cluster("b")),
                List.of(Path.of("/t/x.pdf"), Path.of("/t/y.pdf")),
                List.of());

        FakeReorgLlmClient client = new FakeReorgLlmClient(100);
        // First call succeeds
        client.queueResponse("{\"name\":\"A\",\"confidence\":0.9,\"reason\":\"r\"}");
        // Second call throws BudgetExhausted
        client.queueException(new ReorgLlmClient.BudgetExhausted("out of budget"));

        ReorgToolLoopResult result = new ReorgToolLoop(client).run(plan);

        assertEquals(1, result.namedClusters().size(),
                "first cluster named before budget ran out");
        assertEquals(0, result.judgedLoners().size(),
                "loner stage never starts after Stage 1 stops");
        assertTrue(result.stoppedReason().isPresent());
        assertTrue(result.stoppedReason().get().toLowerCase().contains("budget"));
        assertEquals(2, result.llmCallsAttempted(),
                "we attempted both clusters; the second one threw");
        assertEquals(1, result.llmCallsSuccessful());
    }

    @Test
    void run_stopsGracefullyOnSessionExpiredDuringBatchedJudgment() {
        DirectoryReorgPlan plan = planWith(
                List.of(),
                List.of(Path.of("/t/x.pdf"), Path.of("/t/y.pdf")),
                List.of());

        FakeReorgLlmClient client = new FakeReorgLlmClient(100);
        // The single batched judge_loners call throws — no judgments persisted.
        client.queueException(new ReorgLlmClient.SessionExpired("session gone"));

        ReorgToolLoopResult result = new ReorgToolLoop(client).run(plan);

        assertEquals(0, result.judgedLoners().size(),
                "session expired before any loner judgment landed");
        assertTrue(result.stoppedReason().isPresent());
        assertTrue(result.stoppedReason().get().toLowerCase().contains("session"));
    }

    // -------------------------------------------------------------------------
    // Per-decision failures don't stop the loop
    // -------------------------------------------------------------------------

    @Test
    void run_skipsOverMalformedResponses_doesNotStopLoop() {
        DirectoryReorgPlan plan = planWith(
                List.of(cluster("a"), cluster("b"), cluster("c")),
                List.of(),
                List.of());

        FakeReorgLlmClient client = new FakeReorgLlmClient(100);
        client.queueResponse("garbage");                                          // unparsable
        client.queueResponse("{\"name\":\"B\",\"confidence\":0.9,\"reason\":\"\"}");
        client.queueResponse("{}");                                               // missing name

        ReorgToolLoopResult result = new ReorgToolLoop(client).run(plan);

        assertEquals(3, result.llmCallsAttempted());
        assertEquals(1, result.llmCallsSuccessful(),
                "only the middle response was parsable");
        assertEquals(1, result.namedClusters().size());
        assertEquals("B", result.namedClusters().get(0).naming().name());
        assertTrue(result.stoppedReason().isEmpty(),
                "malformed responses do not stop the loop — they're per-decision failures");
    }

    @Test
    void run_skipsOverTransientHttpErrors_doesNotStopLoop() {
        DirectoryReorgPlan plan = planWith(
                List.of(cluster("a"), cluster("b")),
                List.of(),
                List.of());

        FakeReorgLlmClient client = new FakeReorgLlmClient(100);
        client.queueException(new ReorgLlmClient.LlmHttpException("transient"));
        client.queueResponse("{\"name\":\"B\",\"confidence\":0.9,\"reason\":\"\"}");

        ReorgToolLoopResult result = new ReorgToolLoop(client).run(plan);

        assertEquals(2, result.llmCallsAttempted());
        assertEquals(1, result.llmCallsSuccessful());
        assertTrue(result.stoppedReason().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------

    @Test
    void run_refusesPlanWithScopeError() {
        ScopeError scopeErr = new ScopeError(
                "Too varied", "detail", List.of(), 75, 50);
        DirectoryReorgPlan plan = new DirectoryReorgPlan(
                Path.of("/t"), Map.of(), List.of(), List.of(), List.of(),
                100, 75,
                List.of(), Map.of(),
                Optional.of(scopeErr),
                Map.of(),
                java.util.Set.of(),
                Map.of());

        FakeReorgLlmClient client = new FakeReorgLlmClient(100);
        assertThrows(IllegalStateException.class,
                () -> new ReorgToolLoop(client).run(plan),
                "running the LLM loop on a scope-blocked plan must fail loudly");
    }

    @Test
    void run_acceptsEmptyPlan() {
        DirectoryReorgPlan plan = planWith(List.of(), List.of(), List.of());
        FakeReorgLlmClient client = new FakeReorgLlmClient(100);

        ReorgToolLoopResult result = new ReorgToolLoop(client).run(plan);
        assertEquals(0, result.llmCallsAttempted());
        assertEquals(0, result.namedClusters().size());
        assertEquals(0, result.judgedLoners().size());
        assertTrue(result.stoppedReason().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static DirectoryReorgPlan.NewClusterCandidate cluster(String tag) {
        return new DirectoryReorgPlan.NewClusterCandidate(
                List.of(Path.of("/t/" + tag + "_1.pdf"), Path.of("/t/" + tag + "_2.pdf")), 0.9f);
    }

    private static DirectoryReorgPlan planWith(
            List<DirectoryReorgPlan.NewClusterCandidate> newClusters,
            List<Path> loners,
            List<DirectoryReorgPlan.ExistingSubdirSummary> existingSubdirs) {
        Map<Path, DirectoryReorgPlan.ExistingFolderMatch> assigned = new LinkedHashMap<>();
        int decisions = newClusters.size() + loners.size();
        return new DirectoryReorgPlan(
                Path.of("/t"),
                assigned,
                newClusters,
                loners,
                List.of(),
                decisions,
                decisions,
                existingSubdirs,
                Map.of(),
                Optional.empty(),
                Map.of(),
                java.util.Set.of(),
                Map.of());
    }

    /** Test-only LLM client that hands out canned responses in order. */
    private static final class FakeReorgLlmClient implements ReorgLlmClient {
        private final List<Object> script = new ArrayList<>();
        private final int budget;
        private int cursor = 0;

        FakeReorgLlmClient(int budget) { this.budget = budget; }
        void queueResponse(String response) { script.add(response); }
        void queueException(RuntimeException e) { script.add(e); }

        @Override
        public SessionInfo start() {
            return new SessionInfo("fake-session", budget, Instant.now().plusSeconds(1800));
        }

        @Override
        public String chat(String sessionId, String systemPrompt, String userPrompt, boolean jsonMode, int maxTokens) {
            if (cursor >= script.size()) {
                throw new LlmHttpException("FakeReorgLlmClient ran out of scripted responses");
            }
            Object next = script.get(cursor++);
            if (next instanceof RuntimeException re) throw re;
            return (String) next;
        }
    }
}

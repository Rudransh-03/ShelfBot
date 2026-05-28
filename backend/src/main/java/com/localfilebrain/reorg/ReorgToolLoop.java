package com.localfilebrain.reorg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the LLM half of the reorg pipeline.
 *
 * <p>Given a {@link DirectoryReorgPlan} that's already passed the scope
 * guard (i.e. {@link DirectoryReorgPlan#isInScope()} is true), this:
 * <ol>
 *   <li>Opens a session via {@link ReorgLlmClient#start}.</li>
 *   <li>Asks {@code name_cluster} for each new cluster.</li>
 *   <li>Asks {@code judge_file} for each loner.</li>
 *   <li>Stops gracefully if the per-session LLM budget runs out and
 *       returns partial results.</li>
 * </ol>
 *
 * <p>Per-decision errors (malformed JSON from the model, transient HTTP
 * errors) are swallowed and logged — the loop continues with the next
 * decision. Only {@link ReorgLlmClient.BudgetExhausted} and
 * {@link ReorgLlmClient.SessionExpired} stop the whole loop.
 */
public final class ReorgToolLoop {

    private static final Logger log = LoggerFactory.getLogger(ReorgToolLoop.class);

    private final ReorgLlmClient llmClient;

    public ReorgToolLoop(ReorgLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public ReorgToolLoopResult run(DirectoryReorgPlan plan) {
        if (plan.scopeError().isPresent()) {
            throw new IllegalStateException(
                    "ReorgToolLoop.run called on a plan with a scope error — caller should "
                  + "surface the scope error to the user instead of invoking the LLM.");
        }

        ReorgLlmClient.SessionInfo session = llmClient.start();
        log.info("Reorg session opened: {} (budget {})", session.sessionId(), session.llmBudget());

        List<ReorgToolLoopResult.NamedCluster> namedClusters = new ArrayList<>();
        List<ReorgToolLoopResult.JudgedLoner>  judgedLoners  = new ArrayList<>();
        int attempted  = 0;
        int successful = 0;
        Optional<String> stoppedReason = Optional.empty();

        // ── Stage 1: name new clusters ──────────────────────────────────────
        for (DirectoryReorgPlan.NewClusterCandidate cluster : plan.newClusters()) {
            List<ToolPrompts.FileInput> samples = cluster.members().stream()
                    .limit(ToolPrompts.MAX_CLUSTER_SAMPLES)
                    .map(p -> new ToolPrompts.FileInput(
                            p.getFileName().toString(),
                            plan.looseFileContentPreviews().getOrDefault(p, "")))
                    .toList();

            attempted++;
            try {
                String response = llmClient.chat(
                        session.sessionId(),
                        ToolPrompts.nameClusterSystem(),
                        ToolPrompts.nameClusterUser(samples),
                        true);
                Optional<ToolPrompts.ClusterNaming> naming = ToolPrompts.parseClusterNaming(response);
                if (naming.isPresent()) {
                    namedClusters.add(new ReorgToolLoopResult.NamedCluster(cluster, naming.get()));
                    successful++;
                } else {
                    log.warn("name_cluster returned unparsable JSON; skipping cluster of {} files", cluster.size());
                }
            } catch (ReorgLlmClient.BudgetExhausted e) {
                stoppedReason = Optional.of("LLM budget exhausted during cluster naming.");
                break;
            } catch (ReorgLlmClient.SessionExpired e) {
                stoppedReason = Optional.of("Reorg session expired during cluster naming.");
                break;
            } catch (Exception e) {
                log.warn("name_cluster call failed ({} files): {}", cluster.size(), e.getMessage());
                // continue — per-decision errors don't stop the loop
            }
        }

        // ── Stage 2: judge ALL loners in ONE batched call. ─────────────────
        //
        // Per-loner judgment can't spot semantic groups that span file
        // formats (e.g. AADHAR.jpg + PAN.pdf — both ID documents but
        // different extensions, no shared filename tokens, contents
        // different). Batching gives the model visibility across all
        // loners so it can return the same "NEW:<folder>" placement for
        // related files and form a cross-format group.
        if (stoppedReason.isEmpty() && !plan.loners().isEmpty()) {
            List<ToolPrompts.ExistingFolderContext> folderContext = buildFolderContext(plan);
            List<ToolPrompts.FileInput> fileInputs = plan.loners().stream()
                    .map(p -> new ToolPrompts.FileInput(
                            p.getFileName().toString(),
                            plan.looseFileContentPreviews().getOrDefault(p, "")))
                    .toList();
            List<String> filenames = fileInputs.stream()
                    .map(ToolPrompts.FileInput::filename)
                    .toList();

            attempted++;
            try {
                // ~80 tokens per decision JSON (filename + reason + placement
                // + confidence) × N loners, plus 200-token overhead. Cap at
                // 4000 output. Empirically GPT-4o-mini emits long "reason"
                // strings that easily blow past a 60-tokens/decision budget,
                // so we leave generous headroom.
                int maxOutTokens = Math.min(4000, Math.max(600, filenames.size() * 100 + 200));
                String response = llmClient.chat(
                        session.sessionId(),
                        ToolPrompts.judgeLonersBatchSystem(),
                        ToolPrompts.judgeLonersBatchUser(fileInputs, folderContext),
                        true,
                        maxOutTokens);
                log.debug("judge_loners raw response: {}", response);
                java.util.Map<String, ToolPrompts.FileJudgment> judgments =
                        ToolPrompts.parseLonerBatch(response, filenames);

                if (judgments.isEmpty()) {
                    log.warn("judge_loners returned no parsable decisions for {} loners. raw='{}'",
                            filenames.size(),
                            response == null ? "<null>" : response.substring(0, Math.min(2000, response.length())));
                } else {
                    // Pair each loner with its judgment (or skip if missing —
                    // treated as implicit LEAVE downstream).
                    for (Path loner : plan.loners()) {
                        ToolPrompts.FileJudgment j = judgments.get(loner.getFileName().toString());
                        if (j != null) {
                            judgedLoners.add(new ReorgToolLoopResult.JudgedLoner(loner, j));
                        }
                    }
                    successful++;
                }
            } catch (ReorgLlmClient.BudgetExhausted e) {
                stoppedReason = Optional.of("LLM budget exhausted during loner judgment.");
            } catch (ReorgLlmClient.SessionExpired e) {
                stoppedReason = Optional.of("Reorg session expired during loner judgment.");
            } catch (Exception e) {
                log.warn("judge_loners batched call failed: {}", e.getMessage());
            }
        }

        log.info("Reorg tool loop done — attempted={}, successful={}, stoppedReason={}",
                attempted, successful, stoppedReason.orElse("none"));

        return new ReorgToolLoopResult(
                session.sessionId(),
                List.copyOf(namedClusters),
                List.copyOf(judgedLoners),
                attempted,
                successful,
                stoppedReason);
    }

    /**
     * Builds the existing-folder context shown to the LLM during
     * {@code judge_file}. Only folders that actually have files inside
     * are included — empty subdirs aren't useful targets.
     */
    private static List<ToolPrompts.ExistingFolderContext> buildFolderContext(DirectoryReorgPlan plan) {
        List<ToolPrompts.ExistingFolderContext> out = new ArrayList<>();
        for (DirectoryReorgPlan.ExistingSubdirSummary s : plan.existingSubdirs()) {
            if (s.fileCount() == 0) continue;
            // We don't currently surface sample filenames in the plan
            // (only the count). The folder name alone is enough signal
            // for the model in v1; sample-file context can be added
            // later by carrying a few names through DirectoryAnalyzer.
            out.add(new ToolPrompts.ExistingFolderContext(
                    s.path().getFileName().toString(),
                    List.of()));
        }
        return out;
    }
}

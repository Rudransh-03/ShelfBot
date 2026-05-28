package com.localfilebrain.reorg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Merges local clustering decisions ({@link DirectoryReorgPlan}) with the
 * LLM's verdicts ({@link ReorgToolLoopResult}) into a final
 * {@link ReorgProposal} the UI can render and the user can approve.
 *
 * <p>Conservatism is the policy here: anything below the confidence cutoff
 * is dropped to {@code leftAlone}, anything ambiguous (LLM names a folder
 * that doesn't exist; LLM says LEAVE) becomes a no-op rather than a guess.
 *
 * <p>Folder-name conflict resolution:
 * <ul>
 *   <li>LLM proposes a new folder name that case-insensitively matches an
 *       existing subdir → reuse the existing path (the user's taxonomy
 *       wins, the LLM independently agreed).</li>
 *   <li>Multiple clusters/loners propose the same new name → they all
 *       share one destination folder.</li>
 * </ul>
 */
public final class ReorgPlanBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReorgPlanBuilder.class);

    /**
     * Minimum effective confidence for a move to survive. Below this we
     * drop the move and leave the file alone.
     *
     * <p>0.6 is a deliberate softening from the original 0.7. Combined
     * with the weighted-average formula (vs the original product), this
     * lets a confident-but-not-perfect grouping survive — important for
     * cases like "resume.pdf + resume_template.docx" where each individual
     * signal is moderate but the joint evidence is clearly enough.
     */
    public static final float DEFAULT_CONFIDENCE_CUTOFF = 0.6f;

    /**
     * Minimum cosine similarity required on the SPANNING-TREE edges of a
     * group the LLM forms from loners. Spanning-tree (single-link) min
     * edge sim is the correct cohesion measure for cluster-like groupings:
     * all-pairs is too strict and rejects legitimate chained clusters
     * (e.g. 8 nomination-form variants where one cross-pair sits at 0.65
     * but the spanning tree connects them at 0.78+).
     *
     * <p>BGE-small-en-v1.5 has a baseline ~0.55–0.65 between any two
     * short personal docs (see {@code ReorgDiagnostic}); a spanning-tree
     * floor of 0.65 still catches obvious noise pairs while keeping
     * cross-format groupings viable. The high-confidence content-aware
     * LLM override can still bypass this for cases like
     * aadhaar.jpg + pan.pdf where BGE can't see the relationship.
     */
    public static final float LLM_GROUP_COHESION_THRESHOLD = 0.65f;

    /**
     * A single loner being given its own NEW:&lt;folder&gt; needs to clear
     * this higher bar — a one-file folder is a moonshot with no peer to
     * corroborate, so we demand more LLM confidence than the default
     * cutoff. With content previews now in the prompt, LLM 0.8 means it
     * read the file's content and is sure.
     */
    public static final float SOLO_NEW_FOLDER_CUTOFF = 0.80f;

    /**
     * Confidence threshold above which a unanimous LLM verdict overrides
     * the cohesion gate for a NEW:&lt;folder&gt; group. BGE-small can put
     * legitimately-related files (aadhaar.jpg + pan.pdf — both ID docs,
     * but visually and textually distinct) below the cohesion floor, and
     * we don't want to drop those when the LLM has read both previews
     * and is clearly sure. Override only fires when every member of the
     * group is at this confidence AND at least one side has real
     * content the LLM could read.
     */
    public static final float HIGH_CONFIDENCE_LLM_OVERRIDE = 0.85f;

    /**
     * Folder names that signal the LLM didn't find a real theme. Any
     * NEW:&lt;name&gt; matching one of these (case-insensitive, trimmed)
     * is rejected — that's the LLM's escape hatch when it should have
     * answered LEAVE instead. Compared after sanitization and lowercase.
     */
    private static final Set<String> GENERIC_FOLDER_NAMES = Set.of(
            // Truly empty themes — "anything is a document/file"
            "documents", "document", "docs", "doc",
            "files", "file",
            // Self-admitted "I couldn't find a theme" names
            "mixed", "miscellaneous", "misc", "other", "others",
            "stuff", "various", "general",
            "uncategorized", "random", "assorted",
            // "Personal" is a catch-all the LLM reaches for when ID docs,
            // resumes, and unrelated files end up grouped together
            "personal", "personal documents", "personal files"
            // NOTE: "Photos"/"Images"/"Pictures" are intentionally NOT
            // here — those describe a specific media kind and are
            // reasonable folder names for legitimately-clustered photos.
    );

    /** Cap on filesystem-friendly folder-name length we'll generate from an LLM suggestion. */
    private static final int MAX_FOLDER_NAME_LEN = 60;

    private final float confidenceCutoff;

    public ReorgPlanBuilder() { this(DEFAULT_CONFIDENCE_CUTOFF); }

    public ReorgPlanBuilder(float confidenceCutoff) {
        if (confidenceCutoff < 0f || confidenceCutoff > 1f) {
            throw new IllegalArgumentException(
                    "confidenceCutoff must be in [0, 1], got " + confidenceCutoff);
        }
        this.confidenceCutoff = confidenceCutoff;
    }

    public ReorgProposal build(DirectoryReorgPlan plan, ReorgToolLoopResult llmResult) {
        Path target = plan.targetDir();
        List<ReorgProposal.ProposedMove>      moves     = new ArrayList<>();
        List<ReorgProposal.DroppedDecision>   dropped   = new ArrayList<>();
        List<Path>                            leftAlone = new ArrayList<>();

        // Index existing subdirs by lowercase name for case-insensitive lookup
        // (filesystems on macOS are usually case-insensitive; matching the
        // same way here means "Tax" and "tax" collapse to the same target).
        Map<String, Path> existingByLcName = new LinkedHashMap<>();
        for (var s : plan.existingSubdirs()) {
            existingByLcName.put(s.path().getFileName().toString().toLowerCase(), s.path());
        }

        // Dedupe: when multiple clusters or loners propose the same NEW folder
        // name, they share one destination path so the executor doesn't try
        // to create the same dir twice and the UI groups them together.
        Map<String, Path> newFolderByLcName = new LinkedHashMap<>();

        // Track which loners were judged so we know which ones to leave alone.
        Set<Path> judgedFiles = new HashSet<>();
        for (var jl : llmResult.judgedLoners()) judgedFiles.add(jl.file());

        // ── 1. Fit-to-existing (local decisions, no LLM) ────────────────────
        for (var entry : plan.assignedToExisting().entrySet()) {
            Path file = entry.getKey();
            DirectoryReorgPlan.ExistingFolderMatch match = entry.getValue();
            float confidence = match.similarity();

            if (confidence < confidenceCutoff) {
                dropped.add(new ReorgProposal.DroppedDecision(
                        file,
                        String.format("Match confidence %.2f below cutoff %.2f", confidence, confidenceCutoff),
                        confidence));
                leftAlone.add(file);
                continue;
            }

            moves.add(new ReorgProposal.ProposedMove(
                    file,
                    match.existingFolder(),
                    /* destinationIsNew */ false,
                    ReorgProposal.ProposedMove.Source.FIT_TO_EXISTING,
                    String.format("Fits the contents of %s/", match.existingFolder().getFileName()),
                    confidence));
        }

        // ── 2. New clusters (LLM-named groups of co-clustered files) ────────
        for (var named : llmResult.namedClusters()) {
            String sanitized = sanitizeFolderName(named.naming().name());
            // Effective confidence is the AVERAGE of (LLM naming
            // confidence) and (cluster cohesion). The old formula
            // multiplied them, which over-penalized cases where one
            // signal was moderate even though the joint evidence was
            // clearly enough to act on. With weighted average:
            //   - 0.5 × 0.5 → 0.25 (product) → would always drop
            //   - 0.5 × 0.5 → 0.5  (average) → borderline, may survive
            float effective = clamp(
                    0.5f * named.naming().confidence()
                  + 0.5f * named.cluster().cohesion());

            if (sanitized.isEmpty()) {
                for (Path f : named.cluster().members()) {
                    dropped.add(new ReorgProposal.DroppedDecision(
                            f, "LLM returned an unusable folder name", effective));
                    leftAlone.add(f);
                }
                continue;
            }

            // Generic-name gate: "Documents" / "Images" / "Mixed" — when
            // the LLM picks one of these for a locally-clustered set, the
            // cluster usually exists because of OCR-on-image noise or
            // BGE on weak text overlap, not real semantic agreement.
            if (isGenericFolderName(sanitized)) {
                for (Path f : named.cluster().members()) {
                    dropped.add(new ReorgProposal.DroppedDecision(
                            f,
                            String.format("LLM named the cluster \"%s\" — too generic to act on", sanitized),
                            effective));
                    leftAlone.add(f);
                }
                continue;
            }

            // Filename-token / low-LLM-confidence sanity gate. We DO NOT
            // re-check all-pairs cohesion here: the cluster already passed
            // HAC single-linkage at {@link ClusteringEngine#DEFAULT_DISTANCE_THRESHOLD}
            // (cosine sim 0.78 minimum across the spanning tree), which is
            // a stricter precision bar than any all-pairs floor we could
            // pick without hurting recall on legitimate chained clusters
            // (e.g. 8 nomination-form variants whose cross-pairs sit at 0.65
            // but whose spanning tree is well above 0.78). The token check
            // remains because filename-only pairs are unreliable regardless
            // of vector sim.
            if (!localClusterPassesTokenGate(named.cluster().members(), plan,
                    named.naming().confidence())) {
                for (Path f : named.cluster().members()) {
                    dropped.add(new ReorgProposal.DroppedDecision(
                            f,
                            String.format(
                                    "Cluster \"%s\" rejected — filename-only files in it don't share a meaningful token",
                                    sanitized),
                            effective));
                    leftAlone.add(f);
                }
                continue;
            }

            if (effective < confidenceCutoff) {
                for (Path f : named.cluster().members()) {
                    dropped.add(new ReorgProposal.DroppedDecision(
                            f,
                            String.format("Cluster confidence %.2f below cutoff %.2f", effective, confidenceCutoff),
                            effective));
                    leftAlone.add(f);
                }
                continue;
            }

            DestResolution dest = resolveNewFolderDestination(
                    sanitized, target, existingByLcName, newFolderByLcName);

            for (Path f : named.cluster().members()) {
                moves.add(new ReorgProposal.ProposedMove(
                        f,
                        dest.path,
                        dest.isNew,
                        ReorgProposal.ProposedMove.Source.NEW_CLUSTER,
                        dest.isNew
                                ? String.format("Part of a new \"%s\" group with %d related file%s",
                                        sanitized, named.cluster().size() - 1,
                                        named.cluster().size() - 1 == 1 ? "" : "s")
                                : String.format("Joins existing %s/ along with %d related file%s",
                                        sanitized, named.cluster().size() - 1,
                                        named.cluster().size() - 1 == 1 ? "" : "s"),
                        effective));
            }
        }

        // ── 3. Loner judgments (per-file LLM verdicts) ──────────────────────
        // Two-pass:
        //   3a) bucket judgments by their proposed NEW:<lc-name> destination so
        //       we can verify groupings against the real embedding cohesion.
        //   3b) for each bucket, apply the embedding gate (and a generic-name
        //       blacklist + solo-folder cutoff bump) BEFORE emitting moves.
        //   EXISTING / LEAVE judgments are handled per-file as before — the
        //   risk profile is different (an existing-folder target is grounded;
        //   inventing a new folder from a weak theme is the failure mode).
        Map<String, List<ReorgToolLoopResult.JudgedLoner>> newGroupsByLc = new LinkedHashMap<>();
        Map<String, String> newGroupOriginalName = new LinkedHashMap<>();    // preserve LLM casing for the dest name

        for (var jl : llmResult.judgedLoners()) {
            ToolPrompts.FileJudgment judgment = jl.judgment();
            if (judgment.placement() == ToolPrompts.FileJudgment.Placement.NEW_FOLDER) {
                String sanitized = sanitizeFolderName(judgment.folderName());
                if (sanitized.isEmpty()) continue;                              // handled below in the leftover pass
                String lc = sanitized.toLowerCase();
                newGroupsByLc.computeIfAbsent(lc, k -> new ArrayList<>()).add(jl);
                newGroupOriginalName.putIfAbsent(lc, sanitized);
            }
        }

        Set<Path> consumedByNewGroup = new HashSet<>();

        // 3b — group-level checks for NEW:<folder> proposals.
        for (var entry : newGroupsByLc.entrySet()) {
            String lc = entry.getKey();
            String sanitized = newGroupOriginalName.get(lc);
            List<ReorgToolLoopResult.JudgedLoner> members = entry.getValue();

            // Generic name blacklist — the LLM's escape hatch when it
            // should have answered LEAVE. Reject the whole bucket.
            if (isGenericFolderName(sanitized)) {
                for (var jl : members) {
                    dropped.add(new ReorgProposal.DroppedDecision(
                            jl.file(),
                            String.format("LLM suggested generic folder \"%s\" — treating as no real theme", sanitized),
                            jl.judgment().confidence()));
                    leftAlone.add(jl.file());
                    consumedByNewGroup.add(jl.file());
                }
                continue;
            }

            // Spanning-tree cohesion gate — for ≥2 members, the single-
            // link MST must connect them with min edge cosine sim ≥
            // {@link #LLM_GROUP_COHESION_THRESHOLD}. Same metric HAC
            // already uses; "all-pairs" was too strict and threw out
            // legitimate chained clusters. Filename-only pairs still
            // need a shared meaningful token (BGE on short strings).
            //
            // Exception: when EVERY member's LLM judgment is highly
            // confident (≥ {@link #HIGH_CONFIDENCE_LLM_OVERRIDE}) and at
            // least one member has indexed content the LLM actually
            // read, we trust the LLM's verdict. This is the
            // "aadhaar.jpg + pan.pdf" case — same semantic category,
            // BGE-on-OCR similarity sits below the noise floor, but the
            // content-aware LLM is sure.
            if (members.size() >= 2 && !groupPassesCohesion(members, plan)
                    && !llmOverridesCohesion(members, plan)) {
                for (var jl : members) {
                    dropped.add(new ReorgProposal.DroppedDecision(
                            jl.file(),
                            String.format(
                                    "LLM grouped these files as \"%s\" but their content/filenames don't actually match — keeping them in place",
                                    sanitized),
                            jl.judgment().confidence()));
                    leftAlone.add(jl.file());
                    consumedByNewGroup.add(jl.file());
                }
                continue;
            }

            // Solo NEW folder needs a higher confidence bar — no peer to
            // corroborate, so demand more evidence than the default cutoff.
            float effectiveCutoff = members.size() == 1 ? SOLO_NEW_FOLDER_CUTOFF : confidenceCutoff;

            // Per-member confidence filter (preserves the existing semantics).
            List<ReorgToolLoopResult.JudgedLoner> survivors = new ArrayList<>(members.size());
            for (var jl : members) {
                float c = jl.judgment().confidence();
                if (c < effectiveCutoff) {
                    dropped.add(new ReorgProposal.DroppedDecision(
                            jl.file(),
                            String.format("Judgment confidence %.2f below cutoff %.2f", c, effectiveCutoff),
                            c));
                    leftAlone.add(jl.file());
                    consumedByNewGroup.add(jl.file());
                } else {
                    survivors.add(jl);
                }
            }
            if (survivors.isEmpty()) continue;

            // If trimming dropped a group below 2, re-check whether the
            // remaining file still deserves its own folder.
            if (survivors.size() == 1 && members.size() >= 2) {
                var only = survivors.get(0);
                if (only.judgment().confidence() < SOLO_NEW_FOLDER_CUTOFF) {
                    dropped.add(new ReorgProposal.DroppedDecision(
                            only.file(),
                            String.format(
                                    "Group dissolved when peers dropped; remaining file's confidence %.2f below solo cutoff %.2f",
                                    only.judgment().confidence(), SOLO_NEW_FOLDER_CUTOFF),
                            only.judgment().confidence()));
                    leftAlone.add(only.file());
                    consumedByNewGroup.add(only.file());
                    continue;
                }
            }

            DestResolution dest = resolveNewFolderDestination(
                    sanitized, target, existingByLcName, newFolderByLcName);
            int peers = survivors.size() - 1;
            for (var jl : survivors) {
                String reason = dest.isNew
                        ? (peers > 0
                                ? String.format("Part of a new \"%s\" group with %d related file%s: %s",
                                        sanitized, peers, peers == 1 ? "" : "s",
                                        previewReason(jl.judgment().reason()))
                                : String.format("Suggests its own folder \"%s\": %s",
                                        sanitized, previewReason(jl.judgment().reason())))
                        : String.format("Joins existing %s/: %s",
                                sanitized, previewReason(jl.judgment().reason()));
                moves.add(new ReorgProposal.ProposedMove(
                        jl.file(), dest.path, dest.isNew,
                        ReorgProposal.ProposedMove.Source.LONER_TO_NEW_FOLDER,
                        reason,
                        jl.judgment().confidence()));
                consumedByNewGroup.add(jl.file());
            }
        }

        // EXISTING_FOLDER and LEAVE_ALONE judgments (and unusable-name
        // NEW_FOLDER ones) handled per-file with the original logic.
        for (var jl : llmResult.judgedLoners()) {
            Path file = jl.file();
            if (consumedByNewGroup.contains(file)) continue;
            ToolPrompts.FileJudgment judgment = jl.judgment();
            float confidence = judgment.confidence();

            if (judgment.placement() == ToolPrompts.FileJudgment.Placement.LEAVE_ALONE) {
                leftAlone.add(file);
                continue;
            }

            if (judgment.placement() == ToolPrompts.FileJudgment.Placement.NEW_FOLDER) {
                // Only path that lands here: sanitize() rejected the name.
                dropped.add(new ReorgProposal.DroppedDecision(
                        file, "LLM returned an unusable new folder name", confidence));
                leftAlone.add(file);
                continue;
            }

            if (confidence < confidenceCutoff) {
                dropped.add(new ReorgProposal.DroppedDecision(
                        file,
                        String.format("Judgment confidence %.2f below cutoff %.2f",
                                confidence, confidenceCutoff),
                        confidence));
                leftAlone.add(file);
                continue;
            }

            // EXISTING_FOLDER.
            String requested = judgment.folderName();
            Path existing = existingByLcName.get(requested.toLowerCase());
            if (existing == null) {
                dropped.add(new ReorgProposal.DroppedDecision(
                        file,
                        "LLM referenced a folder that doesn't exist: " + requested,
                        confidence));
                leftAlone.add(file);
            } else {
                moves.add(new ReorgProposal.ProposedMove(
                        file, existing, false,
                        ReorgProposal.ProposedMove.Source.LONER_TO_EXISTING,
                        String.format("LLM judged this fits %s/: %s",
                                existing.getFileName(),
                                previewReason(judgment.reason())),
                        confidence));
            }
        }

        // ── 4. Unjudged loners (budget ran out / parse fail) ────────────────
        for (Path loner : plan.loners()) {
            if (!judgedFiles.contains(loner)) {
                leftAlone.add(loner);
            }
        }

        log.info("Proposal built: {} move(s), {} dropped, {} left-alone",
                moves.size(), dropped.size(), leftAlone.size());

        return new ReorgProposal(
                target,
                List.copyOf(moves),
                List.copyOf(dropped),
                List.copyOf(leftAlone),
                llmResult.llmCallsAttempted(),
                llmResult.llmCallsSuccessful(),
                llmResult.stoppedReason());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a sanitized folder name into a concrete destination path.
     * If an existing subdir matches case-insensitively → reuse it
     * (returns {@code isNew = false}). Otherwise either reuse a path we've
     * already assigned for this name during this build, or stake out a
     * fresh one. Same input always returns the same output within one
     * build — that's how we collapse duplicate-name proposals.
     */
    private DestResolution resolveNewFolderDestination(
            String sanitized,
            Path target,
            Map<String, Path> existingByLcName,
            Map<String, Path> newFolderByLcName) {

        String lc = sanitized.toLowerCase();
        Path existing = existingByLcName.get(lc);
        if (existing != null) {
            return new DestResolution(existing, false);
        }
        Path already = newFolderByLcName.get(lc);
        if (already != null) {
            return new DestResolution(already, true);
        }
        Path fresh = target.resolve(sanitized);
        newFolderByLcName.put(lc, fresh);
        return new DestResolution(fresh, true);
    }

    /**
     * Cleans up an LLM-suggested folder name so it's safe to use on Windows,
     * macOS, and Linux:
     *  - strips path separators and Windows-illegal chars
     *  - strips control characters
     *  - trims whitespace and trailing dots (Windows refuses trailing dots)
     *  - caps length to {@link #MAX_FOLDER_NAME_LEN}
     *  - returns "" if nothing usable remains
     */
    static String sanitizeFolderName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20) continue;                                   // control chars
            // Path separators and Windows-reserved characters become spaces
            // rather than getting deleted — "Tax/Documents" should read as
            // "Tax Documents", not "TaxDocuments". Multiple spaces are
            // collapsed below.
            if (c == '/' || c == '\\' || c == ':' || c == '*' ||
                c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append(' ');
                continue;
            }
            sb.append(c);
        }
        // Collapse runs of whitespace to a single space, then trim.
        String cleaned = sb.toString().replaceAll("\\s+", " ").trim();
        // Strip trailing dots and whitespace — Windows reserves "foo." vs "foo".
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) return "";
        if (cleaned.length() > MAX_FOLDER_NAME_LEN) {
            cleaned = cleaned.substring(0, MAX_FOLDER_NAME_LEN).trim();
        }
        // Reject names that are filesystem-reserved entirely (".", "..").
        if (cleaned.equals(".") || cleaned.equals("..")) return "";
        return cleaned;
    }

    /**
     * Loner-side cohesion check: builds a spanning tree (Kruskal on
     * cosine distance) over the group's file vectors and requires the
     * tree's max edge sim ≥ {@link #LLM_GROUP_COHESION_THRESHOLD}.
     * Filename-only pairs additionally require a shared meaningful
     * filename token (BGE-small on short strings is unreliable).
     *
     * <p>The MST/single-link metric is the correct cohesion measure
     * for cluster-like groupings: it's what HAC itself uses on the
     * way in, and "all-pairs" would reject legitimate chained clusters
     * where a few cross-pairs happen to sit below the floor while the
     * tree connects them tightly.
     *
     * <p>Returns {@code true} when any member's vector is missing — a
     * plumbing gap upstream shouldn't dissolve a grouping the LLM made.
     */
    private static boolean groupPassesCohesion(
            List<ReorgToolLoopResult.JudgedLoner> members,
            DirectoryReorgPlan plan) {
        List<Path> paths = new ArrayList<>(members.size());
        for (var jl : members) paths.add(jl.file());
        return spanningTreePassesCohesion(paths, plan, LLM_GROUP_COHESION_THRESHOLD);
    }

    /**
     * Local-cluster sanity check used by section 2. The cluster already
     * passed HAC at {@link ClusteringEngine#DEFAULT_DISTANCE_THRESHOLD}
     * — that's a strict spanning-tree bar — so we don't re-do the
     * cohesion math here. We only re-apply the filename-token rules
     * that depend on the cluster's metadata + LLM naming confidence.
     */
    private static boolean localClusterPassesTokenGate(
            List<Path> members,
            DirectoryReorgPlan plan,
            float llmNamingConfidence) {
        Set<Path> filenameOnly = plan.filenameOnlyLooseFiles();

        // When the LLM's own naming confidence is low, demand a shared
        // filename token for every pair — the LLM admitted uncertainty,
        // so we don't lean only on the embedding (which is what put the
        // cluster together in the first place — circular).
        boolean tokenGateForAllPairs = llmNamingConfidence < SOLO_NEW_FOLDER_CUTOFF;

        for (int i = 0; i < members.size(); i++) {
            Path pi = members.get(i);
            boolean iFilenameOnly = filenameOnly.contains(pi);
            for (int j = i + 1; j < members.size(); j++) {
                Path pj = members.get(j);
                boolean jFilenameOnly = filenameOnly.contains(pj);
                boolean needToken = iFilenameOnly || jFilenameOnly || tokenGateForAllPairs;
                if (needToken && !FilenameTokenizer.shareMeaningfulToken(
                        pi.getFileName().toString(), pj.getFileName().toString())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * True iff {@code members} can be spanned by a tree of cosine-sim
     * edges whose min edge sim ≥ {@code threshold}. Equivalent to
     * Kruskal's algorithm: sort edges by descending sim, add each as
     * long as it connects two components, stop when the tree spans all
     * nodes. Filename-only pairs are still blocked if they don't share
     * a meaningful token (BGE on short strings is noisy and easily
     * misleads the tree). A missing vector is treated as a "don't
     * penalize" pass — that's a plumbing gap upstream.
     */
    private static boolean spanningTreePassesCohesion(
            List<Path> members,
            DirectoryReorgPlan plan,
            float threshold) {
        int n = members.size();
        if (n <= 1) return true;

        Map<Path, float[]> vectors = plan.looseFileVectors();
        Set<Path> filenameOnly = plan.filenameOnlyLooseFiles();

        float[][] vecs = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] v = vectors.get(members.get(i));
            if (v == null) return true;
            vecs[i] = v;
        }

        BiPredicate<Path, Path> filenameTokenGate = (a, b) ->
                FilenameTokenizer.shareMeaningfulToken(
                        a.getFileName().toString(), b.getFileName().toString());

        // Build edge list, skipping filename-only pairs that fail the
        // token gate — they can never legitimately span the tree.
        record Edge(int i, int j, float sim) {}
        List<Edge> edges = new ArrayList<>(n * (n - 1) / 2);
        for (int i = 0; i < n; i++) {
            Path pi = members.get(i);
            boolean iFilenameOnly = filenameOnly.contains(pi);
            for (int j = i + 1; j < n; j++) {
                Path pj = members.get(j);
                boolean needToken = iFilenameOnly || filenameOnly.contains(pj);
                if (needToken && !filenameTokenGate.test(pi, pj)) continue;
                edges.add(new Edge(i, j, ClusteringEngine.cosineSimilarity(vecs[i], vecs[j])));
            }
        }
        edges.sort((a, b) -> Float.compare(b.sim(), a.sim()));   // descending sim

        // Union-find — same as ClusteringEngine but copied here so we
        // don't widen that class's API for a one-off check.
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
            @Override public int applyAsInt(int x) {
                while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
                return x;
            }
        };

        int components = n;
        float minTreeEdge = 1f;
        for (Edge e : edges) {
            if (e.sim() < threshold) break;       // any further edge is too weak
            int ri = find.applyAsInt(e.i());
            int rj = find.applyAsInt(e.j());
            if (ri == rj) continue;
            parent[ri] = rj;
            if (e.sim() < minTreeEdge) minTreeEdge = e.sim();
            components--;
            if (components == 1) break;
        }
        return components == 1;
    }

    /**
     * Returns true when every judged loner in this group has LLM confidence
     * ≥ {@link #HIGH_CONFIDENCE_LLM_OVERRIDE} AND at least one member has an
     * actual content preview (so the LLM's high confidence is grounded in
     * what it read, not a filename-only guess). When true, the caller may
     * skip the cohesion gate — see the call site for the rationale.
     */
    private static boolean llmOverridesCohesion(
            List<ReorgToolLoopResult.JudgedLoner> members,
            DirectoryReorgPlan plan) {
        if (members.isEmpty()) return false;
        boolean anyHasPreview = false;
        for (var jl : members) {
            if (jl.judgment().confidence() < HIGH_CONFIDENCE_LLM_OVERRIDE) return false;
            String preview = plan.looseFileContentPreviews().get(jl.file());
            if (preview != null && !preview.isBlank()) anyHasPreview = true;
        }
        return anyHasPreview;
    }

    /** Case-insensitive, trimmed match against {@link #GENERIC_FOLDER_NAMES}. */
    static boolean isGenericFolderName(String name) {
        if (name == null) return true;
        return GENERIC_FOLDER_NAMES.contains(name.trim().toLowerCase());
    }

    private static float clamp(float c) {
        if (Float.isNaN(c)) return 0f;
        if (c < 0f) return 0f;
        if (c > 1f) return 1f;
        return c;
    }

    private static String previewReason(String s) {
        if (s == null || s.isBlank()) return "no reason given";
        String trimmed = s.trim();
        if (trimmed.length() <= 120) return trimmed;
        return trimmed.substring(0, 117) + "…";
    }

    /** Internal: a destination path + whether we'll need to mkdir on execute. */
    private record DestResolution(Path path, boolean isNew) {}
}

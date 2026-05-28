package com.localfilebrain.reorg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the local (no-LLM) half of the reorg pipeline:
 *
 * <ol>
 *   <li>Scan {@code targetDir} → loose top-level files + immediate subdirs.</li>
 *   <li>Resolve a vector for every loose file via {@link FileVectorService}
 *       (chunk-pool for indexed files, filename fallback for the rest).</li>
 *   <li>For each existing subdir, compute a centroid from the vectors of
 *       its (recursively visible) files, plus a set of extensions present
 *       inside it for the hard compatibility check.</li>
 *   <li>Fit-to-existing pass: each loose file is offered to every
 *       existing subdir whose extension family includes it; best match
 *       above {@link #FIT_THRESHOLD} wins.</li>
 *   <li>Cluster the remaining loose files via {@link ClusteringEngine},
 *       then split any cluster that straddles incompatible extension
 *       families (hard boundary).</li>
 *   <li>Return a {@link DirectoryReorgPlan}.</li>
 * </ol>
 *
 * <p>Conservatism: v1 never proposes moving files OUT of existing
 * subdirectories — we trust the user's existing taxonomy. Existing
 * subdirs are read-only inputs that bias the placement of loose files.
 */
public final class DirectoryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DirectoryAnalyzer.class);

    /**
     * A loose file is assigned to an existing subdir only when its
     * cosine similarity to the subdir centroid clears this bar.
     *
     * <p>Empirically calibrated against BGE-small-en-v1.5: that model has
     * a noise floor near 0.55–0.65 between any two short personal-doc
     * embeddings (see the in-tree {@code ReorgDiagnostic} numbers).
     * Anything below 0.70 isn't distinguishable from baseline; we won't
     * silently relocate a file unless the fit is well above that floor.
     */
    public static final float FIT_THRESHOLD = 0.70f;

    /**
     * Cap (in characters) for the per-file content preview we collect
     * for the LLM. Big enough to communicate the topic and document type
     * for almost any real file (an Aadhaar OCR pass is &lt; 100 chars; a
     * resume's first chunk is &lt; 250); small enough to keep the
     * batched prompt cheap (~50 files × 320 ≈ 16K chars).
     */
    private static final int CONTENT_PREVIEW_CHARS = 320;

    /**
     * Minimum cosine similarity required between EVERY pair of files in
     * a locally-formed cluster. Single-link HAC at
     * {@link ClusteringEngine#DEFAULT_DISTANCE_THRESHOLD} chains together
     * any files connected by a strong edge — for personal-doc folders
     * BGE-small often puts unrelated documents (offer letter, nomination
     * form, screenshot) within the chain threshold even though they're
     * semantically distinct.
     *
     * <p>Calibrated against the {@code others2} corpus: the 17-file
     * mega-chain there has min pairwise sim 0.66 (Curefit thread ↔
     * GPA Nomination template, totally unrelated), while legitimate
     * near-duplicate pairs (resume ↔ resume_template = 0.81; EPF form ↔
     * filled-EPF form ≈ 0.94) sit much higher. 0.75 cleanly separates
     * the two and demotes the bad chains back to loners so the
     * content-aware LLM batched call can rebuild the real groupings.
     */
    public static final float CLUSTER_ALL_PAIRS_THRESHOLD = 0.75f;

    /**
     * Max depth we walk into an existing subdirectory when profiling its
     * contents. 4 levels covers typical taxonomies (e.g. {@code Tax/2024/Q1/});
     * deeper is rare and would inflate cost without improving the centroid.
     */
    private static final int SUBDIR_PROFILE_MAX_DEPTH = 4;

    /**
     * Cap on number of files inspected per existing-subdir profiling pass.
     * Guards against pathological cases (huge legacy folders) where the
     * profiling cost would dominate the actual reorg.
     */
    private static final int SUBDIR_PROFILE_MAX_FILES = 200;

    /**
     * Hard ceiling on loose files in a single analysis pass. Above this
     * threshold the analyzer short-circuits with a {@link ScopeError}
     * rather than spending O(N) filename embeddings and O(N²) clustering
     * on a directory we'd refuse to reorganize anyway (the LLM-call budget
     * would be exhausted many times over). Hit this when a user picks
     * their home folder or {@code /} by mistake.
     */
    private static final int MAX_LOOSE_FILES = 1000;

    /**
     * Names of directories we never analyze or treat as candidate
     * subdirs (machine-generated state, version-control internals,
     * macOS junk). Comparison is case-insensitive — see
     * {@link #isDenyListedDirName(String)}.
     *
     * <p>{@code restricted} is the user-facing opt-out: any folder named
     * "restricted" (in any case) is skipped entirely. Users put
     * sensitive files there to keep them out of reorg.
     *
     * <p>Hidden dotfiles are excluded separately via the leading-dot check.
     */
    private static final Set<String> DENY_DIR_NAMES = Set.of(
            ".git", ".hg", ".svn", "node_modules", ".idea", ".vscode",
            "target", "build", "dist", "__pycache__", ".DS_Store",
            "restricted"
    );

    /** Case-insensitive lookup against {@link #DENY_DIR_NAMES}. */
    private static boolean isDenyListedDirName(String name) {
        return name != null && DENY_DIR_NAMES.contains(name.toLowerCase());
    }

    private static final Set<String> DENY_FILE_NAMES = Set.of(
            ".DS_Store", "Thumbs.db", ".gitignore", ".gitattributes"
    );

    private final FileVectorService fileVectorService;
    private final ClusteringEngine  clusteringEngine;
    private final ScopeGuard        scopeGuard;

    public DirectoryAnalyzer(FileVectorService fileVectorService) {
        this(fileVectorService, new ClusteringEngine(), new ScopeGuard());
    }

    public DirectoryAnalyzer(FileVectorService fileVectorService,
                             ClusteringEngine clusteringEngine,
                             ScopeGuard scopeGuard) {
        this.fileVectorService = fileVectorService;
        this.clusteringEngine  = clusteringEngine;
        this.scopeGuard        = scopeGuard;
    }

    public DirectoryReorgPlan analyze(Path targetDir) throws IOException {
        if (!Files.isDirectory(targetDir)) {
            throw new IllegalArgumentException("Not a directory: " + targetDir);
        }
        log.info("Analyzing directory for reorg: {}", targetDir.toAbsolutePath());

        Scan scan = scanDir(targetDir);
        log.info("  loose files: {} | existing subdirs: {}",
                scan.looseFiles.size(), scan.subdirs.size());

        // Early scope-bail: if the directory is huge we don't want to spend
        // O(N) embeddings + O(N²) clustering just to discover what we
        // already know — the LLM-call budget would be exhausted many times
        // over. Return a ScopeError with subdir suggestions assembled
        // from a cheap subdir scan, leaving cost essentially zero.
        if (scan.looseFiles.size() > MAX_LOOSE_FILES) {
            List<DirectoryReorgPlan.ExistingSubdirSummary> cheapSummaries = new ArrayList<>();
            for (Path sub : scan.subdirs) {
                cheapSummaries.add(new DirectoryReorgPlan.ExistingSubdirSummary(
                        sub, countDirectChildren(sub)));
            }
            Path absTargetEarly = targetDir.toAbsolutePath();
            ScopeError err = new ScopeError(
                    "This folder has too many loose files to organize in one pass.",
                    String.format(
                            "It contains %d loose files (my limit is %d per pass). "
                          + "Reorganizing this many at once would blow past my "
                          + "per-reorg budget and lead to lower-quality groupings.",
                            scan.looseFiles.size(), MAX_LOOSE_FILES),
                    suggestionsFromSubdirs(cheapSummaries),
                    scan.looseFiles.size(),
                    MAX_LOOSE_FILES);
            return new DirectoryReorgPlan(
                    absTargetEarly,
                    Map.of(), List.of(), List.of(), List.of(),
                    scan.looseFiles.size(), scan.looseFiles.size(),
                    List.copyOf(cheapSummaries),
                    Map.of(),
                    Optional.of(err),
                    Map.of(),
                    Set.of(),
                    Map.of());
        }

        // Resolve vectors for loose files.
        Map<Path, float[]> looseVectors = new LinkedHashMap<>();
        List<Path>         skippedNoVector = new ArrayList<>();
        for (Path f : scan.looseFiles) {
            Optional<float[]> v = fileVectorService.getFileVector(f.toAbsolutePath().toString());
            if (v.isPresent()) looseVectors.put(f, v.get());
            else               skippedNoVector.add(f);
        }

        // Count loose files by extension family — used by the scope guard
        // to suggest "run on just the PDFs" carve-outs when needed.
        Map<ExtensionFamily.Family, Integer> familyCounts = new java.util.EnumMap<>(ExtensionFamily.Family.class);
        for (Path f : scan.looseFiles) {
            ExtensionFamily.Family fam = ExtensionFamily.of(ExtensionFamily.extensionOf(f.getFileName().toString()));
            familyCounts.merge(fam, 1, Integer::sum);
        }

        // Build a centroid + extension-set for each existing subdir, AND
        // record a lightweight summary (path + file count) for the UI / scope
        // error suggestions.
        Map<Path, ExistingFolderProfile> profiles = new LinkedHashMap<>();
        List<DirectoryReorgPlan.ExistingSubdirSummary> subdirSummaries = new ArrayList<>();
        for (Path sub : scan.subdirs) {
            ExistingFolderProfile p = profileExistingSubdir(sub);
            if (p != null) {
                profiles.put(sub, p);
                subdirSummaries.add(new DirectoryReorgPlan.ExistingSubdirSummary(sub, p.fileNames.size()));
            } else {
                // Empty / unreadable subdir — still surface in the summary
                // with fileCount 0 so the UI can show it.
                subdirSummaries.add(new DirectoryReorgPlan.ExistingSubdirSummary(sub, 0));
            }
        }

        // Fit-to-existing pass.
        Map<Path, DirectoryReorgPlan.ExistingFolderMatch> assigned = new LinkedHashMap<>();
        Map<Path, float[]> remaining = new LinkedHashMap<>(looseVectors);

        for (var entry : looseVectors.entrySet()) {
            Path file = entry.getKey();
            float[] vec = entry.getValue();
            String fileName = file.getFileName().toString();

            Path  bestFolder = null;
            float bestSim    = -1f;
            for (var pe : profiles.entrySet()) {
                ExistingFolderProfile prof = pe.getValue();
                if (!ExtensionFamily.compatibleWithAny(fileName, prof.fileNames)) continue;
                float sim = ClusteringEngine.cosineSimilarity(vec, prof.centroid);
                if (sim > bestSim) {
                    bestSim    = sim;
                    bestFolder = pe.getKey();
                }
            }

            if (bestFolder != null && bestSim >= FIT_THRESHOLD) {
                assigned.put(file, new DirectoryReorgPlan.ExistingFolderMatch(bestFolder, bestSim));
                remaining.remove(file);
            }
        }

        // Cluster the leftovers.
        Map<String, float[]> remainingKeyed = new LinkedHashMap<>();
        Map<String, Path>    keyToPath      = new HashMap<>();
        for (var e : remaining.entrySet()) {
            String key = e.getKey().toString();
            remainingKeyed.put(key, e.getValue());
            keyToPath.put(key, e.getKey());
        }

        // Build a per-pair hard gate: two files that are BOTH filename-only
        // (no indexed chunks) must share at least one meaningful filename
        // token to legitimately cluster. Without this, BGE on unrelated
        // short strings (AADHAR_FRONT.jpg vs Screenshot 2026-04-13.png)
        // can produce spuriously high cosine similarity and form garbage
        // clusters. Content-rich files keep the relaxed rule because
        // their vectors carry real semantic signal.
        java.util.Set<String> filenameOnlyKeys = new java.util.HashSet<>();
        for (String key : remainingKeyed.keySet()) {
            if (fileVectorService.isFilenameOnly(keyToPath.get(key).toAbsolutePath().toString())) {
                filenameOnlyKeys.add(key);
            }
        }
        java.util.function.BiPredicate<String, String> pairGate = (kA, kB) -> {
            boolean aFilenameOnly = filenameOnlyKeys.contains(kA);
            boolean bFilenameOnly = filenameOnlyKeys.contains(kB);
            if (!aFilenameOnly && !bFilenameOnly) return true;          // both content-rich → OK
            // At least one side is filename-only. Require shared meaningful
            // filename token to allow the edge. Mixed (one content-rich,
            // one filename-only) also goes through the gate because the
            // filename-only side has no real semantic vector to trust.
            String fnA = keyToPath.get(kA).getFileName().toString();
            String fnB = keyToPath.get(kB).getFileName().toString();
            return FilenameTokenizer.shareMeaningfulToken(fnA, fnB);
        };

        List<ClusteringEngine.Cluster> rawClusters =
                clusteringEngine.cluster(remainingKeyed, pairGate);

        // Apply hard extension-family boundary: split clusters that
        // straddle incompatible families. Each resulting sub-cluster is
        // a homogeneous family.
        List<ClusteringEngine.Cluster> split = new ArrayList<>();
        for (var c : rawClusters) {
            split.addAll(splitByExtensionFamily(c, keyToPath));
        }

        // All-pairs cohesion check: dissolve any cluster where some pair
        // sits below CLUSTER_ALL_PAIRS_THRESHOLD. Single-link HAC's chain
        // effect lets unrelated files ride into a cluster via a strong
        // edge to one member; this is what produces "17 personal docs all
        // grouped as 'Nomination Forms'" on a real folder. Demoted files
        // become loners and the content-aware LLM batched judgment
        // rebuilds the real groupings.
        List<ClusteringEngine.Cluster> validated = new ArrayList<>();
        for (var c : split) {
            if (c.size() <= 1 || clusterPassesAllPairs(c, remainingKeyed, CLUSTER_ALL_PAIRS_THRESHOLD)) {
                validated.add(c);
            } else {
                log.info("Dissolving over-broad cluster of {} files — all-pairs sim floor {} not met",
                        c.size(), CLUSTER_ALL_PAIRS_THRESHOLD);
                for (String m : c.members()) {
                    validated.add(new ClusteringEngine.Cluster(List.of(m), 0f));
                }
            }
        }
        split = validated;

        // Sort into new-cluster candidates vs loners. Also stash the
        // vector + filename-only status for every loose file we put on
        // the table — both cluster members and loners. The plan builder
        // uses these to re-check cohesion against any grouping the LLM
        // proposes (catches OCR'd-image false positives where unrelated
        // images get clustered because BGE on Tika's OCR garbage gives
        // them spurious similarity).
        List<DirectoryReorgPlan.NewClusterCandidate> newClusters = new ArrayList<>();
        List<Path> loners = new ArrayList<>();
        Map<Path, float[]> looseFileVectors = new LinkedHashMap<>();
        Set<Path> filenameOnlyLooseFiles = new HashSet<>();
        Map<Path, String> looseFileContentPreviews = new LinkedHashMap<>();
        for (var c : split) {
            for (String memberKey : c.members()) {
                Path memberPath = keyToPath.get(memberKey);
                looseFileVectors.put(memberPath, remainingKeyed.get(memberKey));
                if (filenameOnlyKeys.contains(memberKey)) filenameOnlyLooseFiles.add(memberPath);
                // Snippet for the LLM. Filename-only files have no chunks
                // → preview empty; the LLM will see "(no content preview)"
                // for those and weigh the filename more carefully.
                fileVectorService
                        .getContentPreview(memberPath.toAbsolutePath().toString(), CONTENT_PREVIEW_CHARS)
                        .ifPresent(p -> looseFileContentPreviews.put(memberPath, p));
            }
            if (c.size() >= 2) {
                List<Path> paths = c.members().stream().map(keyToPath::get).toList();
                // cohesion = 1 - max intra-cluster distance, clamped to [0, 1]
                float cohesion = Math.max(0f, 1f - c.maxIntraDistance());
                newClusters.add(new DirectoryReorgPlan.NewClusterCandidate(paths, cohesion));
            } else {
                loners.add(keyToPath.get(c.members().get(0)));
            }
        }
        // Also preview the files we fit to existing subdirs — the LLM
        // never sees those, but the JSON serialization round-trip in
        // ApiServer surfaces them in dropped/reason text where useful.
        for (Path fitFile : assigned.keySet()) {
            fileVectorService
                    .getContentPreview(fitFile.toAbsolutePath().toString(), CONTENT_PREVIEW_CHARS)
                    .ifPresent(p -> looseFileContentPreviews.put(fitFile, p));
        }

        int totalDecisions = newClusters.size() + loners.size();   // LLM-call estimate
        log.info("  assigned to existing: {} | new clusters: {} | loners: {} | no-vector: {} | decisions: {}",
                assigned.size(), newClusters.size(), loners.size(), skippedNoVector.size(), totalDecisions);

        Path absTargetDir = targetDir.toAbsolutePath();
        List<DirectoryReorgPlan.ExistingSubdirSummary> subdirSummariesImmutable = List.copyOf(subdirSummaries);
        Map<ExtensionFamily.Family, Integer> familyCountsImmutable = Map.copyOf(familyCounts);

        // Run the scope guard against the raw inputs so the plan record can
        // be constructed exactly once with the result baked in.
        Optional<ScopeError> scopeErr = scopeGuard.check(
                totalDecisions, absTargetDir, subdirSummariesImmutable, familyCountsImmutable);
        scopeErr.ifPresent(e -> log.info(
                "  scope error: {} decisions exceeds budget {}",
                totalDecisions, e.decisionBudget()));

        return new DirectoryReorgPlan(
                absTargetDir,
                assigned,
                newClusters,
                loners,
                skippedNoVector,
                scan.looseFiles.size(),
                totalDecisions,
                subdirSummariesImmutable,
                familyCountsImmutable,
                scopeErr,
                Map.copyOf(looseFileVectors),
                Set.copyOf(filenameOnlyLooseFiles),
                Map.copyOf(looseFileContentPreviews));
    }

    // -------------------------------------------------------------------------
    // Subdir profiling: centroid + set of file extensions present inside
    // -------------------------------------------------------------------------

    private ExistingFolderProfile profileExistingSubdir(Path subdir) throws IOException {
        List<float[]> vectors = new ArrayList<>();
        Set<String>   fileNames = new HashSet<>();

        // Walk to a bounded depth so we catch nested structure (Tax/2024/Q1)
        // without descending arbitrarily deep. Cap at SUBDIR_PROFILE_MAX_FILES
        // to avoid runaway costs on huge legacy folders.
        try (var stream = Files.walk(subdir, SUBDIR_PROFILE_MAX_DEPTH)) {
            int seen = 0;
            for (var iter = stream.iterator(); iter.hasNext() && seen < SUBDIR_PROFILE_MAX_FILES; ) {
                Path p = iter.next();
                if (Files.isDirectory(p))                                     continue;
                if (isDeniedUnder(p, subdir))                                 continue;

                fileNames.add(p.getFileName().toString());

                Optional<float[]> v = fileVectorService.getFileVector(p.toAbsolutePath().toString());
                v.ifPresent(vectors::add);
                seen++;
            }
        }

        if (vectors.isEmpty()) {
            // Empty or unreadable subdir — cannot use as a fit target. Drop.
            log.debug("Skipping empty/unreadable subdir for fit target: {}", subdir);
            return null;
        }

        float[] centroid = ClusteringEngine.centroid(vectors);
        return new ExistingFolderProfile(centroid, fileNames);
    }

    // -------------------------------------------------------------------------
    // Hard extension-family boundary on clusters
    // -------------------------------------------------------------------------

    /**
     * Returns true iff every pair of vectors in this cluster has cosine
     * sim ≥ {@code threshold}. O(N²) — N is bounded by HAC cluster size,
     * which is bounded by directory size, so always small enough.
     */
    private static boolean clusterPassesAllPairs(
            ClusteringEngine.Cluster cluster,
            Map<String, float[]> vectors,
            float threshold) {
        var members = cluster.members();
        for (int i = 0; i < members.size(); i++) {
            float[] vi = vectors.get(members.get(i));
            if (vi == null) return true;       // plumbing gap — don't penalize
            for (int j = i + 1; j < members.size(); j++) {
                float[] vj = vectors.get(members.get(j));
                if (vj == null) return true;
                if (ClusteringEngine.cosineSimilarity(vi, vj) < threshold) return false;
            }
        }
        return true;
    }

    private List<ClusteringEngine.Cluster> splitByExtensionFamily(
            ClusteringEngine.Cluster cluster,
            Map<String, Path> keyToPath) {

        if (cluster.size() <= 1) return List.of(cluster);

        // Bucket by (family, exact-ext-if-OTHER). OTHER extensions only
        // bind to themselves, mirroring ExtensionFamily.compatible().
        Map<String, List<String>> buckets = new LinkedHashMap<>();
        for (String key : cluster.members()) {
            String fileName = keyToPath.get(key).getFileName().toString();
            String ext      = ExtensionFamily.extensionOf(fileName);
            ExtensionFamily.Family fam = ExtensionFamily.of(ext);
            String bucketKey = fam == ExtensionFamily.Family.OTHER ? "OTHER:" + ext : fam.name();
            buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(key);
        }

        if (buckets.size() == 1) return List.of(cluster);

        List<ClusteringEngine.Cluster> out = new ArrayList<>(buckets.size());
        for (var bucket : buckets.values()) {
            // After splitting, intra-cluster distance is ≤ original. We don't
            // recompute the exact diameter — the original max is a sound
            // upper bound and cohesion is only ever used as a sort key
            // downstream, never as a hard threshold.
            out.add(new ClusteringEngine.Cluster(List.copyOf(bucket), cluster.maxIntraDistance()));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Directory scan
    // -------------------------------------------------------------------------

    private Scan scanDir(Path dir) throws IOException {
        List<Path> looseFiles = new ArrayList<>();
        List<Path> subdirs    = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isSymbolicLink(p)) continue;
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (Files.isDirectory(p)) {
                    if (isDenyListedDirName(name)) continue;
                    subdirs.add(p);
                } else if (Files.isRegularFile(p)) {
                    if (DENY_FILE_NAMES.contains(name)) continue;
                    looseFiles.add(p);
                }
            }
        }
        return new Scan(looseFiles, subdirs);
    }

    /**
     * Skip-decision for a file encountered during a walk rooted at
     * {@code walkRoot}. Only path segments BETWEEN {@code walkRoot} and the
     * file (exclusive of both) are checked against the deny list — segments
     * above the walk root are part of the user's filesystem layout (e.g. the
     * absolute prefix containing "target/" because the user happens to be
     * organizing files under a Maven project) and must not poison the check.
     */
    private boolean isDeniedUnder(Path file, Path walkRoot) {
        String name = file.getFileName().toString();
        if (name.startsWith(".")) return true;
        if (DENY_FILE_NAMES.contains(name)) return true;

        Path normRoot = walkRoot.toAbsolutePath().normalize();
        Path cursor   = file.toAbsolutePath().normalize().getParent();
        while (cursor != null && !cursor.equals(normRoot)) {
            String segName = cursor.getFileName() != null ? cursor.getFileName().toString() : "";
            if (isDenyListedDirName(segName) || segName.startsWith(".")) return true;
            cursor = cursor.getParent();
        }
        return false;
    }

    private record Scan(List<Path> looseFiles, List<Path> subdirs) {}

    /** Fast, shallow count of regular files directly in {@code dir}. Used by the early-scope-bail path. */
    private int countDirectChildren(Path dir) {
        int n = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && !p.getFileName().toString().startsWith(".")) n++;
            }
        } catch (IOException e) {
            // Permissions / unreadable — return 0 rather than fail the whole call.
            log.debug("countDirectChildren failed for {}: {}", dir, e.getMessage());
        }
        return n;
    }

    /**
     * Builds up to two subdir-pointer suggestions for the early-scope-bail
     * {@link ScopeError}. Mirrors the strategy in {@link ScopeGuard} but
     * inlined here so we don't have to construct a full plan first.
     */
    private static List<ScopeError.Suggestion> suggestionsFromSubdirs(
            List<DirectoryReorgPlan.ExistingSubdirSummary> subs) {
        var copy = new ArrayList<>(subs);
        copy.sort(java.util.Comparator
                .comparingInt(DirectoryReorgPlan.ExistingSubdirSummary::fileCount).reversed());
        List<ScopeError.Suggestion> out = new ArrayList<>();
        for (var s : copy) {
            if (out.size() >= 2) break;
            if (s.fileCount() == 0) continue;
            String label = String.format("Run on %s/ (%d %s) instead",
                    s.path().getFileName(), s.fileCount(),
                    s.fileCount() == 1 ? "file" : "files");
            out.add(new ScopeError.Suggestion(label, s.path(), null));
        }
        if (out.isEmpty()) {
            out.add(new ScopeError.Suggestion(
                    "Move some files into a new subfolder first, then re-run",
                    subs.isEmpty() ? null : subs.get(0).path().getParent(),
                    null));
        }
        return List.copyOf(out);
    }

    /** Per-subdir profile used during the fit-to-existing pass. */
    private record ExistingFolderProfile(float[] centroid, Set<String> fileNames) {}
}

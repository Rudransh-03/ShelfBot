package com.localfilebrain.reorg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Single-linkage hierarchical agglomerative clustering over L2-normalized
 * vectors using cosine distance.
 *
 * <p>Algorithm — equivalent to Kruskal's on a thresholded MST:
 * <ol>
 *   <li>Compute all pairwise cosine distances.</li>
 *   <li>Sort edges ascending by distance.</li>
 *   <li>Union endpoints whose distance is below the cut threshold.</li>
 *   <li>Read connected components out of the union-find structure.</li>
 * </ol>
 *
 * <p>Why this and not HDBSCAN: at the scale where this runs (N &lt; ~500
 * files in a personal directory), single-linkage HAC with a cosine cut is
 * mathematically equivalent to HDBSCAN with {@code minPts=1} for the
 * cluster-extraction step. HDBSCAN's main edge — handling variable-density
 * regions — does not show up for the use case (personal file folders), and
 * implementing it properly in Java is ~500 lines vs. ~80 here. If real
 * data later shows variable density mattering, swapping the algorithm out
 * is a localized change behind this class's interface.
 *
 * <p>Complexity: O(N²) memory + O(N² log N) time for the sort. Fine for
 * N ≤ 500. Above that we'd want a different approach.
 *
 * <p>Input vectors are assumed L2-normalized (which {@link FileVectorService}
 * guarantees) so cosine distance reduces to {@code 1 - dot(a, b)}.
 */
public final class ClusteringEngine {

    /**
     * Default cosine-distance threshold for forming clusters. 0.22 ≈
     * cosine similarity 0.78 — only files that are clearly above the
     * BGE-small noise floor (~0.55–0.65 for any two short personal
     * documents) get clustered locally. Spurious BGE "topic similarity"
     * on short OCR text used to clear the older 0.60-sim bar and pull
     * unrelated images / docs into one cluster; this threshold makes the
     * local pass high-precision and lets the LLM's content-aware
     * batched judgment ({@link ReorgToolLoop}) handle the looser cases.
     */
    public static final float DEFAULT_DISTANCE_THRESHOLD = 0.22f;

    private final float distanceThreshold;

    public ClusteringEngine() { this(DEFAULT_DISTANCE_THRESHOLD); }

    public ClusteringEngine(float distanceThreshold) {
        if (distanceThreshold < 0 || distanceThreshold > 2) {
            throw new IllegalArgumentException("distanceThreshold must be in [0, 2], got " + distanceThreshold);
        }
        this.distanceThreshold = distanceThreshold;
    }

    /**
     * Clusters the given keyed vectors. Iteration order of the input
     * preserved into the output clusters where possible.
     *
     * @param keyedVectors  ordered map of key → vector (all same dim, L2-normalized)
     * @return list of clusters; singletons are returned as 1-element clusters
     */
    public List<Cluster> cluster(Map<String, float[]> keyedVectors) {
        return cluster(keyedVectors, null);
    }

    /**
     * Same as {@link #cluster(Map)} but with a per-pair hard gate. When
     * {@code pairAcceptor} returns {@code false} for a pair, that pair is
     * treated as having infinite distance — they CANNOT cluster together
     * no matter how high their embedding similarity is. Used to enforce
     * "files without meaningful filename overlap don't cluster" semantics
     * for filename-only vectors, where BGE can give spuriously high
     * similarity on unrelated short strings.
     *
     * <p>Pass {@code null} for no gating (identical behaviour to
     * {@link #cluster(Map)}).
     */
    public List<Cluster> cluster(Map<String, float[]> keyedVectors,
                                 BiPredicate<String, String> pairAcceptor) {
        List<String>  keys = new ArrayList<>(keyedVectors.keySet());
        List<float[]> vecs = new ArrayList<>(keyedVectors.values());
        final int n = keys.size();

        if (n == 0) return List.of();
        if (n == 1) return List.of(new Cluster(List.of(keys.get(0)), 0f));

        // Build edge list (i, j, distance) for i < j. Skip any pair the
        // acceptor rejects — same effect as +∞ distance, more efficient.
        List<Edge> edges = new ArrayList<>(n * (n - 1) / 2);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (pairAcceptor != null && !pairAcceptor.test(keys.get(i), keys.get(j))) {
                    continue;
                }
                float d = cosineDistance(vecs.get(i), vecs.get(j));
                edges.add(new Edge(i, j, d));
            }
        }
        edges.sort(Comparator.comparingDouble(e -> e.distance));

        UnionFind uf = new UnionFind(n);
        // Track the max edge weight within each component — that's the
        // component's "cohesion" (smaller = tighter cluster). Initialized
        // to 0 for singletons.
        float[] componentCohesion = new float[n];

        for (Edge e : edges) {
            if (e.distance > distanceThreshold) break;
            if (uf.union(e.i, e.j)) {
                int root = uf.find(e.i);
                // The newly added edge is the largest in the merged component
                // because edges are processed in ascending order.
                componentCohesion[root] = Math.max(componentCohesion[root], e.distance);
            }
        }

        // Read components out of the union-find.
        Map<Integer, List<String>> byRoot = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(uf.find(i), k -> new ArrayList<>()).add(keys.get(i));
        }

        List<Cluster> result = new ArrayList<>(byRoot.size());
        for (var entry : byRoot.entrySet()) {
            result.add(new Cluster(List.copyOf(entry.getValue()), componentCohesion[entry.getKey()]));
        }
        return result;
    }

    /** Cosine distance assuming both vectors are L2-normalized. */
    public static float cosineDistance(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dim mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        // Clamp; floating point can drift slightly outside [-1, 1].
        if (dot > 1.0) dot = 1.0;
        if (dot < -1.0) dot = -1.0;
        return (float) (1.0 - dot);
    }

    /** Cosine similarity assuming both vectors are L2-normalized. */
    public static float cosineSimilarity(float[] a, float[] b) {
        return 1f - cosineDistance(a, b);
    }

    /**
     * Mean of a list of L2-normalized vectors, then L2-normalized again —
     * the standard "centroid" representation for cosine-clustered data.
     */
    public static float[] centroid(List<float[]> vectors) {
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("Cannot centroid empty list");
        }
        return FileVectorService.meanPoolAndNormalize(vectors);
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /**
     * A cluster of files. {@code maxIntraDistance} is the largest pairwise
     * cosine distance between any two members along the single-link path
     * (i.e. the cluster's diameter under single linkage). 0 for singletons.
     */
    public record Cluster(List<String> members, float maxIntraDistance) {
        public boolean isSingleton() { return members.size() == 1; }
        public int size() { return members.size(); }
    }

    private record Edge(int i, int j, float distance) {}

    /** Tiny union-find with path compression and union-by-rank. */
    private static final class UnionFind {
        final int[] parent;
        final int[] rank;

        UnionFind(int n) {
            parent = new int[n];
            rank   = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        /** Returns true if a merge actually happened. */
        boolean union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra == rb) return false;
            if (rank[ra] < rank[rb]) { parent[ra] = rb; }
            else if (rank[ra] > rank[rb]) { parent[rb] = ra; }
            else { parent[rb] = ra; rank[ra]++; }
            return true;
        }
    }
}

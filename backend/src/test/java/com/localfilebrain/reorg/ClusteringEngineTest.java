package com.localfilebrain.reorg;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ClusteringEngineTest {

    @Test
    void empty_returnsEmpty() {
        assertTrue(new ClusteringEngine().cluster(new LinkedHashMap<>()).isEmpty());
    }

    @Test
    void singleton_returnsOneCluster() {
        Map<String, float[]> in = new LinkedHashMap<>();
        in.put("a", unit(1, 0));
        List<ClusteringEngine.Cluster> out = new ClusteringEngine().cluster(in);
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).size());
        assertTrue(out.get(0).isSingleton());
        assertEquals(0f, out.get(0).maxIntraDistance(), 1e-6);
    }

    @Test
    void closePairsMerge_underThreshold() {
        // Two near-identical pairs + one outlier
        Map<String, float[]> in = new LinkedHashMap<>();
        in.put("a1", unit(1, 0));
        in.put("a2", unit(0.99f, 0.14f));    // ~8° from a1, close
        in.put("b1", unit(0, 1));
        in.put("b2", unit(0.14f, 0.99f));    // ~8° from b1
        in.put("c",  unit(-1, 0));            // diametric outlier

        List<ClusteringEngine.Cluster> out = new ClusteringEngine(0.40f).cluster(in);

        // Expect: {a1, a2}, {b1, b2}, {c}
        assertEquals(3, out.size());
        // Find a-cluster
        ClusteringEngine.Cluster aClu = findClusterContaining(out, "a1");
        assertEquals(2, aClu.size());
        assertTrue(aClu.members().contains("a2"));
        ClusteringEngine.Cluster bClu = findClusterContaining(out, "b1");
        assertEquals(2, bClu.size());
        assertTrue(bClu.members().contains("b2"));
        ClusteringEngine.Cluster cClu = findClusterContaining(out, "c");
        assertTrue(cClu.isSingleton());
    }

    @Test
    void singleLinkage_chainsMergeIntoOneCluster() {
        // a-b close, b-c close, but a-c not within threshold of each other:
        // single-linkage still merges all three because they chain via b.
        Map<String, float[]> in = new LinkedHashMap<>();
        in.put("a", unit(1f, 0f));
        in.put("b", unit(0.92f, 0.39f));   // ~23° from a; cos ≈ 0.92 → dist ≈ 0.08
        in.put("c", unit(0.71f, 0.71f));   // ~45° from a; cos ≈ 0.71 → dist ≈ 0.29
        // a-b dist ≈ 0.08; b-c dist ≈ cos(22°) ≈ 0.927 → dist 0.073
        // a-c dist ≈ 0.29 (above 0.2 threshold)

        List<ClusteringEngine.Cluster> out = new ClusteringEngine(0.20f).cluster(in);

        // Threshold 0.20 admits a-b and b-c but not a-c directly. Single
        // linkage chains them anyway.
        assertEquals(1, out.size());
        assertEquals(3, out.get(0).size());
    }

    @Test
    void cosineDistance_returnsZeroForIdentical() {
        float[] v = unit(0.6f, 0.8f);
        assertEquals(0f, ClusteringEngine.cosineDistance(v, v), 1e-5);
    }

    @Test
    void cosineDistance_returnsTwoForOpposite() {
        float[] a = unit(1, 0);
        float[] b = unit(-1, 0);
        assertEquals(2f, ClusteringEngine.cosineDistance(a, b), 1e-5);
    }

    @Test
    void centroid_isL2NormalizedMean() {
        float[] c = ClusteringEngine.centroid(List.of(unit(1, 0), unit(0, 1)));
        // mean = (0.5, 0.5), normalized = (0.7071, 0.7071)
        assertEquals(0.7071f, c[0], 1e-4);
        assertEquals(0.7071f, c[1], 1e-4);
    }

    // --- helpers ------------------------------------------------------------

    private static float[] unit(float x, float y) {
        double n = Math.sqrt(x * x + y * y);
        return new float[] { (float)(x / n), (float)(y / n) };
    }

    private static ClusteringEngine.Cluster findClusterContaining(
            List<ClusteringEngine.Cluster> clusters, String key) {
        return clusters.stream()
                .filter(c -> c.members().contains(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cluster contains " + key));
    }
}

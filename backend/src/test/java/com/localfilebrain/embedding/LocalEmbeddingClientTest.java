package com.localfilebrain.embedding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test for {@link LocalEmbeddingClient}: downloads the BGE
 * model on first run (~130 MB, cached afterwards), runs real ONNX inference,
 * and verifies the produced vectors have the right shape, the right norm,
 * and sensible semantic behaviour (related sentences score higher than
 * unrelated ones).
 *
 * The model is cached under {@code target/test-bge-cache/} so subsequent
 * runs don't redownload. We also gate on a system property so CI envs
 * without network can opt out.
 */
class LocalEmbeddingClientTest {

    @Test
    void embedsTwoSentencesAndDistinguishesRelatedFromUnrelated() throws IOException {
        // CI/offline guard. Run locally with: mvn test -Dshelfbot.runLocalEmbeddingTest=true
        if (!Boolean.parseBoolean(System.getProperty("shelfbot.runLocalEmbeddingTest", "false"))) {
            System.out.println("Skipping LocalEmbeddingClientTest (set -Dshelfbot.runLocalEmbeddingTest=true to enable).");
            return;
        }

        Path cache = Path.of("target", "test-bge-cache");
        Files.createDirectories(cache);

        try (LocalEmbeddingClient client = new LocalEmbeddingClient(cache)) {

            assertEquals(384, client.dimensions(), "BGE-small produces 384-dim vectors");
            assertEquals("local:bge-small-en-v1.5", client.modelId());

            List<float[]> vecs = client.embedBatch(List.of(
                    "The cat sat on the mat.",
                    "A feline rested on the rug.",
                    "Quantum chromodynamics describes the strong nuclear force."
            ));

            assertEquals(3, vecs.size());
            for (float[] v : vecs) {
                assertEquals(384, v.length, "Each vector is 384-dim");
                double norm = 0;
                for (float x : v) norm += (double) x * x;
                // Vectors are L2-normalised → norm should be ~1.0
                assertEquals(1.0, Math.sqrt(norm), 1e-4, "Vectors should be L2-normalised");
            }

            double catSimRelated   = cosine(vecs.get(0), vecs.get(1));
            double catSimUnrelated = cosine(vecs.get(0), vecs.get(2));

            // Semantic sanity: related sentences should be closer than unrelated ones.
            assertTrue(catSimRelated > catSimUnrelated,
                    "Related sentences should have higher cosine similarity. "
                  + "Related=" + catSimRelated + ", Unrelated=" + catSimUnrelated);
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot; // a and b are already L2-normalised
    }
}

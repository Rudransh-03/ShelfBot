package com.localfilebrain.storage;

import com.localfilebrain.embedding.LocalEmbeddingClient;
import com.localfilebrain.model.DocumentChunk;
import com.localfilebrain.storage.VectorStore.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-embedding retrieval probe (gated, like LocalEmbeddingClientTest). Proves
 * that a realistic bank-statement chunk IS retrieved for "what is the bank
 * balance", and demonstrates the bug from the screenshots: a filler-dominated
 * chunk ("line item reviewed and reconciled" repeated) ranks BELOW the real one
 * — i.e. the earlier test corpus, not the pipeline, was the problem. Also
 * re-confirms the per-client scope filter excludes out-of-scope files.
 *
 * Run: mvn test -Dtest=RetrievalProbeTest -Dshelfbot.runLocalEmbeddingTest=true
 */
class RetrievalProbeTest {

    @TempDir Path tmp;

    private static DocumentChunk chunk(String path, String text) {
        return DocumentChunk.builder().chunkId(path + "::chunk-0").sourceFilePath(path)
                .fileName(path.substring(path.lastIndexOf('/') + 1)).fileExtension("pdf")
                .text(text).mimeType("application/pdf").chunkIndex(0).totalChunks(1).build();
    }

    @Test
    void bankBalanceQueryRetrievesTheRealisticStatement() throws Exception {
        if (!Boolean.parseBoolean(System.getProperty("shelfbot.runLocalEmbeddingTest", "false"))) {
            System.out.println("Skipping RetrievalProbeTest (set -Dshelfbot.runLocalEmbeddingTest=true).");
            return;
        }
        Path cache = Path.of("target", "test-bge-cache");
        Files.createDirectories(cache);

        String realistic = "HDFC BANK CURRENT ACCOUNT STATEMENT. Account Holder: Sharma Bakery. "
                + "Statement period January 2024. Card settlement +62,000 Balance 4,65,000. "
                + "Staff salaries -55,000 Balance 4,10,000. Closing Balance: 5,50,000.";
        String filler = ("Line item reviewed and reconciled by the accounts team. ").repeat(20);
        String unrelated = "Salary slip for January. Employee Sunita Rao. Net pay 43,500.";

        try (LocalEmbeddingClient emb = new LocalEmbeddingClient(cache);
             VectorStore vs = new VectorStore(tmp.resolve("idx"))) {

            String stmt = "/c/Sharma-Bank-Statement.pdf", fil = "/c/Filler.pdf", sal = "/c/Salary.pdf";
            for (var p : List.of(stmt, fil, sal)) {
                String text = p.equals(stmt) ? realistic : p.equals(fil) ? filler : unrelated;
                vs.upsert(List.of(chunk(p, text)), emb.embedBatch(List.of(text)));
            }

            float[] q = emb.embedBatch(List.of("what is the bank balance?")).get(0);
            List<SearchResult> hits = vs.query(q, 10);
            assertFalse(hits.isEmpty());
            assertEquals(stmt, hits.get(0).sourceFilePath(), "the realistic statement must be the top hit");

            // The filler chunk must rank below the real statement (the old corpus bug).
            int stmtRank = rankOf(hits, stmt), fillRank = rankOf(hits, fil);
            assertTrue(stmtRank < fillRank, "realistic content must outrank filler (stmt=" + stmtRank + ", filler=" + fillRank + ")");

            // Scope filter: restricting to the salary file must NOT return the statement.
            List<SearchResult> scoped = vs.query(q, 10, Set.of(sal));
            assertTrue(scoped.stream().noneMatch(r -> r.sourceFilePath().equals(stmt)),
                    "scope filter leaked an out-of-scope file");
        }
    }

    private static int rankOf(List<SearchResult> hits, String path) {
        for (int i = 0; i < hits.size(); i++) if (hits.get(i).sourceFilePath().equals(path)) return i;
        return Integer.MAX_VALUE;
    }
}

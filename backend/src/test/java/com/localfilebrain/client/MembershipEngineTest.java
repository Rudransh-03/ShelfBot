package com.localfilebrain.client;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.model.DocumentChunk;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.storage.VectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for content-based client tagging — real metadata store + real
 * vector index, no LLM. Proves the safety rule: a file is tagged to a client
 * only when EXACTLY one client's identifiers appear; shared/none stay
 * unassigned; manual pins are never overridden.
 */
class MembershipEngineTest {

    @TempDir Path tmp;
    private IndexMetadataStore meta;
    private VectorStore vs;

    @BeforeEach void setUp() {
        meta = new IndexMetadataStore(tmp.resolve("meta.db"));
        vs   = new VectorStore(tmp.resolve("idx"));
    }

    @AfterEach void tearDown() { if (vs != null) vs.close(); if (meta != null) meta.close(); }

    private void indexFile(String path, String text) {
        DocumentChunk chunk = DocumentChunk.builder()
                .chunkId(path + "::chunk-0").sourceFilePath(path)
                .fileName(Path.of(path).getFileName().toString()).fileExtension("pdf")
                .text(text).mimeType("application/pdf").chunkIndex(0).totalChunks(1).build();
        vs.upsert(List.of(chunk), List.of(new float[]{1f, 0f, 0f, 0f}));
        meta.upsert(FileRecord.builder()
                .absolutePath(path).fileName(Path.of(path).getFileName().toString())
                .fileExtension("pdf").fileSizeBytes(1).lastModifiedMs(0).contentHash("h")
                .status(FileRecord.Status.INDEXED).chunkCount(1).tokenCount(0)
                .lastIndexedAt(Instant.now()).build());
    }

    @Test
    void tagsSingleMatch_leavesConflictAndNoneUnassigned() {
        indexFile("/d/a.pdf",      "Monthly return for Sharma Bakery, GSTIN 29ABCDE1234F1Z5.");
        indexFile("/d/b.pdf",      "Invoice from Verma Textiles for the period.");
        indexFile("/d/shared.pdf", "Payment from Sharma Bakery to Verma Textiles.");   // both → conflict
        indexFile("/d/none.pdf",   "A generic note with no client in it at all.");

        String a = meta.createClient("Sharma Bakery");   // name auto-added as identifier
        meta.addClientIdentifier(a, "29ABCDE1234F1Z5");
        String b = meta.createClient("Verma Textiles");

        new MembershipEngine(meta, vs).recomputeAll();

        assertEquals(List.of("/d/a.pdf"), meta.pathsForClient(a));
        assertEquals(List.of("/d/b.pdf"), meta.pathsForClient(b));
        // shared (conflict) and none must be unassigned.
        assertFalse(meta.allAssignedPaths().contains("/d/shared.pdf"), "conflicted file must stay unassigned");
        assertFalse(meta.allAssignedPaths().contains("/d/none.pdf"),   "unmatched file must stay unassigned");
    }

    @Test
    void recomputeFileRetagsASingleFile_andRespectsPins() {
        indexFile("/d/a.pdf", "Statement for Globex Industries, period January.");
        String g = meta.createClient("Globex Industries");
        MembershipEngine eng = new MembershipEngine(meta, vs);

        eng.recomputeFile("/d/a.pdf");
        assertEquals(List.of("/d/a.pdf"), meta.pathsForClient(g), "single-file re-tag should assign the match");

        // Identifier no longer matches → single-file recompute clears the auto tag.
        meta.removeClientIdentifier(g, "Globex Industries");
        eng.recomputeFile("/d/a.pdf");
        assertTrue(meta.pathsForClient(g).isEmpty(), "single-file re-tag should clear a no-longer-matching auto tag");

        // A pinned file is never touched by single-file recompute.
        meta.assignFileToClient("/d/a.pdf", g, true);
        eng.recomputeFile("/d/a.pdf");
        assertEquals(List.of("/d/a.pdf"), meta.pathsForClient(g), "pinned file must survive single-file recompute");
    }

    @Test
    void manualPinIsNeverOverriddenByRecompute() {
        indexFile("/d/x.pdf", "A generic note with no client identifiers.");
        String a = meta.createClient("Sharma Bakery");

        meta.assignFileToClient("/d/x.pdf", a, true); // manual, pinned
        new MembershipEngine(meta, vs).recomputeAll(); // would otherwise unassign (no match)

        assertEquals(List.of("/d/x.pdf"), meta.pathsForClient(a), "pinned manual assignment must survive recompute");
    }

    @Test
    void shortAliasLetsUsersMatchByCommonName() {
        // Registering the full legal name must also match the short name a user
        // would actually type — the exact bug from the screenshots.
        String id = meta.createClient("Sharma Bakery Private Limited");
        var clients = meta.listClients();
        // The alias "sharma bakery" should be a registered identifier now.
        assertTrue(clients.get(0).norms().contains("sharma bakery"),
                "short alias should be auto-registered; got " + clients.get(0).norms());
        // And the resolver should scope (not clarify) when the user types the short name.
        var r = com.localfilebrain.client.ClientResolver.resolve(
                "What's Sharma Bakery's GST for January?", null, clients);
        assertEquals(com.localfilebrain.client.ClientResolver.Kind.SCOPED, r.kind());
        assertEquals(id, r.clientId());
    }

    @Test
    void reclassifiesWhenIdentifiersChange() {
        // "Globex Industries" has no legal-suffix, so no extra alias is added —
        // removing the name identifier leaves the client with nothing to match.
        indexFile("/d/a.pdf", "Document mentioning Globex Industries only.");
        String a = meta.createClient("Globex Industries");
        new MembershipEngine(meta, vs).recomputeAll();
        assertEquals(List.of("/d/a.pdf"), meta.pathsForClient(a));

        // Remove the matching identifier → on recompute the auto-tag is dropped.
        meta.removeClientIdentifier(a, "Globex Industries");
        new MembershipEngine(meta, vs).recomputeAll();
        assertTrue(meta.pathsForClient(a).isEmpty(), "auto-tag must clear when the identifier no longer matches");
    }
}

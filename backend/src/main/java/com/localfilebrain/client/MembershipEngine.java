package com.localfilebrain.client;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.Client;
import com.localfilebrain.storage.VectorStore;
import com.localfilebrain.storage.VectorStore.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Assigns indexed files to clients by scanning their already-indexed text
 * (local, no LLM) for registered client identifiers.
 *
 * Safety rule: a file is auto-assigned ONLY when EXACTLY ONE client's
 * identifiers appear in it. If two clients' identifiers both appear (a shared
 * or cross-referencing document) or none do, the file is left UNASSIGNED — we
 * never pick one, because a wrong assignment is the leak we're preventing.
 * Files the user pinned by hand are never touched.
 */
public final class MembershipEngine {

    private static final Logger log = LoggerFactory.getLogger(MembershipEngine.class);

    /** Enough leading text to reliably contain a GSTIN/PAN/name without scanning huge docs. */
    private static final int MAX_SCAN_CHARS = 200_000;

    private final IndexMetadataStore meta;
    private final VectorStore        vectorStore;

    public MembershipEngine(IndexMetadataStore meta, VectorStore vectorStore) {
        this.meta = meta;
        this.vectorStore = vectorStore;
    }

    public record Result(int assigned, int conflicted, int unmatched) {}

    /** Recomputes auto-membership for every indexed file. Pinned (manual)
     *  assignments are preserved. Idempotent — safe to run after indexing, after
     *  a scan, or whenever the client list/identifiers change. */
    public synchronized Result recomputeAll() {
        List<Client> clients = meta.listClients();
        if (clients.isEmpty()) return new Result(0, 0, 0);

        int assigned = 0, conflicted = 0, unmatched = 0;
        for (String path : meta.listIndexedPaths()) {
            if (meta.isFilePinned(path)) continue;            // manual wins, never override
            Set<String> hits = ClientMatcher.matchingClients(fileText(path), clients);
            if (hits.size() == 1) {
                meta.assignFileToClient(path, hits.iterator().next(), false);
                assigned++;
            } else {
                meta.unassignFile(path);                       // 0 or >1 → never auto-assign
                if (hits.isEmpty()) unmatched++; else conflicted++;
            }
        }
        log.info("Client membership recomputed: {} assigned, {} conflicted, {} unmatched (of {} files, {} clients)",
                assigned, conflicted, unmatched, assigned + conflicted + unmatched, clients.size());
        return new Result(assigned, conflicted, unmatched);
    }

    /** Re-tags a single file (cheap — used after a live watcher edit). Pinned
     *  files are left untouched; a file matching exactly one client is assigned,
     *  zero or multiple (or now-deleted) → unassigned. No-op when no clients. */
    public synchronized void recomputeFile(String path) {
        List<Client> clients = meta.listClients();
        if (clients.isEmpty() || meta.isFilePinned(path)) return;
        Set<String> hits = ClientMatcher.matchingClients(fileText(path), clients);
        if (hits.size() == 1) meta.assignFileToClient(path, hits.iterator().next(), false);
        else meta.unassignFile(path);
    }

    /** Concatenated leading text of a file from its indexed chunks. */
    private String fileText(String path) {
        StringBuilder sb = new StringBuilder();
        for (SearchResult c : vectorStore.getChunksForFile(path)) {
            if (c.text() != null) sb.append(c.text()).append('\n');
            if (sb.length() >= MAX_SCAN_CHARS) break;
        }
        return sb.toString();
    }
}

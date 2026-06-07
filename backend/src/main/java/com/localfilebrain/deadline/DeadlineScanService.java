package com.localfilebrain.deadline;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.NewDeadline;
import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.storage.VectorStore;
import com.localfilebrain.storage.VectorStore.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the cross-document deadline scan: for every indexed file not yet
 * scanned at its current content hash, it
 *   1. pulls the file's chunks (local, free),
 *   2. runs the {@link DeadlinePrefilter} to keep only date-bearing chunks,
 *   3. wraps each candidate with its neighbour chunks + a cheap document header
 *      (cached TL;DR or the doc's leading text) for context,
 *   4. packs several documents into one batch by a character budget, and
 *   5. sends each batch through {@link DeadlineExtractionEngine} (one LLM call)
 *      and stores the structured results.
 *
 * <p>Files with no date-bearing chunk cost no LLM call — they're stamped scanned
 * with zero items so they're never re-read. The scan is paced by a per-(UTC)-day
 * call budget and stops cleanly if the upstream daily quota is hit (HTTP 429),
 * resuming on the next pass once the day rolls over. Because each file is stamped
 * once handled, re-running always continues where it left off.
 */
public final class DeadlineScanService {

    private static final Logger log = LoggerFactory.getLogger(DeadlineScanService.class);

    /** Upper bound on input text per LLM call (~70K tokens, leaving room for output). */
    private static final int MAX_BATCH_CHARS      = 280_000;
    /** Cap on one document's snippet text so a single huge doc can't dominate a batch. */
    private static final int MAX_DOC_CHARS        = 100_000;
    /** Cap on candidate snippets per doc (after merging adjacent windows). */
    private static final int MAX_SNIPPETS_PER_DOC = 40;
    /** Trim length for the cheap document header. */
    private static final int HEADER_MAX           = 500;
    /** Output token ceiling for the extraction call (batched JSON can be sizeable). */
    private static final int EXTRACTION_MAX_TOKENS = 2000;

    private final IndexMetadataStore meta;
    private final VectorStore        vectorStore;

    public DeadlineScanService(IndexMetadataStore meta, VectorStore vectorStore) {
        this.meta        = meta;
        this.vectorStore = vectorStore;
    }

    /** Progress sink for the async API job. Called as files are handled. */
    public interface ProgressListener {
        void onProgress(int processed, int total, int found, String currentFile);
        ProgressListener NOOP = (p, t, f, c) -> {};
    }

    /** Why a scan stopped. */
    public enum Stop { COMPLETE, PAUSED_QUOTA, NOT_SIGNED_IN, ERROR }

    /** Outcome of a scan run. */
    public record ScanResult(int filesScanned, int deadlinesFound, int llmCallsUsed,
                             Stop stop, String message) {}

    /**
     * Runs a scan pass. {@code llm} is a ready client (the caller owns sign-in);
     * {@code dailyBudget} caps LLM calls this UTC day across runs.
     */
    public ScanResult scan(GPT4oMiniClient llm, int dailyBudget, ProgressListener listener) {
        if (listener == null) listener = ProgressListener.NOOP;
        LocalDate today  = LocalDate.now();
        String    dayKey = LocalDate.now(ZoneOffset.UTC).toString();

        // Build the work list: indexed files not yet scanned at their current hash.
        List<FileRecord> all = meta.listIndexedFilesBySizeDesc();
        List<FileRecord> pending = new ArrayList<>();
        for (FileRecord r : all) {
            if (r.getContentHash() == null) continue;
            if (!meta.isDeadlineScanned(r.getAbsolutePath(), r.getContentHash())) {
                pending.add(r);
            }
        }
        int total = pending.size();
        if (total == 0) {
            return new ScanResult(0, 0, 0, Stop.COMPLETE, "Everything is already scanned for deadlines.");
        }

        int callsToday = meta.getDeadlineCallsToday(dayKey);
        if (callsToday >= dailyBudget) {
            return new ScanResult(0, 0, 0, Stop.PAUSED_QUOTA,
                    "Daily deadline-scan budget reached. The rest will scan automatically tomorrow.");
        }

        int processed = 0, found = 0, callsUsed = 0;
        listener.onProgress(0, total, 0, null);

        List<DocEntry> batch = new ArrayList<>();
        int batchChars = 0;

        for (int i = 0; i <= pending.size(); i++) {
            // Build the payload for file i (skip the synthetic final iteration).
            DocEntry entry = null;
            if (i < pending.size()) {
                FileRecord r = pending.get(i);
                listener.onProgress(processed, total, found, r.getFileName());
                entry = buildEntry(r);
                if (entry == null) {
                    // No chunks / no date-bearing content → stamp scanned, 0 items, no LLM call.
                    meta.replaceDeadlinesForFile(r.getAbsolutePath(), r.getFileName(),
                            r.getContentHash(), List.of());
                    processed++;
                    listener.onProgress(processed, total, found, r.getFileName());
                    continue;
                }
            }

            boolean lastIteration = (i == pending.size());
            boolean wouldOverflow = entry != null && !batch.isEmpty()
                    && batchChars + entry.chars > MAX_BATCH_CHARS;

            // Flush the current batch when it's full or we've reached the end.
            if (!batch.isEmpty() && (wouldOverflow || lastIteration)) {
                if (callsToday + callsUsed >= dailyBudget) {
                    return new ScanResult(processed, found, callsUsed, Stop.PAUSED_QUOTA,
                            paused(processed, total));
                }
                FlushResult fr = flush(batch, today, llm, dayKey);
                if (fr.stop != null) {
                    // Quota / auth / fatal error mid-scan — stop, keep progress.
                    return new ScanResult(processed, found, callsUsed, fr.stop, fr.message);
                }
                processed += batch.size();
                found     += fr.found;
                callsUsed += 1;
                listener.onProgress(processed, total, found, null);
                batch = new ArrayList<>();
                batchChars = 0;
            }

            if (entry != null) {
                batch.add(entry);
                batchChars += entry.chars;
            }
        }

        return new ScanResult(processed, found, callsUsed, Stop.COMPLETE,
                "Scanned " + processed + " document(s); found " + found + " deadline(s).");
    }

    private record FlushResult(int found, Stop stop, String message) {}

    /** Sends one batch through the extraction engine and persists results. */
    private FlushResult flush(List<DocEntry> batch, LocalDate today,
                              GPT4oMiniClient llm, String dayKey) {
        // Assign batch-local ids and build payloads.
        List<DeadlineExtractionEngine.DocPayload> payloads = new ArrayList<>(batch.size());
        Map<Integer, DocEntry> byId = new LinkedHashMap<>();
        for (int k = 0; k < batch.size(); k++) {
            DocEntry e = batch.get(k);
            int id = k + 1;
            byId.put(id, e);
            payloads.add(new DeadlineExtractionEngine.DocPayload(id, e.fileName, e.header, e.snippets));
        }

        List<ExtractedDeadline> items;
        try {
            items = DeadlineExtractionEngine.extractBatch(payloads, today,
                    (sys, user) -> llm.oneShot(sys, user, EXTRACTION_MAX_TOKENS));
            meta.incrementDeadlineCallsToday(dayKey);
        } catch (GPT4oMiniClient.LLMException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("limit") || msg.contains("quota") || msg.contains("429")) {
                return new FlushResult(0, Stop.PAUSED_QUOTA,
                        "Daily AI limit reached — the remaining documents will scan automatically tomorrow.");
            }
            if (msg.contains("sign in") || msg.contains("signed in") || msg.contains("session")) {
                return new FlushResult(0, Stop.NOT_SIGNED_IN,
                        "Sign in to ShelfBot to scan documents for deadlines.");
            }
            // Unexpected LLM error — surface it and stop (don't mark these scanned,
            // so they retry on the next pass).
            log.warn("Deadline extraction call failed: {}", e.getMessage());
            return new FlushResult(0, Stop.ERROR, e.getMessage());
        }

        // Group results by their (batch-local) document id and persist per file.
        Map<Integer, List<ExtractedDeadline>> grouped = new LinkedHashMap<>();
        for (ExtractedDeadline it : items) {
            grouped.computeIfAbsent(it.docId(), k -> new ArrayList<>()).add(it);
        }

        int found = 0;
        for (Map.Entry<Integer, DocEntry> en : byId.entrySet()) {
            DocEntry doc = en.getValue();
            List<ExtractedDeadline> docItems = grouped.getOrDefault(en.getKey(), List.of());
            List<NewDeadline> toStore = new ArrayList<>(docItems.size());
            java.util.Set<String> seen = new java.util.HashSet<>(); // de-dupe within a file
            for (ExtractedDeadline it : docItems) {
                String key = (it.title() + "|" + it.dueDate()).toLowerCase();
                if (!seen.add(key)) continue;
                toStore.add(new NewDeadline(
                        it.title(), it.description(), it.dueDate(),
                        it.kind(), it.confidence(), it.recurring(), doc.sourceExcerpt));
            }
            // Always persist (even empty) — this stamps the file scanned so it's
            // never re-read until its content changes.
            meta.replaceDeadlinesForFile(doc.absolutePath, doc.fileName, doc.contentHash, toStore);
            found += toStore.size();
        }
        return new FlushResult(found, null, null);
    }

    /**
     * Builds the batch entry for one file, or null if the file has no chunks or
     * no date-bearing content (→ no LLM call needed).
     */
    private DocEntry buildEntry(FileRecord r) {
        String path = r.getAbsolutePath();
        List<SearchResult> chunks = vectorStore.getChunksForFile(path);
        if (chunks.isEmpty()) return null;

        // Candidate chunk indices (date-bearing), via the local prefilter.
        List<Integer> candidates = new ArrayList<>();
        for (int idx = 0; idx < chunks.size(); idx++) {
            String t = chunks.get(idx).text();
            if (DeadlinePrefilter.isCandidate(t)) candidates.add(idx);
        }
        if (candidates.isEmpty()) return null;

        // Expand each candidate to a ±1 neighbour window, then merge overlapping
        // windows into contiguous ranges so neighbouring candidates don't repeat
        // the same chunk text.
        boolean[] need = new boolean[chunks.size()];
        for (int c : candidates) {
            for (int j = Math.max(0, c - 1); j <= Math.min(chunks.size() - 1, c + 1); j++) {
                need[j] = true;
            }
        }
        List<String> snippets = new ArrayList<>();
        int docChars = 0;
        int start = -1;
        for (int idx = 0; idx <= chunks.size(); idx++) {
            boolean on = idx < chunks.size() && need[idx];
            if (on && start < 0) {
                start = idx;
            } else if (!on && start >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int j = start; j < idx; j++) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(chunks.get(j).text() == null ? "" : chunks.get(j).text());
                }
                String snip = sb.toString();
                snippets.add(snip);
                docChars += snip.length();
                start = -1;
                if (snippets.size() >= MAX_SNIPPETS_PER_DOC || docChars >= MAX_DOC_CHARS) break;
            }
        }
        if (snippets.isEmpty()) return null;

        String header     = buildHeader(path, r.getContentHash(), chunks);
        String firstSnip  = snippets.get(0);
        String excerpt    = firstSnip.length() <= 300 ? firstSnip : firstSnip.substring(0, 300) + "…";
        int totalChars    = docChars + (header == null ? 0 : header.length());

        return new DocEntry(path, r.getFileName(), r.getContentHash(), header, snippets, excerpt, totalChars);
    }

    /**
     * Cheapest available document identity: a cached TL;DR if one already exists
     * for this exact content hash (never generated on demand — that would cost an
     * LLM call), otherwise the document's leading chunk text. Filenames are
     * deliberately NOT used here (they're often noise like docScanner_21_12.pdf);
     * the engine receives the filename separately as a weak, ignorable hint.
     */
    private String buildHeader(String path, String contentHash, List<SearchResult> chunks) {
        Optional<IndexMetadataStore.CachedSummary> cached = meta.getSummary(path);
        if (cached.isPresent() && contentHash.equals(cached.get().contentHash())) {
            return trim(cached.get().summary(), HEADER_MAX);
        }
        return trim(chunks.get(0).text(), HEADER_MAX);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String paused(int processed, int total) {
        return "Paused at the daily limit (" + processed + " of " + total
             + " new document(s) scanned). The rest will continue automatically tomorrow.";
    }

    /** Internal per-file batch entry. */
    private record DocEntry(String absolutePath, String fileName, String contentHash,
                            String header, List<String> snippets, String sourceExcerpt, int chars) {}
}

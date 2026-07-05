package com.localfilebrain.extract;

import com.localfilebrain.storage.VectorStore;
import com.localfilebrain.storage.VectorStore.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Orchestrates a structured extraction over a set of already-indexed documents,
 * reusing the batching model of the deadline scan: gather each file's content
 * from the vector store, pack several documents per LLM call (bounded by a
 * character budget and a document count), send each batch through
 * {@link StructuredExtractionEngine} sequentially, and aggregate one row per
 * document.
 *
 * <p>Content comes from the existing index ({@link VectorStore#getChunksForFile})
 * — no re-extraction, no new indexing path. A bounded prefix of each document is
 * used (fields for the supported templates — invoice/contract/resume/bank
 * statement headers — sit near the top), which keeps batches within the model's
 * output budget. Files with no indexed content yield an all-MISSING row rather
 * than being silently skipped.
 */
public final class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    // Reuse the deadline scan's proven batch bounds.
    private static final int MAX_BATCH_CHARS = 280_000;
    private static final int MAX_BATCH_DOCS  = 10;
    /** Per-document content cap. Extraction reads a document prefix (header
     *  fields), not the whole file, so batches stay within the reply budget. */
    private static final int MAX_DOC_CHARS   = 8_000;
    /** Output-token ceiling for one extraction call. */
    public static final int EXTRACTION_MAX_TOKENS = 3_000;

    /** Resolves a file's bounded content snippets. Backed by the vector store in
     *  production; injectable so batching/aggregation is unit-testable without a
     *  live index. */
    private final Function<String, List<String>> contentFn;

    public ExtractionService(VectorStore vectorStore) {
        this.contentFn = path -> gatherSnippets(vectorStore, path);
    }

    /** Test seam: supply document content directly (path → snippets). */
    ExtractionService(Function<String, List<String>> contentFn) {
        this.contentFn = contentFn;
    }

    /** Progress sink for the async API job. */
    public interface ProgressListener {
        void onProgress(int processed, int total);
        ProgressListener NOOP = (p, t) -> {};
    }

    /** Outcome of a run. {@code truncatedBatches} counts batches the model
     *  replied to unreadably (their rows are all-MISSING, never fabricated);
     *  {@code cancelled} is true when the run stopped early on a cancel request
     *  (already-processed rows are returned). */
    public record Result(List<ExtractionRow> rows, int truncatedBatches, boolean cancelled) {
        public Result(List<ExtractionRow> rows, int truncatedBatches) { this(rows, truncatedBatches, false); }
    }

    /** Back-compat overload (no cancellation). */
    public Result run(List<FileRef> files, List<ExtractionField> schema,
                      ExtractionOptions options, StructuredExtractionEngine.LlmCall llm,
                      ProgressListener listener) {
        return run(files, schema, options, llm, listener, () -> false);
    }

    /**
     * Runs the extraction. {@code files} is an ordered list of (fileName,
     * absolutePath) for the selected, indexed documents. {@code cancelled} is
     * polled between batches so a long run can be stopped cleanly, returning the
     * rows completed so far.
     */
    public Result run(List<FileRef> files,
                      List<ExtractionField> schema,
                      ExtractionOptions options,
                      StructuredExtractionEngine.LlmCall llm,
                      ProgressListener listener,
                      BooleanSupplier cancelled) {
        if (listener == null) listener = ProgressListener.NOOP;
        if (cancelled == null) cancelled = () -> false;
        List<ExtractionRow> allRows = new ArrayList<>();
        int total = files == null ? 0 : files.size();
        if (total == 0) return new Result(List.of(), 0, false);

        int processed = 0, truncated = 0;
        List<StructuredExtractionEngine.DocPayload> batch = new ArrayList<>();
        Map<Integer, FileRef> batchFiles = new LinkedHashMap<>();
        int batchChars = 0;
        int nextId = 1;

        boolean wasCancelled = false;
        for (int i = 0; i < total; i++) {
            FileRef fr = files.get(i);
            List<String> snippets = safeSnippets(fr.absolutePath());
            int docChars = snippets.stream().mapToInt(String::length).sum();

            boolean wouldOverflow = !batch.isEmpty()
                    && (batchChars + docChars > MAX_BATCH_CHARS || batch.size() >= MAX_BATCH_DOCS);
            if (wouldOverflow) {
                // Honour a cancel request at the batch boundary (before spending
                // another paid LLM call), returning the rows completed so far.
                if (cancelled.getAsBoolean()) { wasCancelled = true; break; }
                truncated += flush(batch, batchFiles, schema, options, llm, allRows);
                processed += batch.size();
                listener.onProgress(processed, total);
                batch = new ArrayList<>();
                batchFiles = new LinkedHashMap<>();
                batchChars = 0;
            }

            int id = nextId++;
            batch.add(new StructuredExtractionEngine.DocPayload(id, fr.fileName(), snippets));
            batchFiles.put(id, fr);
            batchChars += docChars;

            if (i == total - 1 && !batch.isEmpty()) {
                if (cancelled.getAsBoolean()) { wasCancelled = true; break; }
                truncated += flush(batch, batchFiles, schema, options, llm, allRows);
                processed += batch.size();
                listener.onProgress(processed, total);
            }
        }

        log.info("Extraction {}: {} file(s) -> {} row(s), {} truncated batch(es)",
                wasCancelled ? "cancelled" : "complete", total, allRows.size(), truncated);
        return new Result(allRows, truncated, wasCancelled);
    }

    /** Content lookup that never throws — a per-file index error yields an
     *  empty document (→ all-MISSING row) rather than failing the whole run. */
    private List<String> safeSnippets(String absolutePath) {
        try {
            List<String> s = contentFn.apply(absolutePath);
            return s == null ? List.of() : s;
        } catch (Exception e) {
            log.warn("Content lookup failed for '{}': {}", absolutePath, e.getMessage());
            return List.of();
        }
    }

    /** Runs one batch and appends its rows (re-attaching each file's absolute
     *  path). Returns 1 when the batch was unreadable, else 0. */
    private int flush(List<StructuredExtractionEngine.DocPayload> batch,
                      Map<Integer, FileRef> batchFiles,
                      List<ExtractionField> schema,
                      ExtractionOptions options,
                      StructuredExtractionEngine.LlmCall llm,
                      List<ExtractionRow> out) {
        StructuredExtractionEngine.BatchResult res =
                StructuredExtractionEngine.extractBatch(batch, schema, options, llm);

        if (res.unreadable()) {
            // Never fabricate: emit an all-MISSING row per doc so the user sees
            // the file was attempted but nothing could be read.
            for (StructuredExtractionEngine.DocPayload d : batch) {
                out.add(missingRow(d.docId(), batchFiles.get(d.docId()), schema));
            }
            return 1;
        }

        Map<Integer, ExtractionRow> byId = new LinkedHashMap<>();
        for (ExtractionRow r : res.rows()) byId.put(r.docId(), r);

        // Preserve input order and attach absolute paths; a doc the model omitted
        // gets an all-MISSING row so every selected file appears exactly once.
        for (StructuredExtractionEngine.DocPayload d : batch) {
            FileRef fr = batchFiles.get(d.docId());
            ExtractionRow r = byId.get(d.docId());
            if (r == null) {
                out.add(missingRow(d.docId(), fr, schema));
            } else {
                out.add(new ExtractionRow(d.docId(), fr.fileName(), fr.absolutePath(), r.fields()));
            }
        }
        return 0;
    }

    private static ExtractionRow missingRow(int docId, FileRef fr, List<ExtractionField> schema) {
        Map<String, ExtractedValue> fields = new LinkedHashMap<>();
        for (ExtractionField f : schema) fields.put(f.name(), ExtractedValue.missing());
        return new ExtractionRow(docId, fr == null ? "" : fr.fileName(),
                fr == null ? null : fr.absolutePath(), fields);
    }

    /** Concatenates a document's leading indexed chunks up to the per-doc cap. */
    private static List<String> gatherSnippets(VectorStore vectorStore, String absolutePath) {
        List<SearchResult> chunks = vectorStore.getChunksForFile(absolutePath);
        List<String> snippets = new ArrayList<>();
        int used = 0;
        for (SearchResult c : chunks) {
            String t = c.text();
            if (t == null || t.isBlank()) continue;
            if (used >= MAX_DOC_CHARS) break;
            int room = MAX_DOC_CHARS - used;
            if (t.length() > room) t = t.substring(0, room);
            snippets.add(t);
            used += t.length();
        }
        return snippets;
    }

    /** A selected document: display name + path. */
    public record FileRef(String fileName, String absolutePath) {}
}

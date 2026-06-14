package com.localfilebrain.ingestion;

import com.localfilebrain.auth.AuthTokenStore;
import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.EmbeddingClient;
import com.localfilebrain.embedding.EmbeddingClientFactory;
import com.localfilebrain.model.DocumentChunk;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.model.IngestionResult;
import com.localfilebrain.storage.VectorStore;
import com.localfilebrain.util.FileHashUtil;
import com.localfilebrain.util.PathNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full ingestion pipeline: Scan → Extract → Chunk → Embed → Store.
 *
 * Per file:
 *   1. TextExtractor          → plain text
 *   2. TextChunker            → List&lt;DocumentChunk&gt;
 *   3. VectorStore.deleteBySourceFile → drop old chunks (handles file shrinking)
 *   4. OpenAIEmbeddingClient  → List&lt;float[]&gt;
 *   5. VectorStore.upsert     → store text + vector + metadata in Lucene HNSW
 *   6. IndexMetadataStore.upsert → mark file as INDEXED
 */
public final class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    // ── Indexing size caps ─────────────────────────────────────────────────────
    // These were originally OpenAI-cost guards. Now that embeddings run locally
    // via BGE (zero per-token cost), the caps mainly bound on-disk index size
    // and indexing time. We've kept the per-file cap small so a single 5,000-
    // page PDF can't dominate, but raised the per-user total significantly
    // because there's no longer a $ ceiling to defend.

    /** Max estimated tokens any single file may contribute. ≈ 1,000 pages. */
    public static final long MAX_TOKENS_PER_FILE = 500_000L;

    /**
     * Max estimated tokens an entire user library may contribute.
     * ≈ 400,000 pages. With local embeddings this is purely a sanity check
     * to prevent runaway indexing into the wrong folder (e.g. accidentally
     * pointing the app at the system root). Disk usage at the cap is on
     * the order of ~5 GB of Lucene segments + chunk text.
     */
    public static final long MAX_TOKENS_TOTAL    = 200_000_000L;

    /** Chars-per-token used for the rough estimate. */
    private static final int CHARS_PER_TOKEN = 4;

    /** Chunks embedded per locked sub-batch — small enough to report smooth
     *  "embedded X / N" progress, large enough to keep lock churn negligible. */
    private static final int EMBED_PROGRESS_BATCH = 16;

    /** Serialises embedding across worker threads. The ONNX model already uses
     *  every core for one call, so concurrent embeds would only contend. */
    private final Object embedLock = new Object();

    // Per-file locks (striped), STATIC so they're shared across every pipeline
    // instance in this JVM — the manual index job, the live FileWatcher, and the
    // startup rescan. Without this, two of them could index the same file at once
    // and interleave the vector-store delete+add, leaving duplicate chunks. The
    // lock serialises per-file work so the second thread waits, then skips after
    // re-checking that the file is already up-to-date. Striped (not one lock per
    // path) to bound memory; same path always maps to the same stripe.
    private static final int LOCK_STRIPES = 64;
    private static final Object[] FILE_LOCKS = new Object[LOCK_STRIPES];
    static { for (int i = 0; i < LOCK_STRIPES; i++) FILE_LOCKS[i] = new Object(); }
    private static Object fileLock(String canonicalPath) {
        return FILE_LOCKS[Math.floorMod(canonicalPath.hashCode(), LOCK_STRIPES)];
    }

    private final AppConfig          config;
    private final IndexMetadataStore metadataStore;
    private final FileScanner        fileScanner;
    private final TextExtractor      textExtractor;
    private final TextChunker        textChunker;
    private final EmbeddingClient    embeddingClient;
    private final VectorStore        vectorStore;
    private final boolean            ownsVectorStore;
    private final boolean            ownsEmbeddingClient;

    /** Optional hook invoked with a file's canonical path right after it is
     *  (re)indexed or removed via {@link #indexOne}/{@link #removeFile} — used to
     *  refresh that file's client membership on live watcher edits. Kept as a
     *  plain Consumer so this package stays decoupled from the client package. */
    private volatile java.util.function.Consumer<String> postIndexHook;

    public void setPostIndexHook(java.util.function.Consumer<String> hook) { this.postIndexHook = hook; }

    private void notifyIndexed(String canonicalPath) {
        var hook = postIndexHook;
        if (hook != null) {
            try { hook.accept(canonicalPath); }
            catch (Exception e) { log.warn("post-index hook failed for '{}': {}", canonicalPath, e.getMessage()); }
        }
    }

    public IngestionPipeline(AppConfig config, IndexMetadataStore metadataStore) {
        this(config, metadataStore, null, null);
    }

    public IngestionPipeline(AppConfig config, IndexMetadataStore metadataStore, VectorStore sharedStore) {
        this(config, metadataStore, sharedStore, null);
    }

    /**
     * Lets callers (Main / ApiServer) inject a shared {@link VectorStore} and
     * {@link EmbeddingClient} so the live FileWatcher, the manual indexing
     * job, and the query path all use the same instances — Lucene allows one
     * writer per directory, and the local ONNX embedding session is
     * expensive enough that we never want two.
     */
    public IngestionPipeline(AppConfig config,
                             IndexMetadataStore metadataStore,
                             VectorStore sharedStore,
                             EmbeddingClient sharedEmbedding) {
        this.config        = config;
        this.metadataStore = metadataStore;
        this.fileScanner   = new FileScanner(config, metadataStore);
        this.textExtractor = new TextExtractor();
        this.textChunker   = new TextChunker(config);

        if (sharedEmbedding != null) {
            this.embeddingClient     = sharedEmbedding;
            this.ownsEmbeddingClient = false;
        } else {
            // No shared embedding — fall back to a freshly built one.
            // Pass a transient (empty) token store; the caller really should
            // hand us a shared embedding client in proxy mode so the active
            // JWT is visible.
            this.embeddingClient     = EmbeddingClientFactory.create(config, new AuthTokenStore());
            this.ownsEmbeddingClient = true;
        }

        if (sharedStore != null) {
            this.vectorStore     = sharedStore;
            this.ownsVectorStore = false;
        } else {
            this.vectorStore     = new VectorStore(config.getVectorIndexPath());
            this.ownsVectorStore = true;
        }
    }

    /**
     * Releases the underlying {@link VectorStore} only if this pipeline
     * created it (i.e. wasn't passed a shared one). Safe to call multiple
     * times.
     */
    public void close() {
        if (ownsVectorStore)     vectorStore.close();
        if (ownsEmbeddingClient) embeddingClient.close();
    }

    public IngestionResult run() {
        return run(ProgressListener.NOOP);
    }

    /**
     * Same as {@link #run()} but emits per-file progress events. The listener
     * is called once before the loop starts (with processed=0) and once after
     * each file completes — success, failure, or budget skip — so callers can
     * drive a "X of N · current: foo.pdf" UI without polling internal state.
     */
    public IngestionResult run(ProgressListener listener) {
        if (listener == null) listener = ProgressListener.NOOP;

        long startMs = System.currentTimeMillis();
        log.info("═══ Ingestion Pipeline ═══");
        log.info("Roots:");
        for (Path root : config.getFilesRootPaths()) {
            log.info("  • {}", root.toAbsolutePath());
        }

        // VectorStore is initialised on construction; no separate ensure step.
        FileScanner.ScanResult scan = fileScanner.scan();
        int totalScanned  = scan.totalScanned() + scan.skippedUnsupported().size();
        int skippedCount  = scan.skippedUnchanged().size();
        List<Path> toProcess = scan.filesToProcess();

        if (toProcess.isEmpty()) {
            log.info("All files are up-to-date. Nothing to process.");
            return IngestionResult.builder()
                    .totalFilesScanned(totalScanned)
                    .filesSkipped(skippedCount)
                    .filesProcessed(0)
                    .filesFailed(scan.errors().size())
                    .totalChunksCreated(0)
                    .durationMs(System.currentTimeMillis() - startMs)
                    .failedFilePaths(scan.errors().stream().map(Path::toString).toList())
                    .build();
        }

        log.info("Files to process: {} | Already up-to-date: {}", toProcess.size(), skippedCount);

        // Parallelise across files: extraction + OCR + chunking run fully in
        // parallel per worker, embedding is serialised through embedLock, and
        // Lucene + SQLite writes are already synchronized internally. The pool
        // is sized to ~half the cores so the machine stays usable while
        // indexing, and never spawns more workers than there are files.
        int cores   = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(1, Math.min(toProcess.size(), Math.max(2, cores / 2)));
        log.info("Indexing {} files across {} worker thread(s) ({} cores)",
                toProcess.size(), threads, cores);

        AtomicInteger processed      = new AtomicInteger(0);
        AtomicInteger failed         = new AtomicInteger(scan.errors().size());
        AtomicInteger totalChunksCtr = new AtomicInteger(0);
        List<String>  failedPaths    = Collections.synchronizedList(new ArrayList<>(
                scan.errors().stream().map(Path::toString).toList()));
        final int total = toProcess.size();

        // Starting tick so the UI flips from "indexing…" to a real bar at once.
        listener.onProgress(0, total, failed.get(), null);

        final ProgressListener lst = listener;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "shelfbot-index-worker");
            t.setDaemon(true);
            return t;
        });

        List<Future<?>> futures = new ArrayList<>(total);
        for (Path file : toProcess) {
            futures.add(pool.submit(() -> {
                String fileId   = file.toString();
                String fileName = file.getFileName().toString();
                lst.onFileStart(fileId, fileName);
                try {
                    FileOutcome outcome = indexFileGuarded(file,
                            (stage, done, tot) -> lst.onFileStage(fileId, stage, done, tot));

                    switch (outcome.state()) {
                        case INDEXED -> {
                            processed.incrementAndGet();
                            totalChunksCtr.addAndGet(outcome.chunkCount());
                            log.info("  ✓ {} chunks ({} tokens) embedded for '{}'",
                                    outcome.chunkCount(), outcome.tokenCount(), fileName);
                        }
                        case SKIPPED ->
                            // Another path (watcher/startup) indexed this exact file
                            // first; not a failure, just nothing to do.
                            log.info("  ↦ '{}' already indexed concurrently — skipped", fileName);
                        case EMPTY -> {
                            log.warn("  ✗ No text extracted from '{}'. Skipping.", fileName);
                            recordFailed(file, "No text could be extracted");
                            failed.incrementAndGet();
                            failedPaths.add(fileId);
                        }
                    }
                } catch (BudgetExceededException e) {
                    log.warn("  ⚠ Skipping '{}': {}", fileName, e.getMessage());
                    recordFailed(file, e.getMessage());
                    failed.incrementAndGet();
                    failedPaths.add(fileId);
                } catch (Exception e) {
                    log.error("  ✗ Failed '{}': {}", fileName, e.getMessage(), e);
                    recordFailed(file, e.getMessage());
                    failed.incrementAndGet();
                    failedPaths.add(fileId);
                } finally {
                    lst.onFileEnd(fileId);
                    lst.onProgress(processed.get(), total, failed.get(), null);
                }
            }));
        }
        pool.shutdown();
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { log.warn("indexing worker error: {}", e.getMessage()); }
        }

        int processedCount = processed.get();
        int failedCount    = failed.get();
        int totalChunks    = totalChunksCtr.get();

        // Final tick at 100% so the UI can show the completed state cleanly
        // before the result card replaces the progress bar.
        listener.onProgress(processedCount, toProcess.size(), failedCount, null);

        long duration = System.currentTimeMillis() - startMs;

        IngestionResult result = IngestionResult.builder()
                .totalFilesScanned(totalScanned)
                .filesSkipped(skippedCount)
                .filesProcessed(processedCount)
                .filesFailed(failedCount)
                .totalChunksCreated(totalChunks)
                .durationMs(duration)
                .failedFilePaths(failedPaths)
                .build();

        log.info("\n{}", result.toSummary());
        if (!failedPaths.isEmpty()) failedPaths.forEach(p -> log.warn("  Failed: {}", p));

        return result;
    }

    // -------------------------------------------------------------------------
    // Single-file operations — used by the live FileWatcher to react to
    // individual filesystem events without going through a full scan.
    // -------------------------------------------------------------------------

    /**
     * Indexes one file end-to-end (extract → chunk → embed → upsert →
     * metadata update). Returns the number of chunks stored, or 0 if the
     * file was empty / unsupported.
     *
     * If the file is already up-to-date (timestamp + hash match the
     * metadata store), no work is performed and 0 is returned — this makes
     * the watcher idempotent against duplicate events.
     */
    public int indexOne(Path file) {
        try {
            FileOutcome outcome = indexFileGuarded(file, FileProgress.NOOP);
            switch (outcome.state()) {
                case INDEXED -> {
                    log.info("[watcher] re-indexed '{}' ({} chunks, {} tokens)",
                            file.getFileName(), outcome.chunkCount(), outcome.tokenCount());
                    notifyIndexed(PathNormalizer.canonical(file)); // refresh client tag
                    return outcome.chunkCount();
                }
                case EMPTY -> {
                    recordFailed(file, "No text could be extracted");
                    return 0;
                }
                default -> { return 0; } // SKIPPED — already up-to-date
            }
        } catch (BudgetExceededException e) {
            log.warn("[watcher] skipped '{}': {}", file.getFileName(), e.getMessage());
            recordFailed(file, e.getMessage());
            return 0;
        } catch (Exception e) {
            log.warn("[watcher] failed to index '{}': {}", file, e.getMessage());
            recordFailed(file, e.getMessage());
            return 0;
        }
    }

    /**
     * Removes a file from both ChromaDB and the metadata store. Safe to
     * call for files that were never indexed — both stores are no-ops in
     * that case.
     */
    public void removeFile(Path file) {
        String absolutePath = PathNormalizer.canonical(file);
        try {
            vectorStore.deleteBySourceFile(absolutePath);
            metadataStore.delete(absolutePath);
            notifyIndexed(absolutePath); // clear any client tag for the removed file
            log.info("[watcher] removed '{}' from index", file.getFileName());
        } catch (Exception e) {
            log.warn("[watcher] failed to remove '{}': {}", file, e.getMessage());
        }
    }

    /**
     * Indexes a single file under its per-file lock, so the manual job, the live
     * watcher, and the startup rescan can never process the same file at the same
     * time. Re-checks "already up-to-date" INSIDE the lock (another thread may
     * have just finished it while we waited) and skips if so — preventing both
     * duplicate chunks and redundant re-embedding of identical content.
     */
    private FileOutcome indexFileGuarded(Path file, FileProgress fp) throws Exception {
        String absolutePath = PathNormalizer.canonical(file);
        synchronized (fileLock(absolutePath)) {
            // Re-check under the lock: a concurrent path may have just brought this
            // exact file up-to-date — if so, do nothing. We keep the attrs + hash
            // computed here and hand them to recordSuccess, so a successful index
            // doesn't read the whole file a SECOND time just to re-hash it.
            BasicFileAttributes attrs = null;
            String hash = null;
            try {
                attrs = Files.readAttributes(file, BasicFileAttributes.class);
                long lastModified = attrs.lastModifiedTime().toMillis();
                if (metadataStore.isUpToDateByTimestamp(absolutePath, lastModified)) {
                    return FileOutcome.SKIPPED;
                }
                hash = FileHashUtil.sha256(file);
                if (metadataStore.isUpToDateByHash(absolutePath, hash)) {
                    metadataStore.updateTimestamp(absolutePath, lastModified);
                    return FileOutcome.SKIPPED;
                }
            } catch (IOException stat) {
                // Couldn't stat/hash (e.g. file vanished) — drop what we have and
                // let processFile surface the real error instead of masking it as
                // "skipped"; recordSuccess recomputes attrs/hash if they're null.
                attrs = null;
                hash  = null;
            }

            ProcessResult result = processFile(file, fp);
            if (result == null || result.chunkCount == 0) {
                return FileOutcome.EMPTY;
            }
            // Commit metadata INSIDE the lock so any thread waiting on this same
            // file then sees it up-to-date and skips.
            recordSuccess(file, attrs, hash, result.chunkCount, result.tokenCount);
            return FileOutcome.indexed(result.chunkCount, result.tokenCount);
        }
    }

    private ProcessResult processFile(Path file) throws Exception {
        return processFile(file, FileProgress.NOOP);
    }

    private ProcessResult processFile(Path file, FileProgress fp) throws Exception {
        fp.stage("extracting", 0, 0);
        TextExtractor.ExtractionResult extraction = textExtractor.extract(file);
        if (extraction.isEmpty()) return null;

        String absolutePath = PathNormalizer.canonical(file);

        // Cheap budget check from the raw extracted text length — done BEFORE
        // any work (chunking, OpenAI API call) so we never burn money on a
        // file we're going to refuse anyway.
        long estimatedTokens = Math.max(1L, extraction.text().length() / CHARS_PER_TOKEN);
        enforceBudget(absolutePath, estimatedTokens);

        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        long lastModifiedMs = attrs.lastModifiedTime().toMillis();

        fp.stage("chunking", 0, 0);
        // Page locator is best-effort and PDF-only: it reads per-page PDF text to
        // tag each chunk with its page(s). It never touches the canonical text
        // above, so chunk text / embeddings / retrieval are unchanged; it's null
        // for non-PDFs and scanned PDFs, in which case chunks carry no page.
        PageLocator pageLocator = PdfPageLocator.forFile(file);
        List<DocumentChunk> chunks = textChunker.chunk(
                extraction.text(), file, extraction.mimeType(), lastModifiedMs, pageLocator);
        if (chunks.isEmpty()) return null;

        List<String>  texts      = chunks.stream().map(DocumentChunk::getText).toList();
        List<float[]> embeddings = embedWithProgress(texts, fp);

        fp.stage("saving", texts.size(), texts.size());
        // Atomic delete-old + add-new so a concurrent re-index of the same file
        // can't interleave and leave duplicate chunks.
        vectorStore.replaceBySourceFile(absolutePath, chunks, embeddings);

        // Use the actual chunked text length for storage — slightly more
        // accurate than the pre-chunk estimate (the chunker may trim
        // whitespace, add overlap, etc.).
        long actualChars = chunks.stream().mapToLong(c -> c.getText() == null ? 0 : c.getText().length()).sum();
        long actualTokens = Math.max(1L, actualChars / CHARS_PER_TOKEN);

        return new ProcessResult(chunks.size(), actualTokens);
    }

    /**
     * Embeds the chunk texts in small sub-batches behind {@link #embedLock},
     * reporting "embedded X / N" progress after each batch. Serialising here
     * (rather than per-thread embedders) keeps one model in memory and avoids
     * cores contending, since one embed call already saturates the CPU.
     */
    private List<float[]> embedWithProgress(List<String> texts, FileProgress fp) {
        int total = texts.size();
        fp.stage("embedding", 0, total);
        List<float[]> out = new ArrayList<>(total);
        for (int i = 0; i < total; i += EMBED_PROGRESS_BATCH) {
            List<String> slice = texts.subList(i, Math.min(total, i + EMBED_PROGRESS_BATCH));
            List<float[]> part;
            synchronized (embedLock) {
                part = embeddingClient.embedBatch(slice);
            }
            out.addAll(part);
            fp.stage("embedding", out.size(), total);
        }
        return out;
    }

    /**
     * Throws {@link BudgetExceededException} if indexing {@code file} would
     * blow past either the per-file or the per-user token cap. Uses the
     * stored token count for this file (if any) so that re-indexing only
     * charges the *delta* against the user's budget.
     */
    private void enforceBudget(String absolutePath, long estimatedTokens) {
        if (estimatedTokens > MAX_TOKENS_PER_FILE) {
            throw new BudgetExceededException(String.format(
                    "File too large to index (~%s tokens; max %s per file). "
                  + "Try splitting the document.",
                    formatTokens(estimatedTokens), formatTokens(MAX_TOKENS_PER_FILE)));
        }

        long existing  = metadataStore.getTokenCountForFile(absolutePath);
        long total     = metadataStore.sumIndexedTokens();
        long projected = total - existing + estimatedTokens;

        if (projected > MAX_TOKENS_TOTAL) {
            throw new BudgetExceededException(String.format(
                    "Indexing limit reached (%s of %s tokens used). "
                  + "Delete some files in the Library to free up budget.",
                    formatTokens(total), formatTokens(MAX_TOKENS_TOTAL)));
        }
    }

    private static String formatTokens(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.0fK", n / 1_000.0);
        return Long.toString(n);
    }

    private record ProcessResult(int chunkCount, long tokenCount) {}

    /** Outcome of a guarded single-file index attempt. */
    private enum IndexState { INDEXED, SKIPPED, EMPTY }
    private record FileOutcome(IndexState state, int chunkCount, long tokenCount) {
        static final FileOutcome SKIPPED = new FileOutcome(IndexState.SKIPPED, 0, 0);
        static final FileOutcome EMPTY   = new FileOutcome(IndexState.EMPTY,   0, 0);
        static FileOutcome indexed(int chunkCount, long tokenCount) {
            return new FileOutcome(IndexState.INDEXED, chunkCount, tokenCount);
        }
    }

    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) { super(message); }
    }

    /**
     * Receives progress events during a full {@link #run(ProgressListener)}.
     * Indexing is multi-threaded, so the per-file callbacks fire from several
     * worker threads at once — implementations MUST be thread-safe and cheap
     * (a ConcurrentHashMap + AtomicReference is the expected pattern).
     *
     * {@code onProgress} is the overall "X of N files" tick; the {@code onFile*}
     * callbacks drive the live per-file status panel. {@code fileId} is the
     * file's absolute path and is stable across that file's events.
     */
    public interface ProgressListener {
        void onProgress(int processed, int total, int failed, String currentFile);
        /** A worker picked up this file. */
        default void onFileStart(String fileId, String fileName) {}
        /** Stage update for an in-flight file: extracting | chunking | embedding | saving. */
        default void onFileStage(String fileId, String stage, int done, int total) {}
        /** This file finished (success or failure) and should leave the panel. */
        default void onFileEnd(String fileId) {}
        ProgressListener NOOP = (p, t, f, c) -> {};
    }

    /** Per-file stage callback handed to {@link #processFile} so it can report
     *  "extracting / chunking / embedding X of N / saving" as it goes. */
    @FunctionalInterface
    public interface FileProgress {
        void stage(String stage, int done, int total);
        FileProgress NOOP = (s, d, t) -> {};
    }

    /**
     * Persists the INDEXED metadata row. {@code attrs} and {@code hash} are the
     * values {@link #indexFileGuarded} already computed for its up-to-date check —
     * reused here so a successful index doesn't read the whole file a second time
     * just to re-hash it. Either may be null (the earlier stat/hash failed); in
     * that case we compute it now, preserving the previous behaviour.
     */
    private void recordSuccess(Path file, BasicFileAttributes attrs, String hash,
                               int chunkCount, long tokenCount) {
        try {
            if (attrs == null) attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (hash  == null) hash  = FileHashUtil.sha256(file);
            metadataStore.upsert(FileRecord.builder()
                    .absolutePath(PathNormalizer.canonical(file))
                    .fileName(file.getFileName().toString())
                    .fileExtension(FileScanner.getExtension(file.getFileName().toString()))
                    .fileSizeBytes(attrs.size())
                    .lastModifiedMs(attrs.lastModifiedTime().toMillis())
                    .contentHash(hash)
                    .status(FileRecord.Status.INDEXED)
                    .chunkCount(chunkCount)
                    .tokenCount(tokenCount)
                    .lastIndexedAt(Instant.now())
                    .build());
        } catch (IOException e) {
            log.warn("Could not record success for '{}': {}", file.getFileName(), e.getMessage());
        }
    }

    private void recordFailed(Path file, String errorMessage) {
        String absolutePath = PathNormalizer.canonical(file);
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            String hash = FileHashUtil.sha256(file);
            metadataStore.upsert(FileRecord.builder()
                    .absolutePath(absolutePath)
                    .fileName(file.getFileName().toString())
                    .fileExtension(FileScanner.getExtension(file.getFileName().toString()))
                    .fileSizeBytes(attrs.size())
                    .lastModifiedMs(attrs.lastModifiedTime().toMillis())
                    .contentHash(hash)
                    .status(FileRecord.Status.FAILED)
                    .chunkCount(0)
                    .lastIndexedAt(Instant.now())
                    .errorMessage(errorMessage)
                    .build());
        } catch (IOException e) {
            metadataStore.markFailed(absolutePath, errorMessage);
        }
    }

    public static class IngestionException extends RuntimeException {
        public IngestionException(String message, Throwable cause) { super(message, cause); }
    }
}

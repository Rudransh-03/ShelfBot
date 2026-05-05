package com.localfilebrain.ingestion;

import com.localfilebrain.config.AppConfig;
import com.localfilebrain.embedding.OpenAIEmbeddingClient;
import com.localfilebrain.model.DocumentChunk;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.model.IngestionResult;
import com.localfilebrain.storage.ChromaDBClient;
import com.localfilebrain.util.FileHashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Full ingestion pipeline: Scan → Extract → Chunk → Embed → Store.
 *
 * Per file:
 *   1. TextExtractor   → plain text
 *   2. TextChunker     → List<DocumentChunk>
 *   3. Delete old chunks from ChromaDB (handles file shrinking)
 *   4. OpenAIEmbeddingClient → List<float[]>
 *   5. ChromaDBClient.upsert → store text + vector + metadata
 *   6. IndexMetadataStore.upsert → mark file as INDEXED
 */
public final class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

    private final AppConfig             config;
    private final IndexMetadataStore    metadataStore;
    private final FileScanner           fileScanner;
    private final TextExtractor         textExtractor;
    private final TextChunker           textChunker;
    private final OpenAIEmbeddingClient embeddingClient;
    private final ChromaDBClient        chromaClient;

    public IngestionPipeline(AppConfig config, IndexMetadataStore metadataStore) {
        this.config          = config;
        this.metadataStore   = metadataStore;
        this.fileScanner     = new FileScanner(config, metadataStore);
        this.textExtractor   = new TextExtractor();
        this.textChunker     = new TextChunker(config);
        this.embeddingClient = new OpenAIEmbeddingClient(
                config.getOpenAiApiKey(),
                config.getEmbeddingBatchSize()
        );
        this.chromaClient = new ChromaDBClient(
                config.getChromaDbUrl(),
                config.getChromaDbCollection()
        );
    }

    public IngestionResult run() {
        long startMs = System.currentTimeMillis();
        log.info("═══ Ingestion Pipeline ═══");
        log.info("Root: {}", config.getFilesRootPath().toAbsolutePath());

        chromaClient.ensureCollection();

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

        int processedCount = 0;
        int failedCount    = scan.errors().size();
        int totalChunks    = 0;
        List<String> failedPaths = new ArrayList<>(
                scan.errors().stream().map(Path::toString).toList()
        );

        for (int i = 0; i < toProcess.size(); i++) {
            Path file = toProcess.get(i);
            log.info("[{}/{}] Processing: {}", i + 1, toProcess.size(), file.getFileName());

            try {
                int chunkCount = processFile(file);

                if (chunkCount == 0) {
                    log.warn("  ✗ No text extracted from '{}'. Skipping.", file.getFileName());
                    recordFailed(file, "No text could be extracted");
                    failedCount++;
                    failedPaths.add(file.toString());
                    continue;
                }

                recordSuccess(file, chunkCount);
                processedCount++;
                totalChunks += chunkCount;
                log.info("  ✓ {} chunks embedded and stored for '{}'", chunkCount, file.getFileName());

            } catch (Exception e) {
                log.error("  ✗ Failed '{}': {}", file.getFileName(), e.getMessage(), e);
                recordFailed(file, e.getMessage());
                failedCount++;
                failedPaths.add(file.toString());
            }
        }

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

    private int processFile(Path file) throws Exception {
        TextExtractor.ExtractionResult extraction = textExtractor.extract(file);
        if (extraction.isEmpty()) return 0;

        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        long lastModifiedMs = attrs.lastModifiedTime().toMillis();

        List<DocumentChunk> chunks = textChunker.chunk(
                extraction.text(), file, extraction.mimeType(), lastModifiedMs);
        if (chunks.isEmpty()) return 0;

        chromaClient.deleteBySourceFile(file.toAbsolutePath().toString());

        List<String>  texts      = chunks.stream().map(DocumentChunk::getText).toList();
        List<float[]> embeddings = embeddingClient.embedBatch(texts);

        chromaClient.upsert(chunks, embeddings);

        return chunks.size();
    }

    private void recordSuccess(Path file, int chunkCount) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            String hash = FileHashUtil.sha256(file);
            metadataStore.upsert(FileRecord.builder()
                    .absolutePath(file.toAbsolutePath().toString())
                    .fileName(file.getFileName().toString())
                    .fileExtension(FileScanner.getExtension(file.getFileName().toString()))
                    .fileSizeBytes(attrs.size())
                    .lastModifiedMs(attrs.lastModifiedTime().toMillis())
                    .contentHash(hash)
                    .status(FileRecord.Status.INDEXED)
                    .chunkCount(chunkCount)
                    .lastIndexedAt(Instant.now())
                    .build());
        } catch (IOException e) {
            log.warn("Could not record success for '{}': {}", file.getFileName(), e.getMessage());
        }
    }

    private void recordFailed(Path file, String errorMessage) {
        String absolutePath = file.toAbsolutePath().toString();
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

package com.localfilebrain.model;

import java.util.List;

/**
 * Summary result returned by the IngestionPipeline after a run completes.
 */
public final class IngestionResult {

    private final int totalFilesScanned;
    private final int filesSkipped;       // already up-to-date
    private final int filesProcessed;     // newly indexed this run
    private final int filesFailed;        // encountered errors
    private final int totalChunksCreated;
    private final long durationMs;
    private final List<String> failedFilePaths;

    private IngestionResult(Builder builder) {
        this.totalFilesScanned  = builder.totalFilesScanned;
        this.filesSkipped       = builder.filesSkipped;
        this.filesProcessed     = builder.filesProcessed;
        this.filesFailed        = builder.filesFailed;
        this.totalChunksCreated = builder.totalChunksCreated;
        this.durationMs         = builder.durationMs;
        this.failedFilePaths    = List.copyOf(builder.failedFilePaths);
    }

    public int    getTotalFilesScanned()  { return totalFilesScanned; }
    public int    getFilesSkipped()       { return filesSkipped; }
    public int    getFilesProcessed()     { return filesProcessed; }
    public int    getFilesFailed()        { return filesFailed; }
    public int    getTotalChunksCreated() { return totalChunksCreated; }
    public long   getDurationMs()         { return durationMs; }
    public List<String> getFailedFilePaths() { return failedFilePaths; }

    public String toSummary() {
        return String.format(
            """
            ═══════════════════════════════════════
             Ingestion Complete
            ═══════════════════════════════════════
             Files scanned   : %d
             Files skipped   : %d  (already up-to-date)
             Files processed : %d
             Files failed    : %d
             Chunks created  : %d
             Duration        : %.2f seconds
            ═══════════════════════════════════════""",
            totalFilesScanned,
            filesSkipped,
            filesProcessed,
            filesFailed,
            totalChunksCreated,
            durationMs / 1000.0
        );
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int totalFilesScanned;
        private int filesSkipped;
        private int filesProcessed;
        private int filesFailed;
        private int totalChunksCreated;
        private long durationMs;
        private List<String> failedFilePaths = List.of();

        private Builder() {}

        public Builder totalFilesScanned(int v)    { this.totalFilesScanned = v; return this; }
        public Builder filesSkipped(int v)         { this.filesSkipped = v; return this; }
        public Builder filesProcessed(int v)       { this.filesProcessed = v; return this; }
        public Builder filesFailed(int v)          { this.filesFailed = v; return this; }
        public Builder totalChunksCreated(int v)   { this.totalChunksCreated = v; return this; }
        public Builder durationMs(long v)          { this.durationMs = v; return this; }
        public Builder failedFilePaths(List<String> v) { this.failedFilePaths = v; return this; }

        public IngestionResult build() { return new IngestionResult(this); }
    }
}

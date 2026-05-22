package com.localfilebrain.model;

import java.time.Instant;

/**
 * Represents the indexing record of a file stored in the local SQLite metadata DB.
 * Used by FileScanner and IndexMetadataStore to track what has been processed.
 */
public final class FileRecord {

    public enum Status {
        /** File has been fully indexed (extracted + chunked + embedded + stored). */
        INDEXED,
        /** File was found but ingestion failed — will be retried next run. */
        FAILED,
        /** File is actively being processed (set at start, cleared on completion). */
        IN_PROGRESS
    }

    private final String absolutePath;
    private final String fileName;
    private final String fileExtension;
    private final long   fileSizeBytes;
    private final long   lastModifiedMs;
    private final String contentHash;   // SHA-256 of file content
    private final Status status;
    private final int    chunkCount;
    private final long   tokenCount;    // estimated tokens consumed during indexing
    private final Instant lastIndexedAt;
    private final String errorMessage;  // populated if status = FAILED

    private FileRecord(Builder builder) {
        this.absolutePath   = builder.absolutePath;
        this.fileName       = builder.fileName;
        this.fileExtension  = builder.fileExtension;
        this.fileSizeBytes  = builder.fileSizeBytes;
        this.lastModifiedMs = builder.lastModifiedMs;
        this.contentHash    = builder.contentHash;
        this.status         = builder.status;
        this.chunkCount     = builder.chunkCount;
        this.tokenCount     = builder.tokenCount;
        this.lastIndexedAt  = builder.lastIndexedAt;
        this.errorMessage   = builder.errorMessage;
    }

    public String   getAbsolutePath()   { return absolutePath; }
    public String   getFileName()       { return fileName; }
    public String   getFileExtension()  { return fileExtension; }
    public long     getFileSizeBytes()  { return fileSizeBytes; }
    public long     getLastModifiedMs() { return lastModifiedMs; }
    public String   getContentHash()    { return contentHash; }
    public Status   getStatus()         { return status; }
    public int      getChunkCount()     { return chunkCount; }
    public long     getTokenCount()     { return tokenCount; }
    public Instant  getLastIndexedAt()  { return lastIndexedAt; }
    public String   getErrorMessage()   { return errorMessage; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String absolutePath;
        private String fileName;
        private String fileExtension;
        private long   fileSizeBytes;
        private long   lastModifiedMs;
        private String contentHash;
        private Status status;
        private int    chunkCount;
        private long   tokenCount;
        private Instant lastIndexedAt;
        private String errorMessage;

        private Builder() {}

        public Builder absolutePath(String v)   { this.absolutePath = v; return this; }
        public Builder fileName(String v)        { this.fileName = v; return this; }
        public Builder fileExtension(String v)   { this.fileExtension = v; return this; }
        public Builder fileSizeBytes(long v)     { this.fileSizeBytes = v; return this; }
        public Builder lastModifiedMs(long v)    { this.lastModifiedMs = v; return this; }
        public Builder contentHash(String v)     { this.contentHash = v; return this; }
        public Builder status(Status v)          { this.status = v; return this; }
        public Builder chunkCount(int v)         { this.chunkCount = v; return this; }
        public Builder tokenCount(long v)        { this.tokenCount = v; return this; }
        public Builder lastIndexedAt(Instant v)  { this.lastIndexedAt = v; return this; }
        public Builder errorMessage(String v)    { this.errorMessage = v; return this; }

        public FileRecord build() { return new FileRecord(this); }
    }

    @Override
    public String toString() {
        return String.format("FileRecord{path='%s', status=%s, chunks=%d, hash=%s}",
            absolutePath, status, chunkCount, contentHash != null ? contentHash.substring(0, 8) + "..." : "none");
    }
}

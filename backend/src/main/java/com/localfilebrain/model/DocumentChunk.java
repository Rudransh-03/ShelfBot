package com.localfilebrain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single chunk of text extracted from a file.
 *
 * This is the central data unit that flows through all stages:
 *   Stage 1 (Ingestion)  → produces DocumentChunk objects
 *   Stage 2 (Embedding)  → adds embedding vector to each chunk
 *   Stage 3 (Query)      → retrieves chunks and builds answers from them
 *
 * Immutable by design. Use the Builder to construct.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class DocumentChunk {

    /** Unique ID for this chunk: "<absoluteFilePath>::chunk-<index>" */
    private final String chunkId;

    /** Absolute path of the source file */
    private final String sourceFilePath;

    /** Original filename (for display purposes) */
    private final String fileName;

    /** File extension, lowercased, without dot (e.g. "pdf", "docx") */
    private final String fileExtension;

    /** Zero-based index of this chunk within the source file */
    private final int chunkIndex;

    /** Total number of chunks produced from this file */
    private final int totalChunks;

    /** The actual text content of this chunk */
    private final String text;

    /** Character count of the text */
    private final int charCount;

    /** Last-modified timestamp of the source file (epoch millis) */
    private final long fileLastModifiedMs;

    /** When this chunk was created by the ingestion pipeline */
    private final Instant indexedAt;

    /** MIME type detected by Tika (e.g. "application/pdf") */
    private final String mimeType;

    /** 1-based first page this chunk's text came from; 0 when unknown (non-PDF,
     *  scan, or the page locator couldn't place it). */
    private final int pageStart;

    /** 1-based last page this chunk's text spans; 0 when unknown. Equals
     *  {@link #pageStart} for a chunk contained in a single page, greater when
     *  the chunk straddles a page break. */
    private final int pageEnd;

    private DocumentChunk(Builder builder) {
        this.chunkId            = Objects.requireNonNull(builder.chunkId, "chunkId");
        this.sourceFilePath     = Objects.requireNonNull(builder.sourceFilePath, "sourceFilePath");
        this.fileName           = Objects.requireNonNull(builder.fileName, "fileName");
        this.fileExtension      = Objects.requireNonNull(builder.fileExtension, "fileExtension");
        this.chunkIndex         = builder.chunkIndex;
        this.totalChunks        = builder.totalChunks;
        this.text               = Objects.requireNonNull(builder.text, "text");
        this.charCount          = builder.text.length();
        this.fileLastModifiedMs = builder.fileLastModifiedMs;
        this.indexedAt          = builder.indexedAt != null ? builder.indexedAt : Instant.now();
        this.mimeType           = builder.mimeType != null ? builder.mimeType : "application/octet-stream";
        this.pageStart          = builder.pageStart;
        this.pageEnd            = builder.pageEnd;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public String getChunkId()            { return chunkId; }
    public String getSourceFilePath()     { return sourceFilePath; }
    public String getFileName()           { return fileName; }
    public String getFileExtension()      { return fileExtension; }
    public int    getChunkIndex()         { return chunkIndex; }
    public int    getTotalChunks()        { return totalChunks; }
    public String getText()               { return text; }
    public int    getCharCount()          { return charCount; }
    public long   getFileLastModifiedMs() { return fileLastModifiedMs; }
    public Instant getIndexedAt()         { return indexedAt; }
    public String getMimeType()           { return mimeType; }
    public int    getPageStart()          { return pageStart; }
    public int    getPageEnd()            { return pageEnd; }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String chunkId;
        private String sourceFilePath;
        private String fileName;
        private String fileExtension;
        private int    chunkIndex;
        private int    totalChunks;
        private String text;
        private long   fileLastModifiedMs;
        private Instant indexedAt;
        private String mimeType;
        private int    pageStart;
        private int    pageEnd;

        private Builder() {}

        public Builder chunkId(String chunkId)                      { this.chunkId = chunkId; return this; }
        public Builder sourceFilePath(String sourceFilePath)        { this.sourceFilePath = sourceFilePath; return this; }
        public Builder fileName(String fileName)                    { this.fileName = fileName; return this; }
        public Builder fileExtension(String fileExtension)         { this.fileExtension = fileExtension; return this; }
        public Builder chunkIndex(int chunkIndex)                  { this.chunkIndex = chunkIndex; return this; }
        public Builder totalChunks(int totalChunks)                { this.totalChunks = totalChunks; return this; }
        public Builder text(String text)                           { this.text = text; return this; }
        public Builder fileLastModifiedMs(long fileLastModifiedMs) { this.fileLastModifiedMs = fileLastModifiedMs; return this; }
        public Builder indexedAt(Instant indexedAt)                { this.indexedAt = indexedAt; return this; }
        public Builder mimeType(String mimeType)                   { this.mimeType = mimeType; return this; }
        public Builder pageStart(int pageStart)                    { this.pageStart = pageStart; return this; }
        public Builder pageEnd(int pageEnd)                        { this.pageEnd = pageEnd; return this; }

        public DocumentChunk build() { return new DocumentChunk(this); }
    }

    // -------------------------------------------------------------------------
    // Standard overrides
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentChunk c)) return false;
        return Objects.equals(chunkId, c.chunkId);
    }

    @Override
    public int hashCode() { return Objects.hash(chunkId); }

    @Override
    public String toString() {
        return String.format("DocumentChunk{id='%s', file='%s', chunk=%d/%d, chars=%d}",
            chunkId, fileName, chunkIndex + 1, totalChunks, charCount);
    }
}

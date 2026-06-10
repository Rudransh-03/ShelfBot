package com.localfilebrain.ingestion;

import com.localfilebrain.config.AppConfig;
import com.localfilebrain.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits extracted text into overlapping chunks suitable for embedding.
 *
 * Strategy:
 * 1. Split text into sentences using a simple but robust sentence boundary detector.
 * 2. Accumulate sentences into a chunk until the char limit is reached.
 * 3. When a chunk is full, emit it and start the next chunk from the overlap boundary
 *    (we step back ~{overlapChars} worth of sentences and continue from there).
 *    This ensures no sentence is cut mid-way, and context flows across chunk boundaries.
 *
 * Why char-based instead of token-based?
 * - Token counting requires a tokenizer library (tiktoken, etc.) which adds a dep.
 * - English text averages ~4 chars/token. Our default 1800 chars ≈ 450 tokens,
 *   well within OpenAI's 8191-token embedding limit.
 * - Simple and fast, no external dependencies.
 *
 * Chunk ID format: "<absolutePath>::chunk-<index>"
 */
public final class TextChunker {

    private static final Logger log = LoggerFactory.getLogger(TextChunker.class);

    private final int chunkSizeChars;
    private final int overlapChars;

    public TextChunker(AppConfig config) {
        this.chunkSizeChars = config.getChunkSizeChars();
        this.overlapChars   = config.getChunkOverlapChars();

        if (overlapChars >= chunkSizeChars) {
            throw new IllegalArgumentException(
                "chunk.overlap.chars (" + overlapChars + ") must be less than chunk.size.chars (" + chunkSizeChars + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Splits the given text into DocumentChunk objects (no page metadata).
     *
     * @param text          the full extracted text of a file
     * @param sourceFile    the file this text came from
     * @param mimeType      MIME type detected by Tika
     * @param lastModifiedMs file's last-modified timestamp
     * @return ordered list of chunks (may be empty if text is blank)
     */
    public List<DocumentChunk> chunk(String text, Path sourceFile, String mimeType, long lastModifiedMs) {
        return chunk(text, sourceFile, mimeType, lastModifiedMs, null);
    }

    /**
     * Splits the given text into DocumentChunk objects, optionally attaching the
     * page span of each chunk via {@code pageLocator}.
     *
     * The chunk TEXT and boundaries are computed identically whether or not a
     * locator is supplied — the locator only reads finished chunk text to tag a
     * page, so it can never change embeddings or retrieval. A {@code null}
     * locator (or one that can't place a given chunk) leaves the chunk's pages
     * at 0 (unknown), exactly as before this feature existed.
     */
    public List<DocumentChunk> chunk(String text, Path sourceFile, String mimeType,
                                     long lastModifiedMs, PageLocator pageLocator) {
        if (text == null || text.isBlank()) {
            log.debug("Empty text for '{}', producing no chunks", sourceFile.getFileName());
            return List.of();
        }

        String absolutePath = sourceFile.toAbsolutePath().toString();
        String fileName     = sourceFile.getFileName().toString();
        String extension    = getExtension(fileName);
        Instant now         = Instant.now();

        // Step 1: split into sentences
        List<String> sentences = splitIntoSentences(text);
        log.debug("'{}' → {} sentences, {} total chars", fileName, sentences.size(), text.length());

        // Step 2: pack sentences into chunks with overlap
        List<String> rawChunks = packSentencesIntoChunks(sentences);
        log.debug("'{}' → {} chunks", fileName, rawChunks.size());

        // Step 3: wrap into DocumentChunk objects with metadata
        List<DocumentChunk> chunks = new ArrayList<>(rawChunks.size());
        for (int i = 0; i < rawChunks.size(); i++) {
            String chunkText = rawChunks.get(i).strip();
            if (chunkText.isBlank()) continue;

            String chunkId = absolutePath + "::chunk-" + i;
            DocumentChunk.Builder b = DocumentChunk.builder()
                .chunkId(chunkId)
                .sourceFilePath(absolutePath)
                .fileName(fileName)
                .fileExtension(extension)
                .chunkIndex(i)
                .totalChunks(rawChunks.size())
                .text(chunkText)
                .fileLastModifiedMs(lastModifiedMs)
                .indexedAt(now)
                .mimeType(mimeType);

            if (pageLocator != null) {
                int[] span = pageLocator.locate(chunkText);
                if (span != null) {
                    b.pageStart(span[0]).pageEnd(span[1]);
                }
            }
            chunks.add(b.build());
        }

        if (pageLocator != null) {
            long withPages = chunks.stream().filter(c -> c.getPageStart() > 0).count();
            log.debug("'{}' → {}/{} chunks tagged with a page", fileName, withPages, chunks.size());
        }

        return chunks;
    }

    // -------------------------------------------------------------------------
    // Sentence splitting
    // -------------------------------------------------------------------------

    /**
     * Splits text into sentences.
     *
     * Rules:
     * - Split on ". ", "! ", "? " followed by an uppercase letter or end of string
     * - Split on paragraph breaks (\n\n)
     * - Split on single newlines (often header/list boundaries in docs)
     * - Keep sentence delimiters attached to the sentence they end
     *
     * Not using a full NLP sentence tokenizer to keep dependencies minimal.
     * This handles 95%+ of real document text correctly.
     */
    static List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();

        // First split on paragraph boundaries
        String[] paragraphs = text.split("\n\n+");

        for (String paragraph : paragraphs) {
            paragraph = paragraph.strip();
            if (paragraph.isBlank()) continue;

            // Within each paragraph, split on sentence-ending punctuation
            // Pattern: end of sentence = ". "/"! "/"? " before an uppercase letter
            String[] parts = paragraph.split("(?<=[.!?])(?=\\s+[A-Z\"'(])|(?<=\\n)");

            for (String part : parts) {
                String sentence = part.strip();
                if (!sentence.isBlank()) {
                    sentences.add(sentence);
                }
            }
        }

        return sentences;
    }

    // -------------------------------------------------------------------------
    // Chunk packing with overlap
    // -------------------------------------------------------------------------

    /**
     * Packs sentences into chunks of at most chunkSizeChars characters,
     * with overlapChars of context carried forward from the previous chunk.
     */
    private List<String> packSentencesIntoChunks(List<String> sentences) {
        List<String> chunks = new ArrayList<>();

        int sentenceCount = sentences.size();
        int startIdx      = 0;  // index into sentences[] where the current chunk begins

        while (startIdx < sentenceCount) {
            StringBuilder chunkBuilder = new StringBuilder();
            int endIdx = startIdx;

            // Accumulate sentences until we hit the size limit
            while (endIdx < sentenceCount) {
                String sentence = sentences.get(endIdx);
                int addedLength = chunkBuilder.isEmpty()
                    ? sentence.length()
                    : 1 + sentence.length(); // +1 for the space between sentences

                if (chunkBuilder.length() + addedLength > chunkSizeChars && !chunkBuilder.isEmpty()) {
                    // Adding this sentence would exceed the limit — stop here
                    break;
                }

                if (!chunkBuilder.isEmpty()) {
                    chunkBuilder.append(' ');
                }
                chunkBuilder.append(sentence);
                endIdx++;
            }

            // Edge case: single sentence is longer than chunkSizeChars
            // We still emit it as one chunk rather than splitting mid-sentence
            if (endIdx == startIdx && startIdx < sentenceCount) {
                chunkBuilder.append(sentences.get(startIdx));
                endIdx = startIdx + 1;
            }

            chunks.add(chunkBuilder.toString());

            if (endIdx >= sentenceCount) break;

            // Compute the next start position by stepping back {overlapChars} worth of sentences.
            // Guarantee forward progress: never let the next chunk start at or before the current one.
            int next = computeNextStartIndex(sentences, endIdx, overlapChars);
            startIdx = Math.max(next, startIdx + 1);
        }

        return chunks;
    }

    /**
     * Walks backward from endIdx, accumulating sentence lengths until we've
     * covered at least overlapChars. Returns the index of the sentence where
     * the next chunk should start.
     */
    private int computeNextStartIndex(List<String> sentences, int endIdx, int targetOverlap) {
        int accumulated = 0;
        int idx         = endIdx - 1;

        while (idx > 0 && accumulated < targetOverlap) {
            accumulated += sentences.get(idx).length() + 1; // +1 for space
            idx--;
        }

        // idx points to the sentence just before our desired start — advance by 1
        return Math.max(idx + 1, 0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) return "";
        return fileName.substring(lastDot + 1).toLowerCase();
    }
}

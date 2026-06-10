package com.localfilebrain.ingestion;

/**
 * Maps a chunk's text back to the page(s) of its source document.
 *
 * Deliberately decoupled from text extraction and chunking: the canonical text
 * (and therefore every chunk's text, embedding, and retrieval behaviour) is
 * produced exactly as before. A locator is consulted only to ATTACH page
 * metadata afterwards — it can never alter what gets chunked or embedded, so it
 * cannot affect answer accuracy. When it can't confidently place a chunk it
 * returns {@code null} and the chunk simply carries no page (same as today).
 */
public interface PageLocator {

    /**
     * @return {@code {startPage, endPage}} (1-based, inclusive) for the chunk, or
     *         {@code null} when the chunk can't be confidently placed.
     */
    int[] locate(String chunkText);
}

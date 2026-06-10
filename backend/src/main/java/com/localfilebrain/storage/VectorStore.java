package com.localfilebrain.storage;

import com.localfilebrain.model.DocumentChunk;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded vector store backed by Apache Lucene's HNSW index.
 *
 * Why this design:
 *   • Pure Java — no native libraries to ship per OS, packages cleanly into
 *     the existing shaded JAR.
 *   • HNSW algorithm — sub-millisecond approximate-nearest-neighbour queries
 *     even at 100K+ vectors. The previous SQLite-based implementation did a
 *     full linear scan and loaded every embedding into memory per query;
 *     this scales considerably better while reusing the same on-disk
 *     persistence model (a folder of files).
 *   • Single writer, many readers — Lucene's {@link SearcherManager} handles
 *     concurrent reads while the indexing thread continues to upsert.
 *
 * Storage layout: one Lucene segment directory at the configured path. All
 * chunks live in a single index; we differentiate them by their {@code chunk_id}
 * primary key (overwriting an existing chunk_id replaces the document, which
 * makes re-indexing idempotent).
 *
 * Distance contract (unchanged from before, so QueryEngine's threshold logic
 * is unaffected): the {@link SearchResult#distance} is cosine distance,
 * i.e. {@code 1 - cosine_similarity}, in [0, 1]. 0 = identical, 1 = opposite.
 */
public final class VectorStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    // Field names — kept short to minimise on-disk overhead.
    private static final String F_ID         = "id";
    private static final String F_VECTOR     = "vec";
    private static final String F_TEXT       = "text";
    private static final String F_SRC_PATH   = "src";
    private static final String F_FILE_NAME  = "name";
    private static final String F_CHUNK_IDX  = "cidx";
    private static final String F_TOTAL      = "tot";
    private static final String F_MIME       = "mime";
    private static final String F_MTIME      = "mtime";
    private static final String F_CHAR_CNT   = "chars";
    private static final String F_PAGE_START = "pgs";  // 1-based first page; absent/0 = unknown
    private static final String F_PAGE_END   = "pge";  // 1-based last page;  absent/0 = unknown

    // HNSW build parameters. Defaults are conservative — slightly higher recall
    // than Lucene's defaults at the cost of a bit more memory during build.
    // For RAG use cases recall matters more than raw build speed.
    private static final int HNSW_M               = 16;   // graph neighbours
    private static final int HNSW_BEAM_WIDTH      = 100;  // build-time candidates

    // Lucene's stock KnnVectorsFormat caps vectors at 1024 dimensions. OpenAI's
    // text-embedding-3-small returns 1536-dim vectors, so we ship a custom
    // format that lifts the cap. 2048 leaves headroom for text-embedding-3-large
    // (3072) — though that one would need another bump.
    private static final int MAX_VECTOR_DIM       = 2048;

    private final FSDirectory   directory;
    private final IndexWriter   writer;
    private final SearcherManager searchers;

    public VectorStore(Path indexDirPath) {
        try {
            Files.createDirectories(indexDirPath);

            this.directory = FSDirectory.open(indexDirPath);

            IndexWriterConfig config = new IndexWriterConfig();
            // Custom codec so we control HNSW parameters explicitly rather
            // than relying on Lucene's defaults that may shift between versions.
            // HighDimKnnVectorsFormat also lifts the 1024-dim ceiling so we
            // can store OpenAI's 1536-dim embeddings.
            final KnnVectorsFormat hnswFormat = new HighDimKnnVectorsFormat(
                    new Lucene99HnswVectorsFormat(HNSW_M, HNSW_BEAM_WIDTH),
                    MAX_VECTOR_DIM
            );
            config.setCodec(new Lucene99Codec() {
                @Override
                public KnnVectorsFormat getKnnVectorsFormatForField(String name) {
                    return hnswFormat;
                }
            });
            this.writer    = new IndexWriter(directory, config);

            // Force at least one commit so SearcherManager has something to
            // open even on a fresh empty index.
            writer.commit();

            this.searchers = new SearcherManager(directory, null);
            log.info("VectorStore (Lucene HNSW) opened: {}", indexDirPath.toAbsolutePath());
        } catch (IOException e) {
            throw new VectorStoreException("Failed to open VectorStore at: " + indexDirPath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    /**
     * Upserts a batch of chunks with their embeddings. Existing chunks with
     * the same chunk_id are atomically replaced — safe for re-indexing.
     */
    public synchronized void upsert(List<DocumentChunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks and embeddings must have the same size");
        }
        if (chunks.isEmpty()) return;

        try {
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                writer.updateDocument(new Term(F_ID, chunk.getChunkId()),
                        buildChunkDoc(chunk, embeddings.get(i)));
            }
            writer.commit();
            searchers.maybeRefresh();
            log.debug("Upserted {} chunks", chunks.size());
        } catch (IOException e) {
            throw new VectorStoreException("Failed to upsert chunks", e);
        }
    }

    /** Builds the Lucene document for one chunk. Shared by upsert + replaceBySourceFile. */
    private static Document buildChunkDoc(DocumentChunk chunk, float[] vec) {
        Document doc = new Document();
        doc.add(new StringField(F_ID,        chunk.getChunkId(),        Field.Store.YES));
        doc.add(new StringField(F_SRC_PATH,  chunk.getSourceFilePath(), Field.Store.YES));
        doc.add(new StringField(F_FILE_NAME, chunk.getFileName(),       Field.Store.YES));
        doc.add(new StoredField(F_TEXT,      chunk.getText()));
        doc.add(new StoredField(F_MIME,      chunk.getMimeType()));
        doc.add(new StoredField(F_MTIME,     chunk.getFileLastModifiedMs()));
        doc.add(new StoredField(F_CHAR_CNT,  chunk.getCharCount()));
        doc.add(new StoredField(F_CHUNK_IDX, chunk.getChunkIndex()));
        doc.add(new StoredField(F_TOTAL,     chunk.getTotalChunks()));
        doc.add(new StoredField(F_PAGE_START, chunk.getPageStart()));
        doc.add(new StoredField(F_PAGE_END,   chunk.getPageEnd()));
        // Indexed AND stored so we can both filter and retrieve.
        doc.add(new IntPoint(F_CHUNK_IDX, chunk.getChunkIndex()));
        doc.add(new KnnFloatVectorField(F_VECTOR, vec, VectorSimilarityFunction.COSINE));
        return doc;
    }

    /**
     * Atomically replaces all chunks of one source file — deletes the file's
     * existing chunks and writes the new ones in a single synchronized,
     * single-commit operation. Because the whole delete+add pair holds the
     * writer monitor, two threads re-indexing the SAME file (e.g. the live
     * watcher and a manual job) can never interleave their delete/add and leave
     * duplicate chunks: one fully replaces the other.
     */
    public synchronized void replaceBySourceFile(String absoluteFilePath,
                                                 List<DocumentChunk> chunks,
                                                 List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks and embeddings must have the same size");
        }
        try {
            writer.deleteDocuments(new Term(F_SRC_PATH, absoluteFilePath));
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                writer.updateDocument(new Term(F_ID, chunk.getChunkId()),
                        buildChunkDoc(chunk, embeddings.get(i)));
            }
            writer.commit();
            searchers.maybeRefresh();
            log.debug("Replaced {} chunks for: {}", chunks.size(), absoluteFilePath);
        } catch (IOException e) {
            throw new VectorStoreException("Failed to replace chunks for: " + absoluteFilePath, e);
        }
    }

    /**
     * Wipes every document from the index. Used by the startup migration
     * path when the embedding backend (and therefore vector dimensionality)
     * has changed and the existing vectors would be incompatible with new
     * queries.
     */
    public synchronized void deleteAll() {
        try {
            writer.deleteAll();
            writer.commit();
            searchers.maybeRefresh();
            log.warn("Vector store wiped (deleteAll)");
        } catch (IOException e) {
            throw new VectorStoreException("Failed to wipe vector store", e);
        }
    }

    /**
     * Deletes every chunk that came from a given source file. Called before
     * re-indexing a changed file and when the user removes a file from the
     * Library list.
     */
    public synchronized void deleteBySourceFile(String absoluteFilePath) {
        try {
            writer.deleteDocuments(new Term(F_SRC_PATH, absoluteFilePath));
            writer.commit();
            searchers.maybeRefresh();
            log.debug("Deleted chunks for: {}", absoluteFilePath);
        } catch (IOException e) {
            log.warn("Failed to delete chunks for '{}': {}", absoluteFilePath, e.getMessage());
        }
    }

    /** Returns total chunk count currently in the index. */
    public int count() {
        try {
            searchers.maybeRefresh();
            IndexSearcher searcher = searchers.acquire();
            try {
                return searcher.getIndexReader().numDocs();
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            log.warn("Could not count chunks: {}", e.getMessage());
            return -1;
        }
    }

    // -------------------------------------------------------------------------
    // Vector similarity search
    // -------------------------------------------------------------------------

    /**
     * Returns the top-K chunks most similar to the query embedding, ordered
     * by ascending distance (most-similar first).
     */
    /**
     * Returns every chunk stored for a given source file, ordered by their
     * chunk index in the original document.
     *
     * Used by the query engine to side-step the classic RAG failure mode
     * where a document's "section label" chunk outranks the chunk that
     * actually contains the answer. Once we've decided a file is relevant
     * (via semantic search of the question), we pull the file's entire
     * indexed content in document order — so the LLM never misses the
     * BNY-Mellon-internship-style entry just because its bullet text
     * embedded farther from the query than the "Work Experience" header.
     *
     * Distance is reported as 0.0 because these chunks aren't ranked
     * against a query — they're file-context expansion, not retrieval.
     */
    public List<SearchResult> getChunksForFile(String absolutePath) {
        try {
            searchers.maybeRefresh();
            IndexSearcher searcher = searchers.acquire();
            try {
                TermQuery q = new TermQuery(new Term(F_SRC_PATH, absolutePath));
                // Reasonable upper bound: 1000 chunks per file is ~1.8 MB of text.
                TopDocs td = searcher.search(q, 1000);
                List<SearchResult> out = new ArrayList<>(td.scoreDocs.length);
                for (ScoreDoc sd : td.scoreDocs) {
                    Document d = searcher.storedFields().document(sd.doc);
                    out.add(new SearchResult(
                            d.get(F_ID),
                            d.get(F_SRC_PATH),
                            d.get(F_FILE_NAME),
                            intField(d, F_CHUNK_IDX),
                            d.get(F_TEXT),
                            0.0,
                            intField(d, F_PAGE_START),
                            intField(d, F_PAGE_END)
                    ));
                }
                // Restore the original document order so the LLM reads chunks
                // top-to-bottom of the source file.
                out.sort(java.util.Comparator.comparingInt(SearchResult::chunkIndex));
                return out;
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            log.warn("Failed to fetch all chunks for '{}': {}", absolutePath, e.getMessage());
            return List.of();
        }
    }

    /**
     * Resolves a bare file name (e.g. "PERCENTAGE-1.pdf") to the absolute
     * path(s) of indexed files that carry that exact name. Used by the query
     * engine's file-scope fallback so a user who types just the file name —
     * not its full absolute path — still gets the deterministic "load this
     * whole file" path instead of leaning on semantic search (which
     * underperforms on worksheets / forms / question banks).
     *
     * Match is exact and case-sensitive ({@link #F_FILE_NAME} is a non-analyzed
     * StringField), so it keys off the name exactly as stored at index time —
     * which is what the Library UI shows the user. Returns distinct source
     * paths (one name can collide across folders), or empty when no indexed
     * file has that name.
     */
    public List<String> findPathsByFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return List.of();
        try {
            searchers.maybeRefresh();
            IndexSearcher searcher = searchers.acquire();
            try {
                TermQuery q = new TermQuery(new Term(F_FILE_NAME, fileName));
                TopDocs td = searcher.search(q, 1000);
                java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
                for (ScoreDoc sd : td.scoreDocs) {
                    Document d = searcher.storedFields().document(sd.doc);
                    String p = d.get(F_SRC_PATH);
                    if (p != null) paths.add(p);
                }
                return new ArrayList<>(paths);
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            log.warn("Failed to resolve filename '{}': {}", fileName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the raw embedding vectors for every chunk that came from the
     * given source file. Unlike {@link #getChunksForFile}, this skips the
     * stored-field round-trip and pulls float[] arrays directly out of the
     * HNSW field — used by the reorg pipeline to mean-pool chunk vectors
     * into a single file-level vector.
     *
     * Order is by Lucene docId (i.e. insertion order within each segment),
     * not by chunk_index. Mean-pooling is order-independent, so callers that
     * just want the centroid don't need to sort.
     *
     * Each returned float[] is a defensive clone — Lucene's
     * {@link FloatVectorValues} reuses its internal buffer across calls.
     */
    public List<float[]> getChunkVectorsForFile(String absolutePath) {
        try {
            searchers.maybeRefresh();
            IndexSearcher searcher = searchers.acquire();
            try {
                TermQuery q = new TermQuery(new Term(F_SRC_PATH, absolutePath));
                // Same upper bound as getChunksForFile — 1000 chunks is plenty.
                TopDocs td = searcher.search(q, 1000);
                if (td.scoreDocs.length == 0) return List.of();

                // Group docs by leaf so we open one FloatVectorValues per leaf
                // and advance forward (its iterator is monotonic).
                IndexReader reader = searcher.getIndexReader();
                java.util.Map<Integer, java.util.List<Integer>> docsByLeaf = new java.util.HashMap<>();
                for (ScoreDoc sd : td.scoreDocs) {
                    int leafIdx = org.apache.lucene.index.ReaderUtil.subIndex(sd.doc, reader.leaves());
                    docsByLeaf.computeIfAbsent(leafIdx, k -> new ArrayList<>()).add(sd.doc);
                }

                List<float[]> out = new ArrayList<>(td.scoreDocs.length);
                for (var entry : docsByLeaf.entrySet()) {
                    LeafReaderContext ctx = reader.leaves().get(entry.getKey());
                    FloatVectorValues fvv = ctx.reader().getFloatVectorValues(F_VECTOR);
                    if (fvv == null) continue;
                    // Local docIds (within the leaf) — advance() needs them sorted.
                    java.util.List<Integer> localDocs = new ArrayList<>(entry.getValue().size());
                    for (int globalDoc : entry.getValue()) localDocs.add(globalDoc - ctx.docBase);
                    java.util.Collections.sort(localDocs);
                    for (int localDocId : localDocs) {
                        if (fvv.advance(localDocId) == localDocId) {
                            float[] v = fvv.vectorValue();
                            // Clone — FloatVectorValues may reuse the underlying array.
                            out.add(ArrayUtil.copyOfSubArray(v, 0, v.length));
                        }
                    }
                }
                return out;
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            log.warn("Failed to fetch chunk vectors for '{}': {}", absolutePath, e.getMessage());
            return List.of();
        }
    }

    public List<SearchResult> query(float[] queryEmbedding, int topK) {
        try {
            // Ensure latest writes are visible. maybeRefresh is cheap when
            // the index hasn't changed.
            searchers.maybeRefresh();

            IndexSearcher searcher = searchers.acquire();
            try {
                if (searcher.getIndexReader().numDocs() == 0) return List.of();

                KnnFloatVectorQuery query = new KnnFloatVectorQuery(F_VECTOR, queryEmbedding, topK);
                TopDocs td = searcher.search(query, topK);

                List<SearchResult> results = new ArrayList<>(td.scoreDocs.length);
                for (ScoreDoc sd : td.scoreDocs) {
                    Document d = searcher.storedFields().document(sd.doc);
                    // Lucene maps cosine to a [0, 1] score via (1 + cos) / 2.
                    // Recover cosine distance (in [0, 2], matches old semantics):
                    //   cos_sim    = 2 * score - 1
                    //   cos_dist   = 1 - cos_sim = 2 - 2 * score
                    double distance = 2.0 - 2.0 * sd.score;

                    results.add(new SearchResult(
                            d.get(F_ID),
                            d.get(F_SRC_PATH),
                            d.get(F_FILE_NAME),
                            intField(d, F_CHUNK_IDX),
                            d.get(F_TEXT),
                            distance,
                            intField(d, F_PAGE_START),
                            intField(d, F_PAGE_END)
                    ));
                }
                return results;
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new VectorStoreException("Failed to query vector store", e);
        }
    }

    private static int intField(Document d, String name) {
        IndexableField f = d.getField(name);
        if (f == null) return 0;
        Number n = f.numericValue();
        return n != null ? n.intValue() : 0;
    }

    // -------------------------------------------------------------------------
    // Result type — unchanged contract with QueryEngine
    // -------------------------------------------------------------------------

    public record SearchResult(
            String chunkId,
            String sourceFilePath,
            String fileName,
            int    chunkIndex,
            String text,
            double distance,  // cosine distance: 0 = identical, ~2 = opposite
            int    pageStart, // 1-based first page of source; 0 = unknown
            int    pageEnd    // 1-based last page of source;  0 = unknown
    ) {
        /**
         * Backwards-compatible constructor for callers/tests that don't carry
         * page info — pages default to 0 (unknown). Lets existing six-arg call
         * sites compile unchanged.
         */
        public SearchResult(String chunkId, String sourceFilePath, String fileName,
                            int chunkIndex, String text, double distance) {
            this(chunkId, sourceFilePath, fileName, chunkIndex, text, distance, 0, 0);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (searchers != null) searchers.close();
        } catch (IOException e) {
            log.warn("Error closing SearcherManager: {}", e.getMessage());
        }
        try {
            if (writer != null && writer.isOpen()) {
                writer.commit();
                writer.close();
            }
        } catch (IOException e) {
            log.warn("Error closing IndexWriter: {}", e.getMessage());
        }
        try {
            if (directory != null) directory.close();
        } catch (IOException e) {
            log.warn("Error closing FSDirectory: {}", e.getMessage());
        }
        log.debug("VectorStore closed");
    }

    public static class VectorStoreException extends RuntimeException {
        public VectorStoreException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Thin delegating wrapper around any {@link KnnVectorsFormat} that lifts
     * the per-field max-dimension cap. Needed because Lucene's stock formats
     * are {@code final} and default to a 1024-dim ceiling, while OpenAI's
     * {@code text-embedding-3-small} produces 1536-dim vectors.
     *
     * The format name is kept identical to the delegate's so segment metadata
     * resolves through the standard SPI on read.
     */
    private static final class HighDimKnnVectorsFormat extends KnnVectorsFormat {
        private final KnnVectorsFormat delegate;
        private final int maxDimensions;

        HighDimKnnVectorsFormat(KnnVectorsFormat delegate, int maxDimensions) {
            super(delegate.getName());
            this.delegate      = delegate;
            this.maxDimensions = maxDimensions;
        }

        @Override
        public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws java.io.IOException {
            return delegate.fieldsWriter(state);
        }

        @Override
        public KnnVectorsReader fieldsReader(SegmentReadState state) throws java.io.IOException {
            return delegate.fieldsReader(state);
        }

        @Override
        public int getMaxDimensions(String fieldName) {
            return maxDimensions;
        }
    }
}

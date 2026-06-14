package com.localfilebrain.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device embedding via {@code BAAI/bge-small-en-v1.5}, run through ONNX
 * Runtime. Tokenisation uses HuggingFace's Rust tokeniser via the DJL Java
 * binding — same byte-for-byte tokens the Python ecosystem produces.
 *
 * Produces 384-dimensional L2-normalised vectors suitable for cosine search.
 * For a typical chunk (~250 tokens) inference is single-digit milliseconds
 * on a modern CPU; an indexing run that used to make network round-trips
 * to OpenAI now stays entirely local.
 *
 * Model + tokenizer files live under {@code embedding.model.path} (defaults
 * to {@code ~/.shelfbot/models/bge-small-en-v1.5/}). If they aren't there
 * the constructor downloads them from HuggingFace Hub once and caches them
 * — subsequent launches are offline-friendly.
 */
public final class LocalEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingClient.class);

    public  static final String MODEL_NAME = "bge-small-en-v1.5";
    public  static final int    DIMS       = 384;
    public  static final int    MAX_SEQ    = 512;

    // The BGE recipe says to prefix retrieval queries with this string; the
    // documents themselves are embedded without a prefix. We don't currently
    // differentiate at this layer — both ingestion and query go through the
    // same call — but BGE-small was specifically trained so this matters
    // little for typical RAG corpora.

    private static final String MODEL_URL     =
            "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/onnx/model.onnx";
    private static final String TOKENIZER_URL =
            "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/tokenizer.json";

    private final OrtEnvironment        env;
    private final OrtSession            session;
    private final HuggingFaceTokenizer  tokenizer;

    public LocalEmbeddingClient(Path modelDir) {
        // Build into locals first; assign the final fields only once BOTH the
        // session and tokenizer succeed. If the tokenizer build throws after the
        // ONNX session is created, close the session here so a half-built client
        // can't leak the native session. (OrtEnvironment is a JVM-wide singleton,
        // not ours to close.)
        OrtEnvironment       envLocal       = null;
        OrtSession           sessionLocal   = null;
        HuggingFaceTokenizer tokenizerLocal = null;
        try {
            Path modelFile     = modelDir.resolve("model.onnx");
            Path tokenizerFile = modelDir.resolve("tokenizer.json");

            Files.createDirectories(modelDir);
            ensureFile(modelFile,     MODEL_URL,     "model.onnx (~130 MB)");
            ensureFile(tokenizerFile, TOKENIZER_URL, "tokenizer.json");

            log.info("Loading local embedding model from {}", modelDir.toAbsolutePath());

            envLocal = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            // CPU is fine; we don't ship GPU runtimes.
            sessionLocal   = envLocal.createSession(modelFile.toString(), opts);
            tokenizerLocal = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile)
                    .optAddSpecialTokens(true)
                    .optTruncation(true)
                    .optMaxLength(MAX_SEQ)
                    .build();

            log.info("Local embedding model ready ({} dims)", DIMS);
        } catch (Exception e) {
            if (sessionLocal   != null) { try { sessionLocal.close();   } catch (Exception ignored) {} }
            if (tokenizerLocal != null) { try { tokenizerLocal.close(); } catch (Exception ignored) {} }
            throw new LocalEmbeddingException("Failed to initialise local embedding model: " + e.getMessage(), e);
        }
        this.env       = envLocal;
        this.session   = sessionLocal;
        this.tokenizer = tokenizerLocal;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embedOne(text == null ? "" : text));
        }
        return out;
    }

    @Override public int    dimensions() { return DIMS; }
    @Override public String modelId()    { return "local:" + MODEL_NAME; }

    private float[] embedOne(String text) {
        try {
            Encoding enc = tokenizer.encode(text);
            long[] ids   = enc.getIds();
            long[] mask  = enc.getAttentionMask();
            long[] types = enc.getTypeIds();

            try (OnnxTensor inputIds      = OnnxTensor.createTensor(env, new long[][] { ids });
                 OnnxTensor attentionMask = OnnxTensor.createTensor(env, new long[][] { mask });
                 OnnxTensor tokenTypeIds  = OnnxTensor.createTensor(env, new long[][] { types })) {

                // BGE's ONNX export expects all three standard BERT inputs.
                // Using LinkedHashMap so iteration order is deterministic;
                // ONNX matches by name so order doesn't actually matter.
                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("input_ids",      inputIds);
                inputs.put("attention_mask", attentionMask);
                inputs.put("token_type_ids", tokenTypeIds);

                try (OrtSession.Result result = session.run(inputs)) {
                    // last_hidden_state shape: [batch=1, seq, hidden=384]
                    Object raw = result.get(0).getValue();
                    float[][][] hidden = (float[][][]) raw;
                    // BGE uses the CLS token (position 0) as the sentence vector.
                    float[] cls = hidden[0][0];
                    normaliseInPlace(cls);
                    return cls;
                }
            }
        } catch (OrtException e) {
            throw new LocalEmbeddingException("ONNX inference failed: " + e.getMessage(), e);
        }
    }

    /** L2-normalise so dot product == cosine similarity (what Lucene expects). */
    private static void normaliseInPlace(float[] v) {
        double sum = 0;
        for (float x : v) sum += (double) x * x;
        double norm = Math.sqrt(sum);
        if (norm == 0) return;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
    }

    /**
     * One-time download of a model artefact. We stream to a {@code .part}
     * file and atomically rename so a half-finished download never gets
     * loaded as a valid model on next launch.
     */
    private static void ensureFile(Path target, String url, String displayName) throws IOException {
        if (Files.exists(target) && Files.size(target) > 0) return;

        log.info("Downloading {} from {}", displayName, url);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        log.info("Downloaded {} ({} bytes)", displayName, Files.size(target));
    }

    @Override
    public void close() {
        try { if (session != null)   session.close();   } catch (Exception ignored) {}
        try { if (tokenizer != null) tokenizer.close(); } catch (Exception ignored) {}
        // OrtEnvironment is a JVM-wide singleton; we don't close it here.
    }

    public static class LocalEmbeddingException extends RuntimeException {
        public LocalEmbeddingException(String message, Throwable cause) { super(message, cause); }
    }
}

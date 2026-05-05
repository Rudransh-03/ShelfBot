package com.localfilebrain.ingestion;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.localfilebrain.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * Writes DocumentChunk objects to a JSONL (JSON Lines) file.
 *
 * JSONL format: one JSON object per line. This format is:
 * - Streamable: Stage 2 can read line-by-line without loading everything into RAM
 * - Appendable: new chunks are appended; existing ones are preserved
 * - Human-readable: you can inspect it with any text editor or `jq`
 *
 * AutoCloseable: use in try-with-resources.
 */
public final class ChunkWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChunkWriter.class);

    private final ObjectMapper mapper;
    private final BufferedWriter writer;
    private final Path outputPath;
    private int chunksWritten = 0;

    public ChunkWriter(Path outputPath) throws IOException {
        this.outputPath = outputPath;
        this.mapper     = buildMapper();

        // Open in APPEND mode — preserves previously written chunks across runs
        this.writer = Files.newBufferedWriter(
            outputPath,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );

        log.debug("ChunkWriter opened (append mode): {}", outputPath.toAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Writes all chunks from a single file to the JSONL output.
     * Each chunk is one line of JSON.
     */
    public void writeChunks(List<DocumentChunk> chunks) throws IOException {
        for (DocumentChunk chunk : chunks) {
            String json = mapper.writeValueAsString(chunk);
            writer.write(json);
            writer.newLine();
            chunksWritten++;
        }
        writer.flush(); // Flush after each file so partial progress is preserved on crash
    }

    public int getChunksWritten() { return chunksWritten; }
    public Path getOutputPath()   { return outputPath; }

    @Override
    public void close() throws IOException {
        writer.close();
        log.debug("ChunkWriter closed. Total chunks written this session: {}", chunksWritten);
    }

    // -------------------------------------------------------------------------
    // Jackson setup — manual Instant serializer (avoids jsr310 dependency)
    // -------------------------------------------------------------------------

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, new StdSerializer<>(Instant.class) {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                gen.writeString(value.toString()); // ISO-8601 string e.g. "2024-11-01T12:00:00Z"
            }
        });
        m.registerModule(module);
        return m;
    }
}

package com.localfilebrain.ingestion;

import com.localfilebrain.config.AppConfig;
import com.localfilebrain.model.DocumentChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTest {

    @TempDir
    Path tempDir;

    private TextChunker chunker;
    private Path dummyFile;

    private Path   configPath;
    private byte[] originalConfig; // null when file did not exist before test

    @BeforeEach
    void setUp() throws IOException {
        configPath = Path.of(System.getProperty("user.dir")).resolve("config.properties");

        // Save original content so we can restore it in tearDown
        originalConfig = Files.exists(configPath) ? Files.readAllBytes(configPath) : null;

        Properties props = new Properties();
        if (originalConfig != null) {
            // Start from the existing properties so we don't lose any keys
            try (var in = Files.newInputStream(configPath)) {
                props.load(in);
            }
        }
        // Override only what this test needs
        props.setProperty("files.root.path", tempDir.toString());
        props.setProperty("chunk.size.chars", "200");
        props.setProperty("chunk.overlap.chars", "40");

        try (OutputStream out = Files.newOutputStream(configPath)) {
            props.store(out, null);
        }

        AppConfig config = AppConfig.load();
        chunker   = new TextChunker(config);
        dummyFile = tempDir.resolve("test.txt");
        Files.writeString(dummyFile, "placeholder");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (originalConfig != null) {
            Files.write(configPath, originalConfig);   // restore original file
        } else {
            Files.deleteIfExists(configPath);          // file didn't exist before; remove it
        }
    }

    // -------------------------------------------------------------------------
    // Sentence splitting tests
    // -------------------------------------------------------------------------

    @Test
    void splitIntoSentences_simpleSentences() {
        String text = "The cat sat on the mat. The dog ran away. The bird flew high.";
        List<String> sentences = TextChunker.splitIntoSentences(text);
        assertFalse(sentences.isEmpty());
        String joined = String.join(" ", sentences);
        assertTrue(joined.contains("cat sat"));
        assertTrue(joined.contains("dog ran"));
        assertTrue(joined.contains("bird flew"));
    }

    @Test
    void splitIntoSentences_paragraphBoundaries() {
        String text = "First paragraph content here.\n\nSecond paragraph content here.";
        List<String> sentences = TextChunker.splitIntoSentences(text);
        assertTrue(sentences.size() >= 2, "Should split on paragraph boundary");
    }

    @Test
    void splitIntoSentences_preservesContent() {
        String text = "JWT stands for JSON Web Token. It is used for authentication. Tokens expire after a set time.";
        List<String> sentences = TextChunker.splitIntoSentences(text);
        String rejoined = String.join(" ", sentences);
        assertTrue(rejoined.contains("JWT"));
        assertTrue(rejoined.contains("authentication"));
        assertTrue(rejoined.contains("expire"));
    }

    @Test
    void splitIntoSentences_emptyInput() {
        assertTrue(TextChunker.splitIntoSentences("").isEmpty());
        assertTrue(TextChunker.splitIntoSentences("   ").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Chunking tests
    // -------------------------------------------------------------------------

    @Test
    void chunk_emptyTextProducesNoChunks() {
        List<DocumentChunk> chunks = chunker.chunk("", dummyFile, "text/plain", System.currentTimeMillis());
        assertTrue(chunks.isEmpty());
    }

    @Test
    void chunk_shortTextProducesOneChunk() {
        String text = "This is a short document. It has two sentences.";
        List<DocumentChunk> chunks = chunker.chunk(text, dummyFile, "text/plain", System.currentTimeMillis());
        assertEquals(1, chunks.size(), "Short text should produce exactly one chunk");
    }

    @Test
    void chunk_longTextProducesMultipleChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("Sentence number ").append(i).append(" contains some meaningful content about caching. ");
        }
        String text = sb.toString();
        assertTrue(text.length() > 200);

        List<DocumentChunk> chunks = chunker.chunk(text, dummyFile, "text/plain", System.currentTimeMillis());
        assertTrue(chunks.size() > 1, "Long text should produce multiple chunks");
    }

    @Test
    void chunk_metadataIsCorrect() {
        String text  = "This is the content of my JWT notes. It explains the authentication flow.";
        long modTime = 1_700_000_000_000L;
        List<DocumentChunk> chunks = chunker.chunk(text, dummyFile, "text/plain", modTime);

        assertFalse(chunks.isEmpty());
        DocumentChunk first = chunks.get(0);

        assertTrue(first.getChunkId().contains("::chunk-0"));
        assertEquals(dummyFile.getFileName().toString(), first.getFileName());
        assertEquals("txt", first.getFileExtension());
        assertEquals(0, first.getChunkIndex());
        assertEquals(chunks.size(), first.getTotalChunks());
        assertEquals(modTime, first.getFileLastModifiedMs());
        assertEquals("text/plain", first.getMimeType());
    }

    @Test
    void chunk_chunkIdsAreUnique() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("Sentence ").append(i).append(" about distributed systems and caching strategies. ");
        }
        List<DocumentChunk> chunks = chunker.chunk(sb.toString(), dummyFile, "text/plain", System.currentTimeMillis());
        long uniqueIds = chunks.stream().map(DocumentChunk::getChunkId).distinct().count();
        assertEquals(chunks.size(), uniqueIds, "All chunk IDs must be unique");
    }

    @Test
    void chunk_allChunksHaveNonEmptyText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Content sentence ").append(i).append(". ");
        }
        List<DocumentChunk> chunks = chunker.chunk(sb.toString(), dummyFile, "text/plain", System.currentTimeMillis());
        for (DocumentChunk chunk : chunks) {
            assertFalse(chunk.getText().isBlank(), "No chunk should have blank text");
        }
    }

    @Test
    void chunk_noChunkIsExcessivelyLarge() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("This is sentence ").append(i).append(" with meaningful content about backend design. ");
        }
        List<DocumentChunk> chunks = chunker.chunk(sb.toString(), dummyFile, "text/plain", System.currentTimeMillis());
        for (DocumentChunk chunk : chunks) {
            // Allow single-sentence overflow but flag anything more than 3x the chunk size
            assertTrue(chunk.getCharCount() <= 600,
                "Chunk should not be excessively large. Got: " + chunk.getCharCount());
        }
    }
}

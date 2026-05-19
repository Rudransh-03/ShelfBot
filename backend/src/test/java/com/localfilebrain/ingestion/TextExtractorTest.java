package com.localfilebrain.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TextExtractorTest {

    private TextExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new TextExtractor();
    }

    // -------------------------------------------------------------------------
    // Text cleaning tests
    // -------------------------------------------------------------------------

    @Test
    void cleanText_nullReturnsEmpty() {
        assertEquals("", TextExtractor.cleanText(null));
    }

    @Test
    void cleanText_blankReturnsEmpty() {
        assertEquals("", TextExtractor.cleanText("   \n\n  "));
    }

    @Test
    void cleanText_normalizesWindowsLineEndings() {
        String input    = "Line one\r\nLine two\r\nLine three";
        String expected = "Line one\nLine two\nLine three";
        assertEquals(expected, TextExtractor.cleanText(input));
    }

    @Test
    void cleanText_collapsesManyBlankLines() {
        String input  = "Para one\n\n\n\n\nPara two";
        String result = TextExtractor.cleanText(input);
        assertTrue(result.contains("Para one\n\nPara two"),
            "Expected at most 2 consecutive newlines. Got: " + result.replace("\n", "\\n"));
    }

    @Test
    void cleanText_removesNullBytes() {
        String input  = "Hello\u0000World";
        String result = TextExtractor.cleanText(input);
        assertFalse(result.contains("\u0000"), "Null bytes should be stripped");
        assertTrue(result.contains("Hello") && result.contains("World"));
    }

    @Test
    void cleanText_preservesTabsAndNewlines() {
        String input  = "Column1\tColumn2\nRow2Col1\tRow2Col2";
        String result = TextExtractor.cleanText(input);
        assertTrue(result.contains("\t"), "Tabs should be preserved");
        assertTrue(result.contains("\n"), "Newlines should be preserved");
    }

    @Test
    void cleanText_trims() {
        assertEquals("hello", TextExtractor.cleanText("  hello  "));
    }

    // -------------------------------------------------------------------------
    // Real file extraction tests
    // -------------------------------------------------------------------------

    @Test
    void extract_plainTextFile(@TempDir Path tempDir) throws Exception {
        Path txtFile = tempDir.resolve("test.txt");
        Files.writeString(txtFile, "Hello from a text file.\nThis is line two.", StandardCharsets.UTF_8);

        TextExtractor.ExtractionResult result = extractor.extract(txtFile);

        assertFalse(result.isEmpty(), "Should extract non-empty text from .txt file");
        assertTrue(result.text().contains("Hello from a text file"),
            "Extracted text should contain file content");
        assertNotNull(result.mimeType(), "MIME type should not be null");
    }

    @Test
    void extract_markdownFile(@TempDir Path tempDir) throws Exception {
        Path mdFile = tempDir.resolve("notes.md");
        Files.writeString(mdFile, """
            # My Notes

            ## JWT Authentication
            The JWT flow involves three steps:
            1. Client sends credentials
            2. Server returns signed token
            3. Client includes token in subsequent requests
            """, StandardCharsets.UTF_8);

        TextExtractor.ExtractionResult result = extractor.extract(mdFile);

        assertFalse(result.isEmpty());
        assertTrue(result.text().contains("JWT"), "Should contain JWT content");
        assertTrue(result.text().contains("Authentication"));
    }

    @Test
    void extract_csvFile(@TempDir Path tempDir) throws Exception {
        Path csvFile = tempDir.resolve("data.csv");
        Files.writeString(csvFile, """
            name,score,grade
            Alice,95,A
            Bob,82,B
            """, StandardCharsets.UTF_8);

        TextExtractor.ExtractionResult result = extractor.extract(csvFile);
        assertFalse(result.isEmpty(), "Should extract CSV content");
        assertTrue(result.text().contains("Alice"), "Extracted text should contain CSV data");
    }

    @Test
    void extract_htmlFile(@TempDir Path tempDir) throws Exception {
        Path htmlFile = tempDir.resolve("page.html");
        Files.writeString(htmlFile, """
            <html><body>
            <h1>Caching Strategies</h1>
            <p>We evaluated Redis and Memcached for our backend caching layer.</p>
            </body></html>
            """, StandardCharsets.UTF_8);

        TextExtractor.ExtractionResult result = extractor.extract(htmlFile);
        assertFalse(result.isEmpty());
        // HTML is read as plain text (fast path) — content is preserved including tags
        // In production you would post-process HTML via Jsoup if needed, but for
        // semantic search the raw HTML is still semantically searchable
        assertTrue(result.text().contains("Caching Strategies"),
            "Should contain the heading text");
        assertTrue(result.text().contains("Redis"),
            "Should contain body content");
        assertEquals("text/html", result.mimeType());
    }

    @Test
    void extract_returnsCorrectMimeType(@TempDir Path tempDir) throws Exception {
        Path mdFile = tempDir.resolve("readme.md");
        Files.writeString(mdFile, "# Hello World", StandardCharsets.UTF_8);

        TextExtractor.ExtractionResult result = extractor.extract(mdFile);
        assertEquals("text/markdown", result.mimeType());
    }
}

package com.localfilebrain.ingestion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link PdfPageLocator} end-to-end against a real PDF generated with
 * PDFBox, so we test the same extraction path indexing uses — not a mock.
 */
class PdfPageLocatorTest {

    @Test
    void placesChunksOnTheCorrectPage(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("multi.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, "Alpha page one discusses apples and oranges in considerable depth here.");
            addPage(doc, "Bravo page two covers bicycles helmets and general road safety regulations.");
            addPage(doc, "Charlie page three explains clouds rainfall and seasonal weather patterns.");
            doc.save(pdf.toFile());
        }

        PdfPageLocator loc = PdfPageLocator.forFile(pdf);
        assertNotNull(loc, "locator should build for a digital PDF");

        assertArrayEquals(new int[]{1, 1},
                loc.locate("Alpha page one discusses apples and oranges in considerable depth here."));
        assertArrayEquals(new int[]{2, 2},
                loc.locate("Bravo page two covers bicycles helmets and general road safety regulations."));
        assertArrayEquals(new int[]{3, 3},
                loc.locate("Charlie page three explains clouds rainfall and seasonal weather patterns."));
    }

    @Test
    void reportsARangeForAChunkSpanningTwoPages(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("span.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, "Alpha page one discusses apples and oranges in considerable depth here.");
            addPage(doc, "Bravo page two covers bicycles helmets and general road safety regulations.");
            doc.save(pdf.toFile());
        }
        PdfPageLocator loc = PdfPageLocator.forFile(pdf);
        assertNotNull(loc);

        int[] span = loc.locate(
                "oranges in considerable depth here. Bravo page two covers bicycles helmets");
        assertEquals(1, span[0], "span should start on page 1");
        assertEquals(2, span[1], "span should end on page 2");
    }

    @Test
    void returnsNullForUnplaceableOrNonPdf(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("one.pdf");
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, "Alpha page one discusses apples and oranges in considerable depth here.");
            doc.save(pdf.toFile());
        }
        PdfPageLocator loc = PdfPageLocator.forFile(pdf);
        assertNotNull(loc);
        assertNull(loc.locate("zxqw vbnm plok ijuh tgrf edcs waqp mnbv lkjh poiu ytre"),
                "content not in the document should not be placed");

        Path txt = dir.resolve("notes.txt");
        Files.writeString(txt, "just some text");
        assertNull(PdfPageLocator.forFile(txt), "non-PDF should have no locator");
    }

    @Test
    void normalizeStripsWhitespaceAndPunctuation() {
        assertEquals("abc123", PdfPageLocator.normalize("  A b.C-1!2#3  "));
        assertEquals("", PdfPageLocator.normalize(null));
    }

    private static void addPage(PDDocument doc, String text) throws Exception {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA, 12);
            cs.newLineAtOffset(50, 700);
            cs.showText(text);
            cs.endText();
        }
    }
}

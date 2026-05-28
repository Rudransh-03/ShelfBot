package com.localfilebrain.reorg;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ExtensionFamilyTest {

    @Test
    void extensionOf_extractsLowercase() {
        assertEquals("pdf",  ExtensionFamily.extensionOf("Resume.PDF"));
        assertEquals("docx", ExtensionFamily.extensionOf("tax.DOCX"));
        assertEquals("jpg",  ExtensionFamily.extensionOf("IMG_2847.JPG"));
        assertEquals("",     ExtensionFamily.extensionOf("README"));
        assertEquals("",     ExtensionFamily.extensionOf(".dotfile"));
    }

    @Test
    void of_mapsKnownExtensions() {
        assertEquals(ExtensionFamily.Family.DOCS,   ExtensionFamily.of("pdf"));
        assertEquals(ExtensionFamily.Family.DOCS,   ExtensionFamily.of("docx"));
        assertEquals(ExtensionFamily.Family.IMAGES, ExtensionFamily.of("jpg"));
        assertEquals(ExtensionFamily.Family.IMAGES, ExtensionFamily.of("HEIC"));   // case-insensitive
        assertEquals(ExtensionFamily.Family.VIDEO,  ExtensionFamily.of("mp4"));
        assertEquals(ExtensionFamily.Family.OTHER,  ExtensionFamily.of("xyz"));
        assertEquals(ExtensionFamily.Family.OTHER,  ExtensionFamily.of(""));
    }

    @Test
    void compatible_sameFamily() {
        assertTrue(ExtensionFamily.compatible("a.pdf", "b.docx"));   // both DOCS
        assertTrue(ExtensionFamily.compatible("a.jpg", "b.png"));    // both IMAGES
        assertFalse(ExtensionFamily.compatible("a.pdf", "b.jpg"));   // DOCS vs IMAGES
        assertFalse(ExtensionFamily.compatible("a.mp4", "b.mp3"));   // VIDEO vs AUDIO
    }

    @Test
    void compatible_otherFamily_onlyExactMatch() {
        // Both unknown but same ext → compatible
        assertTrue(ExtensionFamily.compatible("a.xyz", "b.xyz"));
        // Both unknown, different ext → NOT compatible (OTHER doesn't collapse)
        assertFalse(ExtensionFamily.compatible("a.xyz", "b.abc"));
        // Known vs unknown → never compatible
        assertFalse(ExtensionFamily.compatible("a.pdf", "b.xyz"));
    }

    @Test
    void compatibleWithAny_acceptsIfAnyMember() {
        Set<String> folder = Set.of("notes.pdf", "data.csv");
        assertTrue(ExtensionFamily.compatibleWithAny("essay.docx", folder));   // matches notes.pdf (DOCS)
        assertTrue(ExtensionFamily.compatibleWithAny("table.xlsx", folder));   // matches data.csv (SHEETS)
        assertFalse(ExtensionFamily.compatibleWithAny("pic.png", folder));     // matches neither
    }
}

package com.localfilebrain.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileScannerTest {

    // -------------------------------------------------------------------------
    // Extension extraction tests
    // -------------------------------------------------------------------------

    @Test
    void getExtension_normalFile() {
        assertEquals("pdf",  FileScanner.getExtension("report.pdf"));
        assertEquals("docx", FileScanner.getExtension("notes.docx"));
        assertEquals("txt",  FileScanner.getExtension("readme.txt"));
        assertEquals("md",   FileScanner.getExtension("README.MD"));
    }

    @Test
    void getExtension_uppercase() {
        assertEquals("pdf", FileScanner.getExtension("REPORT.PDF"));
        assertEquals("docx", FileScanner.getExtension("Notes.DOCX"));
    }

    @Test
    void getExtension_noExtension() {
        assertEquals("", FileScanner.getExtension("Makefile"));
        assertEquals("", FileScanner.getExtension("README"));
    }

    @Test
    void getExtension_multiDot() {
        // Only the last extension matters
        assertEquals("gz", FileScanner.getExtension("archive.tar.gz"));
        assertEquals("bak", FileScanner.getExtension("config.properties.bak"));
    }

    @Test
    void getExtension_hiddenFile() {
        assertEquals("gitignore", FileScanner.getExtension(".gitignore"));
    }

    @Test
    void getExtension_trailingDot() {
        assertEquals("", FileScanner.getExtension("file."));
    }
}

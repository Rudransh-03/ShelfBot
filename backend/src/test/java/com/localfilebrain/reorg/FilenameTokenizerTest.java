package com.localfilebrain.reorg;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class FilenameTokenizerTest {

    // -------------------------------------------------------------------------
    // meaningfulTokens — strip the noise
    // -------------------------------------------------------------------------

    @Test
    void aadharCard_yieldsSemanticTokens() {
        Set<String> t = FilenameTokenizer.meaningfulTokens("AADHAR_FRONT_JPG.jpg");
        assertTrue(t.contains("aadhar"));
        assertTrue(t.contains("front"));
        assertFalse(t.contains("jpg"), "extension-like leftover must be filtered as noise");
    }

    @Test
    void screenshotFilename_yieldsEmptySet() {
        // This is the case the user hit. macOS-style screenshot filenames
        // are entirely metadata (the word 'Screenshot' + a date + a time).
        // None of that should count as semantic content.
        Set<String> t = FilenameTokenizer.meaningfulTokens(
                "Screenshot 2026-04-13 at 12.51.07 AM.png");
        assertTrue(t.isEmpty(),
                "macOS screenshot filenames must produce zero meaningful tokens — got " + t);
    }

    @Test
    void imgFilename_yieldsEmptySet() {
        assertTrue(FilenameTokenizer.meaningfulTokens("IMG_0001.jpg").isEmpty());
        assertTrue(FilenameTokenizer.meaningfulTokens("DSC04567.JPG").isEmpty());
    }

    @Test
    void dateWithinFilenameIsFilteredOut() {
        Set<String> t = FilenameTokenizer.meaningfulTokens("tax_return_2024.pdf");
        assertTrue(t.contains("tax"));
        assertTrue(t.contains("return"));
        assertFalse(t.contains("2024"), "year-like 4-digit token must be filtered");
    }

    @Test
    void digitLetterBoundarySplitsTokens() {
        Set<String> t = FilenameTokenizer.meaningfulTokens("Invoice2023Acme.pdf");
        assertTrue(t.contains("invoice"));
        assertTrue(t.contains("acme"));
        assertFalse(t.contains("2023"));
    }

    @Test
    void stopwordsAndStateWordsAreFiltered() {
        Set<String> t = FilenameTokenizer.meaningfulTokens("the_final_copy_of_resume.pdf");
        assertTrue(t.contains("resume"));
        assertFalse(t.contains("the"));
        assertFalse(t.contains("final"));
        assertFalse(t.contains("copy"));
        assertFalse(t.contains("of"));    // < min len anyway, double-safe
    }

    // -------------------------------------------------------------------------
    // shareMeaningfulToken — the gate used during clustering
    // -------------------------------------------------------------------------

    @Test
    void aadharVsScreenshot_doesNotShareToken() {
        // This is the headline regression test for the user's bug.
        assertFalse(FilenameTokenizer.shareMeaningfulToken(
                "AADHAR_FRONT_JPG.jpg",
                "Screenshot 2026-04-13 at 12.51.07 AM.png"));
    }

    @Test
    void resumeAndResumeTemplate_shareToken() {
        assertTrue(FilenameTokenizer.shareMeaningfulToken(
                "Rudransh_Resume_05_26.pdf",
                "resume_Template_61e6298661.docx"));
    }

    @Test
    void twoPhotosWithSameSubject_shareToken() {
        assertTrue(FilenameTokenizer.shareMeaningfulToken(
                "vacation_beach.jpg",
                "vacation_sunset.jpg"));
    }

    @Test
    void twoGenericImgFiles_doNotShareToken() {
        // Both files have no meaningful tokens after the noise filter —
        // even though "IMG" is a shared lexical prefix, it's noise.
        assertFalse(FilenameTokenizer.shareMeaningfulToken(
                "IMG_0001.jpg",
                "IMG_0002.jpg"));
    }

    @Test
    void taxReturnsAcrossYears_shareToken() {
        assertTrue(FilenameTokenizer.shareMeaningfulToken(
                "tax_return_2022.pdf",
                "tax_form_2024.pdf"));
    }

    @Test
    void emptyOrNullInputs_returnFalse() {
        assertFalse(FilenameTokenizer.shareMeaningfulToken(null, "anything.pdf"));
        assertFalse(FilenameTokenizer.shareMeaningfulToken("", "anything.pdf"));
        assertFalse(FilenameTokenizer.shareMeaningfulToken("anything.pdf", ""));
    }
}

package com.localfilebrain.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the deterministic safety net that strips spurious "I could not find …"
 * postscripts from the LLM's answer. This is what guarantees a real response
 * isn't mangled by the model occasionally appending the refusal sentence as
 * a footer.
 */
class RefusalPostscriptTest {

    @Test
    void stripsPostscriptAfterRealAnswer() {
        String input =
            "From Rudransh_Resume_05_26.pdf:\n" +
            "- Software Development Engineer, Sprinklr (Aug2024 - Present)\n\n" +
            "I could not find relevant information in your files.";
        String out = GPT4oMiniClient.stripRefusalPostscript(input);
        assertFalse(out.contains("could not find"),
                "postscript must be stripped when it follows a real answer");
        assertTrue(out.contains("Sprinklr"));
    }

    @Test
    void keepsGenuineRefusal() {
        String input = "I could not find relevant information in your files.";
        String out = GPT4oMiniClient.stripRefusalPostscript(input);
        assertEquals(input, out, "a standalone refusal must be preserved");
    }

    @Test
    void handlesVariantWording() {
        String input =
            "From notes.md:\n- something useful\n\n" +
            "I cannot find relevant information in your files.";
        String out = GPT4oMiniClient.stripRefusalPostscript(input);
        assertFalse(out.contains("cannot find"));
        assertTrue(out.contains("something useful"));
    }

    @Test
    void handlesCouldntContraction() {
        String input =
            "From notes.md:\n- here is a fact\n\n" +
            "I couldn't find relevant information in your files.";
        String out = GPT4oMiniClient.stripRefusalPostscript(input);
        assertFalse(out.contains("couldn't find"));
        assertTrue(out.contains("here is a fact"));
    }

    @Test
    void leavesAnswerUntouchedWhenNoPostscript() {
        String input = "From a.pdf:\n- bullet";
        String out = GPT4oMiniClient.stripRefusalPostscript(input);
        assertEquals(input, out);
    }

    @Test
    void nullSafe() {
        assertNull(GPT4oMiniClient.stripRefusalPostscript(null));
    }

    // ── stripInternalJargon: "the excerpts" must never reach the user ────────

    @Test
    void jargon_leadingFromTheExcerpts_rewritten() {
        String out = GPT4oMiniClient.stripInternalJargon(
                "From the excerpts, Rohan Mehta is a graphic designer.");
        assertEquals("From your files, Rohan Mehta is a graphic designer.", out);
    }

    @Test
    void jargon_sentenceStart_recapitalized() {
        String out = GPT4oMiniClient.stripInternalJargon(
                "The excerpts do not mention a car insurance premium.");
        assertEquals("Your files do not mention a car insurance premium.", out);
    }

    @Test
    void jargon_providedVariants_rewritten() {
        assertEquals("Based on your files, the rent is 40,000.",
                GPT4oMiniClient.stripInternalJargon("Based on the provided excerpts, the rent is 40,000."));
        assertEquals("Your files show two resumes.",
                GPT4oMiniClient.stripInternalJargon("these excerpts provided show two resumes."));
    }

    @Test
    void jargon_cleanAnswerAndFilenamesUntouched() {
        String input = "From AcmeCorp-Invoice-330.pdf: the total due is 10,62,000.";
        assertEquals(input, GPT4oMiniClient.stripInternalJargon(input));
        assertNull(GPT4oMiniClient.stripInternalJargon(null));
    }
}

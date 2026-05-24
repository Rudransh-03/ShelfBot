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
}

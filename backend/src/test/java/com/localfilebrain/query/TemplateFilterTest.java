package com.localfilebrain.query;

import com.localfilebrain.storage.VectorStore.SearchResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the code-side template/sample filename filter that runs before the
 * LLM is invoked. This is the safety net that guarantees template files
 * never leak into entity-specific answers — prompt-only filtering was
 * unreliable so we now enforce it before sending chunks to the model.
 *
 * Uses reflection because filterTemplatesIfNotAsked is intentionally
 * package-private — it's an internal implementation detail of QueryEngine
 * and not part of the public API.
 */
class TemplateFilterTest {

    private static List<SearchResult> invoke(QueryEngine engine, List<SearchResult> matches, String question) throws Exception {
        Method m = QueryEngine.class.getDeclaredMethod(
                "filterTemplatesIfNotAsked", List.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SearchResult> result = (List<SearchResult>) m.invoke(engine, matches, question);
        return result;
    }

    // Build a SearchResult without needing a real config — only fields the
    // filter looks at matter.
    private static SearchResult chunk(String fileName) {
        return new SearchResult("id-" + fileName, "/p/" + fileName, fileName, 0, "body", 0.5);
    }

    private static QueryEngine bareEngine() {
        // We don't actually need a working engine for this — but the
        // filter method needs an instance to invoke against. Build the
        // bare-minimum instance without loading config or network.
        try {
            // Use the no-arg sun.misc.Unsafe-style allocate trick via reflection.
            sun.misc.Unsafe unsafe = unsafe();
            return (QueryEngine) unsafe.allocateInstance(QueryEngine.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    @Test
    void filtersOutTemplateNamedFilesForEntityQuery() throws Exception {
        QueryEngine eng = bareEngine();
        List<SearchResult> input = List.of(
                chunk("Rudransh_Resume_05_26.pdf"),
                chunk("resume_Template_61e6298661.docx"),
                chunk("notes.md")
        );
        List<SearchResult> out = invoke(eng, input, "work experience of rudransh?");
        List<String> kept = out.stream().map(SearchResult::fileName).toList();
        assertTrue(kept.contains("Rudransh_Resume_05_26.pdf"));
        assertTrue(kept.contains("notes.md"));
        assertFalse(kept.contains("resume_Template_61e6298661.docx"),
                "Template file MUST be filtered out for a person-specific query");
    }

    @Test
    void keepsTemplateWhenQuestionMentionsTemplateKeyword() throws Exception {
        QueryEngine eng = bareEngine();
        List<SearchResult> input = List.of(
                chunk("Rudransh_Resume_05_26.pdf"),
                chunk("resume_Template_61e6298661.docx")
        );
        List<SearchResult> out = invoke(eng, input, "what is in my resume template?");
        List<String> kept = out.stream().map(SearchResult::fileName).toList();
        assertTrue(kept.contains("resume_Template_61e6298661.docx"),
                "Template MUST be kept when the question is about templates");
    }

    @Test
    void keepsTemplateWhenUserNamesItExplicitly() throws Exception {
        QueryEngine eng = bareEngine();
        List<SearchResult> input = List.of(
                chunk("Rudransh_Resume_05_26.pdf"),
                chunk("resume_Template_61e6298661.docx")
        );
        // User explicitly references the template file by its actual filename.
        List<SearchResult> out = invoke(eng, input,
                "show me content from resume_Template_61e6298661.docx");
        List<String> kept = out.stream().map(SearchResult::fileName).toList();
        assertTrue(kept.contains("resume_Template_61e6298661.docx"),
                "Explicitly-named template file MUST be kept");
    }

    @Test
    void doesNotMatchSubstringsInsideLongerWords() throws Exception {
        QueryEngine eng = bareEngine();
        // "Examples" or "Samplers" might contain template-keyword substrings.
        // The regex uses word boundaries so they should NOT trigger the filter.
        List<SearchResult> input = List.of(
                chunk("Examples_of_my_writing.pdf"),  // starts with 'examples' → matches
                chunk("trampoline.md"),               // contains 'lor' but not 'lorem' → must not match
                chunk("mySamplePack.docx")            // contains 'sample' → matches
        );
        List<SearchResult> out = invoke(eng, input, "what are my favorite books?");
        List<String> kept = out.stream().map(SearchResult::fileName).toList();
        assertTrue(kept.contains("trampoline.md"),
                "filename without a template keyword as a separate word MUST be kept");
    }

    @Test
    void noTemplatesToFilterIsANoOp() throws Exception {
        QueryEngine eng = bareEngine();
        List<SearchResult> input = List.of(
                chunk("a.pdf"), chunk("b.docx"), chunk("c.txt")
        );
        List<SearchResult> out = invoke(eng, input, "anything");
        assertEquals(input.size(), out.size());
    }

    @Test
    void preservesOrder() throws Exception {
        QueryEngine eng = bareEngine();
        List<SearchResult> input = List.of(
                chunk("a.pdf"),
                chunk("resume_template_x.docx"),  // dropped
                chunk("b.pdf"),
                chunk("c.pdf")
        );
        List<SearchResult> out = invoke(eng, input, "what is in my files?");
        List<String> kept = out.stream().map(SearchResult::fileName).toList();
        assertEquals(List.of("a.pdf", "b.pdf", "c.pdf"), kept,
                "filter must preserve the relative order of kept chunks");
    }
}

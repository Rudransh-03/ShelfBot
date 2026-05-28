package com.localfilebrain.reorg;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class ToolPromptsTest {

    // -------------------------------------------------------------------------
    // Prompt shape
    // -------------------------------------------------------------------------

    @Test
    void systemPrompts_containJsonMarker_forOpenAiJsonMode() {
        // OpenAI's response_format=json_object refuses if "json" is missing
        // from the prompt. Both system prompts must satisfy this.
        assertTrue(ToolPrompts.nameClusterSystem().toLowerCase().contains("json"),
                "nameClusterSystem must mention JSON to satisfy json-mode contract");
        assertTrue(ToolPrompts.judgeFileSystem().toLowerCase().contains("json"),
                "judgeFileSystem must mention JSON to satisfy json-mode contract");
    }

    @Test
    void nameClusterUser_listsAllProvidedFilenames() {
        String prompt = ToolPrompts.nameClusterUserFromFilenames(List.of(
                "tax_2022.pdf", "tax_2023.pdf", "irs_1099.pdf"));
        assertTrue(prompt.contains("tax_2022.pdf"));
        assertTrue(prompt.contains("tax_2023.pdf"));
        assertTrue(prompt.contains("irs_1099.pdf"));
    }

    @Test
    void nameClusterUser_includesContentPreviewsWhenPresent() {
        String prompt = ToolPrompts.nameClusterUser(List.of(
                new ToolPrompts.FileInput("resume.pdf",       "Rudransh Tyagi — Software Engineer"),
                new ToolPrompts.FileInput("resume_template.docx", "FirstName M. LastName | email@email.com")));
        assertTrue(prompt.contains("resume.pdf"));
        assertTrue(prompt.contains("Rudransh Tyagi"));
        assertTrue(prompt.contains("resume_template.docx"));
        assertTrue(prompt.contains("FirstName"));
    }

    @Test
    void nameClusterUser_marksMissingPreviewsExplicitly() {
        // Filename-only file → "no readable content" marker so the LLM
        // knows to weigh the filename more cautiously.
        String prompt = ToolPrompts.nameClusterUser(List.of(
                new ToolPrompts.FileInput("notes.pdf", "real content here"),
                new ToolPrompts.FileInput("Mystery.pages", "")));
        assertTrue(prompt.contains("notes.pdf"));
        assertTrue(prompt.contains("real content here"));
        assertTrue(prompt.contains("Mystery.pages"));
        assertTrue(prompt.toLowerCase().contains("no readable content"),
                "missing-preview marker must appear so the model knows to be cautious");
    }

    @Test
    void judgeLonersBatchUser_includesContentPreviews() {
        String prompt = ToolPrompts.judgeLonersBatchUser(List.of(
                new ToolPrompts.FileInput("aadhaar.jpg", "Government of India — Aadhaar — DOB 03/01/2002"),
                new ToolPrompts.FileInput("discord_codes.txt", "These are your Discord backup codes")
        ), List.of());
        assertTrue(prompt.contains("aadhaar.jpg"));
        assertTrue(prompt.contains("Government of India"));
        assertTrue(prompt.contains("discord_codes.txt"));
        assertTrue(prompt.contains("Discord backup codes"));
    }

    @Test
    void judgeFileUser_handlesEmptyFolderList() {
        String prompt = ToolPrompts.judgeFileUser("loose.pdf", List.of());
        assertTrue(prompt.contains("loose.pdf"));
        assertTrue(prompt.toLowerCase().contains("no existing folders"),
                "must tell the model there are no folders to choose from");
    }

    @Test
    void judgeFileUser_listsFoldersWithSampleFiles() {
        String prompt = ToolPrompts.judgeFileUser("x.pdf", List.of(
                new ToolPrompts.ExistingFolderContext("Tax", List.of("a.pdf", "b.pdf")),
                new ToolPrompts.ExistingFolderContext("Photos", List.of())
        ));
        assertTrue(prompt.contains("Tax"));
        assertTrue(prompt.contains("a.pdf"));
        assertTrue(prompt.contains("b.pdf"));
        assertTrue(prompt.contains("Photos"));
    }

    // -------------------------------------------------------------------------
    // parseClusterNaming
    // -------------------------------------------------------------------------

    @Test
    void parseClusterNaming_happyPath() {
        Optional<ToolPrompts.ClusterNaming> result = ToolPrompts.parseClusterNaming(
                "{\"name\":\"Tax Documents\",\"confidence\":0.92,\"reason\":\"all tax-related\"}");
        assertTrue(result.isPresent());
        assertEquals("Tax Documents", result.get().name());
        assertEquals(0.92f, result.get().confidence(), 1e-4);
        assertEquals("all tax-related", result.get().reason());
    }

    @Test
    void parseClusterNaming_missingNameReturnsEmpty() {
        assertTrue(ToolPrompts.parseClusterNaming("{\"confidence\":0.9}").isEmpty());
        assertTrue(ToolPrompts.parseClusterNaming("{\"name\":\"\"}").isEmpty());
    }

    @Test
    void parseClusterNaming_malformedJsonReturnsEmpty() {
        assertTrue(ToolPrompts.parseClusterNaming("not json").isEmpty());
        assertTrue(ToolPrompts.parseClusterNaming("").isEmpty());
    }

    @Test
    void parseClusterNaming_clampsConfidenceOutOfRange() {
        assertEquals(0f, ToolPrompts.parseClusterNaming(
                "{\"name\":\"X\",\"confidence\":-1.5}").orElseThrow().confidence(), 1e-6);
        assertEquals(1f, ToolPrompts.parseClusterNaming(
                "{\"name\":\"X\",\"confidence\":2.7}").orElseThrow().confidence(), 1e-6);
    }

    // -------------------------------------------------------------------------
    // parseFileJudgment
    // -------------------------------------------------------------------------

    @Test
    void parseFileJudgment_existing() {
        ToolPrompts.FileJudgment j = ToolPrompts.parseFileJudgment(
                "{\"placement\":\"EXISTING:Tax\",\"confidence\":0.85,\"reason\":\"matches\"}")
                .orElseThrow();
        assertEquals(ToolPrompts.FileJudgment.Placement.EXISTING_FOLDER, j.placement());
        assertEquals("Tax", j.folderName());
        assertEquals(0.85f, j.confidence(), 1e-4);
    }

    @Test
    void parseFileJudgment_newFolder() {
        ToolPrompts.FileJudgment j = ToolPrompts.parseFileJudgment(
                "{\"placement\":\"NEW:Photos\",\"confidence\":0.78,\"reason\":\"image cluster\"}")
                .orElseThrow();
        assertEquals(ToolPrompts.FileJudgment.Placement.NEW_FOLDER, j.placement());
        assertEquals("Photos", j.folderName());
    }

    @Test
    void parseFileJudgment_leave() {
        ToolPrompts.FileJudgment j = ToolPrompts.parseFileJudgment(
                "{\"placement\":\"LEAVE\",\"confidence\":0.3,\"reason\":\"unclear\"}")
                .orElseThrow();
        assertEquals(ToolPrompts.FileJudgment.Placement.LEAVE_ALONE, j.placement());
        assertNull(j.folderName());
    }

    @Test
    void parseFileJudgment_unknownPlacement_downgradesToLeave() {
        ToolPrompts.FileJudgment j = ToolPrompts.parseFileJudgment(
                "{\"placement\":\"PURGE:Stuff\",\"confidence\":0.9}")
                .orElseThrow();
        assertEquals(ToolPrompts.FileJudgment.Placement.LEAVE_ALONE, j.placement(),
                "unknown placement must downgrade to LEAVE so we never accidentally move a file");
        assertEquals(0.0f, j.confidence(), 1e-6,
                "unknown placement is treated as low confidence");
    }

    @Test
    void parseFileJudgment_emptyFolderInExistingReturnsEmpty() {
        // "EXISTING:" with no folder name is malformed
        assertTrue(ToolPrompts.parseFileJudgment(
                "{\"placement\":\"EXISTING:\",\"confidence\":0.9}").isEmpty());
        assertTrue(ToolPrompts.parseFileJudgment(
                "{\"placement\":\"NEW:\",\"confidence\":0.9}").isEmpty());
    }

    @Test
    void parseFileJudgment_missingPlacementReturnsEmpty() {
        assertTrue(ToolPrompts.parseFileJudgment("{\"confidence\":0.5}").isEmpty());
    }

    @Test
    void parseFileJudgment_malformedJsonReturnsEmpty() {
        assertTrue(ToolPrompts.parseFileJudgment("garbage").isEmpty());
    }

    // -------------------------------------------------------------------------
    // judge_loners (batched) — prompts and parser
    // -------------------------------------------------------------------------

    @Test
    void judgeLonersBatchSystem_mentionsJsonAndCrossFormatGrouping() {
        String s = ToolPrompts.judgeLonersBatchSystem();
        assertTrue(s.toLowerCase().contains("json"));
        // The whole point of this prompt is to teach the model the
        // grouping trick — verify the system prompt actually says so.
        assertTrue(s.toLowerCase().contains("group") || s.toLowerCase().contains("same")
                || s.toLowerCase().contains("cross-format") || s.toLowerCase().contains("share"),
                "system prompt must explain the cross-format grouping mechanic");
    }

    @Test
    void parseLonerBatch_happyPathWithCrossFormatGroup() {
        String json = """
            {"decisions":[
              {"file":"AADHAR_FRONT.jpg","placement":"NEW:Identity Documents","confidence":0.9,"reason":"ID"},
              {"file":"PAN_CARD.pdf",    "placement":"NEW:Identity Documents","confidence":0.85,"reason":"ID"},
              {"file":"random.png",      "placement":"LEAVE",                 "confidence":0.2,"reason":"unclear"}
            ]}""";
        var out = ToolPrompts.parseLonerBatch(json,
                List.of("AADHAR_FRONT.jpg", "PAN_CARD.pdf", "random.png"));
        assertEquals(3, out.size());
        // Both ID files share the EXACT same folder name → same group downstream.
        assertEquals("Identity Documents", out.get("AADHAR_FRONT.jpg").folderName());
        assertEquals("Identity Documents", out.get("PAN_CARD.pdf").folderName());
        assertEquals(ToolPrompts.FileJudgment.Placement.LEAVE_ALONE,
                out.get("random.png").placement());
    }

    @Test
    void parseLonerBatch_dropsUnknownFilenames() {
        String json = """
            {"decisions":[
              {"file":"hallucinated.pdf","placement":"NEW:Junk","confidence":0.9},
              {"file":"real.pdf",        "placement":"LEAVE",   "confidence":0.5}
            ]}""";
        var out = ToolPrompts.parseLonerBatch(json, List.of("real.pdf"));
        assertEquals(1, out.size());
        assertFalse(out.containsKey("hallucinated.pdf"),
                "model-invented filenames must be ignored");
        assertTrue(out.containsKey("real.pdf"));
    }

    @Test
    void parseLonerBatch_missingDecisionsBecomeImplicitLeave() {
        // Model only returned 1 of 3 — others are implicitly absent from the map.
        String json = """
            {"decisions":[
              {"file":"a.pdf","placement":"LEAVE","confidence":0.5}
            ]}""";
        var out = ToolPrompts.parseLonerBatch(json, List.of("a.pdf", "b.pdf", "c.pdf"));
        assertEquals(1, out.size());
        assertTrue(out.containsKey("a.pdf"));
        // b.pdf and c.pdf intentionally not in map — caller treats them as LEAVE.
    }

    @Test
    void parseLonerBatch_handlesMalformedJsonGracefully() {
        assertTrue(ToolPrompts.parseLonerBatch("not json", List.of("a.pdf")).isEmpty());
        assertTrue(ToolPrompts.parseLonerBatch("", List.of("a.pdf")).isEmpty());
        assertTrue(ToolPrompts.parseLonerBatch("{\"decisions\":\"wrong shape\"}",
                List.of("a.pdf")).isEmpty());
    }

    @Test
    void parseLonerBatch_dedupesDuplicateFilenames() {
        // Same file mentioned twice — first wins.
        String json = """
            {"decisions":[
              {"file":"x.pdf","placement":"NEW:First","confidence":0.9},
              {"file":"x.pdf","placement":"NEW:Second","confidence":0.4}
            ]}""";
        var out = ToolPrompts.parseLonerBatch(json, List.of("x.pdf"));
        assertEquals(1, out.size());
        assertEquals("First", out.get("x.pdf").folderName());
    }
}

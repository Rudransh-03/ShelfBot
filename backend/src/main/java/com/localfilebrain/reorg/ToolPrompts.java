package com.localfilebrain.reorg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Prompt builders and JSON parsers for the two reorg tools:
 * {@code name_cluster} and {@code judge_file}.
 *
 * <p>Kept as a pure-static utility so the prompt texts can be unit-tested
 * without spinning anything up. Each builder produces a system prompt + a
 * user prompt; each parser turns a model JSON response into a typed
 * result (or {@link Optional#empty()} if the response was malformed).
 *
 * <p>JSON mode contract: OpenAI's {@code response_format=json_object}
 * requires the prompt to contain the literal substring "JSON". Both
 * system prompts here are written to satisfy that.
 */
public final class ToolPrompts {

    private static final Logger log = LoggerFactory.getLogger(ToolPrompts.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on filename samples per cluster — keeps prompts cheap and focused. */
    public static final int MAX_CLUSTER_SAMPLES = 5;

    // -------------------------------------------------------------------------
    // name_cluster
    // -------------------------------------------------------------------------

    public static String nameClusterSystem() {
        return """
            You are a file-organization assistant. Given a small set of files
            (filenames + short content previews) that already belong together,
            propose a short, descriptive folder name for them — and review
            whether they really do belong together.

            Reply with valid JSON only:
              {"name": "...", "confidence": 0.0-1.0, "reason": "..."}

            Folder-name rules:
              - 1-3 words, Title Case (e.g. "Tax Documents", "Trip Photos").
              - Generic enough to fit similar future files. Avoid years/quarters
                like "Tax 2024 Q3".
              - No path separators, no trailing slash, no file extensions.

            Confidence guidance — judge primarily on CONTENT, secondarily on
            filename. The question is "would the user expect these files to
            live in one folder together?":
              - 0.9+ when content + filenames clearly agree on a single theme
                (same topic, same kind of document, same project, same
                person/event). A real document plus its template still
                belongs together — so does a draft plus its final version.
              - 0.7 when most files share a clear theme and one is slightly
                off-pattern.
              - 0.5 or below when the connection is genuinely thin or the
                content previews disagree with each other. In that case
                name them "Mixed" — the caller will treat low confidence
                as a signal to LEAVE these files alone.

            CRITICAL: if the previews show the files are about clearly
            different topics (e.g. one is an Aadhaar ID, another is a
            Discord backup, another is a job-listing screenshot), return
            confidence below 0.5 even if asked to name them. Don't invent
            a shared theme to cover unrelated content.
            """;
    }

    public static String nameClusterUser(List<FileInput> samples) {
        StringBuilder sb = new StringBuilder("These files clustered together. Review them and name the folder:\n");
        for (FileInput f : samples) {
            sb.append("  - ").append(f.filename()).append('\n');
            String snippet = f.contentPreview();
            if (snippet == null || snippet.isBlank()) {
                sb.append("    preview: (no readable content; judge by filename)\n");
            } else {
                sb.append("    preview: ").append(snippet).append('\n');
            }
        }
        sb.append("\nWhat should this folder be named? Lower the confidence if the previews don't actually agree.");
        return sb.toString();
    }

    /** Convenience: filename-only inputs (no content previews available). */
    public static String nameClusterUserFromFilenames(List<String> sampleFilenames) {
        return nameClusterUser(sampleFilenames.stream().map(n -> new FileInput(n, "")).toList());
    }

    /**
     * Parses a {@code name_cluster} response. Returns empty if the JSON is
     * malformed or {@code name} is missing — caller should treat that as
     * "cluster unnamed, fall back to a placeholder".
     */
    public static Optional<ClusterNaming> parseClusterNaming(String jsonText) {
        try {
            JsonNode root = MAPPER.readTree(jsonText);
            String name = root.path("name").asText("").trim();
            if (name.isEmpty()) return Optional.empty();
            float  confidence = clampConfidence(root.path("confidence").floatValue());
            String reason     = root.path("reason").asText("").trim();
            return Optional.of(new ClusterNaming(name, confidence, reason));
        } catch (Exception e) {
            log.debug("Failed to parse cluster-naming JSON ({}): {}", e.getMessage(), preview(jsonText));
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // judge_file
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // judge_loners (batched)
    // -------------------------------------------------------------------------

    public static String judgeLonersBatchSystem() {
        return """
            You are a file-organization assistant. Given a BATCH of files —
            each with its filename and a short CONTENT PREVIEW (the first
            chunk of indexed text) — and a list of nearby existing folders,
            decide where each file should go.

            JUDGE PRIMARILY BY CONTENT. The preview tells you what the file
            actually is (an Aadhaar card scan, a resume, a Discord backup
            codes dump, a visa checklist, etc.). The filename is a hint but
            can be misleading. Two files only belong together when their
            CONTENTS describe the same kind of thing.

            Reply with valid JSON only:
              {"decisions": [
                {"file":"<exact filename>", "placement":"...", "confidence":0.0-1.0, "reason":"..."}
              ]}

            "placement" values:
              - "EXISTING:<folder name>"  — file clearly fits one of the listed
                existing folders. Use the folder's exact name.
              - "NEW:<folder name>"       — file belongs in a new folder you
                propose. Use 1-3 words, Title Case.
              - "LEAVE"                   — file doesn't strongly belong anywhere.
                Use this when unsure or when the file stands alone.

            HOW TO GROUP: when two or more files share a real-world theme
            based on their CONTENT, give them the EXACT SAME "NEW:<folder>"
            string. Same string = same folder = files placed together. Only
            propose a NEW group of size ≥ 2 when the previews actually agree
            on what each file is about.

            WHAT NOT TO GROUP — the easy mistakes you must avoid:
              - DO NOT group two files just because they are both images,
                both PDFs, both screenshots, or share a file format.
              - DO NOT group two files just because they mention the same
                personal name, email, or date in their previews — these
                are incidental, not semantic categories.
              - DO NOT group files whose filenames share only generic tokens
                like "copy", "final", "new", "doc", "screenshot", "img".
              - DO NOT invent a forced theme to cover unrelated files.
                Folder names like "Miscellaneous", "Mixed", "Files", "Stuff",
                "Personal", "Documents", "Other" are a signal you should
                return LEAVE for those files instead of inventing a group.
              - If you can't state in plain English in 5–8 words what
                SPECIFICALLY two files have in common (using their previews
                as evidence), the connection is too thin — return LEAVE.

            Themes that CAN be cross-format groups (use only when each file's
            preview clearly fits the theme):
              - Identity / government ID  (passport, driver's license, Aadhaar,
                PAN, SSN, voter card).
              - Financial paperwork  (tax returns, bank statements, invoices,
                receipts, pay stubs).
              - Travel paperwork  (tickets, boarding passes, itineraries,
                visa checklists).
              - Job-application materials  (resume, cover letter, portfolio).
              - Medical records  (prescriptions, reports, insurance cards).

            Single-file placements: if exactly ONE file in this batch fits
            a NEW theme by itself, you may still propose NEW:<folder> for
            it — but only when the file's preview leaves no doubt about the
            theme. Otherwise prefer LEAVE.

            Confidence guidance:
              - 0.85+ when the preview leaves no doubt about the placement.
              - 0.70-0.85 when the placement is reasonable and supported by
                content.
              - Below 0.70 → return LEAVE instead. When in doubt, LEAVE.

            Be conservative. A file in the wrong folder is worse than a file
            left in place. Include one decision for every file in the input.
            """;
    }

    public static String judgeLonersBatchUser(List<FileInput> files,
                                              List<ExistingFolderContext> folders) {
        StringBuilder sb = new StringBuilder("Files to place (filename + content preview):\n");
        for (FileInput f : files) {
            sb.append("  - ").append(f.filename()).append('\n');
            String snippet = f.contentPreview();
            if (snippet == null || snippet.isBlank()) {
                // No content extracted (filename-only file). Let the LLM
                // judge from the filename when it strongly signals a
                // category — but don't push it toward LEAVE by default,
                // that's how legitimate groupings of unindexed files got
                // dropped on the floor.
                sb.append("    preview: (no readable content; the filename is your only signal — "
                       + "use it confidently when it clearly names a known category)\n");
            } else {
                sb.append("    preview: ").append(snippet).append('\n');
            }
        }
        if (folders.isEmpty()) {
            sb.append("\nThere are no existing folders nearby. Use NEW:<name> or LEAVE.");
        } else {
            sb.append("\nExisting folders in the directory:\n");
            for (var f : folders) {
                sb.append("  - ").append(f.name());
                if (!f.sampleFiles().isEmpty()) {
                    sb.append(" (contains: ");
                    sb.append(String.join(", ", f.sampleFiles()));
                    sb.append(")");
                }
                sb.append('\n');
            }
        }
        sb.append("\nDecide each file. Group only when previews clearly agree.");
        return sb.toString();
    }

    /** Convenience: legacy filename-only entry (no content previews available). */
    public static String judgeLonersBatchUserFromFilenames(List<String> filenames,
                                                           List<ExistingFolderContext> folders) {
        return judgeLonersBatchUser(
                filenames.stream().map(n -> new FileInput(n, "")).toList(),
                folders);
    }

    /**
     * Parses the batched response. Returns judgments keyed by filename.
     * Robust to truncation, missing decisions, or unknown filenames — only
     * decisions for files present in {@code expectedFilenames} are kept;
     * absent files end up as no-entry (the caller treats them as
     * implicit LEAVE).
     */
    public static java.util.Map<String, FileJudgment> parseLonerBatch(
            String jsonText, java.util.Collection<String> expectedFilenames) {
        java.util.Map<String, FileJudgment> out = new java.util.LinkedHashMap<>();
        try {
            JsonNode root = MAPPER.readTree(jsonText);
            JsonNode arr  = root.path("decisions");
            if (!arr.isArray()) return out;

            java.util.Set<String> known = new java.util.HashSet<>(expectedFilenames);

            for (JsonNode entry : arr) {
                String file = entry.path("file").asText("").trim();
                if (file.isEmpty()) continue;
                if (!known.contains(file)) continue;        // LLM hallucination filter
                if (out.containsKey(file))  continue;        // duplicate — keep first

                String placementStr = entry.path("placement").asText("").trim();
                if (placementStr.isEmpty()) continue;

                float  confidence = clampConfidence(entry.path("confidence").floatValue());
                String reason     = entry.path("reason").asText("").trim();

                FileJudgment j = decodePlacement(placementStr, confidence, reason);
                if (j != null) out.put(file, j);
            }
        } catch (Exception e) {
            log.debug("Failed to parse loner-batch JSON ({}): {}", e.getMessage(), preview(jsonText));
        }
        return out;
    }

    /** Shared placement decoder used by both the per-file and batched paths. */
    private static FileJudgment decodePlacement(String placementStr, float confidence, String reason) {
        if (placementStr.startsWith("EXISTING:")) {
            String folder = placementStr.substring("EXISTING:".length()).trim();
            if (folder.isEmpty()) return null;
            return new FileJudgment(FileJudgment.Placement.EXISTING_FOLDER, folder, confidence, reason);
        }
        if (placementStr.startsWith("NEW:")) {
            String folder = placementStr.substring("NEW:".length()).trim();
            if (folder.isEmpty()) return null;
            return new FileJudgment(FileJudgment.Placement.NEW_FOLDER, folder, confidence, reason);
        }
        if (placementStr.equalsIgnoreCase("LEAVE")) {
            return new FileJudgment(FileJudgment.Placement.LEAVE_ALONE, null, confidence, reason);
        }
        // Unknown placement → downgrade to LEAVE with low confidence so the
        // file definitely stays put.
        return new FileJudgment(FileJudgment.Placement.LEAVE_ALONE, null, 0.0f,
                "Unrecognized placement value: " + placementStr);
    }

    public static String judgeFileSystem() {
        return """
            You are a file-organization assistant. Decide where ONE file
            should be placed: into an existing folder, into a new folder of
            its own, or left alone.

            Reply with valid JSON only:
              {"placement": "...", "confidence": 0.0-1.0, "reason": "..."}

            "placement" must be exactly one of:
              - "EXISTING:<folder>" — file clearly belongs in one of the listed
                existing folders. Use the folder's exact name as shown.
              - "NEW:<folder>"      — file doesn't fit any listed folder, but
                its filename suggests its own clear theme that justifies a
                new 1-3-word Title-Case folder.
              - "LEAVE"             — file doesn't strongly belong anywhere;
                leave it in place. Prefer this when unsure.

            Be conservative: if confidence would be below 0.7, choose LEAVE.
            Moving a file to the wrong place is worse than not moving it.
            """;
    }

    public static String judgeFileUser(String filename, List<ExistingFolderContext> folders) {
        StringBuilder sb = new StringBuilder("File: ").append(filename).append('\n');
        if (folders.isEmpty()) {
            sb.append("\nThere are no existing folders nearby. Choose NEW:<name> or LEAVE.");
        } else {
            sb.append("\nExisting folders in the directory:\n");
            for (var f : folders) {
                sb.append("  - ").append(f.name());
                if (!f.sampleFiles().isEmpty()) {
                    sb.append(" (contains: ");
                    sb.append(String.join(", ", f.sampleFiles()));
                    sb.append(")");
                }
                sb.append('\n');
            }
        }
        sb.append("\nWhere should this file go?");
        return sb.toString();
    }

    /**
     * Parses a {@code judge_file} response. Returns empty for malformed
     * input. Unknown placement prefixes are normalized to LEAVE_ALONE
     * with low confidence — silently downgrading is safer than throwing
     * mid-loop.
     */
    public static Optional<FileJudgment> parseFileJudgment(String jsonText) {
        try {
            JsonNode root = MAPPER.readTree(jsonText);
            String placementStr = root.path("placement").asText("").trim();
            if (placementStr.isEmpty()) return Optional.empty();

            float  confidence = clampConfidence(root.path("confidence").floatValue());
            String reason     = root.path("reason").asText("").trim();

            if (placementStr.startsWith("EXISTING:")) {
                String folder = placementStr.substring("EXISTING:".length()).trim();
                if (folder.isEmpty()) return Optional.empty();
                return Optional.of(new FileJudgment(
                        FileJudgment.Placement.EXISTING_FOLDER, folder, confidence, reason));
            }
            if (placementStr.startsWith("NEW:")) {
                String folder = placementStr.substring("NEW:".length()).trim();
                if (folder.isEmpty()) return Optional.empty();
                return Optional.of(new FileJudgment(
                        FileJudgment.Placement.NEW_FOLDER, folder, confidence, reason));
            }
            if (placementStr.equalsIgnoreCase("LEAVE")) {
                return Optional.of(new FileJudgment(
                        FileJudgment.Placement.LEAVE_ALONE, null, confidence, reason));
            }

            // Unknown prefix — treat as LEAVE with low confidence so the
            // file definitely stays put and the UI can show the reason
            // for human review.
            log.debug("Unknown placement value '{}'; downgrading to LEAVE", placementStr);
            return Optional.of(new FileJudgment(
                    FileJudgment.Placement.LEAVE_ALONE, null, 0.0f,
                    "Unrecognized placement value: " + placementStr));

        } catch (Exception e) {
            log.debug("Failed to parse file-judgment JSON ({}): {}", e.getMessage(), preview(jsonText));
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    public record ClusterNaming(String name, float confidence, String reason) {}

    public record FileJudgment(
            Placement placement,
            String folderName,   // null for LEAVE_ALONE
            float confidence,
            String reason
    ) {
        public enum Placement { EXISTING_FOLDER, NEW_FOLDER, LEAVE_ALONE }
    }

    /** A nearby existing folder, with a few sample filenames for context. */
    public record ExistingFolderContext(String name, List<String> sampleFiles) {}

    /**
     * One file's input to the LLM tool prompts: its display filename and
     * an optional short text preview (first ~200–320 chars of its
     * indexed content). Empty {@code contentPreview} signals "no readable
     * content extracted" — the LLM is told to judge by filename only and
     * to prefer LEAVE in that case.
     */
    public record FileInput(String filename, String contentPreview) {}

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static float clampConfidence(float c) {
        if (Float.isNaN(c)) return 0.0f;
        if (c < 0f) return 0f;
        if (c > 1f) return 1f;
        return c;
    }

    private static String preview(String s) {
        if (s == null) return "<null>";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private ToolPrompts() {}
}

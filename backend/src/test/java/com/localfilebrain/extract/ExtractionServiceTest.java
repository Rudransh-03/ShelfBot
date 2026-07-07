package com.localfilebrain.extract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionServiceTest {

    private static final List<ExtractionField> SCHEMA = List.of(new ExtractionField("Answer", FieldType.TEXT));

    private static List<ExtractionService.FileRef> files(int n) {
        List<ExtractionService.FileRef> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(new ExtractionService.FileRef("f" + i + ".pdf", "/docs/f" + i + ".pdf"));
        return out;
    }

    // Fake model: echoes a row for every "Document id=N" in the prompt, optionally
    // omitting a chosen id (to simulate a partial failure).
    private static StructuredExtractionEngine.LlmCall echo(String value, int omitId) {
        Pattern p = Pattern.compile("Document id=(\\d+)");
        return (sys, user) -> {
            Matcher m = p.matcher(user);
            StringBuilder sb = new StringBuilder("{\"rows\":[");
            boolean first = true;
            while (m.find()) {
                int id = Integer.parseInt(m.group(1));
                if (id == omitId) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"doc\":").append(id).append(",\"fields\":{\"Answer\":\"").append(value).append("\"}}");
            }
            return sb.append("]}").toString();
        };
    }

    private static ExtractionService svc() {
        // content seam: every file has some non-empty content
        return new ExtractionService(path -> List.of("content of " + path));
    }

    @Test
    void batchesAcrossManyDocuments_oneRowPerFile_inOrder() {
        AtomicInteger batches = new AtomicInteger();
        var svc = svc();
        var r = svc.run(files(25), SCHEMA, ExtractionOptions.defaults(), echo("yes", -1),
                (proc, total) -> batches.incrementAndGet(), () -> false);
        assertEquals(25, r.rows().size());
        assertFalse(r.cancelled());
        assertEquals(0, r.truncatedBatches());
        // 25 docs / 3 per batch → 9 batches (ceil), one progress tick each.
        assertEquals(9, batches.get());
        for (int i = 0; i < 25; i++) {
            assertEquals("f" + (i + 1) + ".pdf", r.rows().get(i).fileName());
            assertEquals("/docs/f" + (i + 1) + ".pdf", r.rows().get(i).absolutePath());
            assertEquals("yes", r.rows().get(i).fields().get("Answer").value());
        }
    }

    @Test
    void partialFailure_omittedDocGetsMissingRow() {
        var r = svc().run(files(12), SCHEMA, ExtractionOptions.defaults(), echo("ok", 5),
                ExtractionService.ProgressListener.NOOP, () -> false);
        assertEquals(12, r.rows().size(), "every selected file must appear exactly once");
        assertTrue(r.rows().get(4).fields().get("Answer").isMissing(), "doc 5 (omitted) → MISSING");
        assertEquals("ok", r.rows().get(0).fields().get("Answer").value());
    }

    @Test
    void unreadableBatch_yieldsAllMissing_andCounts() {
        var r = svc().run(files(15), SCHEMA, ExtractionOptions.defaults(), (s, u) -> "not json at all",
                ExtractionService.ProgressListener.NOOP, () -> false);
        assertEquals(15, r.rows().size());
        assertTrue(r.rows().stream().allMatch(x -> x.fields().get("Answer").isMissing()));
        assertEquals(5, r.truncatedBatches()); // 15 docs / 3 per batch → 5 batches
    }

    @Test
    void cancellation_stopsAtBatchBoundary_returnsPartial() {
        AtomicInteger processed = new AtomicInteger();
        var r = svc().run(files(25), SCHEMA, ExtractionOptions.defaults(), echo("yes", -1),
                (proc, total) -> processed.set(proc),
                () -> processed.get() >= 3);  // cancel once the first batch (3 docs) is done
        assertTrue(r.cancelled());
        assertEquals(3, r.rows().size(), "only the first completed batch is returned");
    }

    @Test
    void contentLookupThatThrows_doesNotFailTheRun() {
        ExtractionService svc = new ExtractionService(path -> {
            if (path.endsWith("f2.pdf")) throw new RuntimeException("index error");
            return List.of("content");
        });
        var r = svc.run(files(3), SCHEMA, ExtractionOptions.defaults(), echo("ans", -1),
                ExtractionService.ProgressListener.NOOP, () -> false);
        assertEquals(3, r.rows().size(), "a per-file content error must not drop the file");
    }

    @Test
    void duplicateFilenames_keptDistinctByPath() {
        List<ExtractionService.FileRef> fr = List.of(
                new ExtractionService.FileRef("invoice.pdf", "/a/invoice.pdf"),
                new ExtractionService.FileRef("invoice.pdf", "/b/invoice.pdf"));
        var r = svc().run(fr, SCHEMA, ExtractionOptions.defaults(), echo("x", -1),
                ExtractionService.ProgressListener.NOOP, () -> false);
        assertEquals(2, r.rows().size());
        assertEquals("/a/invoice.pdf", r.rows().get(0).absolutePath());
        assertEquals("/b/invoice.pdf", r.rows().get(1).absolutePath());
    }

    @Test
    void emptySelection_returnsEmpty() {
        var r = svc().run(List.of(), SCHEMA, ExtractionOptions.defaults(), echo("x", -1),
                ExtractionService.ProgressListener.NOOP, () -> false);
        assertTrue(r.rows().isEmpty());
        assertFalse(r.cancelled());
    }
}

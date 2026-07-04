package com.localfilebrain.attention;

import com.localfilebrain.deadline.MissingDocumentDetector.MissingDoc;
import com.localfilebrain.ingestion.IndexMetadataStore.DateRow;
import com.localfilebrain.ingestion.IndexMetadataStore.DeadlineRow;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the "Needs attention" list — strictly TODAY's action items: things due
 * today, overdue obligations, and likely-missing documents in recurring series.
 * Anything due later than today is deliberately excluded — the Deadlines tab is
 * the holistic forward view; this panel answers only "what needs me right now".
 *
 * <p>Deterministic and 100% local: it merges rows the extraction layers already
 * wrote (timeline {@code document_dates}, PENDING Pro deadlines, missing-doc
 * detection) — no LLM call, so it can never hallucinate an item. Every item
 * carries its source file so the user can verify with one click.
 *
 * <p>Merge rules:
 * <ul>
 *   <li>Only PENDING deadlines count (DONE/DISMISSED are the user saying
 *       "handled"); deadlines without a parsable date are skipped — an undated
 *       item can't be ranked by urgency and would just be noise here.</li>
 *   <li>A timeline date is dropped when a deadline exists for the same file +
 *       date (the deadline row is richer and user-curated via the review flow).</li>
 *   <li>Every item has a stable {@code id} the user can dismiss by, so a
 *       handled item never resurfaces and the list can't grow forever.
 *       Date/missing dismissals live in {@code attention_dismissed}; a
 *       deadline item is dismissed by setting its row DISMISSED (which also
 *       clears it from the Deadlines tab — one source of truth).</li>
 *   <li>Order: due-today first, then overdue (most recently missed first),
 *       then missing documents.</li>
 * </ul>
 */
public final class AttentionBuilder {

    /**
     * How far back an overdue item still "needs attention" — mirrors
     * {@link com.localfilebrain.deadline.DeadlineMaintenance#MISSED_GRACE_DAYS}.
     * A first index of an old archive extracts obligation dates years in the
     * past; those are history, not action items, and without this bound they'd
     * flood the panel.
     */
    public static final int OVERDUE_LOOKBACK_DAYS =
            com.localfilebrain.deadline.DeadlineMaintenance.MISSED_GRACE_DAYS;

    /** One attention item. {@code id} is the stable dismissal key;
     *  {@code kind} is "deadline" | "date" | "missing";
     *  date/daysUntil/fileName/path are null for "missing" items. */
    public record Item(String id, String kind, String bucket, String title, String detail,
                       String date, Long daysUntil, String fileName, String path,
                       String docType) {}

    public record Result(List<Item> items, int overdue, int dueToday, int missing) {
        public int total() { return items.size(); }
    }

    private AttentionBuilder() {}

    public static Result build(List<DateRow> dates, List<DeadlineRow> deadlines,
                               List<MissingDoc> missingDocs, Set<String> dismissedKeys,
                               LocalDate today) {
        if (dismissedKeys == null) dismissedKeys = Set.of();
        List<Item> dueToday = new ArrayList<>();
        List<Item> overdue  = new ArrayList<>();
        Set<String> covered = new HashSet<>(); // "path|date" keys already emitted

        // 1. Pro deadlines (user-curated, richer) take precedence. Dismissal for
        //    these is the deadline row's own status, not the dismissed-keys set.
        if (deadlines != null) {
            for (DeadlineRow d : deadlines) {
                if (!"PENDING".equals(d.status())) continue;
                LocalDate due = parse(d.dueDate());
                if (due == null) continue;
                long days = ChronoUnit.DAYS.between(today, due);
                if (days > 0) continue;                      // due later → the Deadlines tab's job
                if (days < -OVERDUE_LOOKBACK_DAYS) continue; // ancient history — not actionable
                covered.add(key(d.absolutePath(), d.dueDate()));
                String detail = notBlank(d.description()) ? d.description() : d.sourceExcerpt();
                Item it = new Item("deadline:" + d.id(), "deadline",
                        days < 0 ? "OVERDUE" : "DUE_TODAY",
                        d.title(), detail, d.dueDate(), days,
                        d.fileName(), d.absolutePath(), null);
                (days < 0 ? overdue : dueToday).add(it);
            }
        }

        // 2. Free local timeline dates, skipping any the deadlines already cover.
        if (dates != null) {
            for (DateRow r : dates) {
                LocalDate due = parse(r.eventDate());
                if (due == null) continue;
                long days = ChronoUnit.DAYS.between(today, due);
                if (days > 0) continue;                      // due later → the Deadlines tab's job
                if (days < -OVERDUE_LOOKBACK_DAYS) continue; // ancient history — not actionable
                String id = "date:" + key(r.absolutePath(), r.eventDate());
                if (dismissedKeys.contains(id)) continue;    // user already handled this
                if (!covered.add(key(r.absolutePath(), r.eventDate()))) continue;
                Item it = new Item(id, "date", days < 0 ? "OVERDUE" : "DUE_TODAY",
                        r.title(), r.sourceExcerpt(), r.eventDate(), days,
                        r.fileName(), r.absolutePath(),
                        r.docType() != null ? r.docType() : null);
                (days < 0 ? overdue : dueToday).add(it);
            }
        }

        dueToday.sort(Comparator.comparing(Item::daysUntil));               // stable
        overdue.sort(Comparator.comparing(Item::daysUntil).reversed());     // most recent first

        // 3. Possibly-missing documents (no date — appended last).
        List<Item> missing = new ArrayList<>();
        if (missingDocs != null) {
            for (MissingDoc d : missingDocs) {
                String id = "missing:" + d.series() + "|" + d.periodLabel();
                if (dismissedKeys.contains(id)) continue;    // user already handled this
                missing.add(new Item(id, "missing", "MISSING",
                        missingTitle(d), missingDetail(d),
                        null, null, null, null, null));
            }
        }

        List<Item> items = new ArrayList<>(dueToday.size() + overdue.size() + missing.size());
        items.addAll(dueToday);
        items.addAll(overdue);
        items.addAll(missing);
        return new Result(items, overdue.size(), dueToday.size(), missing.size());
    }

    private static String missingTitle(MissingDoc d) {
        String who = notBlank(d.issuer()) ? d.issuer() + " " : "";
        return who + d.series() + " for " + d.periodLabel() + " may be missing";
    }

    private static String missingDetail(MissingDoc d) {
        return "Other periods of this " + d.cadence() + " series are in your library ("
                + d.presentCount() + " found) — this one wasn't.";
    }

    private static String key(String path, String date) { return path + "|" + date; }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static LocalDate parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return LocalDate.parse(iso.trim()); }
        catch (Exception e) { return null; }
    }
}

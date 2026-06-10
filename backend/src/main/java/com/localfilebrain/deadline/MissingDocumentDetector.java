package com.localfilebrain.deadline;

import com.localfilebrain.ingestion.IndexMetadataStore.SeriesRow;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds gaps in recurring document series — e.g. "GST return" present for Jan
 * and Mar but not Feb → flags Feb as possibly missing.
 *
 * Pure local logic over the {@link SeriesRow}s the scan classified (no LLM, no
 * I/O). Deliberately conservative to avoid false alarms:
 *   - groups by (series, issuer) so two banks' statements don't mix;
 *   - only flags INTERIOR gaps, strictly between the earliest and latest period
 *     a series actually has — so a period you simply haven't downloaded yet
 *     (after the latest one) is NEVER flagged;
 *   - infers the cadence from the finest gap actually observed, so a genuine
 *     bi-monthly/quarterly series isn't mistaken for one with holes;
 *   - confidence scales with how much evidence there is (2 periods = LOW,
 *     3 = MEDIUM, 4+ = HIGH), and the UI phrases it as "may be missing".
 */
public final class MissingDocumentDetector {

    /** Minimum periods present before we'll claim anything is missing. */
    private static final int MIN_PRESENT = 2;
    /** Skip a single gap that would imply more than this many missing periods —
     *  beyond this it's more likely the cadence is wrong than that N in a row are
     *  all missing, so staying silent is the accurate choice. */
    private static final int MAX_MISSING_PER_GAP = 4;
    /** Overall cap per series so one odd group can't spam the list. */
    private static final int MAX_MISSING_PER_SERIES = 8;

    private enum Granularity { MONTH, QUARTER, YEAR }

    /** One parsed period as a comparable integer index plus its display parts. */
    private record Period(Granularity g, int index, int year, int unit) {}

    /**
     * One gap in a recurring series.
     *
     * @param series      the recurring type, e.g. "GST return"
     * @param issuer      the organisation, or null
     * @param periodLabel human label of the missing period, e.g. "February 2024"
     * @param cadence     "monthly" | "quarterly" | "yearly"
     * @param confidence  "HIGH" | "MEDIUM" | "LOW"
     * @param presentCount how many periods of this series are present
     */
    public record MissingDoc(String series, String issuer, String periodLabel,
                             String cadence, String confidence, int presentCount) {}

    private static final Pattern P_MONTH   = Pattern.compile("^(\\d{4})-(\\d{1,2})$");
    private static final Pattern P_QUARTER = Pattern.compile("^(\\d{4})-[Qq]([1-4])$");
    private static final Pattern P_YEAR    = Pattern.compile("^(\\d{4})$");

    private MissingDocumentDetector() {}

    public static List<MissingDoc> detect(List<SeriesRow> rows) {
        List<MissingDoc> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) return out;

        // Group by (normalized series, normalized issuer).
        Map<String, List<SeriesRow>> groups = new LinkedHashMap<>();
        for (SeriesRow r : rows) {
            if (r.series() == null || r.period() == null) continue;
            String key = norm(r.series()) + "|" + norm(r.issuer());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        for (List<SeriesRow> group : groups.values()) {
            out.addAll(detectGroup(group));
        }
        return out;
    }

    private static List<MissingDoc> detectGroup(List<SeriesRow> group) {
        List<MissingDoc> out = new ArrayList<>();

        // Parse periods, keep the majority granularity, dedupe by index.
        Map<Granularity, TreeSet<Integer>> byGran = new LinkedHashMap<>();
        Map<Integer, Period> sample = new LinkedHashMap<>();
        for (SeriesRow r : group) {
            Period p = parse(r.period());
            if (p == null) continue;
            byGran.computeIfAbsent(p.g(), k -> new TreeSet<>()).add(p.index());
            sample.putIfAbsent(p.index() + p.g().ordinal() * 1_000_000, p);
        }
        if (byGran.isEmpty()) return out;

        // Dominant granularity wins (mixed labels are rare; minority is dropped).
        Granularity gran = null;
        int best = -1;
        for (var e : byGran.entrySet()) {
            if (e.getValue().size() > best) { best = e.getValue().size(); gran = e.getKey(); }
        }
        List<Integer> idx = new ArrayList<>(byGran.get(gran));
        int present = idx.size();
        if (present < MIN_PRESENT) return out;

        // Cadence step = the finest gap actually observed (you can't have a gap
        // smaller than the true cadence). With only two periods we can't observe
        // the cadence, so we assume the finest unit (step 1) and report LOW.
        int step;
        if (present >= 3) {
            int min = Integer.MAX_VALUE;
            for (int i = 1; i < idx.size(); i++) min = Math.min(min, idx.get(i) - idx.get(i - 1));
            step = Math.max(1, min);
        } else {
            step = 1;
        }

        String confidence = present >= 4 ? "HIGH" : present == 3 ? "MEDIUM" : "LOW";
        String series = firstNonNull(group.get(0).series(), "document");
        String issuer = group.get(0).issuer();
        String cadence = cadenceLabel(gran, step);

        int emitted = 0;
        for (int i = 1; i < idx.size() && emitted < MAX_MISSING_PER_SERIES; i++) {
            int a = idx.get(i - 1), b = idx.get(i);
            int span = b - a;
            if (span <= step) continue;              // consecutive at this cadence → no gap
            if (span % step != 0) continue;          // not aligned to the cadence → don't guess
            int missing = span / step - 1;
            if (missing <= 0 || missing > MAX_MISSING_PER_GAP) continue; // too speculative
            for (int m = a + step; m < b && emitted < MAX_MISSING_PER_SERIES; m += step) {
                out.add(new MissingDoc(series, issuer, label(gran, m), cadence, confidence, present));
                emitted++;
            }
        }
        return out;
    }

    // ── period parsing / formatting ────────────────────────────────────────

    private static Period parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        Matcher m;
        if ((m = P_MONTH.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            if (mo < 1 || mo > 12) return null;
            return new Period(Granularity.MONTH, y * 12 + (mo - 1), y, mo);
        }
        if ((m = P_QUARTER.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            int q = Integer.parseInt(m.group(2));
            return new Period(Granularity.QUARTER, y * 4 + (q - 1), y, q);
        }
        if ((m = P_YEAR.matcher(s)).matches()) {
            int y = Integer.parseInt(m.group(1));
            return new Period(Granularity.YEAR, y, y, 0);
        }
        return null;
    }

    private static String label(Granularity g, int index) {
        return switch (g) {
            case MONTH -> {
                int y = Math.floorDiv(index, 12);
                int mo = Math.floorMod(index, 12) + 1;
                yield Month.of(mo).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + y;
            }
            case QUARTER -> {
                int y = Math.floorDiv(index, 4);
                int q = Math.floorMod(index, 4) + 1;
                yield "Q" + q + " " + y;
            }
            case YEAR -> String.valueOf(index);
        };
    }

    private static String cadenceLabel(Granularity g, int step) {
        return switch (g) {
            case MONTH   -> step == 3 ? "quarterly" : step == 12 ? "yearly" : "monthly";
            case QUARTER -> "quarterly";
            case YEAR    -> "yearly";
        };
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String firstNonNull(String a, String b) { return a != null ? a : b; }
}

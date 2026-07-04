package com.localfilebrain.timeline;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.NewDate;
import com.localfilebrain.model.FileRecord;
import com.localfilebrain.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free, local, LLM-free extraction of OBLIGATION dates (due dates, expiries,
 * renewals, filing/response deadlines) from already-indexed document text.
 * Populates the {@code document_dates} table that powers the Timeline view —
 * for EVERY user, at zero run-cost — exactly as {@link com.localfilebrain.client.LocalEntityScanner}
 * makes client-identity detection free rather than Pro-only.
 *
 * <p>High precision by design: a date is recorded only when an obligation
 * trigger word (due / expires / renewal / filing / response / deadline) sits
 * just before it. Plain issue/pay dates with no trigger are ignored, so the
 * Timeline shows only things that need the user's attention — not every date
 * that appears in a document.
 */
public final class LocalDateScanner {

    private static final Logger log = LoggerFactory.getLogger(LocalDateScanner.class);

    // Obligation dates live near the top of these documents; the first ~20k
    // chars is far more than enough and keeps the scan cheap on huge PDFs.
    private static final int MAX_SCAN_CHARS = 20_000;
    // How far back from a date we look for an obligation trigger word.
    private static final int TRIGGER_WINDOW = 60;

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3),
            Map.entry("apr", 4), Map.entry("may", 5), Map.entry("jun", 6),
            Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9),
            Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    private static final String MONTH_RE =
            "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|"
          + "aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

    // "04 April 2024" / "28 February 2024" / "5th March 2026"
    private static final Pattern DMY = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH_RE + "\\.?,?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    // "April 4, 2024" / "April 4 2024"
    private static final Pattern MDY = Pattern.compile(
            "\\b" + MONTH_RE + "\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    // "2024-04-04"
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    // "04/04/2024" or "04-04-2024" or "04.04.2024" — assumed DD/MM/YYYY (Indian)
    private static final Pattern NUMERIC = Pattern.compile("\\b(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})\\b");

    /** Obligation trigger → friendly Timeline title. Order = priority (specific first). */
    private record Trigger(Pattern pattern, String title) {}
    private static final List<Trigger> TRIGGERS = List.of(
            t("payment\\s+due|amount\\s+due|pay\\s+(?:by|before)", "Payment due"),
            t("filing\\s+due(?:\\s+date)?|file\\s+by|last\\s+date(?:\\s+for\\s+filing)?"
            + "|(?:must|shall|should|to)\\s+be\\s+filed\\s+(?:on\\s+or\\s+)?before", "Filing due"),
            t("renewal(?:\\s+review)?(?:\\s+due)?|renew\\s+(?:by|before)", "Renewal"),
            t("expir(?:es|y|ing|ation)|valid\\s+(?:until|till|upto|up\\s+to)|lapses?", "Expires"),
            t("response\\s+(?:required|due)|respond\\s+by|reply\\s+by", "Response due"),
            t("deadline", "Deadline"),
            t("due\\s+(?:by|date|on)|due:", "Due"));

    // Completion guard: if, BETWEEN the trigger and the date, the text says the
    // obligation was already fulfilled ("renewal completed on…", "was paid on…",
    // "already filed"), the date is history, not something the user must act on.
    // Requires past-tense context ("was/has been/already …" or "<verb> on" — but
    // NOT "<verb> on or before", which is future-obligation phrasing), so real
    // deadlines like "to be filed on or before 20 April" are never suppressed.
    private static final Pattern COMPLETED_GUARD = Pattern.compile(
            "\\b(?:was|were|has\\s+been|have\\s+been|already|been)\\s+"
          + "(?:paid|settled|completed|received|cleared|renewed|filed|submitted|made|done)\\b"
          + "|\\b(?:paid|settled|completed|received|cleared|renewed|filed|submitted)\\s+on\\b(?!\\s+or\\b)",
            Pattern.CASE_INSENSITIVE);

    // Completed-event LABEL guard: when the text introducing the date is a
    // record-keeping label ("Date of filing : 28-06-2026" on an ITR
    // acknowledgment, "Submitted on", "Payment date" on a receipt), the date is
    // something that already HAPPENED — even if an obligation trigger word sits
    // earlier in the window. "due/last date of filing" stays an obligation
    // (the lookbehinds), and "filed on or before" is future phrasing (the $
    // anchor never matches it because "or before …" follows).
    private static final Pattern COMPLETED_LABEL = Pattern.compile(
            "(?:(?<!due\\s)(?<!last\\s)\\b(?:date\\s+of\\s+(?:filing|submission|payment|receipt)"
          + "|filing\\s+date|submission\\s+date|payment\\s+date|receipt\\s+date)"
          + "|\\b(?:filed|submitted|paid|received|issued|generated)\\s+on)"
          + "\\s*[:\\-–—]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // Reject years outside a sane document window — OCR noise ("31/12/9999"),
    // serial numbers, and pre-2000 archival dates aren't actionable obligations.
    private static final int MIN_YEAR = 2000, MAX_YEAR = 2099;

    // Bumped whenever extraction rules change: it salts the per-file scan marker,
    // so every file re-scans ONCE under the new rules (free + local) instead of
    // keeping dates extracted by an older, less accurate version.
    // v3: also extracts each file's PRIMARY date (issue/period) into file_index.
    // v4: template/sample files are skipped — clears any junk dates a template's
    //     placeholder text produced under earlier versions.
    // v5: completed-event labels ("Date of filing :", "Submitted on") no longer
    //     count as obligations, and excerpts are sentence-bounded, not blind
    //     byte slices — re-extract so stored rows pick up both.
    private static final String SCAN_VERSION = "v5:";

    // ── Primary-date extraction (the document's OWN date, for period queries) ──

    // Month-name + year in a normalized file name ("jan 2024", "february 2024").
    private static final Pattern NAME_MONTH_YEAR = Pattern.compile(
            "\\b" + MONTH_RE + "\\.?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    // Numeric year-month in a file name ("2024-01", "2024 01" after normalizing).
    private static final Pattern NAME_YEAR_MONTH = Pattern.compile(
            "\\b(20\\d{2})\\s*[-_. ]\\s*(\\d{1,2})\\b");
    // Content cues that introduce the document's own date, in priority order.
    private static final Pattern CONTENT_DATE_CUE = Pattern.compile(
            "(?:invoice\\s+date|statement\\s+period|return\\s+period|bill\\s+month|"
          + "pay\\s+date|date\\s+of\\s+issue|issued\\s+on|issued|dated|date)\\s*[:\\-]?\\s*",
            Pattern.CASE_INSENSITIVE);
    // Month-year without a day in content ("March 2024") → 1st of that month.
    private static final Pattern CONTENT_MONTH_YEAR = Pattern.compile(
            "\\b" + MONTH_RE + "\\.?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    // Only the opening of a document carries its own date reliably.
    private static final int PRIMARY_DATE_SCAN_CHARS = 2_000;

    private final IndexMetadataStore meta;
    private final VectorStore        vectorStore;

    public LocalDateScanner(IndexMetadataStore meta, VectorStore vectorStore) {
        this.meta        = meta;
        this.vectorStore = vectorStore;
    }

    /**
     * Scans every indexed file not already scanned at its current content hash,
     * extracting obligation dates and storing them. Returns the number of files
     * a date was found in.
     */
    public int scanAll() {
        int filesWithDates = 0;
        for (FileRecord f : meta.listIndexedFilesBySizeDesc()) {
            String path = f.getAbsolutePath();
            // Version-salted marker: a rules change (SCAN_VERSION bump) invalidates
            // every old marker, so existing libraries re-extract once for free.
            String hash = SCAN_VERSION + f.getContentHash();
            if (meta.isDateScanned(path, hash)) continue;
            // A template/sample document's placeholder dates are not real
            // obligations — record an empty scan so the Timeline and the
            // Needs-attention panel never surface them.
            if (com.localfilebrain.util.TemplateFiles.isTemplateName(f.getFileName())) {
                meta.replaceDatesForFile(path, f.getFileName(), hash, List.of());
                meta.setPrimaryDate(path, extractPrimaryDate(f.getFileName(), null));
                continue;
            }
            try {
                StringBuilder sb = new StringBuilder();
                for (VectorStore.SearchResult c : vectorStore.getChunksForFile(path)) {
                    if (c.text() != null) sb.append(c.text()).append('\n');
                    if (sb.length() >= MAX_SCAN_CHARS) break;
                }
                String text = sb.toString();
                List<NewDate> events = extractEvents(text);
                meta.replaceDatesForFile(path, f.getFileName(), hash, events);
                // Same pass, same text: the document's own primary date (issue/
                // period) → deterministic "documents from <month>" answers.
                meta.setPrimaryDate(path, extractPrimaryDate(f.getFileName(), text));
                if (!events.isEmpty()) filesWithDates++;
            } catch (Exception e) {
                log.debug("date scan skipped {}: {}", path, e.getMessage());
            }
        }
        log.info("Local date scan: obligation dates found in {} file(s)", filesWithDates);
        return filesWithDates;
    }

    /**
     * The document's own PRIMARY date (issue/period date), as ISO yyyy-MM-dd, or
     * null when none can be found confidently. Priority:
     * 1. Month+year in the FILE NAME ("…-Salary-Slip-Jan2024.pdf" is the January
     *    slip no matter when it was paid) — the strongest user-facing label.
     * 2. The first date after a content cue ("Invoice date:", "issued", "dated",
     *    "Statement period:", "Return period:", …).
     * 3. The first parseable date in the opening text.
     * Month-year forms without a day resolve to the 1st.
     */
    public static String extractPrimaryDate(String fileName, String text) {
        // 1. File name label.
        if (fileName != null) {
            String norm = fileName
                    .replaceAll("([a-z])([A-Z])", "$1 $2")
                    .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                    .replaceAll("([0-9])([A-Za-z])", "$1 $2")
                    .replaceAll("[-_./]+", " ");
            Matcher m = NAME_MONTH_YEAR.matcher(norm);
            if (m.find()) {
                LocalDate d = monthYear(m.group(1), m.group(2));
                if (d != null) return d.toString();
            }
            Matcher ym = NAME_YEAR_MONTH.matcher(norm);
            if (ym.find()) {
                try {
                    int yr = Integer.parseInt(ym.group(1)), mo = Integer.parseInt(ym.group(2));
                    if (mo >= 1 && mo <= 12 && yr >= MIN_YEAR && yr <= MAX_YEAR) {
                        return LocalDate.of(yr, mo, 1).toString();
                    }
                } catch (Exception ignored) { }
            }
        }

        if (text == null || text.isBlank()) return null;
        String head = text.length() > PRIMARY_DATE_SCAN_CHARS
                ? text.substring(0, PRIMARY_DATE_SCAN_CHARS) : text;

        // 2. Date right after a content cue.
        Matcher cue = CONTENT_DATE_CUE.matcher(head);
        while (cue.find()) {
            String after = head.substring(cue.end(), Math.min(head.length(), cue.end() + 40));
            LocalDate d = firstDateIn(after);
            if (d != null) return d.toString();
        }

        // 3. First parseable date anywhere in the opening.
        LocalDate d = firstDateIn(head);
        return d == null ? null : d.toString();
    }

    /** First date (any supported format, incl. bare month-year) in {@code s}, or null. */
    private static LocalDate firstDateIn(String s) {
        LocalDate best = null;
        int bestPos = Integer.MAX_VALUE;
        record Cand(Pattern p, DateParser parse) {}
        List<Cand> cands = List.of(
                new Cand(DMY, LocalDateScanner::fromDMY),
                new Cand(MDY, LocalDateScanner::fromMDY),
                new Cand(ISO, LocalDateScanner::fromISO),
                new Cand(NUMERIC, LocalDateScanner::fromNumeric));
        for (Cand c : cands) {
            Matcher m = c.p().matcher(s);
            if (m.find() && m.start() < bestPos) {
                try {
                    LocalDate d = c.parse().parse(m);
                    if (d != null) { best = d; bestPos = m.start(); }
                } catch (Exception ignored) { }
            }
        }
        // Bare "March 2024" — only wins if it appears before any full date.
        Matcher my = CONTENT_MONTH_YEAR.matcher(s);
        if (my.find() && my.start() < bestPos) {
            LocalDate d = monthYear(my.group(1), my.group(2));
            if (d != null) best = d;
        }
        return best;
    }

    private static LocalDate monthYear(String monthName, String year) {
        try {
            int yr = Integer.parseInt(year);
            if (yr < MIN_YEAR || yr > MAX_YEAR) return null;
            return LocalDate.of(yr, month(monthName), 1);
        } catch (Exception e) {
            return null;
        }
    }

    /** Extracts obligation-dated events from a document's text. Package-visible for tests. */
    public static List<NewDate> extractEvents(String text) {
        if (text == null || text.isBlank()) return List.of();
        String lower = text.toLowerCase();
        List<NewDate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(); // dedupe by date+title within a file

        collect(out, seen, text, lower, DMY,     LocalDateScanner::fromDMY);
        collect(out, seen, text, lower, MDY,     LocalDateScanner::fromMDY);
        collect(out, seen, text, lower, ISO,     LocalDateScanner::fromISO);
        collect(out, seen, text, lower, NUMERIC, LocalDateScanner::fromNumeric);
        return out;
    }

    private interface DateParser { LocalDate parse(Matcher m); }

    private static void collect(List<NewDate> out, Set<String> seen, String original,
                                String lower, Pattern p, DateParser parser) {
        Matcher m = p.matcher(original);
        while (m.find()) {
            LocalDate date;
            try { date = parser.parse(m); } catch (Exception e) { continue; }
            if (date == null) continue;
            String title = triggerBefore(lower, m.start());
            if (title == null) continue; // no obligation context → not a timeline event
            String key = date + "|" + title;
            if (!seen.add(key)) continue;
            out.add(new NewDate(date.toString(), title, excerpt(original, m.start(), m.end())));
        }
    }

    /** Returns the friendly title if an obligation trigger sits in the window
     *  just before {@code dateStart} — and the text between the trigger and the
     *  date doesn't say the obligation was already fulfilled — else null. */
    private static String triggerBefore(String lower, int dateStart) {
        int from = Math.max(0, dateStart - TRIGGER_WINDOW);
        String window = lower.substring(from, dateStart);
        // A record-keeping label right before the date ("Date of filing :")
        // means the date is history, whatever trigger words appear earlier.
        if (COMPLETED_LABEL.matcher(window).find()) return null;
        for (Trigger tr : TRIGGERS) {
            Matcher m = tr.pattern().matcher(window);
            int lastEnd = -1;
            while (m.find()) lastEnd = m.end(); // nearest occurrence to the date
            if (lastEnd < 0) continue;
            String gap = window.substring(lastEnd);
            if (COMPLETED_GUARD.matcher(gap).find()) continue; // "…renewal completed on <date>"
            return tr.title();
        }
        return null;
    }

    // The context line shown under an attention item: the SENTENCE containing
    // the trigger + date, not a blind byte slice (which produced debris like
    // "…458219340280626 Date of filing : 28-06-2026 Gro" — mid-word cuts and
    // leading junk from the previous sentence).
    private static final int EXCERPT_BACK = 120, EXCERPT_FWD = 100;
    // Common abbreviations whose trailing '.' is not a sentence end.
    private static final Pattern ABBREV = Pattern.compile(
            "(?i)^(?:rs|no|dr|mr|mrs|ms|pvt|ltd|inc|co|vs|etc|approx)$");

    private static String excerpt(String text, int start, int end) {
        // Walk back to the start of the sentence (or the line), budget-capped.
        int s = start;
        while (s > 0 && start - s < EXCERPT_BACK) {
            char c = text.charAt(s - 1);
            if (c == '\n' || c == '\r') break;
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(text.charAt(s))
                    && !ABBREV.matcher(wordEndingAt(text, s - 1)).matches()) break;
            s--;
        }
        if (start - s >= EXCERPT_BACK) {   // budget hit mid-word → snap to next word
            while (s < start && !Character.isWhitespace(text.charAt(s))) s++;
        }
        // Walk forward to the end of the sentence (or the line), budget-capped.
        int e = end;
        while (e < text.length() && e - end < EXCERPT_FWD) {
            char c = text.charAt(e);
            if (c == '\n' || c == '\r') break;
            e++;
            if ((c == '.' || c == '!' || c == '?')
                    && (e >= text.length() || Character.isWhitespace(text.charAt(e)))) break;
        }
        if (e - end >= EXCERPT_FWD) {      // budget hit mid-word → back up to last space
            while (e > end && !Character.isWhitespace(text.charAt(e - 1))) e--;
        }
        return text.substring(s, e).replaceAll("\\s+", " ").trim();
    }

    /** The word whose last character is at {@code pos} (exclusive of the '.'). */
    private static String wordEndingAt(String text, int pos) {
        int w = pos;
        while (w > 0 && Character.isLetterOrDigit(text.charAt(w - 1))) w--;
        return text.substring(w, pos);
    }

    private static LocalDate fromDMY(Matcher m) {
        int day = Integer.parseInt(m.group(1));
        int mon = month(m.group(2));
        int yr  = Integer.parseInt(m.group(3));
        return dateInBounds(yr, mon, day);
    }

    private static LocalDate fromMDY(Matcher m) {
        int mon = month(m.group(1));
        int day = Integer.parseInt(m.group(2));
        int yr  = Integer.parseInt(m.group(3));
        return dateInBounds(yr, mon, day);
    }

    private static LocalDate fromISO(Matcher m) {
        return dateInBounds(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    private static LocalDate fromNumeric(Matcher m) {
        int a = Integer.parseInt(m.group(1)); // day (DD/MM, Indian)
        int b = Integer.parseInt(m.group(2)); // month
        int yr = Integer.parseInt(m.group(3));
        try { return dateInBounds(yr, b, a); }              // DD/MM/YYYY
        catch (Exception e) {
            try { return dateInBounds(yr, a, b); }          // fall back to MM/DD/YYYY
            catch (Exception e2) { return null; }
        }
    }

    /** Builds the date, rejecting years outside the sane document window
     *  (OCR junk like 31/12/9999) by throwing — callers already skip on throw. */
    private static LocalDate dateInBounds(int yr, int mon, int day) {
        if (yr < MIN_YEAR || yr > MAX_YEAR) {
            throw new IllegalArgumentException("year out of bounds: " + yr);
        }
        return LocalDate.of(yr, mon, day);
    }

    private static int month(String s) {
        Integer mo = MONTHS.get(s.substring(0, 3).toLowerCase());
        if (mo == null) throw new IllegalArgumentException("bad month: " + s);
        return mo;
    }

    private static Trigger t(String regex, String title) {
        return new Trigger(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), title);
    }
}

package com.localfilebrain.deadline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Local, zero-cost first pass that decides which chunks of a document are worth
 * sending to the LLM for deadline extraction. Pure string matching — no model,
 * no network — so it runs over every page of a 600-page document for free.
 *
 * <p>Design goal is <b>high recall</b>: a chunk becomes a candidate if it
 * matches <i>any</i> date pattern OR <i>any</i> deadline/obligation trigger
 * word. False positives are cheap (they cost a few extra tokens of LLM input
 * and the model simply discards them); a miss is the only real harm, so we
 * cast wide. The only thing that slips through is a deadline written with
 * neither a date nor a trigger word — and such a sentence has no date to set a
 * reminder on anyway. The scan service compensates by always including each
 * document's leading/trailing chunks regardless of this filter.
 *
 * <p>The pattern set was validated against deliberately-varied phrasings
 * (numeric/ISO/month-name dates, "within 30 days", "next quarter", ordinal
 * days, "submit … before the FY ends", Indian GST/Form-15G wording) before
 * being ported here.
 */
public final class DeadlinePrefilter {

    private DeadlinePrefilter() {}

    private static final String MONTHS =
            "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?"
          + "|aug(?:ust)?|sep(?:t)?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

    /** Date-shape patterns, keyed by a human-readable reason (for logging/debug). */
    private static final Map<String, Pattern> DATE_PATTERNS = new LinkedHashMap<>();
    static {
        DATE_PATTERNS.put("numeric-date",  Pattern.compile("\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b"));
        DATE_PATTERNS.put("iso-date",      Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b"));
        DATE_PATTERNS.put("month-name",    Pattern.compile(
                "\\b(\\d{1,2}\\s+" + MONTHS + "|" + MONTHS + "\\.?\\s+\\d{1,2})\\b",
                Pattern.CASE_INSENSITIVE));
        DATE_PATTERNS.put("ordinal-day",   Pattern.compile(
                "\\bthe\\s+\\d{1,2}(?:st|nd|rd|th)\\b", Pattern.CASE_INSENSITIVE));
        DATE_PATTERNS.put("bare-year",     Pattern.compile("\\b20\\d{2}\\b"));
        DATE_PATTERNS.put("relative-date", Pattern.compile(
                "\\b(within\\s+\\d+\\s+(?:day|week|month|year)s?"
              + "|next\\s+(?:week|month|quarter|year)"
              + "|in\\s+\\d+\\s+(?:day|week|month)s?"
              + "|q[1-4]\\b)", Pattern.CASE_INSENSITIVE));
    }

    /** Deadline / obligation trigger words — flag a chunk even without an explicit date. */
    private static final Pattern TRIGGERS = Pattern.compile(
            "\\b(due|deadline|expir\\w+|renew\\w*|valid\\s+(?:until|till|through)"
          + "|no\\s+later\\s+than|on\\s+or\\s+before|last\\s+date|cut[\\s-]?off|payable|remit"
          + "|submit|file[d]?\\s+by|respond|lapse\\w*|maturity|effective\\s+date"
          + "|notice\\s+period|terminat\\w+|grace\\s+period|overdue|reminder|by\\s+the)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns true if {@code text} looks like it could contain a deadline,
     * renewal, or dated obligation and is therefore worth an LLM look.
     */
    public static boolean isCandidate(String text) {
        if (text == null || text.isBlank()) return false;
        for (Pattern p : DATE_PATTERNS.values()) {
            if (p.matcher(text).find()) return true;
        }
        return TRIGGERS.matcher(text).find();
    }

    /**
     * The reasons {@code text} was flagged — useful for logs and tests. Empty
     * list means {@link #isCandidate} would return false.
     */
    public static List<String> reasons(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (Map.Entry<String, Pattern> e : DATE_PATTERNS.entrySet()) {
            if (e.getValue().matcher(text).find()) out.add(e.getKey());
        }
        if (TRIGGERS.matcher(text).find()) out.add("trigger-word");
        return out;
    }
}

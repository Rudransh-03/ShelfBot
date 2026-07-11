package com.localfilebrain.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locale seam for money parsing and formatting.
 *
 * <p>Only the India profile is wired today. US / Europe are added later as new
 * {@link Profile} branches WITHOUT touching any call site — everything that
 * parses or formats an amount routes through here, while the aggregation logic
 * (gather / extract / dedup / sum) stays locale-agnostic. Onboarding will set
 * {@link #setActive} per user; until then the whole app runs {@code INDIA}.
 *
 * <p>Extending to a new market = add a branch to {@link #symbol()},
 * {@link #format(long)} and {@link #amountPattern()}. No other file changes.
 */
public final class MoneyFormat {

    private MoneyFormat() {}

    public enum Profile { INDIA }

    // Single switch point. Volatile: onboarding may flip it once at startup.
    private static volatile Profile active = Profile.INDIA;

    public static void setActive(Profile p) { if (p != null) active = p; }
    public static Profile active() { return active; }

    // India: ₹15,000 / Rs. 15,000 / Rs 1,88,000 / a lone grouped run like
    // "1,88,000". Group 1 = the digit run (with optional grouping commas).
    // Requires either a currency cue OR a grouping comma so a bare id fragment
    // like "015" or a year "2026" isn't mistaken for an amount.
    private static final Pattern INR_AMOUNT = Pattern.compile(
            "(?i)(?:(?:₹|\\brs\\.?\\s*|\\binr\\s*)([0-9][0-9,]*)|([0-9]{1,2}(?:,[0-9]{2,3})+))");

    /** Currency symbol for the active profile. */
    public static String symbol() {
        return switch (active) { case INDIA -> "₹"; };
    }

    /** Format a whole-unit amount with the active profile's grouping + symbol,
     *  e.g. {@code 188000 -> "₹1,88,000"} for INDIA. */
    public static String format(long amount) {
        return switch (active) { case INDIA -> symbol() + indianGroup(amount); };
    }

    /** The first currency amount in {@code text} as a whole-unit long, or
     *  {@code null} if none. Grouping separators are stripped before parsing. */
    public static Long parse(String text) {
        if (text == null) return null;
        // A pure numeric field (e.g. an LLM-returned "15000" or a ledger Amount
        // cell) has no cue/comma — accept it directly.
        String t = text.trim();
        if (t.matches("[0-9]{1,12}")) {
            try { return Long.parseLong(t); } catch (NumberFormatException e) { return null; }
        }
        Matcher m = amountPattern().matcher(text);
        if (!m.find()) return null;
        String digits = (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "");
        if (digits.isEmpty()) return null;
        try { return Long.parseLong(digits); } catch (NumberFormatException e) { return null; }
    }

    /** True when {@code text} contains at least one currency amount. */
    public static boolean hasAmount(String text) {
        return parse(text) != null;
    }

    private static Pattern amountPattern() {
        return switch (active) { case INDIA -> INR_AMOUNT; };
    }

    // Indian digit grouping: last three digits, then two at a time (1,88,000).
    static String indianGroup(long n) {
        boolean neg = n < 0;
        String s = Long.toString(Math.abs(n));
        if (s.length() <= 3) return neg ? "-" + s : s;
        String last3 = s.substring(s.length() - 3);
        String rest  = s.substring(0, s.length() - 3);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = rest.length() - 1; i >= 0; i--) {
            sb.append(rest.charAt(i));
            if (++count % 2 == 0 && i != 0) sb.append(',');
        }
        String grouped = sb.reverse() + "," + last3;
        return neg ? "-" + grouped : grouped;
    }
}

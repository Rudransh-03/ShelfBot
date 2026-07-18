package com.localfilebrain.model;

import com.localfilebrain.config.RegionProfile;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money parsing and formatting for the active market — the one place currency
 * symbol + digit grouping live, so the aggregation logic stays market-agnostic.
 * Reads {@link RegionProfile#active()}: symbol ($/£/€/₹) and grouping (Western
 * 1,234,567 vs Indian 12,34,567). Adding a market = one enum constant there.
 */
public final class MoneyFormat {

    private MoneyFormat() {}

    // Amount written with a currency cue ($/£/€/₹/Rs/INR/USD…) OR with grouping
    // commas — so a bare id fragment ("015") or a year ("2026") is not mistaken
    // for an amount. Group 1 = the integer digit run (grouping commas allowed);
    // any decimal part is ignored (we deal in whole units).
    private static final Pattern AMOUNT = Pattern.compile(
            "(?i)(?:(?:₹|£|\\$|€|\\brs\\.?\\s*|\\binr\\s*|\\busd\\s*|\\bgbp\\s*|\\beur\\s*)([0-9][0-9,]*)"
          + "|([0-9]{1,3}(?:,[0-9]{2,3})+))(?:\\.[0-9]+)?");

    /** Currency symbol for the active market. */
    public static String symbol() { return RegionProfile.active().symbol(); }

    /** Format a whole-unit amount with the active market's symbol + grouping,
     *  e.g. {@code 188000 → "₹1,88,000"} (IN) or {@code "$188,000"} (US). */
    public static String format(long amount) { return symbol() + group(amount); }

    /** Group digits per the active market (Indian for IN, Western otherwise). */
    public static String group(long amount) {
        return RegionProfile.active().grouping() == RegionProfile.Grouping.INDIAN
                ? indianGroup(amount) : westernGroup(amount);
    }

    /** The first currency amount in {@code text} as a whole-unit long, or
     *  {@code null} if none. Grouping separators are stripped; a decimal part is
     *  dropped. A pure numeric field (e.g. an LLM-returned "15000" or a ledger
     *  Amount cell) is accepted directly. */
    public static Long parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.matches("[0-9]{1,15}")) {
            try { return Long.parseLong(t); } catch (NumberFormatException e) { return null; }
        }
        Matcher m = AMOUNT.matcher(text);
        if (!m.find()) return null;
        String digits = (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "");
        if (digits.isEmpty()) return null;
        try { return Long.parseLong(digits); } catch (NumberFormatException e) { return null; }
    }

    /** True when {@code text} contains at least one currency amount. */
    public static boolean hasAmount(String text) { return parse(text) != null; }

    // ── Grouping styles (both exposed so currency-specific rendering — e.g. a
    //    document stated in ₹ regardless of the user's market — can pick one) ──

    /** Western grouping: 1,234,567. */
    public static String westernGroup(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /** Indian grouping: last three digits, then two at a time — 12,34,567. */
    public static String indianGroup(long n) {
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

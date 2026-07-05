package com.localfilebrain.extract;

/**
 * How a CURRENCY field should be rendered: a symbol/code and a digit-grouping
 * style. This is the localized refactor that removes the hardcoded ₹ default
 * from currency formatting — the descriptor is passed in explicitly rather than
 * inferred from locale, document contents, or any previous default.
 *
 * <p>The grouping algorithms are the same ones the chat analytics path uses
 * (Indian {@code 20,66,600} vs Western {@code 2,066,600}); they are reproduced
 * here rather than reaching into the retrieval engine so this stays a small,
 * self-contained, independently-testable unit and the stable {@code QueryEngine}
 * is not modified.
 *
 * <p>Deliberately NOT included (out of scope for this milestone): automatic
 * currency detection, locale inference, jurisdiction-specific formatting, or any
 * multi-currency intelligence. The descriptor is applied uniformly to whatever
 * numeric amount the model extracts.
 *
 * @param symbol   the currency symbol or code to prefix (e.g. "₹", "$", "USD",
 *                 "£"); empty renders a bare grouped number
 * @param grouping the thousands-grouping convention
 */
public record CurrencyDescriptor(String symbol, Grouping grouping) {

    public enum Grouping { WESTERN, INDIAN }

    /**
     * A neutral, explicit fallback: no symbol, Western grouping. Used only as a
     * defensive engine-parameter default — it is intentionally NOT ₹, so nothing
     * silently inherits the old hardcoded rupee behaviour. Extract Mode requires
     * the user to choose a currency (or a workspace default) before running.
     */
    public static final CurrencyDescriptor NONE = new CurrencyDescriptor("", Grouping.WESTERN);

    public CurrencyDescriptor {
        symbol   = symbol == null ? "" : symbol.trim();
        grouping = grouping == null ? Grouping.WESTERN : grouping;
    }

    /**
     * Renders a whole-unit amount with this descriptor's grouping and symbol.
     * Symbols attach directly ({@code ₹1,500} / {@code $1,500}); alphabetic
     * codes get a separating space ({@code USD 1,500}). Never converts between
     * currencies.
     */
    public String format(long amount) {
        String num = grouping == Grouping.INDIAN ? indianGroup(amount)
                                                 : westernGroup(amount);
        if (symbol.isEmpty()) return num;
        boolean wordy = symbol.chars().anyMatch(Character::isLetter); // "USD"/"Rs." vs "₹"/"$"
        return wordy ? symbol + " " + num : symbol + num;
    }

    /** Western grouping, e.g. 2066600 → 2,066,600. */
    static String westernGroup(long n) {
        return String.format(java.util.Locale.US, "%,d", n);
    }

    /** Indian grouping, e.g. 2066600 → 20,66,600 (last three, then pairs). */
    static String indianGroup(long n) {
        boolean neg = n < 0;
        String s = Long.toString(Math.abs(n));
        if (s.length() <= 3) return (neg ? "-" : "") + s;
        String last3 = s.substring(s.length() - 3);
        String rest  = s.substring(0, s.length() - 3);
        StringBuilder sb = new StringBuilder();
        int i = rest.length();
        while (i > 2) { sb.insert(0, "," + rest.substring(i - 2, i)); i -= 2; }
        sb.insert(0, rest.substring(0, i));
        return (neg ? "-" : "") + sb + "," + last3;
    }
}

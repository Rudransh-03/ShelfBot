package com.localfilebrain.config;

/**
 * A market/jurisdiction profile — the single place region-specific behaviour is
 * declared, so the rest of the app never hard-codes a currency, date order, or
 * grouping. Formatting/parsing in CODE reads this; jurisdiction UNDERSTANDING
 * (Phase 2) reads {@link #authority()} into the LLM prompts. Add a market = add
 * one enum constant, nothing else.
 *
 * <p>Only English-speaking markets are targeted for now. Ireland ("EU") uses the
 * English number convention (comma thousands, dot decimal) — i.e. WESTERN
 * grouping, not the continental dot/comma style — so all non-India markets share
 * WESTERN grouping and only the currency symbol + date order differ.
 */
public enum RegionProfile {
    //   code  symbol code   grouping          dateOrder        timezone            tax authority (Phase 2 prompt context)
    US ("US", "$",  "USD", Grouping.WESTERN, DateOrder.MDY, "America/New_York", "the IRS"),
    UK ("UK", "£",  "GBP", Grouping.WESTERN, DateOrder.DMY, "Europe/London",    "HMRC"),
    EU ("EU", "€",  "EUR", Grouping.WESTERN, DateOrder.DMY, "Europe/Dublin",    "Revenue (Ireland)"),
    IN ("IN", "₹",  "INR", Grouping.INDIAN,  DateOrder.DMY, "Asia/Kolkata",     "the Income-Tax Department and GST authorities");

    public enum Grouping { WESTERN, INDIAN }
    public enum DateOrder { MDY, DMY }

    private final String code, symbol, currencyCode, timezone, authority;
    private final Grouping grouping;
    private final DateOrder dateOrder;

    RegionProfile(String code, String symbol, String currencyCode, Grouping grouping,
                  DateOrder dateOrder, String timezone, String authority) {
        this.code = code; this.symbol = symbol; this.currencyCode = currencyCode;
        this.grouping = grouping; this.dateOrder = dateOrder; this.timezone = timezone;
        this.authority = authority;
    }

    public String   code()         { return code; }
    public String   symbol()       { return symbol; }
    public String   currencyCode() { return currencyCode; }
    public Grouping grouping()     { return grouping; }
    public DateOrder dateOrder()   { return dateOrder; }
    public String   timezone()     { return timezone; }
    public String   authority()    { return authority; }

    /** The profile for a stored code ("US"/"UK"/"EU"/"IN"), defaulting to IN for
     *  anything unrecognised or null. Accepts a few friendly aliases. */
    public static RegionProfile of(String code) {
        if (code == null) return IN;
        switch (code.trim().toUpperCase()) {
            case "US": case "USA": case "UNITED STATES": return US;
            case "UK": case "GB": case "GBR": case "UNITED KINGDOM": return UK;
            case "EU": case "IE": case "IRELAND": case "EUROPE": return EU;
            case "IN": case "IND": case "INDIA": return IN;
            default: return IN;
        }
    }

    // ── The one active profile for this running backend ──────────────────────
    // Set once at startup from config (AppConfig user.region). Volatile so a
    // later settings change is picked up without a restart.
    private static volatile RegionProfile active = IN;

    public static void setActive(RegionProfile p) { if (p != null) active = p; }
    public static RegionProfile active() { return active; }
}

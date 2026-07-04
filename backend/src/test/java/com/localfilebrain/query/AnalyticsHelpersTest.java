package com.localfilebrain.query;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The analytics math is only as trustworthy as these pure helpers. */
class AnalyticsHelpersTest {

    @Test
    void parsesAmountsRegardlessOfFormat() {
        assertEquals(708000L,  QueryEngine.parseAmount(" 708000"));
        assertEquals(708000L,  QueryEngine.parseAmount(" 7,08,000"));   // Indian grouping
        assertEquals(708000L,  QueryEngine.parseAmount(" 708,000"));    // Western grouping
        assertEquals(1062000L, QueryEngine.parseAmount(" ₹10,62,000"));
        assertEquals(28000L,   QueryEngine.parseAmount("Rs. 28,000 (paid)"));
        assertEquals(25000L,   QueryEngine.parseAmount("25,000"));
        assertNull(QueryEngine.parseAmount("no number here"));
        assertNull(QueryEngine.parseAmount(null));
    }

    @Test
    void sumsPureComponentChains_only() {
        // CGST + SGST with no net line — the model reports components, code sums.
        assertEquals(396000L, QueryEngine.parseAmount("198000 + 198000"));
        assertEquals(396000L, QueryEngine.parseAmount(" 1,98,000 + ₹1,98,000 "));
        assertEquals(60000L,  QueryEngine.parseAmount("10000+20000+30000"));
        // NOT chains: trailing prose or dates must keep first-number behavior,
        // so "(paid)" notes or a date can never corrupt the amount.
        assertEquals(28000L,   QueryEngine.parseAmount("28,000 (paid)"));
        assertEquals(1062000L, QueryEngine.parseAmount("1062000 due 04 April 2024"));
        assertEquals(198000L,  QueryEngine.parseAmount("198000 + tax"));
    }

    @Test
    void parsesCurrencyLead() {
        assertEquals("₹",   QueryEngine.parseCurrency("₹10,62,000"));
        assertEquals("$",   QueryEngine.parseCurrency(" $1,500"));
        assertEquals("USD", QueryEngine.parseCurrency("USD 1500"));
        assertEquals("Rs.", QueryEngine.parseCurrency("Rs. 28,000"));
        assertEquals("",    QueryEngine.parseCurrency("28,000"));
        assertEquals("",    QueryEngine.parseCurrency("no numbers"));
        assertEquals("",    QueryEngine.parseCurrency(null));
        // Prose lead is not a currency — length-capped.
        assertEquals("", QueryEngine.parseCurrency("approximately 5000"));
    }

    @Test
    void moneyRendersPerCurrency() {
        assertEquals("₹20,66,600",  QueryEngine.money("₹", 2066600));   // Indian grouping
        assertEquals("Rs. 28,000",  QueryEngine.money("Rs.", 28000));
        assertEquals("INR 1,53,000", QueryEngine.money("INR", 153000));
        assertEquals("$2,066,600",  QueryEngine.money("$", 2066600));   // Western grouping
        assertEquals("USD 1,500",   QueryEngine.money("USD", 1500));
        assertEquals("€1,500",      QueryEngine.money("€", 1500));
        assertEquals("25,000",      QueryEngine.money("", 25000));      // unstated → bare, Indian
    }

    @Test
    void componentChainWithCurrencySymbols_sums() {
        assertEquals(396000L, QueryEngine.parseAmount("₹198000 + ₹198000"));
        assertEquals(3000L,   QueryEngine.parseAmount("$1000 + $2000"));
    }

    @Test
    void implausiblyHugeNumbers_rejectedNotMangled() {
        // A 19-digit OCR artifact would lose precision in the double parse and
        // overflow the total — reject it outright instead of guessing.
        assertNull(QueryEngine.parseAmount("9999999999999999999"));
        assertNull(QueryEngine.parseAmount("123456789012345678 + 5"));
        // The ceiling doesn't clip real high-value amounts (₹542 crore).
        assertEquals(5_42_00_00_000L, QueryEngine.parseAmount("5,42,00,00,000"));
    }

    @Test
    void indianGroupingIsCorrect() {
        assertEquals("20,66,600", QueryEngine.indianGroup(2066600));
        assertEquals("7,08,000",  QueryEngine.indianGroup(708000));
        assertEquals("10,62,000", QueryEngine.indianGroup(1062000));
        assertEquals("25,000",    QueryEngine.indianGroup(25000));
        assertEquals("500",       QueryEngine.indianGroup(500));
        assertEquals("0",         QueryEngine.indianGroup(0));
    }

}

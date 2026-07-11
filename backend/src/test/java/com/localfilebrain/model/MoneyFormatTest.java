package com.localfilebrain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** India-profile money parsing/formatting — the locale seam the fee aggregator
 *  routes every amount through. */
class MoneyFormatTest {

    @Test
    void formatsWithIndianGrouping() {
        assertEquals("₹47,200", MoneyFormat.format(47200));
        assertEquals("₹1,88,000", MoneyFormat.format(188000));
        assertEquals("₹62,200", MoneyFormat.format(62200));
        assertEquals("₹500", MoneyFormat.format(500));
    }

    @Test
    void parsesRupeeAmountsInProse() {
        assertEquals(15000L, MoneyFormat.parse("BALANCE Rs. 15,000 still outstanding"));
        assertEquals(29500L, MoneyFormat.parse("fee Rs. 29,500 still pending"));
        assertEquals(188000L, MoneyFormat.parse("₹1,88,000"));
    }

    @Test
    void parsesPlainNumericField() {
        // A ledger Amount cell / an LLM-returned number has no cue or comma.
        assertEquals(17700L, MoneyFormat.parse("17700"));
        assertEquals(35000L, MoneyFormat.parse(" 35000 "));
    }

    @Test
    void doesNotMistakeAnIdOrYearForAnAmount() {
        // "MA/2026-27/015" carries no grouped/cued amount — must not parse 2026.
        assertNull(MoneyFormat.parse("MA/2026-27/015"));
        assertNull(MoneyFormat.parse("invoice number 015"));
    }

    @Test
    void roundTripsThroughGrouping() {
        assertEquals("₹62,200", MoneyFormat.format(MoneyFormat.parse("₹62,200")));
    }
}

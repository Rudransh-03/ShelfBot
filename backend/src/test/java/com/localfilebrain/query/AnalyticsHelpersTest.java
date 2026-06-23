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
    void indianGroupingIsCorrect() {
        assertEquals("20,66,600", QueryEngine.indianGroup(2066600));
        assertEquals("7,08,000",  QueryEngine.indianGroup(708000));
        assertEquals("10,62,000", QueryEngine.indianGroup(1062000));
        assertEquals("25,000",    QueryEngine.indianGroup(25000));
        assertEquals("500",       QueryEngine.indianGroup(500));
        assertEquals("0",         QueryEngine.indianGroup(0));
    }

    @Test
    void detectsAnalyticsIntent() {
        assertEquals(QueryEngine.AnalyticsOp.SUM, QueryEngine.analyticsOp("what is the total amount of all my invoices"));
        assertEquals(QueryEngine.AnalyticsOp.MAX, QueryEngine.analyticsOp("which is my largest invoice by amount"));
        assertEquals(QueryEngine.AnalyticsOp.MIN, QueryEngine.analyticsOp("what is my smallest invoice"));
        assertEquals(QueryEngine.AnalyticsOp.LIST, QueryEngine.analyticsOp("list all my invoices with their amounts"));
        // Not analytics:
        assertNull(QueryEngine.analyticsOp("what is the total amount on this invoice")); // single doc, no scope
        assertNull(QueryEngine.analyticsOp("how many invoices do I have"));
        assertNull(QueryEngine.analyticsOp("summarize my documents"));
    }
}

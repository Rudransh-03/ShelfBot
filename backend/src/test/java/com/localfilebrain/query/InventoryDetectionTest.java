package com.localfilebrain.query;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Enumeration questions (count / list / which-documents) route to the inventory
 * path for exhaustive coverage; focused/content questions must NOT.
 */
class InventoryDetectionTest {

    @Test
    void triggersOnEnumerationAsks() {
        assertTrue(QueryEngine.isInventoryQuery("how many invoices do I have"));
        assertTrue(QueryEngine.isInventoryQuery("how many bank statements do I have and whose are they"));
        assertTrue(QueryEngine.isInventoryQuery("list all my contracts"));
        assertTrue(QueryEngine.isInventoryQuery("list my invoices"));
        assertTrue(QueryEngine.isInventoryQuery("which documents mention Acme Corp"));
        assertTrue(QueryEngine.isInventoryQuery("show me all my receipts"));
    }

    @Test
    void doesNotTriggerOnContentOrScalarQuestions() {
        // Scalar "how many" — a content question about one doc.
        assertFalse(QueryEngine.isInventoryQuery("how many days until my visa appointment"));
        assertFalse(QueryEngine.isInventoryQuery("how many months is the Acme lease term"));
        // "how many" without a possession signal → leave on semantic search.
        assertFalse(QueryEngine.isInventoryQuery("how many invoices did Rohan send"));
        // Content listing, not a document enumeration.
        assertFalse(QueryEngine.isInventoryQuery("list the payment terms in the lease"));
        // Plain factual lookup.
        assertFalse(QueryEngine.isInventoryQuery("what is the GST amount on the Sharma return"));
    }

    @Test
    void nullSafe() {
        assertFalse(QueryEngine.isInventoryQuery(null));
        assertFalse(QueryEngine.isInventoryQuery(""));
    }
}

package com.localfilebrain.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kind recall belt (non-period COUNT/LIST) must add omitted siblings without
 * ever re-admitting a superseded/duplicate copy — otherwise the Meridian
 * "draft_v1" would inflate a GST-return count beside its FINAL.
 */
class SupersededNameTest {

    @Test
    void flagsDraftAndVersionedCopies() {
        assertTrue(QueryEngine.isSupersededName("GSTR3B_Meridian_May2026_draft_v1.pdf"));
        assertTrue(QueryEngine.isSupersededName("report OLD.pdf"));
        assertTrue(QueryEngine.isSupersededName("engagement_letter_backup.docx"));
        assertTrue(QueryEngine.isSupersededName("invoice_v2.pdf"));
    }

    @Test
    void leavesRealFinalsAlone() {
        assertFalse(QueryEngine.isSupersededName("GuptaTextiles_GSTR3B_May2026.pdf"));
        assertFalse(QueryEngine.isSupersededName("GSTR3B_Meridian_May2026_FINAL(2).pdf"));
        assertFalse(QueryEngine.isSupersededName("Zenlite_GSTR3B_Apr2026.pdf"));
        // "Oldfield" contains "old" only as a substring, not a whole segment.
        assertFalse(QueryEngine.isSupersededName("Oldfield_GST_return.pdf"));
    }
}

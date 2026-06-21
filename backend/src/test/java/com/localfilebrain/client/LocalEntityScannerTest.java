package com.localfilebrain.client;

import com.localfilebrain.client.LocalEntityScanner.Hit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalEntityScannerTest {

    @Test
    void extractsGstinAndItsEmbeddedPan() {
        Hit h = LocalEntityScanner.scanText("Tax Invoice\nGSTIN: 27ABCDE1234F1Z5\nAmount due ...");
        assertNotNull(h);
        assertEquals("27ABCDE1234F1Z5", h.gstin());
        assertEquals("ABCDE1234F", h.pan(), "PAN is chars 2..11 of the GSTIN");
    }

    @Test
    void fallsBackToStandalonePanWhenNoGstin() {
        Hit h = LocalEntityScanner.scanText("PAN ABCDE1234F as per records");
        assertNotNull(h);
        assertNull(h.gstin());
        assertEquals("ABCDE1234F", h.pan());
    }

    @Test
    void caseInsensitiveAndPrefersGstinOverEmbeddedPanMatch() {
        // A PAN substring exists inside the GSTIN; the GSTIN must win.
        Hit h = LocalEntityScanner.scanText("gstin 27abcde1234f1z5");
        assertNotNull(h);
        assertEquals("27ABCDE1234F1Z5", h.gstin());
    }

    @Test
    void returnsNullWhenNoIdentifierPresent() {
        assertNull(LocalEntityScanner.scanText("Just an ordinary note with no tax ids."));
        assertNull(LocalEntityScanner.scanText(""));
        assertNull(LocalEntityScanner.scanText(null));
    }

    @Test
    void doesNotMatchMalformedShapes() {
        assertNull(LocalEntityScanner.scanText("ABCD1234F"),  "too few letters for a PAN");
        assertNull(LocalEntityScanner.scanText("12ABCDE1234F1X5"), "GSTIN without the mandatory 'Z'");
    }
}

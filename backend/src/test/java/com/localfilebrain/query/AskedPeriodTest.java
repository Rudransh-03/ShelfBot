package com.localfilebrain.query;

import com.localfilebrain.query.QueryEngine.AskedPeriod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the deterministic period filter behind date-qualified list questions:
 * the model chooses the document KIND, code enforces the PERIOD against each
 * file's locally-extracted primary date.
 */
class AskedPeriodTest {

    @Test
    void monthAndYear_detected() {
        assertEquals(new AskedPeriod(2024, 2),
                QueryEngine.detectAskedPeriod("list my documents from February 2024"));
        assertEquals(new AskedPeriod(2024, 1),
                QueryEngine.detectAskedPeriod("which invoices are from Jan 2024?"));
    }

    @Test
    void bareMonth_anyYear() {
        assertEquals(new AskedPeriod(0, 3),
                QueryEngine.detectAskedPeriod("show me the rent receipts from March"));
    }

    @Test
    void bareYear_wholeYear() {
        assertEquals(new AskedPeriod(2024, 0),
                QueryEngine.detectAskedPeriod("list everything from 2024"));
    }

    @Test
    void noPeriodOrAmbiguous_null() {
        assertNull(QueryEngine.detectAskedPeriod("list my bank statements"));
        assertNull(QueryEngine.detectAskedPeriod("compare February and March returns"));
        assertNull(QueryEngine.detectAskedPeriod("statements from 2023 and 2024"));
        assertNull(QueryEngine.detectAskedPeriod(null));
    }

    @Test
    void bareNumberThatIsNotAPeriod_ignored() {
        // Amounts and thresholds in the 20xx range must not become year filters.
        assertNull(QueryEngine.detectAskedPeriod("list invoices above 2050 rupees"));
        assertNull(QueryEngine.detectAskedPeriod("which bills are more than 2024"));
        // But real period phrasing still works.
        assertEquals(new AskedPeriod(2025, 0),
                QueryEngine.detectAskedPeriod("everything I filed in 2025"));
    }

    @Test
    void inPeriod_checks() {
        AskedPeriod feb2024 = new AskedPeriod(2024, 2);
        assertTrue(QueryEngine.inPeriod("2024-02-01", feb2024));
        assertTrue(QueryEngine.inPeriod("2024-02-28", feb2024));
        assertFalse(QueryEngine.inPeriod("2024-01-01", feb2024)); // Jan file ≠ Feb ask
        assertFalse(QueryEngine.inPeriod("2023-02-01", feb2024)); // wrong year
        assertFalse(QueryEngine.inPeriod(null, feb2024));         // undated → excluded

        assertTrue(QueryEngine.inPeriod("2024-07-15", new AskedPeriod(2024, 0))); // whole year
        assertTrue(QueryEngine.inPeriod("2023-03-01", new AskedPeriod(0, 3)));    // any year, March
    }
}

package com.localfilebrain.model;

import com.localfilebrain.config.RegionProfile;
import com.localfilebrain.ingestion.IndexMetadataStore.NewDate;
import com.localfilebrain.timeline.LocalDateScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Market-aware money formatting/parsing + the date-order flip. These are the
 *  real edge cases of Phase 1: a grouping/symbol regression or a US vs India
 *  date misread would corrupt every amount/deadline for that market. */
class MoneyFormatTest {

    @AfterEach
    void resetRegion() { RegionProfile.setActive(RegionProfile.IN); }

    // ── currency formatting per market ───────────────────────────────────────

    @Test
    void indiaUsesRupeeAndIndianGrouping() {
        RegionProfile.setActive(RegionProfile.IN);
        assertEquals("₹1,88,000", MoneyFormat.format(188000));
        assertEquals("₹47,200", MoneyFormat.format(47200));
    }

    @Test
    void usUkEuUseWesternGroupingAndTheirSymbol() {
        RegionProfile.setActive(RegionProfile.US);
        assertEquals("$188,000", MoneyFormat.format(188000));
        RegionProfile.setActive(RegionProfile.UK);
        assertEquals("£188,000", MoneyFormat.format(188000));
        RegionProfile.setActive(RegionProfile.EU);
        assertEquals("€188,000", MoneyFormat.format(188000));
    }

    // ── parsing tolerates every market's symbol ─────────────────────────────

    @Test
    void parsesAcrossCurrencySymbols() {
        assertEquals(15000L, MoneyFormat.parse("BALANCE Rs. 15,000 still outstanding"));
        assertEquals(1234L,  MoneyFormat.parse("$1,234.56"));   // decimal dropped, whole units
        assertEquals(15000L, MoneyFormat.parse("£15,000 due"));
        assertEquals(7670L,  MoneyFormat.parse("total €7,670"));
        assertEquals(35000L, MoneyFormat.parse("35000"));       // bare numeric field
    }

    @Test
    void doesNotMistakeAnIdOrYearForAnAmount() {
        assertNull(MoneyFormat.parse("MA/2026-27/015"));
        assertNull(MoneyFormat.parse("invoice number 015"));
    }

    // ── date order follows the market ───────────────────────────────────────

    @Test
    void indiaReadsAmbiguousDateDayFirst() {
        RegionProfile.setActive(RegionProfile.IN);
        List<NewDate> out = LocalDateScanner.extractEvents("Payment due by 05/06/2026.");
        assertEquals(1, out.size());
        assertEquals("2026-06-05", out.get(0).eventDate());   // 5 June
    }

    @Test
    void usReadsAmbiguousDateMonthFirst() {
        RegionProfile.setActive(RegionProfile.US);
        List<NewDate> out = LocalDateScanner.extractEvents("Payment due by 05/06/2026.");
        assertEquals(1, out.size());
        assertEquals("2026-05-06", out.get(0).eventDate());   // May 6
    }

    @Test
    void unambiguousDateParsesRegardlessOfMarket() {
        // 25 in the first slot can only be a day → both markets read 25 June.
        RegionProfile.setActive(RegionProfile.US);
        List<NewDate> out = LocalDateScanner.extractEvents("Payment due by 25/06/2026.");
        assertEquals(1, out.size());
        assertEquals("2026-06-25", out.get(0).eventDate());
    }
}

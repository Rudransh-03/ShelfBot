package com.localfilebrain.deadline;

import com.localfilebrain.deadline.MissingDocumentDetector.MissingDoc;
import com.localfilebrain.ingestion.IndexMetadataStore.SeriesRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MissingDocumentDetectorTest {

    private static SeriesRow s(String series, String issuer, String period) {
        return new SeriesRow("/p/" + series + "-" + period + ".pdf",
                series + "-" + period + ".pdf", "h", series, issuer, period);
    }

    private static List<String> labels(List<MissingDoc> ms) {
        return ms.stream().map(MissingDoc::periodLabel).toList();
    }

    @Test
    void flagsInteriorGapBetweenTwoMonths_lowConfidence() {
        // The canonical example: GST present for Jan and Mar → Feb missing.
        List<MissingDoc> m = MissingDocumentDetector.detect(List.of(
                s("GST return", "GSTN", "2024-01"),
                s("GST return", "GSTN", "2024-03")));
        assertEquals(1, m.size());
        assertEquals("February 2024", m.get(0).periodLabel());
        assertEquals("LOW", m.get(0).confidence());
        assertEquals("GST return", m.get(0).series());
        assertEquals("monthly", m.get(0).cadence());
        assertEquals(2, m.get(0).presentCount());
    }

    @Test
    void threePresentMakesItMediumConfidence() {
        List<MissingDoc> m = MissingDocumentDetector.detect(List.of(
                s("bank statement", "HDFC", "2024-01"),
                s("bank statement", "HDFC", "2024-02"),
                s("bank statement", "HDFC", "2024-04")));
        assertEquals(List.of("March 2024"), labels(m));
        assertEquals("MEDIUM", m.get(0).confidence());
    }

    @Test
    void consecutiveMonthsHaveNoGap_andFutureIsNeverFlagged() {
        // Latest present is Feb; March/onward simply not downloaded yet → silent.
        assertTrue(MissingDocumentDetector.detect(List.of(
                s("payslip", "Acme", "2024-01"),
                s("payslip", "Acme", "2024-02"))).isEmpty());
    }

    @Test
    void fullySequentialSeriesHasNoGaps() {
        assertTrue(MissingDocumentDetector.detect(List.of(
                s("invoice", "X", "2024-01"), s("invoice", "X", "2024-02"),
                s("invoice", "X", "2024-03"), s("invoice", "X", "2024-04"))).isEmpty());
    }

    @Test
    void genuineBimonthlyCadenceIsNotMistakenForGaps() {
        // Jan, Mar, May with 3 points establishes a 2-month cadence → nothing missing.
        assertTrue(MissingDocumentDetector.detect(List.of(
                s("statement", "Z", "2024-01"),
                s("statement", "Z", "2024-03"),
                s("statement", "Z", "2024-05"))).isEmpty());
    }

    @Test
    void flagsTwoMissingInOneGap() {
        // Jan + Apr, 2 points → assume monthly → Feb + Mar missing.
        List<MissingDoc> m = MissingDocumentDetector.detect(List.of(
                s("bill", "Power", "2024-01"),
                s("bill", "Power", "2024-04")));
        assertEquals(List.of("February 2024", "March 2024"), labels(m));
    }

    @Test
    void hugeGapBetweenTwoIsTooSpeculativeToFlag() {
        // Jan + Aug (6 missing) → beyond the per-gap cap → stay silent.
        assertTrue(MissingDocumentDetector.detect(List.of(
                s("report", "R", "2024-01"),
                s("report", "R", "2024-08"))).isEmpty());
    }

    @Test
    void quarterlyGapIsDetected() {
        List<MissingDoc> m = MissingDocumentDetector.detect(List.of(
                s("GST return", "GSTN", "2024-Q1"),
                s("GST return", "GSTN", "2024-Q2"),
                s("GST return", "GSTN", "2024-Q4")));
        assertEquals(List.of("Q3 2024"), labels(m));
        assertEquals("quarterly", m.get(0).cadence());
    }

    @Test
    void yearlyGapIsDetected() {
        List<MissingDoc> m = MissingDocumentDetector.detect(List.of(
                s("ITR", "IncomeTax", "2020"),
                s("ITR", "IncomeTax", "2022")));
        assertEquals(List.of("2021"), labels(m));
        assertEquals("yearly", m.get(0).cadence());
    }

    @Test
    void differentIssuersAreTrackedSeparately() {
        List<MissingDoc> m = MissingDocumentDetector.detect(List.of(
                s("bank statement", "HDFC", "2024-01"),
                s("bank statement", "HDFC", "2024-03"),
                s("bank statement", "ICICI", "2024-01"))); // ICICI has only one → no claim
        assertEquals(1, m.size());
        assertEquals("February 2024", m.get(0).periodLabel());
        assertEquals("HDFC", m.get(0).issuer());
    }

    @Test
    void singleDocumentOrEmptyYieldsNothing() {
        assertTrue(MissingDocumentDetector.detect(List.of(
                s("GST return", "GSTN", "2024-01"))).isEmpty());
        assertTrue(MissingDocumentDetector.detect(List.of()).isEmpty());
    }
}

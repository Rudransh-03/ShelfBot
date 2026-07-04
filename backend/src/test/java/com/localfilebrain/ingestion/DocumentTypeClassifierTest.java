package com.localfilebrain.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link DocumentTypeClassifier#classify} — the free, local, LLM-free
 * document typing behind the Library's filter chips. The contract is
 * precision over recall: a confident anchor (distinctive header phrase or a
 * file-name hit) is required, otherwise the file stays "Other" rather than
 * being mis-labelled.
 */
class DocumentTypeClassifierTest {

    @Test
    void taxInvoiceHeader_classifiedAsInvoice() {
        assertEquals("Invoice", DocumentTypeClassifier.classify(
                "scan001.pdf",
                "TAX INVOICE\nInvoice No: 330\nBill To: Nova Systems\nTotal: ₹10,62,000"));
    }

    @Test
    void fileNameAloneAnchors_whenContentIsThin() {
        // OCR'd or image-light docs often yield little text — a clearly-named
        // file must still classify.
        assertEquals("Invoice", DocumentTypeClassifier.classify(
                "AcmeCorp-Invoice-014.pdf", "some faint scanned text"));
    }

    @Test
    void bankStatement_classified() {
        assertEquals("Bank statement", DocumentTypeClassifier.classify(
                "stmt.pdf",
                "ACCOUNT STATEMENT\nOpening Balance: 45,000\nClosing Balance: 52,000\nNEFT credit"));
    }

    @Test
    void salarySlip_classified() {
        assertEquals("Salary slip", DocumentTypeClassifier.classify(
                "jan.pdf", "SALARY SLIP for January\nGross Pay: 90,000\nNet Pay: 74,000"));
    }

    @Test
    void gstReturn_classifiedAsTax() {
        assertEquals("Tax & GST", DocumentTypeClassifier.classify(
                "ret.pdf", "FORM GSTR-3B\nGoods and Services Tax Return\nTaxable value: 2,00,000"));
    }

    @Test
    void camelCaseFileName_tokenizedForMatching() {
        assertEquals("Purchase order", DocumentTypeClassifier.classify(
                "SharmaBakery-PurchaseOrder-77.pdf", "supply of flour and sugar"));
    }

    @Test
    void genericWordsWithoutAnchor_stayOther() {
        // A textbook chapter mentioning "settlement" and "agreement" must NOT
        // become a Contract — no distinctive header, no file-name hit.
        assertEquals("Other", DocumentTypeClassifier.classify(
                "chapter3.pdf",
                "The parties reached agreement after settlement talks were held. "
              + "Historians note the terms and conditions of the era."));
    }

    @Test
    void unrelatedContent_staysOther() {
        assertEquals("Other", DocumentTypeClassifier.classify(
                "notes.md", "Ideas for the weekend trip. Buy groceries. Call mom."));
        assertEquals("Other", DocumentTypeClassifier.classify(null, null));
    }

    @Test
    void leaseAgreement_classifiedAsContract() {
        assertEquals("Contract", DocumentTypeClassifier.classify(
                "flat.pdf", "LEASE AGREEMENT\nThis lease is made between the lessor and lessee"));
    }

    @Test
    void aadhaar_classifiedAsId() {
        assertEquals("ID", DocumentTypeClassifier.classify(
                "card.jpg", "Government of India\nAadhaar\nUIDAI enrolment"));
    }
}

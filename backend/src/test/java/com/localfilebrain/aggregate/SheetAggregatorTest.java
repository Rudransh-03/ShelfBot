package com.localfilebrain.aggregate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic aggregation over real-shaped fact sheets — the exact scenarios that
 * broke before: "unpaid" mis-read as paid, a client's name variants not merging, a
 * settled invoice, and a retainer rate wrongly counted as a due. No LLM, so it's a
 * fast, repeatable guard on the money math.
 */
class SheetAggregatorTest {

    private static SheetExtractor.Sheet sheet(String name, String json) {
        return new SheetExtractor.Sheet("/x/" + name, name, json);
    }

    // The messy corpus, distilled to sheets (mirrors what the extractor produces).
    private static List<SheetExtractor.Sheet> corpus() {
        return List.of(
            sheet("blue_invoice.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Lone Star CPA Group","role":"provider"},
                         {"name":"Blue Ridge Landscaping LLC","role":"client"}],
                 "amounts":[{"label":"total","value":4200,"status":"owed"}]}"""),
            sheet("blue_email.pdf", """
                {"doc_type":"email","is_personal":false,
                 "orgs":[{"name":"Blue Ridge Landscaping","role":"client"},
                         {"name":"Lone Star CPA","role":"provider"}],
                 "amounts":[{"label":"invoice LS-2041","value":4200,"status":"partial"},
                            {"label":"partial payment","value":1200,"status":"paid"},
                            {"label":"remaining balance","value":3000,"status":"owed"}]}"""),
            sheet("technova_invoice.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Lone Star CPA Group","role":"provider"}],
                 "people":[{"name":"TechNova Solutions Inc","role":"client"}],
                 "amounts":[{"label":"total","value":3600,"status":"pending"}]}"""),
            sheet("aurora_invoice.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Lone Star CPA Group","role":"provider"}],
                 "people":[{"name":"Aurora Bakery LLC","role":"client"}],
                 "amounts":[{"label":"total","value":2750,"status":"owed"}]}"""),
            sheet("delgado_invoice.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Lone Star CPA Group","role":"provider"},
                         {"name":"Delgado Auto Repair","role":"client"}],
                 "amounts":[{"label":"total","value":1500,"status":"unpaid"}]}"""),
            sheet("marisol_invoice.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Lone Star CPA Group","role":"provider"},
                         {"name":"Marisol Reyes","role":"client"}],
                 "amounts":[{"label":"total","value":850,"status":"unpaid"}]}"""),
            sheet("summit_invoice.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Lone Star CPA Group","role":"provider"},
                         {"name":"Summit Fitness Inc","role":"client"}],
                 "amounts":[{"label":"total","value":5000,"status":"unpaid"}]}"""),
            sheet("summit_payment.pdf", """
                {"doc_type":"payment confirmation","is_personal":false,
                 "orgs":[{"name":"Summit Fitness Inc","role":"service provider"}],
                 "amounts":[{"label":"total payment","value":5000,"status":"paid"}]}"""),
            sheet("kwan_engagement.pdf", """
                {"doc_type":"engagement letter","is_personal":false,
                 "orgs":[{"name":"Kwan Family Trust","role":"client"},
                         {"name":"Lone Star CPA Group","role":"provider"}],
                 "amounts":[{"label":"quarterly fee","value":500,"status":"unknown"}]}"""),
            sheet("vet_bill.pdf", """
                {"doc_type":"invoice","is_personal":true,
                 "orgs":[{"name":"Lakeway Veterinary Clinic","role":"provider"}],
                 "amounts":[{"label":"total","value":312,"status":"paid"}]}""")
        );
    }

    private final SheetAggregator agg = new SheetAggregator();

    private static SheetQuery amounts(SheetQuery.Op op, String status, String scope) {
        return new SheetQuery(true, "", SheetQuery.Select.AMOUNTS, op, status, "", null, "", "", "", scope, false, "");
    }

    // ── Student corpus: bills the user OWES (a different domain / direction) ──────
    private static List<SheetExtractor.Sheet> studentCorpus() {
        return List.of(
            sheet("tuition.pdf", """
                {"doc_type":"invoice","is_personal":true,
                 "orgs":[{"name":"Northfield State University","role":"biller"}],
                 "amounts":[{"label":"tuition","value":8400,"status":"owed"},
                            {"label":"scholarship","value":-3000,"status":"unknown"},
                            {"label":"payment received","value":-1000,"status":"paid"},
                            {"label":"balance due","value":4400,"status":"owed"}]}"""),
            sheet("rent.pdf", """
                {"doc_type":"lease","is_personal":true,
                 "orgs":[{"name":"Okafor Properties","role":"landlord"}],
                 "amounts":[{"label":"monthly rent","value":650,"status":"owed"},
                            {"label":"security deposit","value":650,"status":"paid"}]}"""),
            sheet("library.pdf", """
                {"doc_type":"statement","is_personal":true,
                 "orgs":[{"name":"Campus Library","role":"biller"}],
                 "amounts":[{"label":"amount due","value":24,"status":"overdue"}]}"""),
            sheet("scholarship.pdf", """
                {"doc_type":"award","is_personal":true,
                 "orgs":[{"name":"NSU Financial Aid","role":"provider"}],
                 "amounts":[{"label":"scholarship","value":3000,"status":"paid"}]}"""),
            sheet("payslip.pdf", """
                {"doc_type":"payslip","is_personal":true,
                 "orgs":[{"name":"Campus Dining","role":"employer"}],
                 "amounts":[{"label":"net pay","value":720,"status":"paid"}]}""")
        );
    }

    @Test
    void studentTotalIOweIncludesPersonalBillsExcludesIncomeAndCredits() {
        // The generic case that used to fail: personal bills ARE money owed.
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.SUM, "unpaid", "i_owe"), studentCorpus());
        assertNotNull(r, "student bills were dropped");
        assertTrue(r.text().contains("5,074"), "was: " + r.text());   // 4400 + 650 + 24
        assertFalse(r.text().contains("720"));    // payslip income, not owed
        assertFalse(r.text().contains("3,000"));  // scholarship credit, not owed
    }

    @Test
    void studentBiggestBillIsTuitionBalanceNotGross() {
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.MAX, "unpaid", "i_owe"), studentCorpus());
        assertNotNull(r);
        assertTrue(r.text().contains("4,400") && !r.text().contains("8,400"), "was: " + r.text());
    }

    // ── Other user types — guard against CA-specific overfit ─────────────────────

    // A doctor's clinic: bills PATIENTS (owed to me), and also receives vendor/rent
    // bills (I owe). The clinic itself must never be counted as its own client.
    private static List<SheetExtractor.Sheet> clinicCorpus() {
        return List.of(
            sheet("sam1.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Rao Family Clinic","role":"provider"}],
                 "people":[{"name":"Sam Wilson","role":"patient"}],
                 "amounts":[{"label":"consultation","value":250,"status":"unpaid"}]}"""),
            sheet("sam2.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Rao Family Clinic","role":"provider"}],
                 "people":[{"name":"Sam Wilson","role":"patient"}],
                 "amounts":[{"label":"follow-up","value":200,"status":"unpaid"}]}"""),
            sheet("nina.pdf", """
                {"doc_type":"statement","is_personal":false,
                 "orgs":[{"name":"Rao Family Clinic","role":"provider"}],
                 "people":[{"name":"Nina Patel","role":"patient"}],
                 "amounts":[{"label":"procedure","value":600,"status":"owed"},
                            {"label":"payment","value":200,"status":"paid"},
                            {"label":"balance due","value":400,"status":"owed"}]}"""),
            sheet("medline.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"MedLine Supplies","role":"provider"},
                         {"name":"Rao Family Clinic","role":"bill to"}],
                 "amounts":[{"label":"supplies","value":1200,"status":"unpaid"}]}"""),
            sheet("rent.pdf", """
                {"doc_type":"lease","is_personal":false,
                 "orgs":[{"name":"Parkview Realty","role":"landlord"},
                         {"name":"Rao Family Clinic","role":"tenant"}],
                 "amounts":[{"label":"rent","value":2000,"status":"owed"}]}""")
        );
    }

    @Test
    void clinicPatientReceivablesSumExcludeOwnPayables() {
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.SUM, "unpaid", "owed_to_me"), clinicCorpus());
        assertNotNull(r);
        assertTrue(r.text().contains("850"), "was: " + r.text());        // Sam 450 + Nina 400
        assertTrue(r.text().contains("Sam Wilson") && r.text().contains("Nina Patel"));
        assertFalse(r.text().contains("Rao Family Clinic"), "owner counted as its own client: " + r.text());
        assertFalse(r.text().contains("1,200"), "own vendor payable counted as receivable: " + r.text());
    }

    @Test
    void statedBalanceDueBeatsMislabeledPaidCharge() {
        // Messy statement: the $600 charge got mislabeled "paid", but the doc
        // explicitly states a $400 balance due. Trust the balance, not the misread.
        List<SheetExtractor.Sheet> s = List.of(sheet("nina.pdf", """
            {"doc_type":"statement","is_personal":false,
             "orgs":[{"name":"Rao Clinic","role":"provider","side":"owed"}],
             "people":[{"name":"Nina Patel","role":"patient","side":"owes"}],
             "amounts":[{"label":"total procedures and labs","value":600,"status":"paid"},
                        {"label":"balance due","value":400,"status":"owed"}]}"""));
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.SUM, "unpaid", "owed_to_me"), s);
        assertNotNull(r);
        assertTrue(r.text().contains("400") && !r.text().contains("600"), "was: " + r.text());
    }

    @Test
    void clinicWhoOwesMostIsAPatient() {
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.MAX, "unpaid", "owed_to_me"), clinicCorpus());
        assertNotNull(r);
        assertTrue(r.text().contains("Sam Wilson"), "was: " + r.text());
    }

    @Test
    void clinicWhatIOweIsVendorsAndRent() {
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.SUM, "unpaid", "i_owe"), clinicCorpus());
        assertNotNull(r);
        assertTrue(r.text().contains("3,200"), "was: " + r.text());       // MedLine 1200 + rent 2000
        assertTrue(r.text().contains("MedLine") && r.text().contains("Parkview"));
    }

    @Test
    void clinicCountsPatientsNotVendors() {
        SheetQuery q = new SheetQuery(true, "", SheetQuery.Select.PARTIES, SheetQuery.Op.COUNT,
                "", "patient", null, "", "", "", "", false, "");
        SheetAggregator.Result r = agg.run(q, clinicCorpus());
        assertNotNull(r);
        assertTrue(r.text().startsWith("2"), "was: " + r.text());          // Sam + Nina
        assertFalse(r.text().contains("MedLine") || r.text().contains("Parkview") || r.text().contains("Rao"));
    }

    @Test
    void novelProfessionRoleWorksViaSideField() {
        // "coach" / "mentee" are in NO role list — only the model-tagged side makes
        // this work. Proves the root fix: code reads side, not a keyword list.
        List<SheetExtractor.Sheet> coach = List.of(
            sheet("session1.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Growth Coaching","role":"coach","side":"issuer"}],
                 "people":[{"name":"Priya","role":"mentee","side":"recipient"}],
                 "amounts":[{"label":"session","value":300,"status":"unpaid"}]}"""),
            sheet("session2.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Growth Coaching","role":"coach","side":"issuer"}],
                 "people":[{"name":"Raj","role":"mentee","side":"recipient"}],
                 "amounts":[{"label":"session","value":200,"status":"unpaid"}]}"""));
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.SUM, "unpaid", "owed_to_me"), coach);
        assertNotNull(r);
        assertTrue(r.text().contains("500"), "was: " + r.text());          // Priya 300 + Raj 200
        assertTrue(r.text().contains("Priya") && r.text().contains("Raj"));
        assertFalse(r.text().contains("Growth Coaching"), "owner counted: " + r.text());
    }

    @Test
    void recurringSubscriptionVendorNotMistakenForOwner() {
        // A monthly subscription: the same vendor RECURS as provider. The old
        // owner-detection would treat the vendor as the owner and drop every bill.
        List<SheetExtractor.Sheet> subs = List.of(
            sheet("cloud_jul.pdf", """
                {"doc_type":"invoice","is_personal":true,
                 "orgs":[{"name":"CloudStore","role":"provider"}],
                 "amounts":[{"label":"monthly plan","value":50,"status":"unpaid"}]}"""),
            sheet("cloud_aug.pdf", """
                {"doc_type":"invoice","is_personal":true,
                 "orgs":[{"name":"CloudStore","role":"provider"}],
                 "amounts":[{"label":"monthly plan","value":50,"status":"unpaid"}]}"""),
            sheet("cloud_jun.pdf", """
                {"doc_type":"invoice","is_personal":true,
                 "orgs":[{"name":"CloudStore","role":"provider"}],
                 "amounts":[{"label":"monthly plan","value":50,"status":"paid"}]}"""));
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.SUM, "unpaid", "i_owe"), subs);
        assertNotNull(r, "recurring vendor's bills were dropped");
        assertTrue(r.text().contains("CloudStore") && r.text().contains("100"), "was: " + r.text());
    }

    @Test
    void freelancerRepeatClientOwedToMe() {
        List<SheetExtractor.Sheet> fl = List.of(
            sheet("acme1.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Me Design Studio","role":"provider"},
                         {"name":"Acme Co","role":"client"}],
                 "amounts":[{"label":"design work","value":500,"status":"unpaid"}]}"""),
            sheet("acme2.pdf", """
                {"doc_type":"invoice","is_personal":false,
                 "orgs":[{"name":"Me Design Studio","role":"provider"},
                         {"name":"Acme Co","role":"client"}],
                 "amounts":[{"label":"logo","value":500,"status":"unpaid"}]}"""));
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.SUM, "unpaid", "owed_to_me"), fl);
        assertNotNull(r);
        assertTrue(r.text().contains("Acme") && r.text().contains("1,000"), "was: " + r.text());
    }

    @Test
    void patientMedicalBillsFromDifferentProviders() {
        List<SheetExtractor.Sheet> med = List.of(
            sheet("hospital.pdf", """
                {"doc_type":"bill","is_personal":true,
                 "orgs":[{"name":"City Hospital","role":"provider"}],
                 "amounts":[{"label":"amount due","value":300,"status":"unpaid"}]}"""),
            sheet("pharmacy.pdf", """
                {"doc_type":"receipt","is_personal":true,
                 "orgs":[{"name":"Rx Pharmacy","role":"provider"}],
                 "amounts":[{"label":"balance due","value":40,"status":"unpaid"}]}"""));
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.SUM, "unpaid", "i_owe"), med);
        assertNotNull(r);
        assertTrue(r.text().contains("340"), "was: " + r.text());
        assertTrue(r.text().contains("City Hospital") && r.text().contains("Rx Pharmacy"));
    }

    @Test
    void totalUnpaidNetsPartialExcludesPaidAndRetainer() {
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.SUM, "unpaid", "owed_to_me"), corpus());
        assertNotNull(r);
        // 3600 + 3000(blue, netted) + 2750 + 1500 + 850 = 11,700
        assertTrue(r.text().contains("11,700"), "total was: " + r.text());
        // Delgado ("unpaid") must not be dropped by an "unpaid".contains("paid") slip.
        assertTrue(r.text().contains("Delgado"));
        // Blue Ridge appears once, netted to 3,000 — never the 4,200 gross.
        assertTrue(r.text().contains("3,000") && !r.text().contains("4,200"));
        // Settled Summit and the Kwan retainer are excluded.
        assertFalse(r.text().contains("Summit"));
        assertFalse(r.text().contains("Kwan"));
    }

    @Test
    void whoOwesMostIsTechNovaAfterBlueIsNetted() {
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.MAX, "unpaid", "owed_to_me"), corpus());
        assertNotNull(r);
        assertTrue(r.text().contains("TechNova"), "was: " + r.text());
    }

    @Test
    void whoHasPaidIsSummit() {
        SheetAggregator.Result r = agg.run(amounts(SheetQuery.Op.LIST, "paid", "owed_to_me"), corpus());
        assertNotNull(r);
        assertTrue(r.text().contains("Summit"), "was: " + r.text());
    }

    @Test
    void deadlinesOnlyDropsRecordDatesAcrossDomains() {
        // Mixed domains, each with a real deadline AND a record date on the same doc.
        List<SheetExtractor.Sheet> mixed = List.of(
            sheet("cpa_filing.pdf", """
                {"doc_type":"tax notice","is_personal":false,
                 "dates":[{"label":"issued","date":"2026-07-02","deadline":false},
                          {"label":"filing due","date":"2026-07-15","deadline":true}]}"""),
            sheet("student_exam.pdf", """
                {"doc_type":"syllabus","is_personal":false,
                 "dates":[{"label":"final exam","date":"2026-07-20","deadline":true}]}"""),
            sheet("lawyer_case.pdf", """
                {"doc_type":"court notice","is_personal":false,
                 "dates":[{"label":"filed on","date":"2026-06-30","deadline":false},
                          {"label":"hearing","date":"2026-07-25","deadline":true}]}"""));
        SheetQuery deadlines = new SheetQuery(true, "", SheetQuery.Select.DATES, SheetQuery.Op.LIST,
                "", "", null, "", "2026-07-01", "2026-07-31", "", true, "");   // obligationsOnly
        SheetAggregator.Result r = agg.run(deadlines, mixed);
        assertNotNull(r);
        assertTrue(r.text().contains("filing due") && r.text().contains("final exam")
                && r.text().contains("hearing"), "was: " + r.text());
        assertFalse(r.text().contains("issued") || r.text().contains("filed on"), "record date leaked: " + r.text());
    }

    @Test
    void emptyConcreteAggregateAnswersNoneNotNull() {
        // A deadlines query for a period with nothing must answer "none" — not return
        // null (which would wrongly hand a valid empty result to the LLM).
        List<SheetExtractor.Sheet> one = List.of(sheet("x.pdf", """
            {"doc_type":"invoice","dates":[{"label":"due","date":"2026-12-01","deadline":true}]}"""));
        SheetQuery q = new SheetQuery(true, "", SheetQuery.Select.DATES, SheetQuery.Op.LIST,
                "", "", null, "", "2026-07-01", "2026-07-31", "", true, "");   // July → nothing
        SheetAggregator.Result r = agg.run(q, one);
        assertNotNull(r, "empty result wrongly returned null → would fall to LLM");
        assertTrue(r.text().toLowerCase().contains("no deadlines"), "was: " + r.text());
    }

    @Test
    void listPersonalDocumentsOnly() {
        SheetQuery q = new SheetQuery(true, "", SheetQuery.Select.DOCUMENTS, SheetQuery.Op.LIST,
                "", "", true, "", "", "", "", false, "");
        SheetAggregator.Result r = agg.run(q, corpus());
        assertNotNull(r);
        assertTrue(r.text().toLowerCase().contains("vet") || r.text().contains("312") || r.sources().contains("vet_bill.pdf"));
        assertFalse(r.text().contains("Invoice"));   // business invoices excluded
    }
}

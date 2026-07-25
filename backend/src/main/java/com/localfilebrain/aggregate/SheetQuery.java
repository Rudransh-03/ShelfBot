package com.localfilebrain.aggregate;

/**
 * A structured, generic plan for a corpus-wide question, produced by the LLM from
 * the user's message and executed deterministically in code over the fact sheets.
 * It is NOT payment-specific: {@code select} chooses WHICH structured field to work
 * on (money amounts, parties, whole documents, or dates) and {@code op} chooses the
 * calculation. The filters narrow the rows. This is how one engine answers totals,
 * counts, lists, "who owes most", "how many clients", "which are personal", and
 * date questions — all by filtering the same sheet fields, never by the LLM
 * guessing which items to include.
 */
public record SheetQuery(
        boolean aggregate,      // false → not a corpus-wide question; let RAG answer
        String rewrite,         // standalone rewrite of a follow-up, else ""
        Select select,          // which structured field to aggregate over
        Op op,                  // the calculation
        String status,          // amounts: "unpaid"|"paid"|"partial"|"owed"|""  (any)
        String role,            // parties:  "client"|"customer"|...|""           (any)
        Boolean isPersonal,     // documents: true|false|null (any)
        String docType,         // documents: substring of doc_type, or ""
        String dateFrom,        // dates: yyyy-MM-dd or ""
        String dateTo,          // dates: yyyy-MM-dd or ""
        String scope,           // amounts: "owed_to_me" | "i_owe" | "" (either direction)
        boolean obligationsOnly, // dates: true → only real deadlines, not record dates
        String category,         // filter to one money category (utility/rent/…), or ""
        String amountRange,      // amounts: ">3000" / "<500" numeric filter, or ""
        String dateType) {       // dates: payment/renewal/filing/appointment kind, or ""

    public enum Select { AMOUNTS, PARTIES, DOCUMENTS, DATES }
    // AVERAGE / COMPARE / DIFFERENCE: the open calculations the plain menu lacked. The
    // LLM only NAMES which one (routing); code resolves the exact per-party table and
    // does the arithmetic itself — a live test proved gpt-4o-mini mis-adds even 6
    // correct figures, so the math must never be delegated to it.
    public enum Op { SUM, COUNT, LIST, MAX, MIN, AVERAGE, COMPARE, DIFFERENCE, NONE }

    /** A non-aggregate plan: the question isn't corpus-wide, so RAG should answer. */
    public static SheetQuery passthrough(String rewrite) {
        return new SheetQuery(false, rewrite == null ? "" : rewrite,
                Select.DOCUMENTS, Op.NONE, "", "", null, "", "", "", "", false, "", "", "");
    }
}

package com.localfilebrain.aggregate;

import com.localfilebrain.ingestion.IndexMetadataStore;

/**
 * The starter set of aggregation categories the app ships knowing — so the
 * planner MATCHES these instead of inventing a fresh one (and re-scanning) for
 * the common questions. The registry still grows on genuinely new questions;
 * these are just the well-worn ones seeded once, idempotently.
 */
public final class BaseCategories {

    private BaseCategories() {}

    public static void seed(IndexMetadataStore meta) {
        if (meta == null) return;
        meta.putCategory("client_fee_owed",
                "money a client owes me for my professional fees",
                "A professional fee THIS firm billed a client and whether it is paid. "
              + "subject=client, key=invoice id, value=gross fee, balance=amount still owed if "
              + "partly paid, status=PAID/PENDING/PARTIAL/RECEIVED. Exclude the firm's own bills "
              + "to pay, a client's own sales invoices, taxes and refunds.",
                // Specific fee/receivable words only — NOT "$"/"paid"/"due"/"payment" (those
                // appear in nearly every doc and would extract the whole corpus).
                "fee fees professional invoice invoices owe owed owes outstanding unpaid receivable");

        meta.putCategory("upcoming_deadline",
                "an upcoming due date / filing deadline / renewal",
                "Any obligation with a due date. subject=what it is, date=the due date (yyyy-MM-dd), "
              + "label=type (filing/renewal/response/payment). One fact per distinct deadline.",
                "due deadline by expires expiry renewal file filing return response pay");

        meta.putCategory("client_party",
                "a client of the firm (a party the firm serves)",
                "The client this document belongs to - a person or business the firm provides "
              + "services to. subject=client name. EXCLUDE the firm's own employees, vendors, and "
              + "people merely mentioned in passing. One fact per distinct client.",
                "");   // no keyword — a client can appear in any document, so scan all

        meta.putCategory("personal_doc",
                "a personal, non-business document",
                "Whether this document is a PERSONAL (non-business) item - a personal bill, "
              + "receipt, ID, medical, vehicle, insurance for the owner. subject=the document, "
              + "label='personal' only when it is personal; output [] when it is a business doc.",
                "");   // no keyword — personal docs share no signal word, so scan all
    }
}

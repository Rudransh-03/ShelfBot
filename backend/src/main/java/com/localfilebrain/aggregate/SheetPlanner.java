package com.localfilebrain.aggregate;

import com.localfilebrain.llm.GPT4oMiniClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the user's message into a {@link SheetQuery} — deciding whether it is a
 * corpus-wide question and, if so, WHICH structured field to work on and WHICH
 * calculation to run. The LLM only classifies (its strength); the actual filtering
 * and arithmetic happen deterministically in {@link SheetAggregator}.
 */
public final class SheetPlanner {

    private static final Logger log = LoggerFactory.getLogger(SheetPlanner.class);

    private final GPT4oMiniClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public SheetPlanner(GPT4oMiniClient llm) { this.llm = llm; }

    private static final String SYSTEM = """
            You turn a user's message about their own documents into a small JSON plan.
            Output ONLY the JSON object.

            {"aggregate":true|false,"rewrite":"",
             "select":"amounts|parties|documents|dates",
             "operation":"sum|count|list|max|min|none",
             "status":"unpaid|paid|partial|owed|",
             "role":"client|customer|",
             "is_personal":true|false|null,
             "doc_type":"","date_from":"","date_to":"",
             "scope":"owed_to_me|i_owe|","obligations_only":true|false}

            "aggregate" is true when answering needs the WHOLE collection: totals,
            counts, "list all X", "who owes the most", "how many clients", "which are
            personal", deadlines across everything. It is false for a fact from one or
            a few specific documents (then RAG answers). It is ALSO false whenever the
            question is scoped to ONE specific named item — a particular sale, property,
            invoice, client, or person — EVEN IF it mentions money ("am I owed on the
            Pine St sale?", "is the Blue Ridge invoice paid?"). Aggregate is only for
            the whole collection, never a single named thing.

            Choose "select" — the kind of thing to work on:
            • amounts  → money questions (owed, unpaid, paid, totals, who owes most,
                         who/what I need to pay). set "status" (unpaid/paid/…), use
                         operation sum / list / max / min / count, and set "scope":
                           – "owed_to_me" when it's money OTHERS owe the user (their
                             fees, receivables, "who owes me").
                           – "i_owe" when it's money the USER owes (their bills, "how
                             much do I owe", "who do I need to pay").
            • parties  → people or organisations (how many/which clients, customers).
                         set "role" and use operation count / list.
            • documents→ the documents themselves (how many docs, list personal ones,
                         list invoices). set "is_personal" and/or "doc_type"; use
                         count / list.
            • dates    → deadlines / due dates / expiries / appointments. set
                         "date_from"/"date_to" (yyyy-MM-dd) if a period is implied; use
                         list / count. Set "obligations_only":true when the question is
                         about things the user must DO/meet (deadlines, due dates,
                         renewals) rather than every date on file.

            "rewrite": if the message leans on the conversation (pronouns, "and
            Aurora?", "the biggest of those"), rewrite it standalone. Else "".
            Leave unused fields empty ("" or null). When not aggregate, set
            "aggregate":false and the rest can be empty.

            You are given TODAY'S DATE. Use it to fill date_from/date_to for relative
            periods: "this month" = the 1st to the last day of the current month;
            "this week", "next 30 days", "by Friday" likewise. Always resolve to
            concrete yyyy-MM-dd.

            Examples (assume today is 2026-07-14):
            "total unpaid fees across my clients" →
              {"aggregate":true,"select":"amounts","operation":"sum","status":"unpaid","scope":"owed_to_me"}
            "who owes me the most?" →
              {"aggregate":true,"select":"amounts","operation":"max","status":"unpaid","scope":"owed_to_me"}
            "which clients have already paid?" →
              {"aggregate":true,"select":"amounts","operation":"list","status":"paid","scope":"owed_to_me"}
            "how much money do I owe in total?" →
              {"aggregate":true,"select":"amounts","operation":"sum","status":"unpaid","scope":"i_owe"}
            "who do I need to pay?" →
              {"aggregate":true,"select":"amounts","operation":"list","status":"unpaid","scope":"i_owe"}
            "what's my biggest bill?" →
              {"aggregate":true,"select":"amounts","operation":"max","status":"unpaid","scope":"i_owe"}
            "how many clients do I have?" →
              {"aggregate":true,"select":"parties","operation":"count","role":"client"}
            "list all my personal documents" →
              {"aggregate":true,"select":"documents","operation":"list","is_personal":true}
            "what deadlines do I have this month?" →
              {"aggregate":true,"select":"dates","operation":"list","date_from":"2026-07-01","date_to":"2026-07-31","obligations_only":true}
            "did I get any scholarship?" → {"aggregate":false}
            "am I owed anything on the Pine St sale?" → {"aggregate":false}
            "when is my credit card payment due?" → {"aggregate":false}
            "when is the rent due?" → {"aggregate":false}
            "what does the Blue Ridge invoice say?" → {"aggregate":false}

            "when is <a specific bill/document> due/renew/expire" is a LOOKUP about one
            thing → aggregate:false. Only "what deadlines / what's due (this period)"
            across everything is the dates aggregate.

            A question about ONE named thing (a specific property, invoice, person,
            sale) is aggregate:false — it's a lookup, not a corpus-wide roll-up.
            """;

    public SheetQuery plan(String question, String conversationContext) {
        if (llm == null) return SheetQuery.passthrough("");
        try {
            String user = "Today's date: " + java.time.LocalDate.now() + "\n"
                        + (conversationContext == null || conversationContext.isBlank() ? ""
                        : "Recent conversation:\n" + conversationContext + "\n")
                        + "Message: " + question;
            return parse(llm.oneShot(SYSTEM, user, 240, 0.0));
        } catch (Exception e) {
            log.warn("Sheet planner failed ({}), treating as non-aggregate", e.getMessage());
            return SheetQuery.passthrough("");
        }
    }

    SheetQuery parse(String raw) {
        try {
            JsonNode n = mapper.readTree(extractJson(raw));
            String rewrite = n.path("rewrite").asText("").trim();
            if (!n.path("aggregate").asBoolean(false)) return SheetQuery.passthrough(rewrite);

            SheetQuery.Select select = switch (n.path("select").asText("documents").trim().toLowerCase()) {
                case "amounts" -> SheetQuery.Select.AMOUNTS;
                case "parties" -> SheetQuery.Select.PARTIES;
                case "dates"   -> SheetQuery.Select.DATES;
                default        -> SheetQuery.Select.DOCUMENTS;
            };
            SheetQuery.Op op = switch (n.path("operation").asText("list").trim().toLowerCase()) {
                case "sum"   -> SheetQuery.Op.SUM;
                case "count" -> SheetQuery.Op.COUNT;
                case "max", "who_most", "highest" -> SheetQuery.Op.MAX;
                case "min", "lowest" -> SheetQuery.Op.MIN;
                case "none"  -> SheetQuery.Op.NONE;
                default      -> SheetQuery.Op.LIST;
            };
            Boolean isPersonal = null;
            JsonNode ip = n.get("is_personal");
            if (ip != null && ip.isBoolean()) isPersonal = ip.booleanValue();

            return new SheetQuery(true, rewrite, select, op,
                    n.path("status").asText("").trim().toLowerCase(),
                    n.path("role").asText("").trim().toLowerCase(),
                    isPersonal,
                    n.path("doc_type").asText("").trim().toLowerCase(),
                    n.path("date_from").asText("").trim(),
                    n.path("date_to").asText("").trim(),
                    n.path("scope").asText("").trim().toLowerCase(),
                    n.path("obligations_only").asBoolean(false));
        } catch (Exception e) {
            return SheetQuery.passthrough("");
        }
    }

    private static String extractJson(String s) {
        if (s == null) return "{}";
        int lb = s.indexOf('{'), rb = s.lastIndexOf('}');
        return (lb >= 0 && rb > lb) ? s.substring(lb, rb + 1) : "{}";
    }
}

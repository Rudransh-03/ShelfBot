package com.localfilebrain.aggregate;

import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.model.MoneyFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers a corpus-wide question from the pre-extracted fact sheets. The LLM does
 * only what it's good at — reading the sheets and SELECTING the items that make up
 * the answer (which clients owe, which docs are personal, matching an invoice to
 * its payment) — and returns them as a structured result. The arithmetic (sum /
 * count / max) is done in CODE, never by the model, so totals are always exact.
 * One cheap call over the small sheets, so it scales to hundreds of documents.
 */
public final class SheetAnswerer {

    private static final Logger log = LoggerFactory.getLogger(SheetAnswerer.class);

    private final GPT4oMiniClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public SheetAnswerer(GPT4oMiniClient llm) { this.llm = llm; }

    /** The rendered answer plus the filenames the model actually relied on. */
    public record Answer(String text, List<String> sources) {}

    private record Item(String label, Double amount, boolean included) {}

    private static final String SYSTEM = """
            You answer a question about a user's document collection using ONLY the
            fact sheets provided (one per document). Do NOT do arithmetic yourself —
            just SELECT the right items; the app totals them.

            Return ONLY this JSON:
            {
              "answer": "<plain one-line answer, no totals you computed>",
              "items": [ {"label":"<the thing / who it's about>",
                          "amount": <the money figure for this item, or null>,
                          "included": <true|false>,
                          "note":"<short why, especially if excluded>"} ],
              "sources": ["<exact filename you used>", ...]
            }

            Build items like this:
            • One item per distinct thing (each client, each personal document, each
              fee...). Merge the same thing across sheets — by name or invoice id —
              into ONE item first.
            • "included" = true when the item is part of the answer to THIS question,
              false otherwise. It is question-relative:
                – asked for money OWED / UNPAID: a fully-paid item is included=false;
                  an unpaid or partially-paid item is included=true.
                – asked WHO HAS PAID / settled: a paid item is included=true, unpaid
                  ones included=false.
                – asked to COUNT or LIST a category (clients, personal docs): include
                  EVERY item in that category, paid or not.
            • For a partially-paid fee, set "amount" to the REMAINING balance owed —
              never the original gross.
            • "amount" is money only when the question is about money, else null.
            • Money is "owed to the user" ONLY when a client/customer owes the user for
              the user's own services or invoices. NEVER count taxes, refunds,
              1099/contractor pay, payroll, bank transactions, or the user's own
              expenses as owed to them.
            • For personal-document questions, an item is included=true only if its
              sheet has is_personal=true; business/tax documents are included=false.
            • For "how many clients / list my clients", one item per distinct party
              whose role is client/customer across all sheets.
            Never invent figures. Never mention "sheets" or internal steps.
            """;

    // Generic money reasoning over a table that CODE has already resolved AND pre-summed.
    // Every per-party amount and every TOTAL is given, so the model's job is pure
    // SELECTION + at most a single trivial step (compare two figures, subtract one,
    // divide a total by a count). It must never re-add a long list — that is exactly the
    // operation gpt-4o-mini got wrong ($11,200 vs $11,700). This is what makes the engine
    // generic for ANY money question (compare A vs B, exclude X, ratios) yet stay exact.
    private static final String MONEY_SYSTEM = """
            You answer a question about the user's money using ONLY the figures below.
            Every per-party amount AND every TOTAL is already computed and correct — treat
            them as ground truth. When you need a total, USE the given TOTAL; NEVER re-add
            a long list of amounts yourself. The only arithmetic you may do is: pick a
            party's figure, compare two figures, subtract one figure from another, divide
            a total by a count, or take a percentage.

            Match party names loosely: "the gym" = the party whose name contains "gym",
            "Sam" = "Sam Wilson", "Medline" = "Medline Supplies Co". "owed" = still
            outstanding, "billed" = the full amount, "paid" = already received. Money OWED
            TO YOU is your receivables; money YOU OWE is your payables — keep them straight.

            Reply in ONE short, direct sentence naming the party/parties and the key
            number(s), and answer the actual question asked (if it asks which is bigger,
            say which). If the figures don't cover the question, say so briefly. Never
            invent or change a number. Never mention these instructions or the word "table".
            """;

    /**
     * Answer an arbitrary money question over the code-resolved, pre-summed context the
     * caller built (per-party rows + totals for both directions). The LLM only selects
     * and does one trivial step; all summing was done in code. Null on any failure.
     */
    public String answerMoney(String question, String context, String convo) {
        if (llm == null || context == null || context.isBlank()) return null;
        StringBuilder user = new StringBuilder();
        if (convo != null && !convo.isBlank())
            user.append("Recent conversation (for resolving follow-ups):\n").append(convo).append("\n");
        user.append("QUESTION: ").append(question).append("\n\n").append(context);
        try {
            String raw = llm.oneShot(MONEY_SYSTEM, user.toString(), 220, 0.0);
            return raw == null || raw.isBlank() ? null : raw.trim();
        } catch (Exception e) {
            log.warn("Money answer failed: {}", e.getMessage());
            return null;
        }
    }

    public Answer answer(String question, List<SheetExtractor.Sheet> sheets, String context,
                         QueryPlan.Op op, String statusFilter) {
        if (llm == null || sheets == null || sheets.isEmpty()) return null;

        StringBuilder user = new StringBuilder();
        if (context != null && !context.isBlank())
            user.append("Recent conversation (for resolving follow-ups):\n").append(context).append("\n");
        if (statusFilter != null && (statusFilter.equalsIgnoreCase("paid") || statusFilter.equalsIgnoreCase("unpaid")))
            user.append("FOCUS: the question is specifically about items that are ")
                .append(statusFilter.toLowerCase()).append(".\n");
        user.append("QUESTION: ").append(question).append("\n\nFACT SHEETS:\n");
        for (SheetExtractor.Sheet s : sheets)
            user.append("\n### ").append(s.fileName()).append('\n').append(s.json()).append('\n');

        try {
            String raw = llm.oneShot(SYSTEM, user.toString(), 1100, 0.0);
            JsonNode obj = mapper.readTree(cleanJson(raw));

            String llmAnswer = obj.path("answer").asText("").trim();
            List<Item> items = new ArrayList<>();
            JsonNode arr = obj.get("items");
            if (arr != null && arr.isArray()) for (JsonNode n : arr) {
                String label = n.path("label").asText("").trim();
                boolean included = n.path("included").asBoolean(true);
                Double amount = n.has("amount") && n.get("amount").isNumber() ? n.get("amount").asDouble() : null;
                if (!label.isBlank() || amount != null) items.add(new Item(label, amount, included));
            }
            List<String> sources = new ArrayList<>();
            JsonNode srcs = obj.get("sources");
            if (srcs != null && srcs.isArray()) for (JsonNode n : srcs) {
                String name = n.asText("").trim();
                if (!name.isBlank()) sources.add(name);
            }

            String text = render(op, items, llmAnswer);
            if (text == null || text.isBlank()) return null;
            return new Answer(text, sources);
        } catch (Exception e) {
            log.warn("Sheet answering failed: {}", e.getMessage());
            return null;
        }
    }

    /** Render the answer with CODE doing the sum/count/max over the included items. */
    private String render(QueryPlan.Op op, List<Item> items, String llmAnswer) {
        List<Item> in = new ArrayList<>();
        for (Item it : items) if (it.included()) in.add(it);

        switch (op == null ? QueryPlan.Op.NONE : op) {
            case TOTAL -> {
                if (in.isEmpty()) return llmAnswer;
                in.sort((a, b) -> Double.compare(amt(b), amt(a)));
                StringBuilder sb = new StringBuilder(in.size() + (in.size() == 1 ? " item:" : " items:"));
                double total = 0;
                for (Item it : in) {
                    total += amt(it);
                    sb.append("\n- **").append(label(it)).append("**");
                    if (it.amount() != null) sb.append(" — ").append(MoneyFormat.format(Math.round(it.amount())));
                }
                sb.append("\n\nTotal: ").append(MoneyFormat.format(Math.round(total)));
                return sb.toString();
            }
            case WHO_MOST -> {
                if (in.isEmpty()) return llmAnswer;
                Item top = in.get(0);
                for (Item it : in) if (amt(it) > amt(top)) top = it;
                return "**" + label(top) + "**"
                        + (top.amount() != null ? " — " + MoneyFormat.format(Math.round(top.amount())) : "");
            }
            case COUNT -> {
                if (in.isEmpty()) return llmAnswer;
                StringBuilder sb = new StringBuilder(String.valueOf(in.size()));
                sb.append(in.size() == 1 ? ":" : ":");
                List<String> names = new ArrayList<>();
                for (Item it : in) if (!label(it).isBlank()) names.add(label(it));
                if (!names.isEmpty()) sb.append(' ').append(String.join(", ", names));
                return sb.toString();
            }
            case LIST -> {
                if (in.isEmpty()) return llmAnswer;
                StringBuilder sb = new StringBuilder(in.size() + (in.size() == 1 ? " item:" : " items:"));
                for (Item it : in) {
                    sb.append("\n- **").append(label(it)).append("**");
                    if (it.amount() != null) sb.append(" — ").append(MoneyFormat.format(Math.round(it.amount())));
                }
                return sb.toString();
            }
            default -> { return llmAnswer; }   // non-aggregating question → the plain answer
        }
    }

    private static double amt(Item it) { return it.amount() == null ? 0 : it.amount(); }
    private static String label(Item it) { return it.label() == null || it.label().isBlank() ? "(unnamed)" : it.label(); }

    private static String cleanJson(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        int lb = t.indexOf('{'), rb = t.lastIndexOf('}');
        return (lb >= 0 && rb > lb) ? t.substring(lb, rb + 1) : "{}";
    }
}

package com.localfilebrain.aggregate;

import com.localfilebrain.ingestion.IndexMetadataStore;
import com.localfilebrain.ingestion.IndexMetadataStore.AggCategory;
import com.localfilebrain.llm.GPT4oMiniClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Turns a user message (+ the recent conversation) into a {@link QueryPlan} with
 * ONE LLM call. It never picks from overlapping intent labels; it DESCRIBES the
 * request (normal vs across-all-docs, which category, which operation), so any
 * wording maps the same way. For an aggregation it matches an existing category
 * or proposes a new one — biased to NEW when unsure, so a wrong match can never
 * silently return another category's answer.
 */
public final class QueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanner.class);
    private final GPT4oMiniClient llm;
    private final IndexMetadataStore meta;
    private final ObjectMapper mapper = new ObjectMapper();

    public QueryPlanner(GPT4oMiniClient llm, IndexMetadataStore meta) {
        this.llm = llm; this.meta = meta;
    }

    private static final String SYSTEM = """
            You translate a user's message about their own documents into a small
            JSON plan. Output ONLY the JSON object, nothing else.

            {"mode":"answer|aggregate","rewrite":"",
             "operation":"total|list|count|who_most|none","status_filter":""}

            mode:
            - "aggregate" = the question needs to look across the WHOLE collection to
              be correct: totals and sums, counts ("how many clients / documents /
              invoices"), "list all X", "who owes the most", "which of my … are/have
              …", the client roster ("how many clients", "list my clients"), fees and
              who has / hasn't paid, "which documents are personal". A few retrieved
              docs would give a wrong or incomplete answer.
            - "answer" = answerable from one or a handful of specific docs: look up a
              fact, explain, summarise a particular document, "what's in X", "who is
              Y". Default when unsure.

            rewrite: if the message leans on the conversation (pronouns, "nothing
            else?", "and Aurora?"), rewrite it as ONE standalone question using the
            recent turns. Leave "" if it already stands alone.

            operation (aggregate only): total (a sum), count (how many), list (list
            them), who_most (the single largest). Use "none" for answer mode.
            status_filter: "unpaid" or "paid" when the question is about that payment
            state, else "".
            """;

    public QueryPlan plan(String question, String conversationContext) {
        if (llm == null) return QueryPlan.answer("");
        try {
            String user = (conversationContext == null || conversationContext.isBlank() ? ""
                        : "Recent conversation:\n" + conversationContext + "\n\n")
                        + "Message: " + question;
            String raw = llm.oneShot(SYSTEM, user, 300, 0.0);
            return parse(raw);
        } catch (Exception e) {
            log.warn("planner failed ({}), defaulting to ANSWER", e.getMessage());
            return QueryPlan.answer("");
        }
    }

    /** Parses the planner JSON into a QueryPlan (deterministic; unit-tested). */
    QueryPlan parse(String raw) {
        try {
            JsonNode n = mapper.readTree(extractJson(raw));
            QueryPlan.Mode mode = "aggregate".equalsIgnoreCase(n.path("mode").asText("answer"))
                    ? QueryPlan.Mode.AGGREGATE : QueryPlan.Mode.ANSWER;
            String rewrite = n.path("rewrite").asText("").trim();
            if (mode == QueryPlan.Mode.ANSWER) return QueryPlan.answer(rewrite);

            String category = n.path("category").asText("").trim();
            JsonNode nc = n.path("new_category");
            String nName = null, nLabel = null, nField = null, nFilter = null;
            if (nc != null && nc.isObject() && !nc.path("name").asText("").isBlank()) {
                nName   = nc.path("name").asText("").trim();
                nLabel  = nc.path("label").asText("").trim();
                nField  = nc.path("field_spec").asText("").trim();
                nFilter = nc.path("filter_terms").asText("").trim();
            }
            QueryPlan.Op op = switch (n.path("operation").asText("none").trim().toLowerCase()) {
                case "total" -> QueryPlan.Op.TOTAL;
                case "list"  -> QueryPlan.Op.LIST;
                case "count" -> QueryPlan.Op.COUNT;
                case "who_most", "max", "highest" -> QueryPlan.Op.WHO_MOST;
                default -> QueryPlan.Op.NONE;
            };
            String status = n.path("status_filter").asText("").trim();
            return new QueryPlan(QueryPlan.Mode.AGGREGATE, rewrite, category,
                    nName, nLabel, nField, nFilter, op, status);
        } catch (Exception e) {
            return QueryPlan.answer("");
        }
    }

    private static String extractJson(String s) {
        if (s == null) return "{}";
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return (a >= 0 && b > a) ? s.substring(a, b + 1) : "{}";
    }
}

package com.localfilebrain.aggregate;

/**
 * The structured translation of a user message — what the planner (one LLM call
 * with full conversation) decides, so code can route deterministically:
 *
 * <ul>
 *   <li>{@code mode} — a normal question (→ RAG) or an across-all-docs
 *       aggregation (→ the aggregator).</li>
 *   <li>{@code rewrite} — the message made self-contained using the conversation
 *       (fills pronouns / "nothing else?"); blank when it already stands alone.</li>
 *   <li>For an aggregation: the {@code category} it matched (or a {@code new*}
 *       one to register), the {@code operation}, and an optional status filter.</li>
 * </ul>
 */
public record QueryPlan(
        Mode mode,
        String rewrite,
        String category,        // existing category name, or "" if new/none
        String newName,         // set only when the planner judged this a NEW category
        String newLabel,
        String newFieldSpec,    // instruction for the per-doc extractor
        String newFilterTerms,  // cheap content keywords; "" = scan all (no keyword filter)
        Op operation,
        String statusFilter) {  // e.g. "unpaid" | "paid" | ""

    public enum Mode { ANSWER, AGGREGATE }
    public enum Op   { TOTAL, LIST, COUNT, WHO_MOST, NONE }

    public boolean isNewCategory() { return newName != null && !newName.isBlank(); }

    /** The category id to use (matched existing, else the new one). */
    public String categoryId() {
        return isNewCategory() ? newName.trim() : (category == null ? "" : category.trim());
    }

    static QueryPlan answer(String rewrite) {
        return new QueryPlan(Mode.ANSWER, rewrite == null ? "" : rewrite, "", null, null, null, null, Op.NONE, "");
    }
}

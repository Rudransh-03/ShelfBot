package com.localfilebrain.aggregate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic parsing of the planner's JSON into a QueryPlan. */
class QueryPlannerTest {

    private final QueryPlanner p = new QueryPlanner(null, null);

    @Test
    void parsesAnswerMode() {
        QueryPlan plan = p.parse("{\"mode\":\"answer\",\"rewrite\":\"what is in the lease?\"}");
        assertEquals(QueryPlan.Mode.ANSWER, plan.mode());
        assertEquals("what is in the lease?", plan.rewrite());
    }

    @Test
    void parsesAggregateWithExistingCategory() {
        QueryPlan plan = p.parse("""
            {"mode":"aggregate","rewrite":"","category":"client_fee_owed",
             "new_category":null,"operation":"total","status_filter":"unpaid"}""");
        assertEquals(QueryPlan.Mode.AGGREGATE, plan.mode());
        assertEquals("client_fee_owed", plan.categoryId());
        assertFalse(plan.isNewCategory());
        assertEquals(QueryPlan.Op.TOTAL, plan.operation());
        assertEquals("unpaid", plan.statusFilter());
    }

    @Test
    void parsesAggregateWithNewCategory() {
        QueryPlan plan = p.parse("""
            {"mode":"aggregate","rewrite":"which docs are personal?","category":"",
             "new_category":{"name":"personal_doc","label":"a personal (non-business) document",
             "field_spec":"is this a personal document","filter_terms":""},
             "operation":"list","status_filter":""}""");
        assertTrue(plan.isNewCategory());
        assertEquals("personal_doc", plan.categoryId());
        assertEquals("a personal (non-business) document", plan.newLabel());
        assertEquals(QueryPlan.Op.LIST, plan.operation());
    }

    @Test
    void whoMostMapsToOp() {
        assertEquals(QueryPlan.Op.WHO_MOST,
                p.parse("{\"mode\":\"aggregate\",\"operation\":\"who_most\",\"category\":\"client_fee_owed\"}").operation());
    }

    @Test
    void garbageDefaultsToAnswer() {
        assertEquals(QueryPlan.Mode.ANSWER, p.parse("not json at all").mode());
    }
}

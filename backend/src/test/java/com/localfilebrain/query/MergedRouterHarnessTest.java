package com.localfilebrain.query;

import com.localfilebrain.llm.GPT4oMiniClient;
import com.localfilebrain.query.QueryEngine.ClassifiedIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE routing harness for the merged one-call classifier. Feeds a broad sample of
 * questions through the REAL {@link QueryEngine#INTENT_CLASSIFIER_PROMPT} +
 * {@link QueryEngine#parseIntent} and checks each lands on the intended route. It
 * touches NO corpus — only the cheap classification call — so it proves the merge
 * didn't regress routing without paying for extraction.
 *
 * Off by default (never runs in a normal build / costs nothing). To run:
 *   OPENAI_API_KEY=... RUN_ROUTER_HARNESS=1 mvn test -Dtest=MergedRouterHarnessTest
 */
@EnabledIfEnvironmentVariable(named = "RUN_ROUTER_HARNESS", matches = "1")
class MergedRouterHarnessTest {

    /** ctx = prior conversation (or ""), q = message, expect = route string,
     *  strict = must match (else a genuinely-ambiguous boundary, just logged). */
    private record Case(String ctx, String q, String expect, boolean strict) {}

    private static Case c(String q, String expect)              { return new Case("", q, expect, true); }
    private static Case boundary(String q, String expect)       { return new Case("", q, expect, false); }
    private static Case ctx(String ctx, String q, String expect){ return new Case(ctx, q, expect, true); }

    // Route string: "SHEETS:<select>[/<scope>][/cat=<category>]" for an aggregate,
    // else the plain intent name. Kept coarse — we check WHERE it goes, not every field.
    private static String route(ClassifiedIntent ci) {
        if (ci.aggregate()) {
            StringBuilder s = new StringBuilder("SHEETS:").append(ci.select());
            if (ci.select().equals("amounts") && !ci.scope().isBlank()) s.append('/').append(ci.scope());
            if (!ci.category().isBlank()) s.append("/cat=").append(ci.category());
            return s.toString();
        }
        return ci.intent().name();
    }

    private static List<Case> cases() {
        List<Case> l = new ArrayList<>();
        // ── AMOUNTS aggregate (money by who-owes-whom, across everyone) ──
        l.add(c("who owes me the most?",                       "SHEETS:amounts/owed_to_me"));
        l.add(c("total unpaid fees across my clients",         "SHEETS:amounts/owed_to_me"));
        l.add(c("which clients still owe me?",                 "SHEETS:amounts/owed_to_me"));
        l.add(c("which clients have already paid?",            "SHEETS:amounts/owed_to_me"));
        l.add(c("how much money do I owe in total?",           "SHEETS:amounts/i_owe"));
        l.add(c("who do I need to pay?",                       "SHEETS:amounts/i_owe"));
        l.add(c("list everything I owe",                       "SHEETS:amounts/i_owe"));
        l.add(c("total of my utility bills",                   "SHEETS:amounts/i_owe/cat=utility"));
        l.add(c("how much do I owe on my credit card?",        "SHEETS:amounts/i_owe/cat=credit_card"));
        l.add(c("what's my biggest bill to pay?",              "SHEETS:amounts/i_owe"));
        // ── PARTIES aggregate (roster) ──
        l.add(c("how many clients do I have?",                 "SHEETS:parties"));
        l.add(c("who are my clients?",                         "SHEETS:parties"));
        l.add(c("list my vendors",                             "SHEETS:parties"));
        // ── DOCUMENTS aggregate (inventory by kind / personal) ──
        l.add(c("list all my personal documents",             "SHEETS:documents"));
        l.add(c("how many invoices do I have?",               "SHEETS:documents"));
        // ── DATES aggregate (deadlines across everything) ──
        l.add(c("what deadlines do I have this month?",        "SHEETS:dates"));
        l.add(c("anything due next week?",                     "SHEETS:dates"));
        // ── OVERVIEW ──
        l.add(c("summarize my documents",                     "OVERVIEW"));
        l.add(c("what are the most important things to know from my files?", "OVERVIEW"));
        // ── LOOKUP (single named item / existence / when-is-X) ──
        l.add(c("is the Blue Ridge invoice paid?",            "LOOKUP"));
        l.add(c("am I owed anything on the Pine St sale?",    "LOOKUP"));
        l.add(c("when is the rent due?",                      "LOOKUP"));
        l.add(c("did I get any scholarship?",                 "LOOKUP"));
        l.add(c("who is Rohan Mehta?",                        "LOOKUP"));
        l.add(c("what's in the lease?",                       "LOOKUP"));
        // ── CHITCHAT / UNCLEAR ──
        l.add(c("thanks, that's great!",                      "CHITCHAT"));
        l.add(c("what's the capital of France?",              "UNCLEAR"));
        // ── Entity/kind-scoped analytics & inventory (NOT the corpus aggregate) ──
        l.add(c("how many invoices mention Acme?",            "COUNT"));
        // content search may land LIST (inventory) or LOOKUP (RAG) — both enumerate
        // the matching docs; genuinely a boundary, so logged not asserted.
        l.add(boundary("which documents mention Acme?",       "LIST"));
        l.add(c("compare the GST returns",                    "COMPARE"));
        // ── Genuinely ambiguous boundary cases — logged, not hard-asserted ──
        l.add(boundary("total of all my invoices",            "SUM"));
        l.add(boundary("total of Rohan's invoices",           "SUM"));
        l.add(boundary("how much rent did I pay in total?",   "SUM"));
        // ── Continuation follow-ups (need prior turn) ──
        String oweCtx = "user: which clients owe me money?\n"
                + "assistant: Aurora Bakery owes you $2,750, Delgado Auto $1,500, and TechNova $3,600.\n";
        l.add(ctx(oweCtx, "and Anjali Rao?",                  "LOOKUP"));            // one named client → lookup
        l.add(ctx(oweCtx, "is that all of them?",             "SHEETS:amounts/owed_to_me")); // whole set again
        return l;
    }

    private static String buildInput(String ctx, String q) {
        String in = "Today's date: " + LocalDate.now() + "\n";
        if (!ctx.isBlank()) in += "Recent conversation (for resolving references):\n" + ctx + "\n";
        return in + "Message: " + q;
    }

    @Test
    void routingMatchesIntendedAcrossABroadSample() { run("core", cases()); }

    @Test
    void routingHandlesOtherDomains() { run("clinic/realtor/student/home", domainCases()); }

    // Domains CA doesn't exercise: a clinic that bills patients, a realtor owed
    // commission, a student who OWES tuition (i_owe), a household with category
    // bills. Category is a bonus refinement — expected routes ending '*' assert only
    // the direction/select (prefix), so a right route with a slightly-off category
    // still passes; the actual category is printed either way.
    private static List<Case> domainCases() {
        List<Case> l = new ArrayList<>();
        // ── Clinic: patients owe the practice (owed_to_me), suppliers billed to it ──
        l.add(c("how many patients do I have?",                "SHEETS:parties"));
        l.add(c("how much do my patients owe me in total?",    "SHEETS:amounts/owed_to_me*"));
        l.add(c("which patient owes me the most?",             "SHEETS:amounts/owed_to_me*"));
        l.add(c("what do I owe my suppliers?",                 "SHEETS:amounts/i_owe*"));
        // ── Realtor: commission owed, client roster ──
        l.add(c("what commission am I owed in total?",         "SHEETS:amounts/owed_to_me*"));
        l.add(c("who still owes me commission?",               "SHEETS:amounts/owed_to_me*"));
        l.add(boundary("which sale was the largest?",          "MAX"));   // sale amount → analytics or sheets
        // Superlative over the user's OWN bills/receivables = amounts aggregate, NOT
        // the entity analytics MAX; "earned/total so far" = amounts (status all).
        l.add(c("what's my most expensive bill?",              "SHEETS:amounts/i_owe*"));
        l.add(c("how much have I earned in commissions so far?", "SHEETS:amounts/owed_to_me*"));
        // ── Student: OWES tuition/rent, scholarship is a credit not a debt ──
        l.add(c("how much do I owe in total?",                 "SHEETS:amounts/i_owe*"));
        l.add(c("how much tuition do I owe?",                  "SHEETS:amounts/i_owe*"));
        l.add(c("did I get a scholarship?",                    "LOOKUP"));
        l.add(c("what deadlines do I have this semester?",     "SHEETS:dates"));
        // ── Home: category subtotals, tenant/mortgage ──
        l.add(c("total of my utility bills",                   "SHEETS:amounts/i_owe*"));
        l.add(c("how much rent do I owe?",                     "SHEETS:amounts/i_owe*"));
        l.add(c("how much do I owe on my mortgage?",           "SHEETS:amounts/i_owe*"));
        l.add(c("what bills are due this month?",              "SHEETS:dates"));
        return l;
    }

    private void run(String label, List<Case> cases) {
        String key = System.getenv("OPENAI_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set OPENAI_API_KEY to run the live harness");
        GPT4oMiniClient llm = new GPT4oMiniClient(key);

        List<String> strictFails = new ArrayList<>();
        int okStrict = 0, okBoundary = 0;
        StringBuilder report = new StringBuilder("\n=== merged router harness: " + label + " ===\n");

        for (Case k : cases) {
            String raw = llm.oneShot(QueryEngine.INTENT_CLASSIFIER_PROMPT, buildInput(k.ctx(), k.q()), 260, 0.0);
            ClassifiedIntent ci = QueryEngine.parseIntent(raw);
            String got = route(ci);
            boolean match = matches(k.expect(), got);
            String tag = k.strict() ? (match ? "OK  " : "FAIL") : (match ? "ok~ " : "note");
            report.append(String.format("%-4s  %-46s  exp %-28s got %s%n", tag, trim(k.q()), k.expect(), got));
            if (k.strict()) { if (match) okStrict++; else strictFails.add(k.q() + " → expected " + k.expect() + ", got " + got); }
            else if (match) okBoundary++;
        }
        report.append(String.format("strict %d/%d passed; boundary %d/%d matched%n",
                okStrict, (int) cases.stream().filter(Case::strict).count(),
                okBoundary, (int) cases.stream().filter(x -> !x.strict()).count()));
        System.out.println(report);

        assertTrue(strictFails.isEmpty(), label + " strict routing regressions:\n" + String.join("\n", strictFails));
    }

    /** Exact match, or prefix match when the expected route ends with '*'. */
    private static boolean matches(String expect, String got) {
        return expect.endsWith("*") ? got.startsWith(expect.substring(0, expect.length() - 1)) : got.equals(expect);
    }

    private static String trim(String s) { return s.length() <= 44 ? s : s.substring(0, 43) + "…"; }
}

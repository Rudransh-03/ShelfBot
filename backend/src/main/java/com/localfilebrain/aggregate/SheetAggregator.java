package com.localfilebrain.aggregate;

import com.localfilebrain.model.MoneyFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes a {@link SheetQuery} deterministically over the fact sheets — the same
 * input always yields the same answer. No LLM here: it filters the structured
 * fields the sheets already hold (money amounts, parties, documents, dates), then
 * counts / sums / lists / picks the max. Generic across question types; money is
 * just one of four {@code select} kinds.
 */
public final class SheetAggregator {

    private static final Logger log = LoggerFactory.getLogger(SheetAggregator.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public record Result(String text, List<String> sources) {}

    /** One party's fully-resolved money, computed in code (netted, payments applied,
     *  settled bills excluded). This is the "100% correct" figure set handed to the LLM
     *  for open calculations (average / compare / difference) it must not re-derive. */
    public record ClientMoney(String name, long billed, long paid, long owed, List<String> sources) {}

    // ── parsed view of one sheet ─────────────────────────────────────────────
    private record Party(String name, String role, String side) {}
    // role = the model's canonical tag for what this amount IS (charge|payment|
    // deposit|balance|refund|estimate|other), filled lazily for ambiguous docs. Blank
    // when untagged → the aggregator falls back to label keywords.
    private record Amount(double value, String status, String label, String role) {}
    // type = the model's canonical kind for the date (payment_due|renewal|filing|
    // appointment|other), filled lazily so "payment due dates" filters cleanly instead
    // of guessing from free-text labels. Blank when untagged → falls back to the label.
    private record Dated(String label, String date, boolean deadline, String type) {}
    private static final class Doc {
        String fileName, docType, title, gist, category = "";
        boolean isPersonal;
        List<Party> parties = new ArrayList<>();
        List<Amount> amounts = new ArrayList<>();
        List<Dated> dates = new ArrayList<>();
        String counterparty = "";   // the non-owner party (client/customer/payer)
    }

    public Result run(SheetQuery q, List<SheetExtractor.Sheet> sheets) {
        return run(q, sheets, java.util.List.of());
    }

    /**
     * @param ownerNames the owner's own identities (their name + business, from a
     *   one-time setting). Anything matching these is the owner and is never a
     *   client or a vendor. When empty, the owner is guessed heuristically (whoever
     *   bills 2+ different parties) — reliable for a practice, ambiguous for a lone
     *   consumer, which is exactly why a configured identity is preferred.
     */
    public Result run(SheetQuery q, List<SheetExtractor.Sheet> sheets, java.util.Collection<String> ownerNames) {
        Prepared p = prepare(sheets, ownerNames);
        if (p == null) return null;
        return switch (q.select()) {
            case AMOUNTS   -> amounts(q, p.docs(), p.ownerKeys());
            case PARTIES   -> parties(q, p.docs(), p.ownerKeys());
            case DOCUMENTS -> documents(q, p.docs());
            case DATES     -> dates(q, p.docs());
        };
    }

    /** The parsed corpus plus the resolved owner identity — the shared setup that both
     *  {@link #run} and {@link #moneyTable} need. Null when nothing parses. */
    private record Prepared(List<Doc> docs, Set<String> ownerKeys) {}

    private Prepared prepare(List<SheetExtractor.Sheet> sheets, java.util.Collection<String> ownerNames) {
        List<Doc> docs = new ArrayList<>();
        for (SheetExtractor.Sheet s : sheets) {
            Doc d = parse(s.json(), s.fileName());
            if (d != null) docs.add(d);
        }
        if (docs.isEmpty()) return null;

        Set<String> ownerKeys = new java.util.HashSet<>();
        for (String o : ownerNames) if (o != null && !o.isBlank()) ownerKeys.add(normName(o));
        if (ownerKeys.isEmpty()) {
            String guess = detectOwner(docs);
            if (!guess.isBlank()) ownerKeys.add(normName(guess));
        }

        String ownerFirm = detectOwnerFirm(docs);
        for (Doc d : docs) d.counterparty = counterpartyOf(d, ownerFirm);
        return new Prepared(docs, ownerKeys);
    }

    /**
     * The exact per-party money table for one direction — the same resolution the
     * AMOUNTS aggregate uses (grouping name variants, netting partial payments,
     * excluding settled bills), exposed as structured figures. Fed to the LLM for
     * open calculations (average, compare, difference) so it computes over correct
     * numbers instead of re-deciding which amounts count.
     *
     * @param owedToMe true = the user's receivables (money others owe them);
     *                 false = the user's payables (money they owe others).
     * @param category one money category to filter to, or "" for all.
     */
    public List<ClientMoney> moneyTable(List<SheetExtractor.Sheet> sheets,
                                        java.util.Collection<String> ownerNames,
                                        boolean owedToMe, String category) {
        Prepared p = prepare(sheets, ownerNames);
        if (p == null) return List.of();
        return perClientMoney(p.docs(), p.ownerKeys(), owedToMe, category == null ? "" : category);
    }

    private static boolean isOwner(String name, Set<String> ownerKeys) {
        return name != null && !name.isBlank() && ownerKeys.contains(normName(name));
    }

    // Placeholder names an extractor falls back to instead of a real party — never a
    // real client or vendor, so they must not be counted or grouped on.
    private static final Set<String> GENERIC_NAMES = Set.of("owner", "client", "clients",
            "customer", "customers", "tenant", "patient", "member", "me", "self", "n a",
            "na", "unnamed", "unknown", "account holder", "cardholder", "policyholder",
            "user", "borrower", "the client", "a client", "recipient", "payer");
    private static boolean isGenericName(String name) {
        return name == null || GENERIC_NAMES.contains(normName(name));
    }

    // ── AMOUNTS: money owed / paid, grouped by client ────────────────────────
    private Result amounts(SheetQuery q, List<Doc> docs, Set<String> ownerKeys) {
        boolean owedToMe = q.scope() != null && q.scope().contains("owed_to_me");
        boolean wantAll  = q.status().equals("all");   // total earned/billed = paid + owed
        // "paid" OR "partial" → the paid path (a partial payer HAS paid something); the
        // partial ones are labelled. "unpaid" stays the outstanding path.
        boolean wantPaid = !wantAll && (q.status().contains("paid") || q.status().contains("partial"))
                && !q.status().contains("un");
        List<String[]> rows = new ArrayList<>();               // {client, formattedAmount, rawAmount}
        List<String> sources = new ArrayList<>();
        long total = 0;

        for (ClientMoney m : perClientMoney(docs, ownerKeys, owedToMe, q.category())) {
            long billed = m.billed(), paid = m.paid(), owed = m.owed();
            long shown; String amtStr; boolean include;
            if (wantAll) {                       // total billed = what's paid PLUS still owed
                shown = billed; include = billed > 0;
                amtStr = MoneyFormat.format(billed);
            } else if (wantPaid) {               // paid at all — in full, or partial (called out)
                include = paid > 0;
                if (owed > 0) {                  // partially paid — state it explicitly
                    shown = paid;
                    amtStr = MoneyFormat.format(paid) + " paid, " + MoneyFormat.format(owed) + " still owed (partial)";
                } else {                         // paid in full
                    shown = billed; amtStr = MoneyFormat.format(billed);
                }
            } else {                             // unpaid / outstanding
                include = owed > 0; shown = owed; amtStr = MoneyFormat.format(owed);
            }
            if (!include) continue;
            rows.add(new String[]{m.name(), amtStr, String.valueOf(shown)});
            total += shown;
            for (String s : m.sources()) if (!sources.contains(s)) sources.add(s);
        }
        // Numeric threshold ("do any clients owe more than $3000?"): drop the rows
        // that don't clear the bound and re-total.
        if (q.amountRange() != null && !q.amountRange().isBlank()) {
            rows.removeIf(r -> !amountPasses(Long.parseLong(r[2]), q.amountRange()));
            total = 0;
            for (String[] r : rows) total += Long.parseLong(r[2]);
        }
        rows.sort((a, b) -> Long.compare(Long.parseLong(b[2]), Long.parseLong(a[2])));
        return renderRows(q, rows, total, sources);
    }

    /**
     * The resolved money per party for one direction — the single source of truth the
     * AMOUNTS render and the LLM compute path both read. One owed/paid/billed figure
     * per client, netting partial payments and excluding fully-settled invoices.
     * Grouped by a NORMALISED client name so an invoice ("Blue Ridge Landscaping LLC")
     * and its follow-up note ("Blue Ridge Landscaping") land together, and so does a
     * separate payment confirmation. Direction: "owed_to_me" (the user's receivables)
     * counts only groups with a real client/customer party; "i_owe" counts every bill,
     * including the user's own personal bills — the is_personal flag never gates money,
     * the direction does.
     */
    private List<ClientMoney> perClientMoney(List<Doc> docs, Set<String> ownerKeys,
                                             boolean owedToMe, String category) {
        Map<String, List<Doc>> byClient = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        Set<String> clientKeys = new java.util.HashSet<>();
        for (Doc d : docs) {
            if (!categoryMatch(d, category)) continue;         // "utility bills" etc.
            // is_personal is too fuzzy to gate money (a clinic's patient invoice gets
            // mislabeled "personal"). Direction + side + owner-exclusion do the gating:
            // a receivable is only counted for a group that has a real client party
            // (clientKeys below), which already excludes the owner's own vet/gym bills.
            String cp = counterpartyForScope(d, owedToMe, ownerKeys);
            String key = normName(cp);
            if (key.isBlank()) continue;
            if (d.amounts.isEmpty() && !isPaymentDoc(d)) continue;
            byClient.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            display.merge(key, cp, (a, b) -> b.length() > a.length() ? b : a);
            if (hasClientParty(d, ownerKeys)) clientKeys.add(key);
        }

        List<ClientMoney> out = new ArrayList<>();
        for (Map.Entry<String, List<Doc>> e : byClient.entrySet()) {
            if (owedToMe && !clientKeys.contains(e.getKey())) continue;   // receivables only
            List<Doc> group = e.getValue();
            double gross = grossOwed(group);       // largest single owed amount (for the clearing check)
            double sumPlain = 0;                    // sum of separate unpaid bills for this party
            double maxFace = 0, sumFace = 0;        // bill face values: max (one debt) / sum (separate bills)
            Double balance = null;                  // an explicit remaining balance overrides the sum
            boolean paidFull = false;               // an owed bill has been fully cleared
            for (Doc d : group) {
                // A doc's FACE value = its largest non-refund amount (what the bill is
                // for). We total these to get what was BILLED. A payment-confirmation
                // doc is not a new bill, so it doesn't add to the summed face.
                double docFace = 0;
                for (Amount a : d.amounts) {
                    if (a.value <= 0) continue;
                    String role = lc(a.role()), lb = lc(a.label);
                    boolean refundish = role.equals("refund") || lb.contains("refund") || lb.contains("credit");
                    if (!refundish) docFace = Math.max(docFace, a.value);
                }
                maxFace = Math.max(maxFace, docFace);
                if (!isPaymentDoc(d)) sumFace += docFace;
                if (isPaymentDoc(d)) paidFull = true;
                for (Amount a : d.amounts) {
                    if (a.value <= 0) continue;
                    String st = lc(a.status), lb = lc(a.label), role = lc(a.role());
                    if (isSettled(st)) {
                        boolean notPartial = !lb.contains("deposit") && !lb.contains("advance")
                                && !lb.contains("credit") && !lb.contains("refund")
                                && !lb.contains("partial");
                        if (notPartial) {
                            // CLEARING an owed bill stays conservative — only a line that
                            // reads/roles as a payment AND covers the owed gross settles
                            // it, so a separate paid recurring invoice doesn't wipe out
                            // the still-unpaid ones. Prefer the model's role over labels.
                            boolean clearing = !role.isBlank() ? role.equals("payment")
                                    : (lb.contains("payment") || lb.contains("paid")
                                       || lb.contains("total") || lb.contains("balance") || lb.contains("amount"));
                            if (clearing && a.value >= gross * 0.9) paidFull = true;
                        }
                        continue;
                    }
                    if (!isOwedCandidate(st, d)) continue;
                    // The remaining balance overrides the summed charges. Prefer the
                    // model's role; fall back to label keywords when untagged.
                    boolean balanceish = !role.isBlank() ? role.equals("balance")
                            : (lb.contains("remaining") || lb.contains("balance") || lb.contains("outstanding"));
                    if (balanceish) {
                        balance = balance == null ? a.value : Math.min(balance, a.value);  // the net still owed
                    } else if (!st.contains("partial")) {
                        sumPlain += a.value;         // a separate unpaid bill → add it
                    }
                }
            }
            // Still OWED: an explicit balance is the truth (you can't be paid in full
            // while a balance is due); else fully settled → 0, else sum the bills.
            long owed;
            if (balance != null && balance > 0) owed = Math.round(balance);
            else owed = paidFull ? 0 : Math.round(sumPlain);
            // Total BILLED: with an explicit balance the docs describe ONE debt (an
            // invoice + its follow-up) → take the largest face; otherwise they're
            // separate bills → sum their faces. PAID so far = billed − still owed, which
            // sidesteps a charge that was mislabeled "paid" (we never trust that figure).
            long billed = Math.max(owed, Math.round(balance != null ? maxFace : sumFace));
            long paid = billed - owed;
            List<String> gsrc = new ArrayList<>();
            for (Doc d : group) if (!gsrc.contains(d.fileName)) gsrc.add(d.fileName);
            out.add(new ClientMoney(display.get(e.getKey()), billed, paid, owed, gsrc));
        }
        return out;
    }

    /** The largest genuine "owed" amount on a bill in the group (ignores partial
     *  lines and settled payments), used as the gross to net against. */
    private double grossOwed(List<Doc> group) {
        double g = 0;
        for (Doc d : group) for (Amount a : d.amounts) {
            if (a.value <= 0) continue;
            String st = lc(a.status);
            if (isSettled(st) || st.contains("partial")) continue;
            if (isOwedCandidate(st, d)) g = Math.max(g, a.value);
        }
        return g;
    }

    // An amount counts as money owed only when its status is a definite debt, or it
    // sits on an actual bill/invoice and isn't settled. This keeps rate cards and
    // retainer schedules (an engagement letter's "quarterly fee", status unknown)
    // out of what's currently owed.
    private static boolean isOwedCandidate(String status, Doc d) {
        if (isRateDoc(d)) return false;   // an agreement's fee is a rate, not a current due
        return isDefiniteOwed(status) || (isBill(d) && !isSettled(status));
    }
    // A rate/terms document states a RATE, not money currently owed — an engagement
    // letter, a listing, a quote/estimate. NOT a lease or generic "agreement": those
    // carry real dues (a lease's monthly rent). Kept narrow so a real due isn't lost.
    // A date label that plainly names a future obligation to meet/attend — a safety
    // net for when the model's per-date deadline flag misses one. Excludes record
    // words (issued/paid) by only matching obligation words.
    /** True when a date matches the requested KIND (payment_due / renewal / filing /
     *  appointment). Prefers the model's canonical {@code type}; when a date is untagged
     *  or "other", falls back to label keywords so nothing is silently dropped. Empty
     *  request → always true (no kind filter). */
    private static boolean dateTypeMatches(Dated dt, String requested) {
        if (requested == null || requested.isBlank()) return true;
        String req = lc(requested);
        String base = req.contains("payment") ? "payment_due"
                    : (req.contains("renew") || req.contains("expir")) ? "renewal"
                    : req.contains("fil") ? "filing"
                    : (req.contains("appoint") || req.contains("meeting") || req.contains("hearing")) ? "appointment"
                    : req;
        String t = lc(dt.type());
        if (!t.isBlank() && !t.equals("other"))
            return t.contains(base) || base.contains(t) || t.contains(req);
        String lb = lc(dt.label());   // untagged → label fallback
        return switch (base) {
            case "payment_due" -> lb.contains("payment") || lb.contains("invoice") || lb.contains("bill") || lb.contains("due");
            case "renewal"     -> lb.contains("renew") || lb.contains("expir");
            case "filing"      -> lb.contains("fil") || lb.contains("return") || lb.contains("submiss");
            case "appointment" -> lb.contains("appoint") || lb.contains("hearing") || lb.contains("exam") || lb.contains("meeting");
            default            -> true;
        };
    }

    private static boolean isObligationLabel(String label) {
        String l = lc(label);
        // No bare "payment" — it also matches "payment received" (a record). A real
        // payment deadline reads "payment due", caught by "due".
        return l.contains("due") || l.contains("deadline") || l.contains("exam")
                || l.contains("final") || l.contains("test") || l.contains("quiz")
                || l.contains("hearing") || l.contains("appointment") || l.contains("renew")
                || l.contains("expir") || l.contains("filing");
    }

    /** True when the doc matches the requested category (or none requested). Compares
     *  the model's category tag (both sides drawn from the same fixed set). A doc the
     *  model couldn't categorise ("other"/blank) is UNKNOWN — not "not this" — so a
     *  narrowing filter must NOT silently drop it (that hid a paid commission the model
     *  had filed as "other"). Only a doc firmly tagged a DIFFERENT category is excluded. */
    /** Applies a numeric bound like ">3000" / "<500" to a client's amount. Unparseable
     *  or empty → passes (no filter). */
    private static boolean amountPasses(long amount, String range) {
        try {
            String r = range.trim();
            if (r.startsWith(">")) return amount >  Long.parseLong(r.replaceAll("[^0-9]", ""));
            if (r.startsWith("<")) return amount <  Long.parseLong(r.replaceAll("[^0-9]", ""));
            if (r.matches(".*\\d\\s*-\\s*\\d.*")) {          // "1000-3000" = between (inclusive)
                String[] p = r.split("-", 2);
                long lo = Long.parseLong(p[0].replaceAll("[^0-9]", ""));
                long hi = Long.parseLong(p[1].replaceAll("[^0-9]", ""));
                return amount >= lo && amount <= hi;
            }
            return true;
        } catch (Exception e) { return true; }
    }

    private static boolean categoryMatch(Doc d, String category) {
        if (category == null || category.isBlank()) return true;
        String dc = lc(d.category), c = lc(category);
        return dc.isBlank() || dc.equals("other") || dc.equals(c) || dc.contains(c) || c.contains(dc);
    }

    private static boolean isRateDoc(Doc d) {
        String t = lc(d.docType);
        return t.contains("engagement") || t.contains("listing") || t.contains("proposal")
                || t.contains("quote") || t.contains("estimate") || t.contains("rate card");
    }
    private static boolean isDefiniteOwed(String st) {
        st = lc(st);
        return st.contains("unpaid") || st.contains("not paid") || st.contains("owe")
                || st.contains("outstanding") || st.contains("overdue") || st.contains("pending")
                || st.contains("partial") || st.contains("payable") || st.contains("due")
                || st.contains("balance");
    }
    private static boolean isSettled(String st) {
        st = lc(st);
        // NB: "unpaid" contains "paid" — must not read as settled.
        return (st.contains("paid") && !st.contains("unpaid") && !st.contains("not paid"))
                || st.contains("settled") || st.contains("received") || st.contains("cleared");
    }
    private static boolean isBill(Doc d) {
        String t = lc(d.docType);
        return t.contains("invoice") || t.contains("bill") || t.contains("statement");
    }
    // The two sides of any billing relationship — profession-neutral. CLIENT_ROLES
    // = the party the owner serves/bills (a client, patient, tenant, member…);
    // PROVIDER_ROLES = the party that bills the owner (a vendor, landlord, biller…).
    private static final Set<String> CLIENT_ROLES = Set.of("client", "customer", "patient",
            "tenant", "member", "student", "guest", "policyholder", "insured", "buyer",
            "seller", "payer", "debtor", "resident", "subscriber", "borrower", "bill to", "bill-to");
    private static final Set<String> PROVIDER_ROLES = Set.of("provider", "biller", "issuer",
            "vendor", "landlord", "merchant", "lender", "creditor", "supplier",
            "bank", "insurer", "employer", "practice", "clinic", "firm");

    private static boolean roleIn(String role, Set<String> set) {
        String r = lc(role);
        for (String w : set) if (r.contains(w)) return true;
        return false;
    }

    // Root of genericness: the model tags each party's SIDE — "recipient" (the party
    // the owner serves / who owes) or "issuer" (the party that bills the owner). Code
    // reads the side, so a novel profession's role word ("congregant", "policyholder")
    // still works. The role-word lists are only a fallback for that field being blank.
    // Client-side = the party that OWES (a debtor: client/patient/tenant who must pay).
    // Provider-side = the party that is OWED (a creditor: biller/vendor/landlord).
    // Reads the money-direction side first; "issuer/recipient" kept for old sheets.
    private static boolean isClientSide(Party p) {
        String s = lc(p.side());
        if (s.contains("owes") || s.contains("debtor") || s.contains("recipient")
                || s.contains("client") || s.contains("billed") || s.contains("customer")) return true;
        if (s.contains("owed") || s.contains("creditor") || s.contains("issuer")
                || s.contains("provider") || s.contains("biller")) return false;
        return roleIn(p.role(), CLIENT_ROLES);
    }
    private static boolean isProviderSide(Party p) {
        String s = lc(p.side());
        if (s.contains("owed") || s.contains("creditor") || s.contains("issuer")
                || s.contains("provider") || s.contains("biller")) return true;
        if (s.contains("owes") || s.contains("debtor") || s.contains("recipient")
                || s.contains("client") || s.contains("billed")) return false;
        return roleIn(p.role(), PROVIDER_ROLES);
    }

    /**
     * The owner of the collection = the party that BILLS 2+ DIFFERENT counterparties
     * (a practice/firm running its book of clients). A vendor bills only the owner, a
     * client is billed — neither qualifies. Empty when no one bills many (a consumer's
     * corpus), which is correct: a consumer has no receivables. Distinguishes the
     * doctor's own clinic from an ordinary vendor without a keyword list.
     */
    private static String detectOwner(List<Doc> docs) {
        Map<String, Set<String>> billed = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (Doc d : docs) {
            List<String> providers = new ArrayList<>(), others = new ArrayList<>();
            for (Party p : d.parties) {
                if (p.name() == null || p.name().isBlank()) continue;
                if (isProviderSide(p)) providers.add(p.name().trim());
                else others.add(p.name().trim());
            }
            for (String pr : providers) {
                display.putIfAbsent(normName(pr), pr);
                for (String o : others)
                    if (!normName(pr).equals(normName(o)))
                        billed.computeIfAbsent(normName(pr), k -> new java.util.HashSet<>()).add(normName(o));
            }
        }
        return billed.entrySet().stream().filter(e -> e.getValue().size() >= 2)
                .max(java.util.Comparator.comparingInt(e -> e.getValue().size()))
                .map(e -> display.get(e.getKey())).orElse("");
    }

    private static boolean sameName(String a, String b) {
        return !a.isBlank() && !b.isBlank() && normName(a).equals(normName(b));
    }
    private static boolean namedIn(Doc d, Set<String> ownerKeys) {
        for (Party p : d.parties) if (isOwner(p.name(), ownerKeys)) return true;
        return false;
    }
    private static boolean ownerIsProvider(Doc d, Set<String> ownerKeys) {
        for (Party p : d.parties)
            if (isOwner(p.name(), ownerKeys) && isProviderSide(p)) return true;
        return false;
    }
    /** The owner appears as a client/owes (billed) party on this doc — meaning it's a
     *  payable of theirs, even if a stray duplicate also tags them provider-side. */
    private static boolean ownerIsClient(Doc d, Set<String> ownerKeys) {
        for (Party p : d.parties)
            if (isOwner(p.name(), ownerKeys) && isClientSide(p)) return true;
        return false;
    }

    /** A client/customer/patient/… party for this doc, other than the owner. */
    private static boolean hasClientParty(Doc d, Set<String> ownerKeys) {
        for (Party p : d.parties) {
            if (p.name() == null || p.name().isBlank() || isOwner(p.name(), ownerKeys)) continue;
            if (isClientSide(p)) return true;
        }
        return false;
    }

    /** The party on the other side of the money for grouping, owner excluded:
     *  "owed to me" → the client the owner bills; "I owe" → the biller. */
    private static String counterpartyForScope(Doc d, boolean owedToMe, Set<String> ownerKeys) {
        if (owedToMe) {
            for (Party p : d.parties) {
                String n = nm(p);
                if (n.isBlank() || isOwner(n, ownerKeys) || isGenericName(n)) continue;
                if (isClientSide(p)) return n;
            }
            // A payment/receipt from a client where the owner isn't named: take the
            // non-owner party. But if the owner IS named here (a bill TO the owner),
            // this is a payable, not a receivable → no client counterparty.
            if (ownerKeys.isEmpty() || !namedIn(d, ownerKeys))
                for (Party p : d.parties) { String n = nm(p); if (!n.isBlank() && !isOwner(n, ownerKeys)) return n; }
            return "";
        }
        // "I owe": skip anything the owner ISSUED (those are receivables) — but only
        // when the owner isn't ALSO billed on the same doc. A stray duplicate that
        // tags the owner provider-side (e.g. "member/owed" on a dues bill they owe)
        // must not hide a real payable, so an owner that appears as a client/owes
        // party keeps the doc in scope.
        if (!ownerKeys.isEmpty() && ownerIsProvider(d, ownerKeys) && !ownerIsClient(d, ownerKeys)) return "";
        for (Party p : d.parties) {
            String n = nm(p);
            if (n.isBlank() || isOwner(n, ownerKeys)) continue;
            if (isProviderSide(p)) return n;
        }
        // Fallback: a non-owner party that is NOT a debtor. Never pick a client-side
        // party here — that would turn a receivable (a client who owes me) into a
        // payable (me owing them).
        for (Party p : d.parties) {
            String n = nm(p);
            if (!n.isBlank() && !isOwner(n, ownerKeys) && !isClientSide(p)) return n;
        }
        return "";
    }
    private static String nm(Party p) { return p.name() == null ? "" : p.name().trim(); }
    private static String lc(String s) { return s == null ? "" : s.toLowerCase().trim(); }

    /** Normalised client key for grouping: lowercased, common company suffixes and
     *  punctuation stripped, so name variants of the same client merge. */
    private static String normName(String name) {
        if (name == null) return "";
        String s = name.toLowerCase().replaceAll("[.,]", " ");
        s = s.replaceAll("\\b(llc|inc|llp|ltd|co|corp|corporation|company|group|the)\\b", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    // ── PARTIES: clients / customers ─────────────────────────────────────────
    private Result parties(SheetQuery q, List<Doc> docs, Set<String> ownerKeys) {
        // "How many clients/patients/tenants" → count the parties the owner serves
        // (client-side roles), owner excluded. "How many vendors" → provider-side.
        // Personal docs and the owner's own service providers aren't counted.
        boolean wantProviders = roleIn(q.role(), PROVIDER_ROLES);
        Set<String> want = wantProviders ? PROVIDER_ROLES : CLIENT_ROLES;
        Map<String, String> byKey = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>();
        for (Doc d : docs) {
            // No is_personal gate — a client is identified by SIDE + not being the
            // owner (a clinic's patient invoice is often mislabeled personal). The
            // owner (their name AND business) is excluded, so a regulatory notice's
            // "licensee" — the owner himself under another name — isn't a client.
            boolean added = false;
            for (Party p : d.parties) {
                if (p.name() == null || p.name().isBlank()
                        || isOwner(p.name(), ownerKeys) || isGenericName(p.name())) continue;
                // Count by ROLE (a "client"/"patient"/"seller" is one regardless of a
                // possibly-wrong money-side tag), or by an unambiguous side.
                boolean match = roleIn(p.role(), want) || (wantProviders ? isProviderSide(p) : isClientSide(p));
                if (match) { addName(byKey, p.name().trim()); added = true; }
            }
            if (added) sources.add(d.fileName);
        }
        List<String[]> rows = new ArrayList<>();
        for (String n : byKey.values()) rows.add(new String[]{n, "", "0"});
        return renderRows(q, rows, 0, sources);
    }

    private static void addName(Map<String, String> byKey, String name) {
        String key = normName(name);
        if (key.isBlank()) return;
        byKey.merge(key, name, (a, b) -> b.length() > a.length() ? b : a);
    }

    // ── DOCUMENTS: filter by personal / type ─────────────────────────────────
    private Result documents(SheetQuery q, List<Doc> docs) {
        List<String[]> rows = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (Doc d : docs) {
            if (q.isPersonal() != null && d.isPersonal != q.isPersonal()) continue;
            if (!q.docType().isBlank() && !d.docType.toLowerCase().contains(q.docType())) continue;
            if (!categoryMatch(d, q.category())) continue;
            String label = d.title == null || d.title.isBlank() ? d.fileName : d.title;
            rows.add(new String[]{label, "", "0"});
            sources.add(d.fileName);
        }
        return renderRows(q, rows, 0, sources);
    }

    // ── DATES: deadlines within an optional range ────────────────────────────
    private Result dates(SheetQuery q, List<Doc> docs) {
        List<String[]> rows = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (Doc d : docs) for (Dated dt : d.dates) {
            if (dt.date() == null || dt.date().isBlank()) continue;
            // Deadlines only: trust the model's flag, but also honour a label that
            // plainly names an obligation (exam, hearing, due, renewal…) the flag
            // sometimes misses — never a record date (issued/paid).
            if (q.obligationsOnly() && !dt.deadline() && !isObligationLabel(dt.label())) continue;
            // Date KIND filter ("payment due dates" vs "renewals"): match the model's
            // canonical type; fall back to the label only when the date is untagged.
            if (!dateTypeMatches(dt, q.dateType())) continue;
            if (!q.dateFrom().isBlank() && dt.date().compareTo(q.dateFrom()) < 0) continue;
            if (!q.dateTo().isBlank() && dt.date().compareTo(q.dateTo()) > 0) continue;
            String label = (d.title == null || d.title.isBlank() ? d.fileName : d.title)
                    + " — " + dt.label() + " " + dt.date();
            rows.add(new String[]{label, "", "0"});
            if (!sources.contains(d.fileName)) sources.add(d.fileName);
        }
        return renderRows(q, rows, 0, sources);
    }

    // ── render per operation ─────────────────────────────────────────────────
    private Result renderRows(SheetQuery q, List<String[]> rows, long total, List<String> sources) {
        if (rows.isEmpty()) {
            // A concrete aggregate that simply found nothing is a real answer ("none"),
            // not a failure — don't hand it to the LLM. Only a vague op (NONE) defers.
            if (q.op() == SheetQuery.Op.NONE) return null;
            return new Result(emptyMessage(q), List.of());
        }
        StringBuilder sb = new StringBuilder();
        switch (q.op()) {
            case COUNT -> {
                sb.append(rows.size()).append(':');
                List<String> names = new ArrayList<>();
                for (String[] r : rows) names.add(r[0]);
                sb.append(' ').append(String.join(", ", names));
            }
            case MAX, MIN -> {
                String[] pick = rows.get(q.op() == SheetQuery.Op.MAX ? 0 : rows.size() - 1);
                sb.append("**").append(pick[0]).append("**");
                if (!pick[1].isBlank()) sb.append(" — ").append(pick[1]);
            }
            case SUM -> {
                sb.append(rows.size()).append(rows.size() == 1 ? " item:" : " items:");
                for (String[] r : rows) sb.append("\n- **").append(r[0]).append("**")
                        .append(r[1].isBlank() ? "" : " — " + r[1]);
                sb.append("\n\nTotal: ").append(MoneyFormat.format(total));
            }
            default -> {   // LIST (and NONE)
                sb.append(rows.size()).append(rows.size() == 1 ? " item:" : " items:");
                for (String[] r : rows) sb.append("\n- **").append(r[0]).append("**")
                        .append(r[1].isBlank() ? "" : " — " + r[1]);
            }
        }
        return new Result(sb.toString(), sources);
    }

    /** A natural "found nothing" answer for a concrete aggregate. */
    private static String emptyMessage(SheetQuery q) {
        return switch (q.select()) {
            case AMOUNTS -> (q.status().contains("paid") && !q.status().contains("un"))
                    ? "None of them are marked paid yet."
                    : "Nothing outstanding — you're all settled up.";
            case DATES -> q.obligationsOnly()
                    ? "No deadlines in that period." : "No dates found in that period.";
            case DOCUMENTS -> "No matching documents found.";
            case PARTIES -> "None found.";
        };
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private static boolean isPaymentDoc(Doc d) {
        String t = d.docType == null ? "" : d.docType.toLowerCase();
        return t.contains("payment") || t.contains("confirmation") || t.contains("receipt of payment");
    }

    /** The owner's own firm = the org that RECURS as the provider/biller across
     *  documents (e.g. a CPA who issues many invoices). When no provider repeats —
     *  a student whose bills come from many different billers — there is no owner
     *  firm, so the biller on each doc becomes its counterparty. */
    private static String detectOwnerFirm(List<Doc> docs) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (Doc d : docs) for (Party p : d.parties) {
            String r = p.role() == null ? "" : p.role().toLowerCase();
            if ((r.contains("provider") || r.contains("biller") || r.contains("issuer")) && p.name() != null && !p.name().isBlank())
                tally.merge(p.name().trim(), 1, Integer::sum);
        }
        return tally.entrySet().stream().max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() >= 2)     // must recur to be the owner firm
                .map(Map.Entry::getKey).orElse("");
    }

    /** The party a document is really about for grouping: the first named party that
     *  isn't the owner firm (works even when the role label is wrong). */
    private static String counterpartyOf(Doc d, String ownerFirm) {
        for (Party p : d.parties) {
            String name = p.name() == null ? "" : p.name().trim();
            if (name.isBlank()) continue;
            if (!name.equalsIgnoreCase(ownerFirm)) return name;
        }
        return "";
    }

    private Doc parse(String json, String fileName) {
        try {
            JsonNode n = mapper.readTree(json);
            Doc d = new Doc();
            d.fileName = fileName;
            d.docType = n.path("doc_type").asText("");
            d.category = n.path("category").asText("");
            d.title = n.path("title").asText("");
            d.gist = n.path("gist").asText("");
            d.isPersonal = n.path("is_personal").asBoolean(false);
            for (String f : new String[]{"orgs", "people"}) {
                JsonNode arr = n.get(f);
                if (arr != null && arr.isArray()) for (JsonNode p : arr)
                    d.parties.add(new Party(p.path("name").asText(""), p.path("role").asText(""),
                            p.path("side").asText("")));
            }
            // If every party with a MONEY side shares the same one (impossible — on a
            // bill someone owes and someone is owed), the tags are unreliable → drop
            // them so role decides. Only owes/owed count; "other"/blank noise ignored.
            Set<String> money = new java.util.HashSet<>();
            int moneySided = 0;
            for (Party p : d.parties) {
                String sd = lc(p.side());
                if (sd.contains("owed")) { money.add("owed"); moneySided++; }
                else if (sd.contains("owes")) { money.add("owes"); moneySided++; }
            }
            if (money.size() == 1 && moneySided > 1) {
                List<Party> cleaned = new ArrayList<>();
                for (Party p : d.parties) cleaned.add(new Party(p.name(), p.role(), ""));
                d.parties = cleaned;
            }
            JsonNode am = n.get("amounts");
            if (am != null && am.isArray()) for (JsonNode a : am) {
                if (!a.path("value").isNumber()) continue;
                d.amounts.add(new Amount(a.path("value").asDouble(), a.path("status").asText(""),
                        a.path("label").asText(""), a.path("role").asText("")));
            }
            JsonNode dt = n.get("dates");
            if (dt != null && dt.isArray()) for (JsonNode x : dt)
                d.dates.add(new Dated(x.path("label").asText(""), x.path("date").asText(""),
                        x.path("deadline").asBoolean(false), x.path("type").asText("")));
            return d;
        } catch (Exception e) {
            log.warn("Could not parse sheet for '{}': {}", fileName, e.getMessage());
            return null;
        }
    }
}

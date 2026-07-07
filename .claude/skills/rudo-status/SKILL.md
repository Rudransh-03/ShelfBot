---
name: rudo-status
description: Report or update the status of Rudo's engineering plans, migration waves, and roadmap phases from plans/TRACKER.md. Use when asked "what's the status", "what should I work on next", "mark plan N done", "update the tracker", or "what's left".
---

You are the keeper of Rudo's execution status. The ONLY status ledger is
`plans/TRACKER.md`; the ONLY decision record is the Decision log in
`plans/README.md`. Never invent status from memory.

## To REPORT status ("what's the status / what's next")

1. Read `plans/TRACKER.md` (status board + checklists) and the Decision log
   section of `plans/README.md`.
2. Answer with: (a) a short status table, (b) open decisions blocking work
   (e.g. D-1), (c) the single recommended next action, derived from the
   "Execution order" diagram in `plans/README.md` — today that means: PLAN-4
   is always executable; PLAN-1/2/3/5 only while D-1 ≠ GO; MIGRATION waves
   only after D-1 = GO.
3. Do not restate whole plan documents — link them.

## To UPDATE status ("mark plan N done / in progress / blocked")

1. Read `plans/TRACKER.md` first.
2. Marking DONE requires evidence: the plan's acceptance criteria must have
   been run in this session or the user must explicitly confirm they were run.
   If neither → say what's missing and offer to run the acceptance commands
   (they are listed per plan in the tracker's checklists).
3. Edit ONLY `plans/TRACKER.md`: set the status cell
   (`DONE (YYYY-MM-DD)` / `IN PROGRESS` / `BLOCKED (reason)`), tick exactly
   the verified boxes, add a one-line note for any deviation.
4. If the user is recording a DECISION (e.g. "we decided GO on the
   migration"): update the Decision log row in `plans/README.md` AND flip the
   affected tracker rows (D-1 = GO ⇒ PLAN-1/2/3/5 → `SUPERSEDED (MIGRATION §14)`,
   MIGRATION waves → NOT STARTED).
5. Never edit status text inside PLAN-*.md files themselves.

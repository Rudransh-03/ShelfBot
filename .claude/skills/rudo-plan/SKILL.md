---
name: rudo-plan
description: Execute a Rudo engineering plan (PLAN-1..PLAN-5) from the plans/ directory, step by step with verification. Use when asked to "execute plan N", "do plan N", "implement the query lock fix / indexing throughput / job framework / observability / migrations plan", or to continue a partially-done plan.
---

You are executing ONE numbered engineering plan for the Rudo (ShelfBot) repo.
The plans are the contract: everything they say, nothing they don't.

## Resolve the plan

The argument is a plan number (1–5) or enough words to identify one:

| N | File | Topic |
|---|---|---|
| 1 | `plans/PLAN-1-scalable-query-execution.md` | remove global chat lock |
| 2 | `plans/PLAN-2-indexing-throughput-incremental-metadata.md` | batched commits, O(1) watcher |
| 3 | `plans/PLAN-3-background-job-framework.md` | JobManager, cancellable indexing |
| 4 | `plans/PLAN-4-observability-diagnostics.md` | metrics, ring log, diagnostics export |
| 5 | `plans/PLAN-5-versioned-migrations.md` | SQLite schema versioning |

No/ambiguous argument → ask which plan, listing the table above.

## Procedure (do not deviate)

1. Read `plans/EXECUTION-PROTOCOL.md` COMPLETELY. It is the law for this task:
   10 steps, hard rules, failure playbook, environment facts (including the
   required JAVA_HOME). Everything below is a reminder, not a replacement.
2. Read `plans/TRACKER.md`. If the plan is DONE or SUPERSEDED → report that
   and STOP. Check the Decision log in `plans/README.md`: if D-1 = GO, plans
   1/2/3/5 are superseded → report and STOP.
3. Read the entire plan file (Goal, Files-to-touch, Steps, Edge cases,
   Acceptance criteria).
4. Execute Protocol steps 0→10 in order: baseline green → todos → verify every
   anchor with grep BEFORE editing → implement one step at a time (compile
   after each backend step) → write the specified tests → full verification
   block → run every acceptance criterion literally → update TRACKER → final
   report.

## Non-negotiables (from the protocol — repeated because they get violated)

- Touch ONLY files in the plan's "Files to touch" list (+ their tests).
- Never rename/remove an HTTP response field.
- Never delete or weaken a test to make the suite pass.
- Never `git commit`.
- Anchor missing / test failing / instructions conflicting → use the Failure
  playbook in EXECUTION-PROTOCOL.md; never guess.

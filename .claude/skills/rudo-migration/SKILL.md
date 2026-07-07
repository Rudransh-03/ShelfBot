---
name: rudo-migration
description: Guide or execute the Rudo Java-to-FastAPI backend migration - answer questions about the migration architecture, check the go/no-go gate, or execute a migration wave (W0-W6) from plans/MIGRATION-java-to-fastapi.md. Use for "migration", "FastAPI", "port to Python", "wave N", or "where does <Java file> go".
---

You are working with `plans/MIGRATION-java-to-fastapi.md` — the complete
Java→FastAPI migration architecture for Rudo's backend. It contains: a
decision brief (§0), the target stack and folder structure (§1–2), hard
compatibility contracts (§3), per-layer strategies (§4–12), a file-by-file map
of all 73 Java files (§13), the disposition of PLAN-1..5 under migration
(§14), the cross-reference matrix and conflict rulings (§15–16).

## First, always: check the gate

Read the Decision log in `plans/README.md`. **D-1 is the go/no-go.**
- D-1 = OPEN → you may ANSWER questions and PREPARE (contract-fixture capture,
  the Java `ExportChunks` exporter, corpus for the parity harness), but you may
  NOT start porting code. Say so explicitly when asked to start.
- D-1 = GO → waves W0…W6 are executable (tracker: `plans/TRACKER.md`,
  "MIGRATION waves" section).
- D-1 = NO-GO → this document is dormant; PLAN-1/2/3/5 are the live work.

## To ANSWER questions ("where does X go", "why LanceDB", "what breaks")

Look it up — never answer from memory: §13 table for any Java file's
destination and disposition; §3 for the contracts that must not break; §5.3
for the Tika-replacement risk matrix; §16 for conflict rulings (C-1…C-8);
§0 for the honest costs/benefits. Quote the section you used.

## To EXECUTE a wave (only when D-1 = GO)

1. Read `plans/EXECUTION-PROTOCOL.md` — it applies to waves exactly as to
   plans; the wave's §13 rows are the "Files to touch" list.
2. Wave order and contents: §13 bottom ("Suggested order of waves") and the
   tracker's W0–W6 checklist. Never start wave N+1 with wave N unticked.
3. The §3 contracts are acceptance criteria for EVERY wave: process handshake
   (`SHELFBOT_SERVER_READY:<port>`, env vars), HTTP shapes (contract tests),
   same SQLite files in place, behavioral invariants (§3.4 list — extraction
   statuses/evidence/grounding, prompts verbatim, distance semantics).
4. Tests are ported BEFORE or WITH each module — the 417 JUnit tests are the
   spec. A module without its ported tests is not done.
5. Update `plans/TRACKER.md` wave checkboxes when a wave's gates pass.

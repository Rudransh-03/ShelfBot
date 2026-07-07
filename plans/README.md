# 📚 Rudo Engineering Plans — SOURCE OF TRUTH

> This directory is the canonical record of what Rudo builds next, in what
> order, and how. If a decision isn't recorded here (or in a doc this index
> links), it isn't decided. **Start every work session on this page.**

## How to use this directory (30 seconds)

1. **Executing work?** → open [EXECUTION-PROTOCOL.md](EXECUTION-PROTOCOL.md)
   and follow its 10 steps against the plan you were assigned. Never execute
   a plan without the protocol.
2. **Checking/recording progress?** → [TRACKER.md](TRACKER.md) is the only
   status ledger. Docs themselves never change status text; the tracker does.
3. **Deciding strategy?** → [Decision log](#decision-log) below, then
   [MIGRATION](MIGRATION-java-to-fastapi.md) §0/§14 and
   [ROADMAP](ROADMAP-rudo-v2.md) §0.
4. **Using Claude Code?** → project skills exist for all of this:
   `/rudo-plan <n>` (execute plan n), `/rudo-status`, `/rudo-migration`,
   `/rudo-roadmap`. Defined in `.claude/skills/`.

## Document map

| File | What it is | Execute it? | Depends on | Superseded when |
|---|---|---|---|---|
| [README.md](README.md) | This index + decision log | — | — | never |
| [EXECUTION-PROTOCOL.md](EXECUTION-PROTOCOL.md) | The 10-step loop for executing ANY plan | reference | — | never |
| [TRACKER.md](TRACKER.md) | Living status board + acceptance checklists | update always | — | never |
| [PLAN-1-scalable-query-execution.md](PLAN-1-scalable-query-execution.md) | Remove the global chat lock | ✅ if D-1 ≠ GO | none | D-1 = GO → absorbed into [MIGRATION §5.1](MIGRATION-java-to-fastapi.md#51-query-engine) |
| [PLAN-2-indexing-throughput-incremental-metadata.md](PLAN-2-indexing-throughput-incremental-metadata.md) | Batched commits, O(1) watcher events, cache fixes | ✅ if D-1 ≠ GO | none | D-1 = GO → requirements carry into [MIGRATION §14](MIGRATION-java-to-fastapi.md#14-disposition-of-plan-15-under-the-migration) |
| [PLAN-3-background-job-framework.md](PLAN-3-background-job-framework.md) | Unified cancellable JobManager | ✅ if D-1 ≠ GO | benefits from PLAN-2 | D-1 = GO → [MIGRATION §8](MIGRATION-java-to-fastapi.md#8-background-jobs--async-processing) |
| [PLAN-4-observability-diagnostics.md](PLAN-4-observability-diagnostics.md) | Metrics, ring log, diagnostics export, UI error capture | ✅ **ALWAYS** (regardless of D-1) | none | never (backend half re-ports per [MIGRATION §10](MIGRATION-java-to-fastapi.md#10-logging-monitoring-observability-error-handling)) |
| [PLAN-5-versioned-migrations.md](PLAN-5-versioned-migrations.md) | `user_version` schema migration framework | ✅ if D-1 ≠ GO | none | D-1 = GO → Alembic ([MIGRATION §6](MIGRATION-java-to-fastapi.md#6-database-layer-migration)) |
| [MIGRATION-java-to-fastapi.md](MIGRATION-java-to-fastapi.md) | Java→FastAPI architecture + all-73-file map + plans cross-reference | ⛔ gated on D-1 | Phase 0 of roadmap | never (it IS the migration record) |
| [ROADMAP-rudo-v2.md](ROADMAP-rudo-v2.md) | V2 strategy (agents/MCP/CLI/enterprise/API) + Phases 0–8 | strategy ref | — | revised quarterly |

**Related documents outside this directory** (context, not instructions):
`../production_report.md` (the audit all of this answers) ·
`../rudo-milestone-2-platform-reliability.md` (data layer + supervisor — DONE) ·
`../rudo-milestone-1-*.md` (historical) · `../LOCAL_SETUP.md` (dev setup).

## Decision log

The gates that change which documents are live. When a decision is made,
update this table AND the affected rows in [TRACKER.md](TRACKER.md).

| ID | Decision | Status | Consequence when decided |
|---|---|---|---|
| **D-1** | Java→FastAPI migration go/no-go ([MIGRATION §0](MIGRATION-java-to-fastapi.md#0-decision-brief--read-this-before-committing)) | **OPEN** | GO → PLAN-1/2/3/5 marked SUPERSEDED in tracker; MIGRATION waves unblock. NO-GO → execute PLAN-1→2→3→5 per this index. Either way PLAN-4 executes. |
| **D-2** | Shared-workspace sync/permission architecture ([ROADMAP Phase 6](ROADMAP-rudo-v2.md#phase-6--enterprise-mo-914--lane-a-ceiling--sm-if-enterprise-revenue-is-the-goal)) | OPEN (not due until Phase 6 entry) | Requires a written design review before any code. |
| **D-3** | Post-Phase-5 lead: enterprise (Lane A) vs platform (Lane B) ([ROADMAP §0](ROADMAP-rudo-v2.md#0-strategic-framing--read-first)) | OPEN (decide with revenue data) | Re-orders Phases 6/7. |

## Execution order (what to do next, today)

```
                 ┌─────────────────────────────────────────────┐
                 │ Phase 0 (always, now):                      │
                 │  • PLAN-4 observability            ← START  │
                 │  • signing/notarization (ops)               │
                 │  • deploy proxy, set PROD_PROXY_URL (ops)   │
                 └──────────────────┬──────────────────────────┘
                                    │  decide D-1
                    ┌───────────────┴───────────────┐
              D-1 = NO-GO                      D-1 = GO
                    │                               │
      PLAN-1 → PLAN-2 → PLAN-3 → PLAN-5      MIGRATION Waves 0…6
                    │                               │
                    └───────────────┬───────────────┘
                                    ▼
                     ROADMAP Phases 2…8 (V2 platform)
```

## Already done — never redo

- **Persistent User Data Layer** & **Backend Process Supervisor** (Milestone 2)
  — evidence in [TRACKER.md § Completed history](TRACKER.md#completed-history-do-not-re-do).
- **Extraction accuracy 5-pack** (batch-3, grounding/UNVERIFIED, 16k context,
  JSON retry, evidence field) — these are now INVARIANTS listed in
  [MIGRATION §3.4](MIGRATION-java-to-fastapi.md#3-non-negotiable-compatibility-contracts);
  no future change may weaken them.

## Open TODO not covered by any plan

- `ui/src/main/index.js:30` — `PROD_PROXY_URL` placeholder. Release blocker,
  ops-owned (deploy a proxy + set the URL + signing). Tracked in
  [TRACKER.md](TRACKER.md) under Phase 0.

## Ground rules (apply to ALL work in this repo)

1. **Verification block** (exact commands + JAVA_HOME) — see
   [EXECUTION-PROTOCOL.md § Environment facts](EXECUTION-PROTOCOL.md#environment-facts-memorize-these).
2. Never change an existing HTTP response field name/shape — the Electron
   renderer is the consumer spec.
3. Never log/export document text, chat content, or auth tokens.
4. Match surrounding code style; records for DTOs; SLF4J; `map(...)` helper in
   ApiServer; synchronized single-connection SQLite stores.
5. Do not commit; leave changes in the working tree.
6. Status lives in [TRACKER.md](TRACKER.md) only.
